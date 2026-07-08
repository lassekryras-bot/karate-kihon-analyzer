package dk.lasse.karatecliprecorder

import android.net.Uri

data class RecordingResult(
    val fileName: String,
    val absolutePath: String,
    val uri: Uri,
)
