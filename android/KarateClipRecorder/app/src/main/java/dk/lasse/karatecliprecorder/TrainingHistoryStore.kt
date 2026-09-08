package dk.lasse.karatecliprecorder

import android.content.Context
import android.os.Environment
import java.io.File

/** Only profile-owned capture folders may be removed; legacy shared captures stay. */
class TrainingHistoryStore(context: Context) {
    private val appContext = context.applicationContext

    fun clear(profileId: String): ClearTrainingHistoryResult {
        require(profileId.matches(Regex("[A-Za-z0-9_-]+")))
        val pictures = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir
        val root = File(pictures, "punch_height_level_1/profiles").canonicalFile
        val target = File(root, profileId).canonicalFile
        require(target.parentFile == root)
        val existed = target.exists()
        val failed = existed && !target.deleteRecursively()
        return ClearTrainingHistoryResult(if (existed && !failed) 1 else 0,
            if (failed) listOf(target.absolutePath) else emptyList())
    }
}

data class ClearTrainingHistoryResult(
    val removedDirectoryCount: Int,
    val failedDirectories: List<String>,
) {
    val succeeded: Boolean get() = failedDirectories.isEmpty()
}
