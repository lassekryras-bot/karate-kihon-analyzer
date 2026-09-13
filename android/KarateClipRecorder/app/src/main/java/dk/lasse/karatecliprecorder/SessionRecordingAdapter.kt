package dk.lasse.karatecliprecorder

import java.io.File

/**
 * Common abstraction for session recording operations.
 */
interface SessionRecordingAdapter {
    fun beginMeasurementSession()
    fun endMeasurementSession()
    fun startRecording(customName: String? = null)
    fun stopRecording()
    fun createGuidedSessionFile(fileName: String): File
}

