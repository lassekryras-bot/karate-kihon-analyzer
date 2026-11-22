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

data class CurrentFrameAngularTargetGeometry(
    val targetType: PunchHeightTargetType,
    val state: CurrentFrameAngularTargetState,
    val targetPoint: EvidencePoint?,
    val idealEndpoint: EvidencePoint?,
    val idealAngleDeg: Double?,
    val signedErrorDeg: Double?,
    val horizontalErrorNormalized: Double?,
    val horizontalErrorCanonicalPx: Double?,
    val guideEstimatorVersion: String,
    val reason: String,
)

data class StraightPunchCurrentFrameAngularGeometry(
    val recordingId: String,
    val landmarkTrackId: String,
    val frameGeometryId: String,
    val frameGeometryContractVersion: String,
    val canonicalWidth: Int,
    val canonicalHeight: Int,
    val timestampUs: Long,
    val frameIndex: Long,
    val activeSide: BodySide,
    val state: CurrentFrameAngularState,
    val selectionMode: CurrentFrameTargetSelectionMode,
    val declaredTargetRaw: String?,
    val selectedTarget: PunchHeightTargetType?,
    val selectionReason: String,
    val comparisonComplete: Boolean,
    val facing: ImageFacingDirection,
    val facingSource: String,
    val shoulder: EvidencePoint?,
    val fist: EvidencePoint?,
    val fistEstimatorVersion: String,
    val actualAngleDeg: Double?,
    val signedErrorDeg: Double?,
    val reach: CompositeFistReachEvidence,
    val targets: List<CurrentFrameAngularTargetGeometry>,
)

/** Persisted exact-frame angular evidence. All analytical quantities are decoded, never re-estimated by UI. */
object StraightPunchCurrentFrameAngularGeometryCodec {
    const val CONTRACT = "straight_punch_current_frame_angular_geometry_v1"
    const val COORDINATES = StraightPunchCurrentFrameAngularAnalyzer.COORDINATE_SPACE

    fun encode(result: StraightPunchCurrentFrameAngularResult, horizontal: StraightPunchHorizontalTargetResult): String {
        require(result.identity == horizontal.identity)
        fun SourceNormalizedPoint.json() = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        fun number(value: Double?) = value ?: JSONObject.NULL
        val identity = result.identity
        return JSONObject().apply {
            put("contract", CONTRACT)
            put("coordinateSpace", COORDINATES)
            put("recordingId", identity.recordingId)
            put("landmarkTrackId", identity.landmarkTrackId)
            put("frameGeometryId", identity.frameGeometryId)
            put("frameGeometryContractVersion", identity.frameGeometryContractVersion)
            put("canonicalWidth", identity.canonicalWidth)
            put("canonicalHeight", identity.canonicalHeight)
            put("timestampUs", identity.timestampUs)
            put("frameIndex", identity.frameIndex)
            put("activeSide", identity.side.name)
            put("state", result.state.name)
            put("selectionMode", result.selectionMode.name)
            put("declaredTargetRaw", result.declaredTargetRaw ?: JSONObject.NULL)
            put("selectedTarget", result.selectedTarget?.name ?: JSONObject.NULL)
            put("selectionReason", result.selectionReason)
            put("comparisonComplete", result.comparisonComplete)
            put("facing", result.facing.name)
            put("facingSource", result.facingSource)
            put("shoulder", result.shoulder?.json() ?: JSONObject.NULL)
            put("fist", result.fist?.json() ?: JSONObject.NULL)
            put("fistEstimatorVersion", result.fistEstimatorVersion)
            put("actualAngleDeg", number(result.actualAngleDeg))
            put("signedErrorDeg", number(result.signedErrorDeg))
            put("reach", JSONObject().apply {
                put("estimatorVersion", result.reach.estimatorVersion)
                put("state", result.reach.state.name)
                put("radiusAspectCorrect", number(result.reach.radiusAspectCorrect))
                put("upperArmP80AspectCorrect", number(result.reach.upperArmP80AspectCorrect))
                put("distalCompositeFistP80AspectCorrect", number(result.reach.distalCompositeFistP80AspectCorrect))
                put("supportedTimestampsUs", JSONArray(result.reach.supportedTimestampsUs))
                put("intervalStartUs", result.reach.intervalStartUs)
                put("intervalEndUs", result.reach.intervalEndUs)
                put("minimumConfidence", result.reach.minimumConfidence)
                put("minimumObservedConfidence", number(result.reach.minimumObservedConfidence))
                put("reason", result.reach.reason)
            })
            put("targets", JSONArray().apply {
                PunchHeightTargetType.entries.forEach { target ->
                    val angular = result.targets[target] ?: CurrentFrameAngularTargetEvaluation(
                        target, CurrentFrameAngularTargetState.UNAVAILABLE, reason = "angular_target_not_evaluated")
                    val guide = horizontal.guides[target]
                    put(JSONObject().apply {
                        put("targetType", target.name)
                        put("state", angular.state.name)
                        put("targetPoint", angular.targetPoint?.json() ?: JSONObject.NULL)
                        put("idealEndpoint", angular.idealEndpoint?.json() ?: JSONObject.NULL)
                        put("idealAngleDeg", number(angular.idealAngleDeg))
                        put("signedErrorDeg", number(angular.signedErrorDeg))
                        put("horizontalErrorNormalized", number(guide?.signedErrorNormalized))
                        put("horizontalErrorCanonicalPx", number(guide?.signedErrorCanonicalPx))
                        put("guideEstimatorVersion", guide?.estimatorVersion ?: "unavailable")
                        put("reason", angular.reason)
                    })
                }
            })
        }.toString().also { requireNotNull(decode(it)) }
    }

