package dk.lasse.karatecliprecorder.movement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MovementDebugReportTest {

    @Test
    fun sectionAndFullTextAreDerivedFromTheSameOrderedReport() {
        val report = MovementDebugReport(
            title = "Movement debug",
            identity = "movement-42",
            sections = listOf(
                MovementDebugSection(
                    id = "movement",
                    title = "Movement",
                    entries = listOf(
                        MovementDebugEntry("first", "First", MovementDebugValue.Text("one")),
                        MovementDebugEntry("second", "Second", MovementDebugValue.Integer(2L)),
                    ),
                ),
                MovementDebugSection(
                    id = "analysis",
                    title = "Analysis",
                    entries = listOf(
                        MovementDebugEntry("status", "Status", MovementDebugValue.Text("COMPLETED")),
                    ),
                ),
            ),
        )

        assertEquals(
            "Movement\nFirst [first]: one\nSecond [second]: 2",
            report.renderSectionPlainText("movement"),
        )
        assertEquals(
            "Movement debug\nIdentity: movement-42\n\n" +
                "Movement\nFirst [first]: one\nSecond [second]: 2\n\n" +
                "Analysis\nStatus [status]: COMPLETED",
            report.toPlainText(),
        )
        assertFailsWith<IllegalStateException> { report.renderSectionPlainText("missing") }
    }

    @Test
    fun zeroAndUnavailableRemainDistinctAndUnavailableReasonIsRendered() {
        val zero = MovementDebugValue.Decimal(0.0)
        val unavailable = MovementDebugValue.Unavailable("JOINT_GEOMETRY_UNAVAILABLE")
        val section = MovementDebugSection(
            id = "values",
            title = "Values",
            entries = listOf(
                MovementDebugEntry("zero", "Zero", zero),
                MovementDebugEntry("missing", "Missing", unavailable),
            ),
        )

        assertEquals("0.0", zero.asPlainText())
        assertEquals("UNAVAILABLE (JOINT_GEOMETRY_UNAVAILABLE)", unavailable.asPlainText())
        assertTrue(section.toPlainText().contains("Zero [zero]: 0.0"))
        assertTrue(section.toPlainText().contains("Missing [missing]: UNAVAILABLE (JOINT_GEOMETRY_UNAVAILABLE)"))
    }

    @Test
    fun exactLongAndDoubleStringsAreUsedWithoutLocaleFormatting() {
        val section = MovementDebugSection(
            id = "raw",
            title = "Raw",
            entries = listOf(
                MovementDebugEntry("timestamp", "Timestamp", MovementDebugValue.Integer(1_234_567_890_123L)),
                MovementDebugEntry("sample", "Sample", MovementDebugValue.Decimal(1.0E-9)),
            ),
        )

        assertEquals("Raw\nTimestamp [timestamp]: 1234567890123\nSample [sample]: 1.0E-9", section.toPlainText())
    }

    @Test
    fun markdownEscapesContentAndPreservesOrder() {
        val report = MovementDebugReport(
            title = "Report * [title]",
            identity = "id\\part",
            sections = listOf(
                MovementDebugSection(
                    id = "first",
                    title = "First # section",
                    entries = listOf(
                        MovementDebugEntry(
                            key = "a_b",
                            label = "Label [x]",
                            value = MovementDebugValue.Text("line1\\line2\n*star _under #hash [bracket] <angle> |pipe|"),
                        ),
                    ),
                ),
                MovementDebugSection(
                    id = "second",
                    title = "Second",
                    entries = listOf(
                        MovementDebugEntry("flag", "Flag", MovementDebugValue.BooleanValue(true)),
                    ),
                ),
            ),
        )

        assertEquals(
            "# Report \\* \\[title\\]\n\n" +
                "Identity: id\\\\part\n\n" +
                "## First \\# section\n" +
                "- Label \\[x\\] [a\\_b]: line1\\\\line2\\n\\*star \\_under \\#hash \\[bracket\\] \\<angle\\> \\|pipe\\|\n\n" +
                "## Second\n" +
                "- Flag [flag]: true",
            report.toMarkdown(),
        )
    }
}
