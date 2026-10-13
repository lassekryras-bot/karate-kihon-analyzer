package dk.lasse.karatecliprecorder.movement

/**
 * Immutable, presentation-only representation of diagnostic movement state.
 *
 * This type deliberately contains no analysis or persistence behavior.  A
 * report builder supplies the values that are already authoritative; the
 * adapters below only render those values.
 */
class MovementDebugReport(
    val title: String = "Movement Debug Report",
    val identity: String? = null,
    sections: List<MovementDebugSection> = emptyList(),
) {
    val sections: List<MovementDebugSection> = sections.toList()
    val orderedSections: List<MovementDebugSection> get() = sections

    init {
        require(sections.map { it.id }.distinct().size == sections.size) {
            "Movement debug section ids must be unique"
        }
    }

    fun findSection(id: String): MovementDebugSection? = sections.firstOrNull { it.id == id }

    fun requireSection(id: String): MovementDebugSection =
        findSection(id) ?: error("Movement debug section not found: $id")

    fun renderSectionPlainText(id: String): String = requireSection(id).toPlainText()

    fun toPlainText(): String = buildString {
        append(title)
        if (identity != null) {
            append("\nIdentity: ")
            append(identity)
        }
        if (sections.isNotEmpty()) append("\n\n")
        append(sections.joinToString("\n\n") { it.toPlainText() })
    }

    fun toMarkdown(): String = buildString {
        append("# ")
        append(markdownEscape(title))
        if (identity != null) {
            append("\n\nIdentity: ")
            append(markdownEscape(identity))
        }
        sections.forEach { section ->
            append("\n\n")
            append(section.toMarkdown())
        }
    }
}

class MovementDebugSection(
    val id: String,
    val title: String,
    entries: List<MovementDebugEntry> = emptyList(),
) {
    val entries: List<MovementDebugEntry> = entries.toList()
    val orderedEntries: List<MovementDebugEntry> get() = entries

    fun toPlainText(): String = buildString {
        append(title)
        entries.forEach { entry ->
            append("\n")
            append(entry.label)
            append(" [")
            append(entry.key)
            append("]: ")
            append(entry.value.asPlainText())
        }
    }

    internal fun toMarkdown(): String = buildString {
        append("## ")
        append(markdownEscape(title))
        entries.forEach { entry ->
            append("\n- ")
            append(markdownEscape(entry.label))
            append(" [")
            append(markdownEscape(entry.key))
            append("]: ")
            append(entry.value.asMarkdownText())
        }
    }
}

data class MovementDebugEntry(
    val key: String,
    val label: String,
    val value: MovementDebugValue,
)

sealed interface MovementDebugValue {
    data class Text(val value: String) : MovementDebugValue
    data class Integer(val value: Long) : MovementDebugValue
    data class Decimal(val value: Double) : MovementDebugValue
    data class BooleanValue(val value: Boolean) : MovementDebugValue
    data class Unavailable(val reason: String? = null) : MovementDebugValue

    fun asPlainText(): String = when (this) {
        is Text -> value
        is Integer -> value.toString()
        is Decimal -> value.toString()
        is BooleanValue -> value.toString()
        is Unavailable -> if (reason == null) "UNAVAILABLE" else "UNAVAILABLE ($reason)"
    }

    fun asMarkdownText(): String = markdownEscape(asPlainText())
}

private fun markdownEscape(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '*', '_', '#', '[', ']', '<', '>', '|' -> {
                append('\\')
                append(character)
            }
            else -> append(character)
        }
    }
}
