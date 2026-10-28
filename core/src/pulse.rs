use std::collections::VecDeque;
use std::f64::consts::PI;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::{NUM_CHANNELS, SAMPLE_RATE};

const RATE: f64 = SAMPLE_RATE as f64;
const WINDOW: usize = SAMPLE_RATE as usize / 100;
const BASS_HZ: f64 = 150.0;
const KEEP: usize = 200;
const STALE: Duration = Duration::from_millis(250);

#[derive(Debug, Clone, Copy, Default, PartialEq, uniffi::Record)]
pub struct AudioPulse {
    pub bass: f32,
    pub level: f32,
    pub air: f32,
}

#[derive(Default)]
pub struct Pulse {
    heard: Mutex<VecDeque<(Instant, AudioPulse)>>,
}

impl Pulse {
    pub fn at(&self, now: Instant) -> AudioPulse {
        let mut heard = self.heard.lock().unwrap();
        while heard.len() > 1 && heard[1].0 <= now {
            heard.pop_front();
        }
        match heard.front() {
            Some((at, pulse)) if *at <= now && now.duration_since(*at) < STALE => *pulse,
            _ => AudioPulse::default(),
        }
    }

    fn push(&self, at: Instant, pulse: AudioPulse) {
        let mut heard = self.heard.lock().unwrap();
        if heard.len() >= KEEP {
            heard.pop_front();
        }
        heard.push_back((at, pulse));
    }

    fn clear(&self) {
        self.heard.lock().unwrap().clear();
    }
}

fn span(frames: u64) -> Duration {
    Duration::from_secs_f64(frames as f64 / RATE)
}

struct Meter {
    pulse: Arc<Pulse>,
    smoothing: f64,
    origin: Option<Instant>,
    frames: u64,
    low: [f64; 2],
    last: f64,
    count: usize,
    sums: [f64; 3],
}

impl Meter {
    fn new(pulse: Arc<Pulse>) -> Self {
        Self {
            pulse,
            smoothing: 1.0 - (-2.0 * PI * BASS_HZ / RATE).exp(),
            origin: None,
            frames: 0,
            low: [0.0; 2],
            last: 0.0,
            count: 0,
            sums: [0.0; 3],
        }
    }

    fn feed(&mut self, samples: &[f64], now: Instant) {
        let mut origin = *self.origin.get_or_insert(now);
        let due = origin + span(self.frames);
        if now > due {
            origin += now - due;
            self.origin = Some(origin);
        }
        for frame in samples.chunks_exact(NUM_CHANNELS as usize) {
            let mono = frame.iter().sum::<f64>() / frame.len() as f64;
            self.low[0] += self.smoothing * (mono - self.low[0]);
            self.low[1] += self.smoothing * (self.low[0] - self.low[1]);
            let high = mono - self.last;
            self.last = mono;
            self.sums[0] += self.low[1] * self.low[1];
            self.sums[1] += mono * mono;
            self.sums[2] += high * high;
            self.count += 1;
            self.frames += 1;
            if self.count == WINDOW {
                let rms = |sum: f64| (sum / WINDOW as f64).sqrt() as f32;
                let pulse = AudioPulse { bass: rms(self.sums[0]), level: rms(self.sums[1]), air: rms(self.sums[2]) };
                self.pulse.push(origin + span(self.frames), pulse);
                self.count = 0;
                self.sums = [0.0; 3];
            }
        }
    }

    fn reset(&mut self) {
        self.origin = None;
        self.frames = 0;
        self.count = 0;
        self.sums = [0.0; 3];
        self.pulse.clear();
    }
}

pub struct PulseSink {
    inner: Box<dyn Sink>,
    meter: Meter,
}

impl PulseSink {
    pub fn new(inner: Box<dyn Sink>, pulse: Arc<Pulse>) -> Self {
        Self { inner, meter: Meter::new(pulse) }
    }
}

impl Sink for PulseSink {
    fn start(&mut self) -> SinkResult<()> {
        self.meter.reset();
        self.inner.start()
    }

    fn stop(&mut self) -> SinkResult<()> {
        let stopped = self.inner.stop();
        self.meter.reset();
        stopped
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = &packet {
            self.meter.feed(samples, Instant::now());
        }
        self.inner.write(packet, converter)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(freq: f64, seconds: f64) -> Vec<f64> {
        let frames = (RATE * seconds) as usize;
        (0..frames)
            .flat_map(|i| {
                let v = 0.5 * (2.0 * PI * freq * i as f64 / RATE).sin();
                [v, v]
            })
            .collect()
    }

    fn heard(freq: f64) -> AudioPulse {
        let pulse = Arc::new(Pulse::default());
        let mut meter = Meter::new(pulse.clone());
        let start = Instant::now();
        meter.feed(&sine(freq, 0.5), start);
        pulse.at(start + Duration::from_millis(400))
    }

    #[test]
    fn bass_and_air_follow_the_spectrum() {
        let low = heard(60.0);
        let high = heard(6000.0);
        assert!(low.bass > 0.2 && low.bass > low.air * 4.0, "{low:?}");
        assert!(high.air > 0.2 && high.air > high.bass * 20.0, "{high:?}");
        assert!((low.level - 0.35).abs() < 0.02 && (high.level - 0.35).abs() < 0.02);
    }

    #[test]
    fn a_pulse_shows_when_it_is_heard_not_when_it_is_written() {
        let pulse = Arc::new(Pulse::default());
        let mut meter = Meter::new(pulse.clone());
        let start = Instant::now();
        meter.feed(&sine(60.0, 1.0), start);
        assert_eq!(pulse.at(start), AudioPulse::default());
        assert!(pulse.at(start + Duration::from_millis(500)).bass > 0.0);
        assert_eq!(pulse.at(start + Duration::from_secs(3)), AudioPulse::default());
    }

    #[test]
    fn an_underrun_moves_the_timeline() {
        let pulse = Arc::new(Pulse::default());
        let mut meter = Meter::new(pulse.clone());
        let start = Instant::now();
        meter.feed(&sine(60.0, 0.1), start);
        let late = start + Duration::from_secs(2);
        meter.feed(&sine(60.0, 0.1), late);
        assert_eq!(pulse.at(late), AudioPulse::default());
        assert!(pulse.at(late + Duration::from_millis(50)).bass > 0.0);
    }

    #[test]
    fn stopping_forgets_what_was_queued() {
        let pulse = Arc::new(Pulse::default());
        let mut meter = Meter::new(pulse.clone());
        let start = Instant::now();
        meter.feed(&sine(60.0, 1.0), start);
        meter.reset();
        assert_eq!(pulse.at(start + Duration::from_millis(500)), AudioPulse::default());
    }
}
