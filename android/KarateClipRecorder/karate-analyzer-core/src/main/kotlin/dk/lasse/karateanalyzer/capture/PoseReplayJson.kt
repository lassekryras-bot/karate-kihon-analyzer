package dk.lasse.karateanalyzer.capture

import dk.lasse.karateanalyzer.core.LandmarkSource
import dk.lasse.karateanalyzer.core.Point3
import dk.lasse.karateanalyzer.core.PoseFrame
import dk.lasse.karateanalyzer.core.PoseLandmarkId
import dk.lasse.karateanalyzer.core.PoseLandmarkSample

/** Narrow deterministic JSON codec for replay fixtures; it is not a general persistence layer. */
object PoseReplayJson {
    fun encode(fixture: PoseReplayFixture): String = buildString {
        append('{')
        field("schema_version", fixture.schemaVersion); append(',')
        field("sequence_id", fixture.sequenceId); append(',')
        nullableNumber("arm_timestamp_ms", fixture.armTimestampMs); append(',')
        nullableNumber("cue_timestamp_ms", fixture.cueTimestampMs); append(',')
        nullableNumber("activity_deadline_ms", fixture.activityDeadlineMs); append(',')
        field("expected_end_pose_relationship", fixture.expectedEndPoseRelationship.name); append(',')
        append("\"notes\":"); stringArray(fixture.notes); append(',')
        append("\"labels\":"); labels(fixture.labels); append(',')
        append("\"frames\":[")
        fixture.frames.forEachIndexed { frameIndex, frame ->
            if (frameIndex > 0) append(',')
            append("{\"timestamp_ms\":${frame.timestampMs},\"landmarks\":[")
            frame.landmarks.entries.sortedBy { it.key.ordinal }.forEachIndexed { index, (id, sample) ->
                if (index > 0) append(',')
                append('{'); field("id", id.name); append(',')
                append("\"normalized\":"); point(sample.position); append(',')
                append("\"world\":"); point(sample.worldPosition); append(',')
                append("\"visibility\":${sample.visibility},\"presence\":${sample.presence},")
                field("source", sample.source.name); append('}')
            }
            append("]}")
        }
        append("]}\n")
    }

    fun decode(json: String): PoseReplayFixture {
        val root = JsonParser(json).parseObject()
        val labels = root.objOrNull("labels")?.let(::decodeLabels)
        return PoseReplayFixture(
            schemaVersion = root.string("schema_version"),
            sequenceId = root.string("sequence_id"),
            frames = root.array("frames").map { value ->
                val frame = value.asObject()
                PoseFrame(
                    timestampMs = frame.long("timestamp_ms"),
                    landmarks = frame.array("landmarks").associate { landmarkValue ->
                        val item = landmarkValue.asObject()
                        val id = PoseLandmarkId.valueOf(item.string("id"))
                        id to PoseLandmarkSample(
                            position = item.pointOrNull("normalized"),
                            worldPosition = item.pointOrNull("world"),
                            visibility = item.double("visibility").toFloat(),
                            presence = item.double("presence").toFloat(),
                            source = LandmarkSource.valueOf(item.string("source")),
                        )
                    },
                )
            },
            armTimestampMs = root.longOrNull("arm_timestamp_ms"),
            cueTimestampMs = root.longOrNull("cue_timestamp_ms"),
            activityDeadlineMs = root.longOrNull("activity_deadline_ms"),
            expectedEndPoseRelationship = EndPoseRelationship.valueOf(root.string("expected_end_pose_relationship")),
            labels = labels,
            notes = root.array("notes").map { it.asString() },
        )
    }

    private fun StringBuilder.labels(labels: PoseSequenceLabels?) {
        if (labels == null) { append("null"); return }
        append('{')
        nullableNumber("movement_start_ms", labels.movementStartTimestampMs); append(',')
        nullableNumber("movement_end_ms", labels.movementEndTimestampMs); append(',')
        append("\"terminal_stable_interval\":"); interval(labels.terminalStableInterval); append(',')
        append("\"intermediate_stable_plateaus\":"); intervals(labels.intermediateStablePlateaus); append(',')
        append("\"tracking_loss_intervals\":"); intervals(labels.trackingLossIntervals); append(',')
        append("\"ambiguous_intervals\":"); intervals(labels.ambiguousIntervals); append(',')
        append("\"anticipatory_movement\":${labels.anticipatoryMovement}}")
    }

    private fun decodeLabels(value: Map<String, JsonValue>) = PoseSequenceLabels(
        movementStartTimestampMs = value.longOrNull("movement_start_ms"),
        movementEndTimestampMs = value.longOrNull("movement_end_ms"),
        terminalStableInterval = value.intervalOrNull("terminal_stable_interval"),
        intermediateStablePlateaus = value.intervals("intermediate_stable_plateaus"),
        trackingLossIntervals = value.intervals("tracking_loss_intervals"),
        ambiguousIntervals = value.intervals("ambiguous_intervals"),
        anticipatoryMovement = value.boolean("anticipatory_movement"),
    )

