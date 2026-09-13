# Task 5 Archived Research: Multi-Safeguard State Machine

> **IMPORTANT**: This is historical research, not an alternative production path.
> The production movement capture pipeline uses the minimal generic `BaseMovementSegmenter` with the `NORMAL` profile.

---

## 1. Executive Summary

During Tasks 5A through 5I, a series of state-machine safeguards were introduced to handle edge cases observed during offline exploration:
- Conservative baseline dwell and reference pose matching
- Region-specific coverage gating (`requiredRegions`, `requiredRegionsForTerminalStillness`)
- Side-specific requirements (`TORSO,RIGHT_ARM`, camera-near arm baseline)
- Pose relationship matching (`SAME_AS_START`, `DIFFERENT_STABLE_POSE`, `terminalPoseSimilarity`)
- Trailing 300 ms sliding-window displacement gates ($D_{\text{slow, 300ms}} \le 0.05$)
- Local anchor settling displacement gates ($d(P_{\text{anchor}}, P(t)) \le 0.03$)
- Middle-band dwell pausing (`MID` pauses dwell)

While each safeguard solved a local exploratory question, collectively they over-engineered the segmentation state machine and introduced severe failure modes:
1. **Sequence Collapse in Kihon**: The 300 ms trailing displacement gate took ~234 ms to decay, imposing an artificial ~334 ms stillness floor that exceeded natural kihon inter-strike holds (200–330 ms). This collapsed a 10-punch sequence into one ~9.9 s combination.
2. **Occlusion Deadlocks**: Demanding coverage on specific regions (e.g. `LEFT_ARM`, `LEFT_LEG`) prevented terminal stillness confirmation when an athlete turned sideways, even when the active strike had completely halted.
3. **Fragile Pose Matching**: Comparing terminal postures to starting poses failed on alternating strikes and non-cyclic techniques.

---

## 2. Policy for Future Safeguards

The rule going forward is:
> **Build the smallest recorder that works. Add a behaviour only when a real recording demonstrates why it is needed.**

Historical safeguards have been retired from production and parked here. They may inform future specialized profiles if concrete data justifies them:
- **`SLOW_MOTION`**: Potential future profile for slow-motion taiji or sustained drift tracking.
- **`COMBINATION`**: Potential future profile with explicit multi-strike grouping rules if natural kinematic dwell is insufficient.
- **`STANCE`**: Potential future profile for static posture hold verification and stability scoring.
- **`OCCLUSION_AWARE`**: Potential future profile for extreme multi-camera or single-side visibility conditions.

None of these profiles are implemented in production today. Production contains only `NORMAL`.

---

## 3. Preserved Code References

The historical multi-safeguard segmenter (`GenericMotionSegmenter`) has been moved to test/archive scope (`src/test/kotlin/dk/lasse/karateanalyzer/capture/archive/`) to preserve historical experiment reproducibility without polluting the production classpath or creating competing segmenter implementations.