    fun decode(json: String?): StraightPunchCurrentFrameAngularGeometry? = json?.let { runCatching {
        val root = JSONObject(it)
        require(root.getString("contract") == CONTRACT && root.getString("coordinateSpace") == COORDINATES)
        fun JSONObject.longIdentity(key: String): Long = (get(key) as? Number)?.let { number ->
            number.toLong().also { require(it >= 0L && number.toDouble() == it.toDouble()) }
        } ?: error("Invalid identity")
        fun JSONObject.number(key: String): Double? = if (isNull(key)) null else
            ((get(key) as? Number)?.toDouble() ?: error("Invalid number")).also { require(it.isFinite()) }
        fun JSONObject.point(key: String): EvidencePoint? = if (isNull(key)) null else getJSONObject(key).let { point ->
            EvidencePoint(point.number("x")!!.toFloat(), point.number("y")!!.toFloat())
        }
        val width = root.getInt("canonicalWidth")
        val height = root.getInt("canonicalHeight")
        require(width > 0 && height > 0)
        val side = BodySide.valueOf(root.getString("activeSide"))
        require(side == BodySide.LEFT || side == BodySide.RIGHT)
        val reachJson = root.getJSONObject("reach")
        val supportedJson = reachJson.getJSONArray("supportedTimestampsUs")
        val supported = (0 until supportedJson.length()).map { supportedJson.getLong(it) }
        require(supported.zipWithNext().all { (a, b) -> a < b })
        val reach = CompositeFistReachEvidence(
            reachJson.getString("estimatorVersion"), CurrentFrameAngularTargetState.valueOf(reachJson.getString("state")),
            reachJson.number("radiusAspectCorrect"), reachJson.number("upperArmP80AspectCorrect"),
            reachJson.number("distalCompositeFistP80AspectCorrect"), supported,
            reachJson.longIdentity("intervalStartUs"), reachJson.longIdentity("intervalEndUs"),
            reachJson.number("minimumConfidence")!!, reachJson.number("minimumObservedConfidence"), reachJson.getString("reason"),
        )
        require(reach.intervalStartUs <= reach.intervalEndUs)
        val targetJson = root.getJSONArray("targets")
        require(targetJson.length() == PunchHeightTargetType.entries.size)
        val targets = (0 until targetJson.length()).map { index -> targetJson.getJSONObject(index).let { target ->
            CurrentFrameAngularTargetGeometry(
                PunchHeightTargetType.valueOf(target.getString("targetType")),
                CurrentFrameAngularTargetState.valueOf(target.getString("state")),
                target.point("targetPoint"), target.point("idealEndpoint"), target.number("idealAngleDeg"),
                target.number("signedErrorDeg"), target.number("horizontalErrorNormalized"),
                target.number("horizontalErrorCanonicalPx"), target.getString("guideEstimatorVersion"), target.getString("reason"),
            )
        } }
        require(targets.map { it.targetType }.toSet() == PunchHeightTargetType.entries.toSet())
        val state = CurrentFrameAngularState.valueOf(root.getString("state"))
        val selected = if (root.isNull("selectedTarget")) null else PunchHeightTargetType.valueOf(root.getString("selectedTarget"))
        val error = root.number("signedErrorDeg")
        require((state == CurrentFrameAngularState.RESOLVED) == (selected != null && error != null))
        StraightPunchCurrentFrameAngularGeometry(
            root.getString("recordingId"), root.getString("landmarkTrackId"), root.getString("frameGeometryId"),
            root.getString("frameGeometryContractVersion"), width, height, root.longIdentity("timestampUs"),
            root.longIdentity("frameIndex"), side, state,
            CurrentFrameTargetSelectionMode.valueOf(root.getString("selectionMode")),
            if (root.isNull("declaredTargetRaw")) null else root.getString("declaredTargetRaw"), selected,
            root.getString("selectionReason"), root.getBoolean("comparisonComplete"),
            ImageFacingDirection.valueOf(root.getString("facing")), root.getString("facingSource"),
            root.point("shoulder"), root.point("fist"), root.getString("fistEstimatorVersion"),
            root.number("actualAngleDeg"), error, reach, targets,
        )
    }.getOrNull() }
}