    private fun StringBuilder.field(name: String, value: String) { append('"').append(name).append("\":"); quoted(value) }
    private fun StringBuilder.nullableNumber(name: String, value: Long?) { append('"').append(name).append("\":").append(value ?: "null") }
    private fun StringBuilder.quoted(value: String) { append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"') }
    private fun StringBuilder.stringArray(values: List<String>) { append('['); values.forEachIndexed { i, value -> if (i > 0) append(','); quoted(value) }; append(']') }
    private fun StringBuilder.point(value: Point3?) { if (value == null) append("null") else append("[${value.x},${value.y},${value.z}]") }
    private fun StringBuilder.interval(value: ReplayInterval?) { if (value == null) append("null") else append("[${value.startTimestampMs},${value.endTimestampMs}]") }
    private fun StringBuilder.intervals(values: List<ReplayInterval>) { append('['); values.forEachIndexed { i, value -> if (i > 0) append(','); interval(value) }; append(']') }
}

private sealed interface JsonValue {
    data class Object(val value: Map<String, JsonValue>) : JsonValue
    data class Array(val value: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class Number(val value: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

private class JsonParser(private val source: String) {
    private var index = 0
    fun parseObject(): Map<String, JsonValue> = parseValue().asObject().also { whitespace(); require(index == source.length) }
    private fun parseValue(): JsonValue { whitespace(); return when (source.getOrNull(index)) {
        '{' -> objectValue(); '[' -> arrayValue(); '"' -> JsonValue.StringValue(string())
        't' -> literal("true", JsonValue.BooleanValue(true)); 'f' -> literal("false", JsonValue.BooleanValue(false))
        'n' -> literal("null", JsonValue.Null); else -> number()
    } }
    private fun objectValue(): JsonValue.Object { expect('{'); val result = linkedMapOf<String, JsonValue>(); whitespace(); if (take('}')) return JsonValue.Object(result); do { whitespace(); val key = string(); expect(':'); result[key] = parseValue() } while (take(',')); expect('}'); return JsonValue.Object(result) }
    private fun arrayValue(): JsonValue.Array { expect('['); val result = mutableListOf<JsonValue>(); whitespace(); if (take(']')) return JsonValue.Array(result); do { result += parseValue() } while (take(',')); expect(']'); return JsonValue.Array(result) }
    private fun string(): String { expect('"'); val out = StringBuilder(); while (true) { val char = source[index++]; if (char == '"') return out.toString(); if (char == '\\') { val escaped = source[index++]; out.append(when (escaped) { '"' -> '"'; '\\' -> '\\'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> error("Unsupported escape \\$escaped") }) } else out.append(char) } }
    private fun number(): JsonValue.Number { val start = index; while (source.getOrNull(index)?.let { it.isDigit() || it in ".-+eE" } == true) index++; require(index > start); return JsonValue.Number(source.substring(start, index).toDouble()) }
    private fun literal(text: String, value: JsonValue): JsonValue { require(source.startsWith(text, index)); index += text.length; return value }
    private fun expect(char: Char) { whitespace(); require(source.getOrNull(index) == char) { "Expected $char at $index" }; index++ }
    private fun take(char: Char): Boolean { whitespace(); if (source.getOrNull(index) != char) return false; index++; return true }
    private fun whitespace() { while (source.getOrNull(index)?.isWhitespace() == true) index++ }
}

private fun JsonValue.asObject() = (this as JsonValue.Object).value
private fun JsonValue.asString() = (this as JsonValue.StringValue).value
private fun Map<String, JsonValue>.string(key: String) = getValue(key).asString()
private fun Map<String, JsonValue>.array(key: String) = (getValue(key) as JsonValue.Array).value
private fun Map<String, JsonValue>.double(key: String) = (getValue(key) as JsonValue.Number).value
private fun Map<String, JsonValue>.long(key: String) = double(key).toLong()
private fun Map<String, JsonValue>.longOrNull(key: String) = getValue(key).let { if (it == JsonValue.Null) null else (it as JsonValue.Number).value.toLong() }
private fun Map<String, JsonValue>.boolean(key: String) = (getValue(key) as JsonValue.BooleanValue).value
private fun Map<String, JsonValue>.objOrNull(key: String) = getValue(key).let { if (it == JsonValue.Null) null else it.asObject() }
private fun Map<String, JsonValue>.pointOrNull(key: String): Point3? = getValue(key).let { value -> if (value == JsonValue.Null) null else (value as JsonValue.Array).value.map { (it as JsonValue.Number).value }.let { Point3(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) } }
private fun Map<String, JsonValue>.intervalOrNull(key: String): ReplayInterval? = getValue(key).let { if (it == JsonValue.Null) null else it.toInterval() }
private fun Map<String, JsonValue>.intervals(key: String) = array(key).map { it.toInterval() }
private fun JsonValue.toInterval() = (this as JsonValue.Array).value.map { (it as JsonValue.Number).value.toLong() }.let { ReplayInterval(it[0], it[1]) }
