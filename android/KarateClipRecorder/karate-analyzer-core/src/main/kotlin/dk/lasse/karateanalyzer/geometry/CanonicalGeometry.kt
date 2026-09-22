package dk.lasse.karateanalyzer.geometry

/**
 * Declared order of operations for source-to-canonical transformation.
 */
enum class TransformOrder {
    ROTATION_THEN_MIRROR,
    MIRROR_THEN_ROTATION,
}

/**
 * Explicit transformation from encoded source media coordinate space
 * to the canonical upright, unmirrored coordinate space.
 */
data class SourceToCanonicalTransform(
    val rotationDegrees: Int, // 0, 90, 180, 270
    val isMirrored: Boolean = false,
    val scaleX: Float = 1.0f,
    val scaleY: Float = 1.0f,
    val order: TransformOrder = TransformOrder.ROTATION_THEN_MIRROR,
) {
    init {
        require(rotationDegrees in setOf(0, 90, 180, 270)) {
            "rotationDegrees must be one of 0, 90, 180, 270; was $rotationDegrees"
        }
        require(scaleX.isFinite() && scaleX > 0f) { "scaleX must be finite and > 0, was $scaleX" }
        require(scaleY.isFinite() && scaleY > 0f) { "scaleY must be finite and > 0, was $scaleY" }
    }
}

/**
 * Immutable, versioned descriptor defining the canonical coordinate interpretation
 * of a landmark stream.
 */
data class CanonicalGeometryDescriptor(
    val geometryId: String,
    val contractVersion: String = CONTRACT_VERSION,
    val recordingId: String,
    val landmarkTrackId: String? = null,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val canonicalOrientation: CanonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
    val sourceToCanonicalTransform: SourceToCanonicalTransform = SourceToCanonicalTransform(0),
    val adapterVersion: String = "v1",
    val sourceHash: String? = null,
    val trackHash: String? = null,
    val encodedWidth: Int? = null,
    val encodedHeight: Int? = null,
    val containerRotation: Int? = null,
) {
    init {
        require(geometryId.isNotBlank()) { "geometryId cannot be blank" }
        require(contractVersion.isNotBlank()) { "contractVersion cannot be blank" }
        require(recordingId.isNotBlank()) { "recordingId cannot be blank" }
        require(canonicalWidth >= 0) { "canonicalWidth cannot be negative, was $canonicalWidth" }
        require(canonicalHeight >= 0) { "canonicalHeight cannot be negative, was $canonicalHeight" }
    }

    val isAvailable: Boolean
        get() = geometryId != UNKNOWN_GEOMETRY_ID && canonicalWidth > 0 && canonicalHeight > 0

    fun toFrameGeometry(): FrameGeometry {
        check(isAvailable) { "Cannot create FrameGeometry from unavailable or unknown geometry: $geometryId" }
        return FrameGeometry(
            sourceWidth = canonicalWidth,
            sourceHeight = canonicalHeight,
            canonicalOrientation = canonicalOrientation,
        )
    }

    companion object {
        const val CONTRACT_VERSION = "canonical-geometry-v1"
        const val UNKNOWN_GEOMETRY_ID = "UNKNOWN_GEOMETRY"

        val UNKNOWN = CanonicalGeometryDescriptor(
            geometryId = UNKNOWN_GEOMETRY_ID,
            contractVersion = CONTRACT_VERSION,
            recordingId = "unknown",
            landmarkTrackId = null,
            canonicalWidth = 0,
            canonicalHeight = 0,
            canonicalOrientation = CanonicalOrientation.UPRIGHT_UNMIRRORED,
            sourceToCanonicalTransform = SourceToCanonicalTransform(0),
            adapterVersion = "unknown",
        )
    }
}

/**
 * Pure Kotlin codec for serializing and deserializing [CanonicalGeometryDescriptor] to/from JSON.
 * Avoids any platform (Android, org.json, Gson, Jackson) dependencies in karate-analyzer-core.
 */
object CanonicalGeometryCodec {

    fun encode(descriptor: CanonicalGeometryDescriptor): String {
        val t = descriptor.sourceToCanonicalTransform
        return buildString {
            append("{")
            append("\"geometryId\":\"").append(escape(descriptor.geometryId)).append("\",")
            append("\"contractVersion\":\"").append(escape(descriptor.contractVersion)).append("\",")
            append("\"recordingId\":\"").append(escape(descriptor.recordingId)).append("\",")
            if (descriptor.landmarkTrackId != null) {
                append("\"landmarkTrackId\":\"").append(escape(descriptor.landmarkTrackId)).append("\",")
            } else {
                append("\"landmarkTrackId\":null,")
            }
            append("\"canonicalWidth\":").append(descriptor.canonicalWidth).append(",")
            append("\"canonicalHeight\":").append(descriptor.canonicalHeight).append(",")
            append("\"canonicalOrientation\":\"").append(descriptor.canonicalOrientation.name).append("\",")
            append("\"sourceToCanonicalTransform\":{")
            append("\"rotationDegrees\":").append(t.rotationDegrees).append(",")
            append("\"isMirrored\":").append(t.isMirrored).append(",")
            append("\"scaleX\":").append(t.scaleX).append(",")
            append("\"scaleY\":").append(t.scaleY).append(",")
            append("\"order\":\"").append(t.order.name).append("\"")
            append("},")
            append("\"adapterVersion\":\"").append(escape(descriptor.adapterVersion)).append("\",")
            append("\"sourceHash\":").append(descriptor.sourceHash?.let { "\"${escape(it)}\"" } ?: "null").append(",")
            append("\"trackHash\":").append(descriptor.trackHash?.let { "\"${escape(it)}\"" } ?: "null").append(",")
            append("\"encodedWidth\":").append(descriptor.encodedWidth?.toString() ?: "null").append(",")
            append("\"encodedHeight\":").append(descriptor.encodedHeight?.toString() ?: "null").append(",")
            append("\"containerRotation\":").append(descriptor.containerRotation?.toString() ?: "null")
            append("}")
        }
    }

