package dk.lasse.karatecliprecorder.movement

/** Holds exactly the Markdown snapshot selected before the document picker opens. */
internal class MovementDebugExportSnapshot {
    private var pending: String? = null

    fun begin(report: MovementDebugReport) {
        pending = report.toMarkdown()
    }

    fun consume(): String? = pending.also { pending = null }

    fun cancel() {
        pending = null
    }
}
