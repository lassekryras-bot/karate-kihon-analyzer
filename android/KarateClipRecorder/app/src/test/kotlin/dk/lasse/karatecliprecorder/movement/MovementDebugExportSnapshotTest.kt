package dk.lasse.karatecliprecorder.movement

import kotlin.test.*
import org.junit.Test

class MovementDebugExportSnapshotTest {
    private fun report(value: String) = MovementDebugReport(
        sections = listOf(MovementDebugSection("movement", "Movement", listOf(
            MovementDebugEntry("value", "Value", MovementDebugValue.Text(value)),
        ))),
    )

    @Test
    fun exportConsumesTheClickedSnapshotNotLaterReportState() {
        val state = MovementDebugExportSnapshot()
        state.begin(report("before"))
        report("after")
        assertTrue(state.consume()!!.contains("before"))
        assertNull(state.consume())
    }

    @Test
    fun cancelledPickerClearsPendingSnapshot() {
        val state = MovementDebugExportSnapshot()
        state.begin(report("pending"))
        state.cancel()
        assertNull(state.consume())
    }
}
