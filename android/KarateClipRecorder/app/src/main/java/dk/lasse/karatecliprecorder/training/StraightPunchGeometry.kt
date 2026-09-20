package dk.lasse.karatecliprecorder.training

import dk.lasse.karateanalyzer.core.*
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
