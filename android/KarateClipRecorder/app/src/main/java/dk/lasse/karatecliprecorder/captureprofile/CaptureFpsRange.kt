package dk.lasse.karatecliprecorder.captureprofile

data class CaptureFpsRange(
    val minFps: Int,
    val maxFps: Int,
) {
    fun supports60(): Boolean = minFps <= 60 && maxFps >= 60

    fun supports30(): Boolean = minFps <= 30 && maxFps >= 30
}
