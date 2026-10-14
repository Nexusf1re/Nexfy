//! A 10-band equalizer for what plays on this phone: peaking filters run
//! on the decoded samples just before the speaker (other Connect devices
//! never hear it), with the settings and the user's presets kept in the
//! core's app-private dir.

use std::f64::consts::PI;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use librespot_playback::audio_backend::{Sink, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::{NUM_CHANNELS, SAMPLE_RATE};
use serde::{Deserialize, Serialize};

pub const BANDS: usize = 10;
/// Band centres in Hz, an octave apart.
pub const FREQUENCIES: [f64; BANDS] = [31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0];
pub const MAX_GAIN_DB: f32 = 12.0;
/// Wide enough that neighbouring bands blend into one smooth curve.
const Q: f64 = 1.0;
const RATE: f64 = SAMPLE_RATE as f64;
const CHANNELS: usize = NUM_CHANNELS as usize;
/// About 12 ms: new settings glide in this long, so a drag never clicks.
const RAMP_FRAMES: u32 = 512;
/// Below this a gain counts as zero (the UI snaps to tenths of a dB).
const FLAT_DB: f32 = 0.05;
/// The clipper is linear up to here (about -0.9 dBFS) and rounds off above.
const KNEE: f64 = 0.9;

#[derive(Debug, Clone, uniffi::Record)]
pub struct EqualizerState {
    pub enabled: bool,
    pub preamp_db: f32,
    pub gains_db: Vec<f32>,
    /// The preset these gains match, if any.
    pub preset: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct EqualizerPreset {
    pub name: String,
    pub gains_db: Vec<f32>,
    pub builtin: bool,
}

/// 31 Hz to 16 kHz.
const BUILTIN: [(&str, [f32; BANDS]); 10] = [
    ("Plano", [0.0; BANDS]),
    ("Graves", [6.0, 5.0, 4.0, 2.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0]),
    ("Graves e agudos", [5.0, 4.0, 2.5, 0.5, -1.0, -1.0, 0.0, 2.0, 4.0, 5.0]),
    ("Vocal", [-2.0, -2.0, -1.0, 0.0, 2.0, 3.5, 4.0, 3.0, 1.0, 0.0]),
    ("Rock", [4.0, 3.5, 2.0, 0.0, -1.0, -1.0, 1.0, 2.5, 3.5, 4.0]),
    ("Pop", [-1.0, -0.5, 1.0, 2.5, 3.5, 3.0, 1.0, 0.0, -0.5, -1.0]),
    ("Eletrônica", [5.0, 4.5, 1.5, 0.0, -2.0, 0.0, 1.0, 1.5, 4.0, 4.5]),
    ("Acústico", [2.0, 2.0, 1.5, 1.0, 1.5, 1.5, 2.5, 3.0, 2.5, 1.5]),
    ("Clássica", [3.0, 2.5, 1.5, 0.0, 0.0, 0.0, -1.0, -1.0, 1.0, 2.5]),
    // A gentle loudness curve: the ear loses lows and highs at low volume.
    ("Noite", [5.0, 4.0, 2.5, 1.0, 0.0, 0.0, 0.0, 0.5, 2.0, 3.0]),
];

#[derive(Default, Serialize, Deserialize)]
struct Saved {
    enabled: bool,
    preamp_db: f32,
    gains_db: Vec<f32>,
    presets: Vec<SavedPreset>,
}

#[derive(Clone, Serialize, Deserialize)]
struct SavedPreset {
    name: String,
    gains_db: Vec<f32>,
}

/// What the audio thread reads: plain values, copied out of the atomics.
#[derive(Clone, Copy)]
struct Settings {
    enabled: bool,
    preamp_db: f32,
    gains_db: [f32; BANDS],
}

impl Settings {
    fn flat(&self) -> bool {
        self.preamp_db.abs() < FLAT_DB && self.gains_db.iter().all(|g| g.abs() < FLAT_DB)
    }
}

/// The settings, shared with the audio thread. Its reads are atomic loads:
/// the app never holds anything the audio thread waits on.
pub struct Equalizer {
    dir: PathBuf,
    /// Bumped after every change; the audio thread compares it per packet.
    version: AtomicU64,
    enabled: AtomicBool,
    preamp: AtomicU32,
    gains: [AtomicU32; BANDS],
    /// The user's presets, only touched from the app.
    presets: Mutex<Vec<SavedPreset>>,
}

impl Equalizer {
    pub fn load(dir: &Path) -> Self {
        let saved: Saved = std::fs::read(dir.join(FILE))
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        let gains = clean_gains(&saved.gains_db);
        Self {
            dir: dir.to_owned(),
            version: AtomicU64::new(1),
            enabled: AtomicBool::new(saved.enabled),
            preamp: AtomicU32::new(clamp_db(saved.preamp_db).to_bits()),
            gains: gains.map(|g| AtomicU32::new(g.to_bits())),
            presets: Mutex::new(saved.presets),
        }
    }

    fn settings(&self) -> Settings {
        Settings {
            enabled: self.enabled.load(Ordering::Relaxed),
            preamp_db: f32::from_bits(self.preamp.load(Ordering::Relaxed)),
            gains_db: std::array::from_fn(|i| f32::from_bits(self.gains[i].load(Ordering::Relaxed))),
        }
    }

    pub fn state(&self) -> EqualizerState {
        let settings = self.settings();
        let preset = self
            .presets()
            .into_iter()
            .find(|preset| preset.gains_db.iter().zip(&settings.gains_db).all(|(a, b)| (a - b).abs() < FLAT_DB))
            .map(|preset| preset.name);
        EqualizerState {
            enabled: settings.enabled,
            preamp_db: settings.preamp_db,
            gains_db: settings.gains_db.to_vec(),
            preset,
        }
    }

    /// Heard within a packet. Gains are clamped to +-12 dB; missing bands are 0.
    pub fn set(&self, enabled: bool, preamp_db: f32, gains_db: &[f32]) {
        self.enabled.store(enabled, Ordering::Relaxed);
        self.preamp.store(clamp_db(preamp_db).to_bits(), Ordering::Relaxed);
        for (slot, gain) in self.gains.iter().zip(clean_gains(gains_db)) {
            slot.store(gain.to_bits(), Ordering::Relaxed);
        }
        // Release: whoever sees the new version sees the values above.
        self.version.fetch_add(1, Ordering::Release);
        self.save();
    }

    /// Built-in presets first, then the user's in the order they were saved.
    pub fn presets(&self) -> Vec<EqualizerPreset> {
        let builtin = BUILTIN.iter().map(|(name, gains)| EqualizerPreset {
            name: (*name).to_owned(),
            gains_db: gains.to_vec(),
            builtin: true,
        });
        let user: Vec<EqualizerPreset> = self
            .presets
            .lock()
            .unwrap()
            .iter()
            .map(|preset| EqualizerPreset {
                name: preset.name.clone(),
                gains_db: clean_gains(&preset.gains_db).to_vec(),
                builtin: false,
            })
            .collect();
        builtin.chain(user).collect()
    }

    /// Saving under a user preset's name replaces it; built-in names are taken.
    pub fn save_preset(&self, name: &str, gains_db: &[f32]) -> Result<(), String> {
        let name = name.trim();
        if name.is_empty() {
            return Err("O preset precisa de um nome".into());
        }
        if BUILTIN.iter().any(|(builtin, _)| builtin.to_lowercase() == name.to_lowercase()) {
            return Err(format!("Já existe um preset chamado {name}"));
        }
        {
            let mut presets = self.presets.lock().unwrap();
            let preset = SavedPreset {
                name: name.to_owned(),
                gains_db: clean_gains(gains_db).to_vec(),
            };
            match presets.iter_mut().find(|p| p.name == name) {
                Some(existing) => *existing = preset,
                None => presets.push(preset),
            }
        }
        self.save();
        Ok(())
    }

    pub fn delete_preset(&self, name: &str) {
        self.presets.lock().unwrap().retain(|preset| preset.name != name);
        self.save();
    }

    fn save(&self) {
        let settings = self.settings();
        let saved = Saved {
            enabled: settings.enabled,
            preamp_db: settings.preamp_db,
            gains_db: settings.gains_db.to_vec(),
            presets: self.presets.lock().unwrap().clone(),
        };
        if let Ok(json) = serde_json::to_vec(&saved) {
            let _ = std::fs::write(self.dir.join(FILE), json);
        }
    }
}

const FILE: &str = "equalizer.json";

fn clamp_db(db: f32) -> f32 {
    if db.is_finite() { db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB) } else { 0.0 }
}

fn clean_gains(gains: &[f32]) -> [f32; BANDS] {
    std::array::from_fn(|i| gains.get(i).copied().map_or(0.0, clamp_db))
}

// ------------------------------------------------------------ filters --

/// Normalised biquad coefficients: b0, b1, b2, a1, a2 (a0 = 1).
type Coefs = [f64; 5];

const IDENTITY: Coefs = [1.0, 0.0, 0.0, 0.0, 0.0];

/// A peaking filter (Robert Bristow-Johnson's cookbook) at `freq`.
fn peaking(freq: f64, gain_db: f64) -> Coefs {
    if gain_db.abs() < f64::from(FLAT_DB) / 10.0 {
        return IDENTITY;
    }
    let a = 10f64.powf(gain_db / 40.0);
    let w0 = 2.0 * PI * freq / RATE;
    let (sin, cos) = w0.sin_cos();
    let alpha = sin / (2.0 * Q);
    let a0 = 1.0 + alpha / a;
    [
        (1.0 + alpha * a) / a0,
        -2.0 * cos / a0,
        (1.0 - alpha * a) / a0,
        -2.0 * cos / a0,
        (1.0 - alpha / a) / a0,
    ]
}

fn band_coefs(gains_db: &[f32; BANDS]) -> [Coefs; BANDS] {
    std::array::from_fn(|i| peaking(FREQUENCIES[i], f64::from(gains_db[i])))
}

/// The bands' combined gain at `freq`, in dB.
fn response_db(coefs: &[Coefs; BANDS], freq: f64) -> f64 {
    let w = 2.0 * PI * freq / RATE;
    let (s1, c1) = w.sin_cos();
    let (s2, c2) = (2.0 * w).sin_cos();
    coefs
        .iter()
        .map(|[b0, b1, b2, a1, a2]| {
            let num = (b0 + b1 * c1 + b2 * c2).powi(2) + (b1 * s1 + b2 * s2).powi(2);
            let den = (1.0 + a1 * c1 + a2 * c2).powi(2) + (a1 * s1 + a2 * s2).powi(2);
            10.0 * (num / den).log10()
        })
        .sum()
}

/// The highest point of the curve, sampled finely across the audible range.
fn peak_db(coefs: &[Coefs; BANDS]) -> f64 {
    const POINTS: i32 = 120;
    (0..=POINTS)
        .map(|i| 20.0 * 1000f64.powf(f64::from(i) / f64::from(POINTS)))
        .map(|freq| response_db(coefs, freq))
        .fold(f64::NEG_INFINITY, f64::max)
}

/// The output gain: the preamp, less whatever the boosts would push over
/// full scale. A negative preamp counts toward that headroom; a positive
/// one is a boost the user asked for (the soft clipper catches its peaks).
fn output_gain(settings: &Settings, coefs: &[Coefs; BANDS]) -> f64 {
    let preamp = f64::from(settings.preamp_db);
    let headroom = (peak_db(coefs) + preamp.min(0.0)).max(0.0);
    10f64.powf((preamp - headroom) / 20.0)
}

/// Linear to the knee, then bends smoothly toward full scale (same slope at
/// the knee, so there is no edge to hear).
#[inline]
fn soft_clip(x: f64) -> f64 {
    let level = x.abs();
    if level <= KNEE {
        return x;
    }
    let over = (level - KNEE) / (1.0 - KNEE);
    (KNEE + (1.0 - KNEE) * over.tanh()).copysign(x)
}

/// One band's memory for one channel (direct form I, which copes best with
/// coefficients that change while it runs).
#[derive(Clone, Copy, Default)]
struct History {
    x1: f64,
    x2: f64,
    y1: f64,
    y2: f64,
}

impl History {
    #[inline]
    fn run(&mut self, [b0, b1, b2, a1, a2]: &Coefs, x: f64) -> f64 {
        let y = b0 * x + b1 * self.x1 + b2 * self.x2 - a1 * self.y1 - a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }

    /// Tails decaying in silence turn subnormal, which some CPUs run slowly.
    fn flush(&mut self) {
        for value in [&mut self.x1, &mut self.x2, &mut self.y1, &mut self.y2] {
            if value.abs() < 1e-20 {
                *value = 0.0;
            }
        }
    }
}

/// The filters as the audio thread runs them. Coefficients, output gain
/// and the dry/wet mix move linearly to their targets over [RAMP_FRAMES]
/// (any blend of two stable biquads is stable), and with the equalizer off
/// or flat the samples are left untouched.
struct Dsp {
    eq: Arc<Equalizer>,
    seen: u64,
    coefs: [Coefs; BANDS],
    steps: [Coefs; BANDS],
    targets: [Coefs; BANDS],
    gain: f64,
    gain_step: f64,
    gain_target: f64,
    /// 0 is the untouched signal, 1 the equalized one.
    mix: f64,
    mix_step: f64,
    mix_target: f64,
    ramp: u32,
    history: [[History; BANDS]; CHANNELS],
}

impl Dsp {
    fn new(eq: Arc<Equalizer>) -> Self {
        Self {
            eq,
            seen: 0,
            coefs: [IDENTITY; BANDS],
            steps: [[0.0; 5]; BANDS],
            targets: [IDENTITY; BANDS],
            gain: 1.0,
            gain_step: 0.0,
            gain_target: 1.0,
            mix: 0.0,
            mix_step: 0.0,
            mix_target: 0.0,
            ramp: 0,
            history: [[History::default(); BANDS]; CHANNELS],
        }
    }

    /// Pick up new settings: one atomic load when nothing changed.
    fn refresh(&mut self) {
        let version = self.eq.version.load(Ordering::Acquire);
        if version == self.seen {
            return;
        }
        self.seen = version;
        let settings = self.eq.settings();
        if settings.enabled && !settings.flat() {
            self.targets = band_coefs(&settings.gains_db);
            self.gain_target = output_gain(&settings, &self.targets);
            self.mix_target = 1.0;
            if self.mix == 0.0 {
                // Out of bypass: start clean at the new curve and fade it in.
                self.coefs = self.targets;
                self.gain = self.gain_target;
                self.history = [[History::default(); BANDS]; CHANNELS];
            }
        } else if self.mix == 0.0 {
            // Already bypassed: nothing to fade.
            self.mix_target = 0.0;
            self.ramp = 0;
            return;
        } else {
            // Off or flat: hold the curve and fade back to the dry signal.
            self.targets = self.coefs;
            self.gain_target = self.gain;
            self.mix_target = 0.0;
        }
        let frames = f64::from(RAMP_FRAMES);
        for ((step, target), coef) in self.steps.iter_mut().zip(&self.targets).zip(&self.coefs) {
            *step = std::array::from_fn(|i| (target[i] - coef[i]) / frames);
        }
        self.gain_step = (self.gain_target - self.gain) / frames;
        self.mix_step = (self.mix_target - self.mix) / frames;
        self.ramp = RAMP_FRAMES;
    }

    fn advance(&mut self) {
        self.ramp -= 1;
        if self.ramp == 0 {
            // Land exactly, free of accumulated rounding.
            self.coefs = self.targets;
            self.gain = self.gain_target;
            self.mix = self.mix_target;
            return;
        }
        for (coef, step) in self.coefs.iter_mut().zip(&self.steps) {
            for (c, s) in coef.iter_mut().zip(step) {
                *c += s;
            }
        }
        self.gain += self.gain_step;
        self.mix += self.mix_step;
    }

    /// Filter interleaved stereo samples in place.
    fn process(&mut self, samples: &mut [f64]) {
        self.refresh();
        if self.ramp == 0 && self.mix == 0.0 {
            return;
        }
        for frame in samples.as_chunks_mut::<CHANNELS>().0 {
            if self.ramp > 0 {
                self.advance();
            }
            for (sample, history) in frame.iter_mut().zip(self.history.iter_mut()) {
                let dry = *sample;
                let mut x = dry;
                for (coefs, band) in self.coefs.iter().zip(history.iter_mut()) {
                    x = band.run(coefs, x);
                }
                let wet = soft_clip(x * self.gain);
                *sample = dry + self.mix * (wet - dry);
            }
        }
        self.history.iter_mut().flatten().for_each(History::flush);
    }
}

/// The audio output with the equalizer in front of it. The player hands it
/// samples already scaled by the volume, so the clipper sees real levels.
pub struct EqSink {
    inner: Box<dyn Sink>,
    dsp: Dsp,
}

impl EqSink {
    pub fn new(inner: Box<dyn Sink>, eq: Arc<Equalizer>) -> Self {
        Self { inner, dsp: Dsp::new(eq) }
    }
}

impl Sink for EqSink {
    fn start(&mut self) -> SinkResult<()> {
        self.inner.start()
    }

    fn stop(&mut self) -> SinkResult<()> {
        self.inner.stop()
    }

    fn write(&mut self, mut packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        if let AudioPacket::Samples(samples) = &mut packet {
            self.dsp.process(samples);
        }
        self.inner.write(packet, converter)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("nexfy-eq-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// Steady-state level of a sine at `freq` after the filters, in dB.
    fn measured_db(dsp: &mut Dsp, freq: f64) -> f64 {
        let frames = SAMPLE_RATE as usize;
        let mut samples: Vec<f64> = (0..frames)
            .flat_map(|i| {
                let v = 0.25 * (2.0 * PI * freq * i as f64 / RATE).sin();
                [v, v]
            })
            .collect();
        dsp.process(&mut samples);
        // Skip the first half: ramps and filter start-up.
        let tail = &samples[frames..];
        let peak = tail.iter().fold(0f64, |m, v| m.max(v.abs()));
        20.0 * (peak / 0.25).log10()
    }

    #[test]
    fn flat_is_identity() {
        let coefs = band_coefs(&[0.0; BANDS]);
        assert!(coefs.iter().all(|c| *c == IDENTITY));
        for freq in [20.0, 100.0, 1000.0, 10_000.0, 20_000.0] {
            assert!(response_db(&coefs, freq).abs() < 1e-9);
        }
        // Enabled but flat leaves the samples exactly as they were.
        let eq = Arc::new(Equalizer::load(&temp_dir("flat")));
        eq.set(true, 0.0, &[0.0; BANDS]);
        let mut dsp = Dsp::new(eq);
        let mut samples: Vec<f64> = (0..2048).map(|i| (i as f64 * 0.01).sin() * 0.5).collect();
        let before = samples.clone();
        dsp.process(&mut samples);
        assert_eq!(samples, before);
    }

    #[test]
    fn a_band_boosts_its_centre() {
        for (band, freq) in FREQUENCIES.iter().enumerate() {
            let mut gains = [0.0; BANDS];
            gains[band] = 6.0;
            let coefs = band_coefs(&gains);
            let at_centre = response_db(&coefs, *freq);
            assert!((at_centre - 6.0).abs() < 0.3, "{freq} Hz: {at_centre} dB");
        }
        // Far from the boosted band the curve is back near 0 dB.
        let mut gains = [0.0; BANDS];
        gains[5] = 6.0;
        assert!(response_db(&band_coefs(&gains), 31.0).abs() < 0.5);
    }

    #[test]
    fn running_filters_match_the_curve() {
        let eq = Arc::new(Equalizer::load(&temp_dir("run")));
        let mut gains = [0.0; BANDS];
        gains[5] = -6.0;
        eq.set(true, 0.0, &gains);
        let mut dsp = Dsp::new(eq);
        let db = measured_db(&mut dsp, 1000.0);
        assert!((db + 6.0).abs() < 0.3, "{db} dB");
    }

    #[test]
    fn boosts_leave_headroom() {
        let eq = Arc::new(Equalizer::load(&temp_dir("headroom")));
        let mut gains = [0.0; BANDS];
        gains[5] = 6.0;
        eq.set(true, 0.0, &gains);
        let mut dsp = Dsp::new(eq);
        // The boosted band lands at about 0 dB; the rest is lowered.
        assert!(measured_db(&mut dsp, 1000.0).abs() < 0.3);
        assert!((measured_db(&mut dsp, 31.0) + 6.0).abs() < 0.5);
    }

    #[test]
    fn soft_clip_is_gentle_and_bounded() {
        assert_eq!(soft_clip(0.5), 0.5);
        assert_eq!(soft_clip(-KNEE), -KNEE);
        assert!(soft_clip(4.0) <= 1.0 && soft_clip(1.5) > 0.99 && soft_clip(1.0) < 1.0);
        assert!(soft_clip(-4.0) >= -1.0);
    }

    #[test]
    fn settings_and_presets_persist() {
        let dir = temp_dir("persist");
        let eq = Equalizer::load(&dir);
        eq.set(true, -3.0, &[20.0, 1.0]);
        eq.save_preset("Meu", &[1.0; BANDS]).unwrap();
        assert!(eq.save_preset("graves", &[0.0; BANDS]).is_err());

        let again = Equalizer::load(&dir);
        let state = again.state();
        assert!(state.enabled);
        assert_eq!(state.preamp_db, -3.0);
        assert_eq!(state.gains_db[0], MAX_GAIN_DB);
        assert_eq!(state.gains_db[1], 1.0);
        assert_eq!(state.gains_db.len(), BANDS);
        let presets = again.presets();
        assert_eq!(presets.len(), BUILTIN.len() + 1);
        assert!(presets.last().is_some_and(|p| p.name == "Meu" && !p.builtin));

        again.set(true, 0.0, &[1.0; BANDS]);
        assert_eq!(again.state().preset.as_deref(), Some("Meu"));
        again.set(false, 0.0, &[0.0; BANDS]);
        assert_eq!(again.state().preset.as_deref(), Some("Plano"));
        again.delete_preset("Meu");
        assert_eq!(again.presets().len(), BUILTIN.len());
    }
}
