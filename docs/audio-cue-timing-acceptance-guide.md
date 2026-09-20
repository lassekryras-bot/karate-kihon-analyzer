# Audio Cue Timing & Physical-Device Acceptance Guide

## 1. Timing Model & Evidence Definitions

Spoken movement cues in the karate training analyzer serve as the reference anchor for measurements such as `cue_to_movement_latency`. To maintain scientific honesty without claiming unobserved laboratory-grade physics:

1. **`cue_playback_start`** — *App playback request / start-command timestamp*.
   - Recorded when the app issues the playback command to Android's `SoundPool`.
   - Captures the exact software initiation time. Any hardware routing, Bluetooth, or speaker output latency is distinct from this event.
2. **`spoken_count`** — *Package-defined estimated audible cue timestamp*.
   - Formulated as `playback_start + immutable A10 offset` (or the pre-rolled target cue time).
   - Represents the moment the cumulative absolute amplitude of the waveform reaches 10% (the A10 anchor).
   - Serves as the authoritative, immutable reference for all downstream reaction and movement latency analyses.
3. **Future Hardware Calibration**:
   - Device-specific audio hardware output latency (e.g. built-in speaker vs Bluetooth earbuds) can be measured and applied downstream as an analytical calibration offset.
   - Historical recordings are **never retroactively rewritten**; both `cue_playback_start` and `spoken_count` remain immutable capture evidence.

---

## 2. Package Cadence Safety Rule

Pre-rolling audio playback ahead of the intended cue creates the possibility of overlapping or truncated audio if cadence is set too fast. For any two consecutive audio assets $A$ and $B$, the safe cadence condition is:

$$\text{cadence} \ge \text{duration}(A) - \text{anchor}(A) + \text{anchor}(B)$$

For `japanese_count:v1`:
- Tightest transition: `COUNT_7` (Shichi: duration 590 ms, anchor 118 ms; remaining tail = 472 ms) followed by `COUNT_8` (Hachi: anchor 123 ms).
- Safe cadence requirement: $472\text{ ms} + 123\text{ ms} = 595\text{ ms}$ (rounded to 596 ms).
- Standard training cadences (1000 ms to 1500 ms) comfortably satisfy this constraint.
- Fast cadences below 596 ms are rejected safely during capture preparation before any recording or countdown commences.

---

## 3. Physical-Device Acceptance Protocol

Physical-device acceptance validates the perceptual and timing properties of the audio cue subsystem on real hardware, focusing on the historical auditory irregularity between **Ichi** (1) and **Ni** (2).

### Acceptance Steps:

1. **Cadence Selection:**
   - Launch Assisted Capture on the target device.
   - Configure a standard training cadence (e.g., 1.0 s, 1.1 s, 1.2 s) with Japanese counting enabled (`japanese_count:v1`).

2. **Aural Cadence Rhythm Check:**
   - Listen carefully to the live audio countdown and counting sequence.
   - Specifically verify that the transition **Ichi $\rightarrow$ Ni sounds rhythmically equal** to **Ni $\rightarrow$ San $\rightarrow$ Shi**.
   - Because `order_ichi` has a ~131 ms onset anchor and `order_ni` has a ~58 ms onset anchor, Ichi starts ~73 ms earlier than Ni relative to their respective cue points. The perceived acoustic pulses must land on an even, steady beat.

3. **Session Persistence & Event Inspection:**
   - Complete the training set (e.g., 10 alternating straight punches) and allow the session to finalize and save.
   - Inspect the persisted `RecordingSession` and `SessionEvent` records:
     - Verify `audioCuePackageVersionId == "japanese_count:v1"`.
     - Confirm that both `cue_playback_start` and `spoken_count` events are present for every repetition.
     - Confirm that the delta between consecutive `spoken_count` timestamps matches the selected cadence.

4. **Reaction & Kinematic Comparison:**
   - Inspect the downstream latency metrics:
     - `cue -> movement start`: Reaction latency from the estimated audible cue to physical movement onset.
     - `cue -> terminal extension / impact`: Latency from the estimated audible cue to maximum extension or impact frame.
   - Anticipatory reactions (moving prior to the audible cue) will produce negative latency values (`latencyUs < 0`).

5. **Version Invariance & Discipline:**
   - **Do not tune A10 from that single recording** merely to alter or improve latency figures.
   - A10 is an intrinsic, permanent parameter of `japanese_count:v1`.
   - If future cross-device empirical evidence or human perception studies support an alternative threshold (such as A15 or onset energy detection), that definition will be introduced in a new, distinct package version (e.g., `japanese_count:v2`).
   - Every recording captured with `japanese_count:v1` permanently retains its original, immutable provenance.

