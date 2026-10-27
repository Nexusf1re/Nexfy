use std::process::exit;
use std::thread;
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait};
use thiserror::Error;

use super::{Sink, SinkError, SinkResult};
use crate::config::AudioFormat;
use crate::convert::Converter;
use crate::decoder::AudioPacket;
use crate::{NUM_CHANNELS, SAMPLE_RATE};

#[cfg(all(
    feature = "rodiojack-backend",
    not(any(target_os = "linux", target_os = "dragonfly", target_os = "freebsd"))
))]
compile_error!("Rodio JACK backend is currently only supported on linux.");

#[cfg(feature = "rodio-backend")]
pub fn mk_rodio(device: Option<String>, format: AudioFormat) -> Box<dyn Sink> {
    Box::new(open(cpal::default_host(), device, format))
}

#[cfg(feature = "rodiojack-backend")]
pub fn mk_rodiojack(device: Option<String>, format: AudioFormat) -> Box<dyn Sink> {
    Box::new(open(
        cpal::host_from_id(cpal::HostId::Jack).unwrap(),
        device,
        format,
    ))
}

#[derive(Debug, Error)]
pub enum RodioError {
    #[error("<RodioSink> No Device Available")]
    NoDeviceAvailable,
    #[error("<RodioSink> device \"{0}\" is Not Available")]
    DeviceNotAvailable(String),
    #[error("<RodioSink> Play Error: {0}")]
    PlayError(#[from] rodio::PlayError),
    #[error("<RodioSink> Stream Error: {0}")]
    StreamError(#[from] rodio::StreamError),
    #[error("<RodioSink> Cannot Get Audio Devices: {0}")]
    DevicesError(#[from] cpal::DevicesError),
    #[error("<RodioSink> {0}")]
    Samples(String),
}

impl From<RodioError> for SinkError {
    fn from(e: RodioError) -> SinkError {
        use RodioError::*;
        let es = e.to_string();
        match e {
            StreamError(_) | PlayError(_) | Samples(_) => SinkError::OnWrite(es),
            NoDeviceAvailable | DeviceNotAvailable(_) => SinkError::ConnectionRefused(es),
            DevicesError(_) => SinkError::InvalidParams(es),
        }
    }
}

impl From<cpal::DefaultStreamConfigError> for RodioError {
    fn from(_: cpal::DefaultStreamConfigError) -> RodioError {
        RodioError::NoDeviceAvailable
    }
}

impl From<cpal::SupportedStreamConfigsError> for RodioError {
    fn from(_: cpal::SupportedStreamConfigsError) -> RodioError {
        RodioError::NoDeviceAvailable
    }
}

pub struct RodioSink {
    rodio_sink: rodio::Sink,
    // None while stopped: an open output plays silence and keeps the
    // phone's audio hardware awake.
    stream: Option<rodio::OutputStream>,
    // Kept to open the output again: Android closes it when the route
    // changes (headphones, Bluetooth) and it then never drains.
    host: cpal::Host,
    device: Option<String>,
    format: AudioFormat,
}

/// No drain for this long while playing means the output is gone.
const STALL: Duration = Duration::from_millis(800);

fn list_formats(device: &cpal::Device) {
    match device.default_output_config() {
        Ok(cfg) => {
            debug!("  Default config:");
            debug!("    {cfg:?}");
        }
        Err(e) => {
            // Use loglevel debug, since even the output is only debug
            debug!("Error getting default rodio::Sink config: {e}");
        }
    };

    match device.supported_output_configs() {
        Ok(mut cfgs) => {
            if let Some(first) = cfgs.next() {
                debug!("  Available configs:");
                debug!("    {first:?}");
            } else {
                return;
            }

            for cfg in cfgs {
                debug!("    {cfg:?}");
            }
        }
        Err(e) => {
            debug!("Error getting supported rodio::Sink configs: {e}");
        }
    }
}

fn list_outputs(host: &cpal::Host) -> Result<(), cpal::DevicesError> {
    let mut default_device_name = None;

    if let Some(default_device) = host.default_output_device() {
        default_device_name = default_device.name().ok();
        println!(
            "Default Audio Device:\n  {}",
            default_device_name.as_deref().unwrap_or("[unknown name]")
        );

        list_formats(&default_device);

        println!("Other Available Audio Devices:");
    } else {
        warn!("No default device was found");
    }

    for device in host.output_devices()? {
        match device.name() {
            Ok(name) if Some(&name) == default_device_name.as_ref() => (),
            Ok(name) => {
                println!("  {name}");
                list_formats(&device);
            }
            Err(e) => {
                warn!("Cannot get device name: {e}");
                println!("   [unknown name]");
                list_formats(&device);
            }
        }
    }

    Ok(())
}

fn create_sink(
    host: &cpal::Host,
    device: Option<String>,
    format: AudioFormat,
) -> Result<(rodio::Sink, rodio::OutputStream), RodioError> {
    let cpal_device = match device.as_deref() {
        Some("?") => match list_outputs(host) {
            Ok(()) => exit(0),
            Err(e) => {
                error!("{e}");
                exit(1);
            }
        },
        Some(device_name) => {
            // Ignore devices for which getting name fails, or format doesn't match
            host.output_devices()?
                .find(|d| d.name().ok().is_some_and(|name| name == device_name)) // Ignore devices for which getting name fails
                .ok_or_else(|| RodioError::DeviceNotAvailable(device_name.to_string()))?
        }
        None => host
            .default_output_device()
            .ok_or(RodioError::NoDeviceAvailable)?,
    };

    let name = cpal_device.name().ok();
    info!(
        "Using audio device: {}",
        name.as_deref().unwrap_or("[unknown name]")
    );

    // First try native stereo 44.1 kHz playback, then fall back to the device default sample rate
    // (some devices only support 48 kHz and Rodio will resample linearly), then fall back to
    // whatever the default device config is (like mono).
    let default_config = cpal_device.default_output_config()?;
    let config = cpal_device
        .supported_output_configs()?
        .find(|c| c.channels() == NUM_CHANNELS as cpal::ChannelCount)
        .and_then(|c| {
            c.try_with_sample_rate(cpal::SampleRate(SAMPLE_RATE))
                .or_else(|| c.try_with_sample_rate(default_config.sample_rate()))
        })
        .unwrap_or(default_config);

    let sample_format = match format {
        AudioFormat::F64 => cpal::SampleFormat::F64,
        AudioFormat::F32 => cpal::SampleFormat::F32,
        AudioFormat::S32 => cpal::SampleFormat::I32,
        AudioFormat::S24 | AudioFormat::S24_3 => cpal::SampleFormat::I24,
        AudioFormat::S16 => cpal::SampleFormat::I16,
    };

    let mut stream_config = config.config();
    // A computer's output gets a tenth of a second of room. WASAPI's own
    // default is a single period, about 10 ms, and any busy moment (the
    // window drawing, a download) left it with nothing to play: a crackle.
    // The phone keeps AAudio's choice, which it tunes to the hardware.
    #[cfg(not(target_os = "android"))]
    {
        let wanted = stream_config.sample_rate.0 / 10;
        let frames = match config.buffer_size() {
            cpal::SupportedBufferSize::Range { min, max } => wanted.clamp(*min, (*max).max(*min)),
            cpal::SupportedBufferSize::Unknown => wanted,
        };
        stream_config.buffer_size = cpal::BufferSize::Fixed(frames);
    }

    let mut stream = match rodio::OutputStreamBuilder::default()
        .with_device(cpal_device.clone())
        .with_config(&stream_config)
        .with_sample_format(sample_format)
        .open_stream()
    {
        Ok(exact_stream) => exact_stream,
        Err(e) => {
            warn!("unable to create Rodio output, falling back to default: {e}");
            rodio::OutputStreamBuilder::from_device(cpal_device)?.open_stream_or_fallback()?
        }
    };

    // disable logging on stream drop
    stream.log_on_drop(false);

    let sink = rodio::Sink::connect_new(stream.mixer());
    Ok((sink, stream))
}

pub fn open(host: cpal::Host, device: Option<String>, format: AudioFormat) -> RodioSink {
    info!(
        "Using Rodio sink with format {format:?} and cpal host: {}",
        host.id().name()
    );

    let (sink, stream) = create_sink(&host, device.clone(), format).unwrap();

    debug!("Rodio sink was created");
    RodioSink {
        rodio_sink: sink,
        // Opened (and the device checked) above, then closed until the
        // first play: start opens it again.
        stream: {
            drop(stream);
            None
        },
        host,
        device,
        format,
    }
}

impl Sink for RodioSink {
    fn start(&mut self) -> SinkResult<()> {
        if self.stream.is_none() {
            self.reopen()?;
        }
        self.rodio_sink.play();
        Ok(())
    }

    fn stop(&mut self) -> SinkResult<()> {
        // Not sleep_until_end: on a closed output it would wait forever.
        let mut left = self.rodio_sink.len();
        let mut since = Instant::now();
        while left > 0 && since.elapsed() < STALL {
            thread::sleep(Duration::from_millis(10));
            let now = self.rodio_sink.len();
            if now < left {
                left = now;
                since = Instant::now();
            }
        }
        self.rodio_sink.pause();
        self.stream = None;
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = packet
            .samples()
            .map_err(|e| RodioError::Samples(e.to_string()))?;
        let samples_f32: &[f32] = &converter.f64_to_f32(samples);
        let source = rodio::buffer::SamplesBuffer::new(
            NUM_CHANNELS as cpal::ChannelCount,
            SAMPLE_RATE,
            samples_f32,
        );
        self.rodio_sink.append(source);

        // Chunk sizes seem to be about 256 to 3000 ish items long.
        // Assuming they're on average 1628 then a half second buffer is:
        // 44100 elements --> about 27 chunks
        let mut left = self.rodio_sink.len();
        let mut since = Instant::now();
        while left > 26 {
            // sleep and wait for rodio to drain a bit
            thread::sleep(Duration::from_millis(10));
            let now = self.rodio_sink.len();
            if now < left {
                left = now;
                since = Instant::now();
            } else if since.elapsed() > STALL && !self.rodio_sink.is_paused() {
                warn!("audio output stopped draining, opening it again");
                self.reopen()?;
                break;
            }
        }
        Ok(())
    }
}

impl RodioSink {
    /// A new output on the current default device; what was queued on the
    /// dead one (about half a second) is dropped.
    fn reopen(&mut self) -> Result<(), RodioError> {
        let (sink, stream) = create_sink(&self.host, self.device.clone(), self.format)?;
        sink.play();
        self.rodio_sink = sink;
        self.stream = Some(stream);
        Ok(())
    }
}

impl RodioSink {
    #[allow(dead_code)]
    pub const NAME: &'static str = "rodio";
}
