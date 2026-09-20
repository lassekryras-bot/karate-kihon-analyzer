package dk.lasse.karatecliprecorder.mediapipeposeadapter

import com.google.mediapipe.tasks.core.Delegate

enum class PoseLandmarkerVariant(
    val defaultAssetPath: String,
    val variantKey: String,
    val displayName: String,
) {
    FULL("mediapipe/pose_landmarker_full.task", "pose_full", "Full"),
    HEAVY("pose_landmarker_heavy.task", "pose_heavy", "Heavy"),
}

data class PoseLandmarkerConfig(
    val assetPath: String,
    val variant: PoseLandmarkerVariant,
    val delegate: Delegate = Delegate.CPU,
    val tasksVersion: String = "0.10.26",
    val decoderVersion: String = "sequential-v1",
) {
    fun pipelineIdentity(sha256: String): String =
        "${variant.variantKey}_sha256:$sha256;decoder=1;tasks=$tasksVersion;${delegate.name};settings=1"

    fun trackConfiguration(): String =
        "tasks-vision=$tasksVersion;${delegate.name};VIDEO;numPoses=1;detection=0.5;presence=0.5;tracking=0.5;decoder=$decoderVersion;timestamps=source-PTS-ms;model=${variant.variantKey}"

    companion object {
        fun liveDefault(): PoseLandmarkerConfig = PoseLandmarkerConfig(
            assetPath = PoseLandmarkerVariant.FULL.defaultAssetPath,
            variant = PoseLandmarkerVariant.FULL,
        )

        fun offlineDefault(assetExists: (String) -> Boolean = { true }): PoseLandmarkerConfig {
            val candidatePaths = listOf(
                "mediapipe/pose_landmarker_heavy.task",
                "pose_landmarker_heavy.task",
            )
            val path = candidatePaths.firstOrNull(assetExists) ?: "pose_landmarker_heavy.task"
            return PoseLandmarkerConfig(
                assetPath = path,
                variant = PoseLandmarkerVariant.HEAVY,
            )
        }
    }
}

