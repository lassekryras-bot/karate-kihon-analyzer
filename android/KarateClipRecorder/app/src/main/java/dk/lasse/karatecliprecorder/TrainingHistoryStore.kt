package dk.lasse.karatecliprecorder

import android.content.Context
import android.os.Environment
import java.io.File

/** Deletes only the known completed-session directories owned by this app. */
class TrainingHistoryStore(context: Context) {
    private val appContext = context.applicationContext

    fun clear(): ClearTrainingHistoryResult {
        val targets = knownHistoryDirectories().distinctBy(File::getAbsolutePath)
        val existing = targets.filter(File::exists)
        val failed = existing.filterNot(File::deleteRecursively)
        return ClearTrainingHistoryResult(
            removedDirectoryCount = existing.size - failed.size,
            failedDirectories = failed.map(File::getAbsolutePath),
        )
    }

    internal fun knownHistoryDirectories(): List<File> {
        val moviesRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir
        val picturesRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: appContext.filesDir
        return listOf(
            File(moviesRoot, "guided_jodan_session"),
            File(picturesRoot, "punch_height_level_1"),
        )
    }
}

data class ClearTrainingHistoryResult(
    val removedDirectoryCount: Int,
    val failedDirectories: List<String>,
) {
    val succeeded: Boolean get() = failedDirectories.isEmpty()
}