    fun decode(json: String?): CanonicalGeometryDescriptor? {
        if (json.isNullOrBlank()) return null
        return try {
            val geometryId = extractString(json, "geometryId") ?: return null
            if (geometryId == CanonicalGeometryDescriptor.UNKNOWN_GEOMETRY_ID) {
                return CanonicalGeometryDescriptor.UNKNOWN
            }
            val contractVersion = extractString(json, "contractVersion") ?: CanonicalGeometryDescriptor.CONTRACT_VERSION
            val recordingId = extractString(json, "recordingId") ?: return null
            val landmarkTrackId = extractString(json, "landmarkTrackId")
            val canonicalWidth = extractInt(json, "canonicalWidth") ?: return null
            val canonicalHeight = extractInt(json, "canonicalHeight") ?: return null
            val canonicalOrientationStr = extractString(json, "canonicalOrientation") ?: CanonicalOrientation.UPRIGHT_UNMIRRORED.name
            val canonicalOrientation = runCatching { CanonicalOrientation.valueOf(canonicalOrientationStr) }
                .getOrDefault(CanonicalOrientation.UPRIGHT_UNMIRRORED)

            val rotationDegrees = extractNestedInt(json, "sourceToCanonicalTransform", "rotationDegrees") ?: 0
            val isMirrored = extractNestedBoolean(json, "sourceToCanonicalTransform", "isMirrored") ?: false
            val scaleX = extractNestedFloat(json, "sourceToCanonicalTransform", "scaleX") ?: 1.0f
            val scaleY = extractNestedFloat(json, "sourceToCanonicalTransform", "scaleY") ?: 1.0f
            val orderStr = extractNestedString(json, "sourceToCanonicalTransform", "order") ?: TransformOrder.ROTATION_THEN_MIRROR.name
            val order = runCatching { TransformOrder.valueOf(orderStr) }.getOrDefault(TransformOrder.ROTATION_THEN_MIRROR)

            val transform = SourceToCanonicalTransform(
                rotationDegrees = rotationDegrees,
                isMirrored = isMirrored,
                scaleX = scaleX,
                scaleY = scaleY,
                order = order,
            )

            val adapterVersion = extractString(json, "adapterVersion") ?: "unknown"
            val sourceHash = extractString(json, "sourceHash")
            val trackHash = extractString(json, "trackHash")
            val encodedWidth = extractInt(json, "encodedWidth")
            val encodedHeight = extractInt(json, "encodedHeight")
            val containerRotation = extractInt(json, "containerRotation")

            CanonicalGeometryDescriptor(
                geometryId = geometryId,
                contractVersion = contractVersion,
                recordingId = recordingId,
                landmarkTrackId = landmarkTrackId,
                canonicalWidth = canonicalWidth,
                canonicalHeight = canonicalHeight,
                canonicalOrientation = canonicalOrientation,
                sourceToCanonicalTransform = transform,
                adapterVersion = adapterVersion,
                sourceHash = sourceHash,
                trackHash = trackHash,
                encodedWidth = encodedWidth,
                encodedHeight = encodedHeight,
                containerRotation = containerRotation,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun extractString(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractInt(json: String, key: String): Int? {
        val pattern = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractNestedObject(json: String, parentKey: String): String? {
        val idx = json.indexOf("\"$parentKey\"")
        if (idx < 0) return null
        val braceStart = json.indexOf('{', idx)
        if (braceStart < 0) return null
        var depth = 1
        var cur = braceStart + 1
        while (cur < json.length && depth > 0) {
            if (json[cur] == '{') depth++
            else if (json[cur] == '}') depth--
            cur++
        }
        return if (depth == 0) json.substring(braceStart, cur) else null
    }

    private fun extractNestedInt(json: String, parentKey: String, key: String): Int? {
        val nested = extractNestedObject(json, parentKey) ?: return null
        return extractInt(nested, key)
    }

    private fun extractNestedFloat(json: String, parentKey: String, key: String): Float? {
        val nested = extractNestedObject(json, parentKey) ?: return null
        val pattern = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+)")
        return pattern.find(nested)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractNestedBoolean(json: String, parentKey: String, key: String): Boolean? {
        val nested = extractNestedObject(json, parentKey) ?: return null
        val pattern = Regex("\"$key\"\\s*:\\s*(true|false)")
        return pattern.find(nested)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractNestedString(json: String, parentKey: String, key: String): String? {
        val nested = extractNestedObject(json, parentKey) ?: return null
        return extractString(nested, key)
    }
}
