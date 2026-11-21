package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
import dk.lasse.karateanalyzer.geometry.SourceNormalizedPoint
import org.json.JSONArray
import org.json.JSONObject

data class EvidencePoint(val x: Float, val y: Float)
data class TargetRayGeometry(val targetType: PunchHeightTargetType, val endpoint: EvidencePoint, val isClosest: Boolean)
data class StraightPunchGeometry(
    val timestampUs: Long,
    val frameIndex: Long?,
    val activeSide: BodySide,
    val origin: EvidencePoint,
    val rays: List<TargetRayGeometry>,
)

/** Persisted drawing evidence; never reconstructs targets from landmarks on read. */
object StraightPunchGeometryCodec {
    const val CONTRACT = "straight_punch_geometry_v1"
    const val COORDINATES = "normalized_upright_unmirrored_image"

    fun encode(evaluation: StraightPunchTargetEvaluation, frameIndex: Long?): String? = runCatching {
        require(evaluation.state == TargetRayState.VALID || evaluation.state == TargetRayState.UNREACHABLE)
        val origin = requireNotNull(evaluation.shoulderPoint)
        val side = when (evaluation.activeArm) {
            ActiveArm.LEFT -> BodySide.LEFT
            ActiveArm.RIGHT -> BodySide.RIGHT
            else -> error("Missing active arm")
        }
        val rays = JSONArray()
        evaluation.targetResults.values.filter { it.state == TargetRayState.VALID }.forEach { ray ->
            val endpoint = requireNotNull(ray.idealEndpoint)
            rays.put(JSONObject().apply {
                put("targetType", ray.targetType.name)
                put("endpointX", endpoint.x.toDouble())
                put("endpointY", endpoint.y.toDouble())
                put("isClosest", evaluation.closestTarget == ray.targetType)
            })
        }
        val json = JSONObject().apply {
            put("contract", CONTRACT)
            put("coordinateSpace", COORDINATES)
            put("timestampUs", evaluation.analysisFrameTimestampMs * 1000L)
            put("frameIndex", frameIndex ?: JSONObject.NULL)
            put("activeSide", side.name)
            put("origin", JSONObject().put("x", origin.x.toDouble()).put("y", origin.y.toDouble()))
            put("rays", rays)
        }.toString()
        requireNotNull(decode(json))
        json
    }.getOrNull()

    /** All-or-nothing validation. Unversioned legacy payloads require reanalysis. */
    fun decode(json: String?): StraightPunchGeometry? = json?.let { runCatching {
        val root = JSONObject(it)
        require(root.getString("contract") == CONTRACT)
        require(root.getString("coordinateSpace") == COORDINATES)
        fun JSONObject.coordinate(key: String): Float {
            val value = get(key) as? Number ?: error("Invalid coordinate")
            return value.toFloat().also { result -> require(result.isFinite()) }
        }
        fun JSONObject.identity(key: String): Long {
            val value = get(key) as? Number ?: error("Invalid sample identity")
            val result = value.toLong()
            require(result >= 0 && value.toDouble().isFinite() && value.toDouble() == result.toDouble())
            return result
        }
        val side = BodySide.valueOf(root.getString("activeSide"))
        require(side == BodySide.LEFT || side == BodySide.RIGHT)
        val origin = root.getJSONObject("origin").let { point ->
            EvidencePoint(point.coordinate("x"), point.coordinate("y"))
        }
        val array = root.getJSONArray("rays")
        require(array.length() in 1..3)
        val rays = (0 until array.length()).map { index ->
            val ray = array.getJSONObject(index)
            val closest = ray.get("isClosest") as? Boolean ?: error("Invalid classification")
            TargetRayGeometry(
                PunchHeightTargetType.valueOf(ray.getString("targetType")),
                EvidencePoint(ray.coordinate("endpointX"), ray.coordinate("endpointY")), closest
            )
        }
        require(rays.map { ray -> ray.targetType }.distinct().size == rays.size)
        require(rays.count { ray -> ray.isClosest } <= 1)
        val frameIndex = if (root.isNull("frameIndex")) null else root.identity("frameIndex")
        StraightPunchGeometry(root.identity("timestampUs"), frameIndex, side, origin, rays)
    }.getOrNull() }
}

data class HorizontalGuideGeometry(
    val targetType: PunchHeightTargetType,
    val targetPoint: EvidencePoint?,
    val signedErrorNormalized: Double?,
    val signedErrorCanonicalPx: Double?,
    val estimatorVersion: String,
    val reason: String,
)

data class StraightPunchHorizontalGeometry(
    val recordingId: String,
    val landmarkTrackId: String,
    val frameGeometryId: String,
    val frameGeometryContractVersion: String,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val timestampUs: Long,
    val frameIndex: Long,
    val activeSide: BodySide,
    val fist: EvidencePoint,
    val fistEstimatorVersion: String,
    val shoulderCenter: EvidencePoint?,
    val hipCenter: EvidencePoint?,
    val guides: List<HorizontalGuideGeometry>,
    val closestState: HorizontalClosestState,
    val closestTarget: PunchHeightTargetType?,
    val closestReason: String,
    val facing: ImageFacingDirection,
    val facingSource: String,
)

