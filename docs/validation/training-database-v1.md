# Training database v1 validation

Date: 2026-09-14. Local changes based on commit
`f5b1f212b6b945c758a8856c971683fd96a21359`; not deployed.

## Results

| Check | Result |
| --- | --- |
| Focused training, profile and guided-controller tests | **32 passed**, zero failures/skips |
| Full Android app unit suite | **223 executed**, 221 passed, two pre-existing UI architecture assertion failures |
| Debug app APK | Built successfully |
| Debug instrumentation-test APK | Built successfully |
| Exported schema / production Room opening | Passed with `MigrationTestHelper` under Robolectric |
| Unsupported database version | Fails without deleting stored user data |
| Synthetic continuous ten-punch pipeline | Exactly **10 movements**, one persisted landmark decode |
| Existing local real-recording pose fixture | **13 movements**, including additional detected post-drill movement; count not forced to ten |
| Reopen after MP4 deletion | Movement identities, landmark track and measurements preserved; no second landmark decode |
| Rolling history | Filters and preferred-version selection verified over 120 synthetic movements |
| Whitespace | `git diff --check` passed; new files also checked |
| Device instrumentation / fresh recording | Not run: `adb devices` returned no connected devices |

The full-suite failures are:

- `HomeStartupArchitectureTest.trainModeChooserIsPassiveAndSeparateFromLearningCatalogue`:
  expects two Skill Coach placeholder callbacks. The baseline commit already has
  zero because Skill Coach has a concrete screen.
- `LearningArtworkArchitectureTest.continueCardUsesTheLearningPathsStableEnsoAndResponsiveProgressLayout`:
  expects the literal `Continue learning` in `HomeScreenView`. That literal is
  already absent in the baseline commit. The file was not changed by this task.

After the full run, the guided callback guard was tightened to also reject an old
recording during a new attempt's Ready/countdown phase. The final focused 32-test
run and both APK builds passed after that change.

## Commands

Run from `android/KarateClipRecorder` with Android Studio's JBR as `JAVA_HOME`:

```text
gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --continue

gradlew :app:testDebugUnitTest \
  --tests dk.lasse.karatecliprecorder.training.* \
  --tests dk.lasse.karatecliprecorder.profile.ProfileRepositoryTest \
  --tests dk.lasse.karatecliprecorder.GuidedJodanSessionControllerTest \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

The local logs are under `android/KarateClipRecorder/build/`:
`training-final-check.log` (full suite), `training-focused-final.log` (final
focused run), and `schema-regeneration.log`. They are build outputs, not committed
source artifacts.

## Query-plan evidence

SQLite reports these operations for the implemented rolling-history candidate
query on the synthetic dataset:

```text
MeasurementResult: index_MeasurementResult_measurementKey
MovementAnalysis: primary-key index (analysisId)
SessionMovement: primary-key index (movementId)
RecordingSession: primary-key index (sessionId)
Temporary B-tree for ORDER BY
```

The per-session chronological movement query uses its composite index. No new
cache or speculative index is justified by this small dataset.

## Limits

These checks verify persistence and integration, not dynamic technique validity.
The current Android static-pose adapter produces partial/abstained evidence.
Python dynamic wrist-path analyzers remain outside the Android runtime. Real
CameraX start/cue alignment, native MediaPipe decoding, a fresh physical ten-punch
session, device process-kill recovery and the instrumentation migration test
still require hardware. See the [remaining acceptance task](../backlog/karate-training-database.md).