/** Distinct persisted contract for exact-frame horizontal target evidence. */
object StraightPunchHorizontalGeometryCodec {
    const val CONTRACT = "straight_punch_horizontal_geometry_v1"
    const val COORDINATES = StraightPunchHorizontalTargetAnalyzer.COORDINATE_SPACE

    fun encode(result: StraightPunchHorizontalTargetResult): String = JSONObject().apply {
        put("contract", CONTRACT)
        put("coordinateSpace", COORDINATES)
        put("recordingId", result.identity.recordingId)
        put("landmarkTrackId", result.identity.landmarkTrackId)
        put("frameGeometryId", result.identity.frameGeometryId)
        put("frameGeometryContractVersion", result.identity.frameGeometryContractVersion)
        put("canonicalWidth", result.identity.canonicalWidth)
        put("canonicalHeight", result.identity.canonicalHeight)
        put("timestampUs", result.identity.timestampUs)
        put("frameIndex", result.identity.frameIndex)
        put("activeSide", result.identity.side.name)
        put("fist", result.fist.toJson())
        put("fistEstimatorVersion", result.fistEstimatorVersion)
        put("shoulderCenter", result.shoulderCenter?.toJson() ?: JSONObject.NULL)
        put("hipCenter", result.hipCenter?.toJson() ?: JSONObject.NULL)
        put("closestState", result.closestState.name)
        put("closestTarget", result.closestTarget?.name ?: JSONObject.NULL)
        put("closestReason", result.closestReason)
        put("facing", result.facing.name)
        put("facingSource", result.facingSource)
        put("guides", JSONArray().apply {
            result.guides.values.forEach { guide -> put(JSONObject().apply {
                put("targetType", guide.target.name)
                put("targetPoint", guide.targetPoint?.toJson() ?: JSONObject.NULL)
                put("signedErrorNormalized", guide.signedErrorNormalized ?: JSONObject.NULL)
                put("signedErrorCanonicalPx", guide.signedErrorCanonicalPx ?: JSONObject.NULL)
                put("estimatorVersion", guide.estimatorVersion)
                put("reason", guide.reason)
            }) }
        })
    }.toString().also { requireNotNull(decode(it)) }

    fun decode(json: String?): StraightPunchHorizontalGeometry? = json?.let { runCatching {
        val root = JSONObject(it)
        require(root.getString("contract") == CONTRACT)
        require(root.getString("coordinateSpace") == COORDINATES)
        fun JSONObject.identity(key: String): Long {
            val number = get(key) as? Number ?: error("Invalid identity")
            return number.toLong().also { require(it >= 0L && number.toDouble() == it.toDouble()) }
        }
        fun JSONObject.point(key: String): EvidencePoint? = if (isNull(key)) null else getJSONObject(key).let { p ->
            val x = (p.get("x") as? Number)?.toFloat() ?: error("Invalid point")
            val y = (p.get("y") as? Number)?.toFloat() ?: error("Invalid point")
            require(x.isFinite() && y.isFinite())
            EvidencePoint(x, y)
        }
        fun JSONObject.number(key: String): Double? = if (isNull(key)) null else
            ((get(key) as? Number)?.toDouble() ?: error("Invalid number")).also { require(it.isFinite()) }
        val side = BodySide.valueOf(root.getString("activeSide"))
        require(side == BodySide.LEFT || side == BodySide.RIGHT)
        val guidesJson = root.getJSONArray("guides")
        require(guidesJson.length() == 3)
        val guides = (0 until guidesJson.length()).map { index -> guidesJson.getJSONObject(index).let { guide ->
            val point = guide.point("targetPoint")
            val normalized = guide.number("signedErrorNormalized")
            val pixels = guide.number("signedErrorCanonicalPx")
            require((point == null) == (normalized == null) && (normalized == null) == (pixels == null))
            HorizontalGuideGeometry(PunchHeightTargetType.valueOf(guide.getString("targetType")), point,
                normalized, pixels, guide.getString("estimatorVersion"), guide.getString("reason"))
        } }
        require(guides.map { it.targetType }.toSet() == PunchHeightTargetType.entries.toSet())
        val closestState = HorizontalClosestState.valueOf(root.getString("closestState"))
        val closest = if (root.isNull("closestTarget")) null else PunchHeightTargetType.valueOf(root.getString("closestTarget"))
        require((closestState == HorizontalClosestState.RESOLVED) == (closest != null))
        val canonicalWidth = root.getInt("canonicalWidth")
        val canonicalHeight = root.getInt("canonicalHeight")
        require(canonicalWidth > 0 && canonicalHeight > 0)
        StraightPunchHorizontalGeometry(
            root.getString("recordingId"), root.getString("landmarkTrackId"), root.getString("frameGeometryId"),
            root.getString("frameGeometryContractVersion"), canonicalWidth, canonicalHeight,
            root.identity("timestampUs"), root.identity("frameIndex"), side,
            requireNotNull(root.point("fist")), root.getString("fistEstimatorVersion"), root.point("shoulderCenter"),
            root.point("hipCenter"), guides, closestState, closest, root.getString("closestReason"),
            ImageFacingDirection.valueOf(root.getString("facing")), root.getString("facingSource"),
        )
    }.getOrNull() }

    private fun SourceNormalizedPoint.toJson() = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
}
