package dk.lasse.karateanalyzer.core

import kotlin.math.abs

/** Converts stabilized landmarks into hand-size-independent, analyzer-neutral features. */
class HandFeatureExtractor(
    private val configuration: HandFeatureExtractorConfiguration = HandFeatureExtractorConfiguration(),
) {
    init {
        require(configuration.thumbNearestFingerPointCount > 0) { "thumbNearestFingerPointCount must be > 0" }
        require(configuration.thumbClosedDistanceRatio >= 0f) { "thumbClosedDistanceRatio must be >= 0" }
        require(configuration.thumbOpenDistanceRatio > configuration.thumbClosedDistanceRatio) {
            "thumbOpenDistanceRatio must be greater than thumbClosedDistanceRatio"
        }
    }

    fun extract(frame: TrackedHandFrame): HandFeatures {
        val palm = palmCoordinateSystem(frame)
        val palmCenter = palm?.origin
        val palmWidth = palm?.palmWidth
        val index = finger(frame, HandLandmarkId.INDEX_MCP, HandLandmarkId.INDEX_PIP, HandLandmarkId.INDEX_DIP, HandLandmarkId.INDEX_TIP, palmCenter, palmWidth)
        val middle = finger(frame, HandLandmarkId.MIDDLE_MCP, HandLandmarkId.MIDDLE_PIP, HandLandmarkId.MIDDLE_DIP, HandLandmarkId.MIDDLE_TIP, palmCenter, palmWidth)
        val ring = finger(frame, HandLandmarkId.RING_MCP, HandLandmarkId.RING_PIP, HandLandmarkId.RING_DIP, HandLandmarkId.RING_TIP, palmCenter, palmWidth)
        val little = finger(frame, HandLandmarkId.LITTLE_MCP, HandLandmarkId.LITTLE_PIP, HandLandmarkId.LITTLE_DIP, HandLandmarkId.LITTLE_TIP, palmCenter, palmWidth)
        val fingers = listOf(index, middle, ring, little)
        return HandFeatures(
            timestampMs = frame.timestampMs,
            handedness = frame.handedness,
            palmCoordinateSystem = palm,
            palmCenter = palmCenter,
            palmWidth = palmWidth,
            index = index,
            middle = middle,
            ring = ring,
            little = little,
            thumb = thumb(frame, palm, palmCenter, palmWidth),
            openPalmScore = aggregate(fingers.mapNotNull { it.extensionScore }),
            fourFingerCurlScore = aggregate(fingers.mapNotNull { it.curlScore }),
            dataQuality = quality(HandLandmarkId.entries.toList(), frame),
            observedLandmarkRatio = ratio(frame) { it == LandmarkSource.OBSERVED },
            estimatedLandmarkRatio = ratio(frame) { it == LandmarkSource.PREDICTED || it == LandmarkSource.INTERPOLATED },
        )
    }

    private fun palmCoordinateSystem(frame: TrackedHandFrame): PalmCoordinateSystem? {
        val index = frame.point(HandLandmarkId.INDEX_MCP)
        val middle = frame.point(HandLandmarkId.MIDDLE_MCP)
        val ring = frame.point(HandLandmarkId.RING_MCP)
        val little = frame.point(HandLandmarkId.LITTLE_MCP)
        val wrist = frame.point(HandLandmarkId.WRIST)
        val origin = averageOfPoints(listOf(index, middle, ring, little)) ?: return null
        val width = distanceBetween(index, little) ?: return null
        val rawX = (little ?: return null).minus(index ?: return null).normalized() ?: return null
        val rawY = (middle ?: return null).minus(wrist ?: return null).normalized() ?: return null
        val zAxis = rawX.cross(rawY)?.normalized() ?: return null
        val yAxis = zAxis.cross(rawX)?.normalized() ?: return null
        return PalmCoordinateSystem(origin, rawX, yAxis, zAxis, width)
    }

    private fun finger(frame: TrackedHandFrame, mcpId: HandLandmarkId, pipId: HandLandmarkId, dipId: HandLandmarkId, tipId: HandLandmarkId, palmCenter: Point3?, palmWidth: Float?): FingerFeatures {
        val wrist = frame.point(HandLandmarkId.WRIST)
        val mcp = frame.point(mcpId); val pip = frame.point(pipId); val dip = frame.point(dipId); val tip = frame.point(tipId)
        val pipAngle = angleBetweenThreePoints(mcp, pip, dip)
        val dipAngle = angleBetweenThreePoints(pip, dip, tip)
        val curl = curlScore(pipAngle, dipAngle)
        return FingerFeatures(
            mcpAngleDegrees = angleBetweenThreePoints(wrist, mcp, pip),
            pipAngleDegrees = pipAngle,
            dipAngleDegrees = dipAngle,
            tipToPalmRatio = safeDivide(distanceBetween(tip, palmCenter), palmWidth),
            tipToMcpRatio = safeDivide(distanceBetween(tip, mcp), palmWidth),
            curlScore = curl,
            extensionScore = curl?.let { 1f - it },
            quality = quality(listOf(mcpId, pipId, dipId, tipId), frame),
        )
    }

    private fun thumb(frame: TrackedHandFrame, palm: PalmCoordinateSystem?, palmCenter: Point3?, palmWidth: Float?): ThumbFeatures {
        val wrist = frame.point(HandLandmarkId.WRIST); val cmc = frame.point(HandLandmarkId.THUMB_CMC); val mcp = frame.point(HandLandmarkId.THUMB_MCP)
        val ip = frame.point(HandLandmarkId.THUMB_IP); val tip = frame.point(HandLandmarkId.THUMB_TIP)
        val indexMcp = frame.point(HandLandmarkId.INDEX_MCP); val indexPip = frame.point(HandLandmarkId.INDEX_PIP); val middleMcp = frame.point(HandLandmarkId.MIDDLE_MCP)
        val ringMcp = frame.point(HandLandmarkId.RING_MCP); val littleMcp = frame.point(HandLandmarkId.LITTLE_MCP)
        val weightedFingerDistanceRatio = thumbWeightedFingerDistanceRatio(frame, palmWidth)
        val closedScore = thumbClosedScore(weightedFingerDistanceRatio)
        val indexBoundary = thumbIndexBoundary(tip, palm, indexMcp, indexPip, middleMcp, ringMcp, littleMcp)
        return ThumbFeatures(
            cmcAngleDegrees = angleBetweenThreePoints(wrist, cmc, mcp),
            mcpAngleDegrees = angleBetweenThreePoints(cmc, mcp, ip),
            ipAngleDegrees = angleBetweenThreePoints(mcp, ip, tip),
            tipToPalmRatio = safeDivide(distanceBetween(tip, palmCenter), palmWidth),
            tipToIndexMcpRatio = safeDivide(distanceBetween(tip, indexMcp), palmWidth),
            tipToMiddleMcpRatio = safeDivide(distanceBetween(tip, middleMcp), palmWidth),
            tipLateralToPalmRatio = thumbTipLateralToPalmRatio(tip, palm),
            weightedFingerDistanceRatio = weightedFingerDistanceRatio,
            closedScore = closedScore,
            openScore = closedScore?.let { 1f - it },
            tipInsideIndexBoundaryRatio = indexBoundary?.tipInsideRatio,
            tipInsideIndexBoundary = indexBoundary?.tipInsideRatio?.let { it > 0f },
            indexBoundaryLine = indexBoundary?.line,
            crossesPalmAxis = thumbCrossesPalmAxis(tip, palm),
            quality = quality(listOf(HandLandmarkId.THUMB_CMC, HandLandmarkId.THUMB_MCP, HandLandmarkId.THUMB_IP, HandLandmarkId.THUMB_TIP), frame),
        )
    }

    private fun thumbWeightedFingerDistanceRatio(frame: TrackedHandFrame, palmWidth: Float?): Float? {
        if (palmWidth == null || !palmWidth.isFinite() || palmWidth <= 0f) return null
        val fingerPoints = fourFingerPointIds.mapNotNull { frame.point(it) }
        if (fingerPoints.size < configuration.thumbNearestFingerPointCount) return null
        val weightedDistances = thumbPointWeights.mapNotNull { (id, weight) ->
            val thumbPoint = frame.point(id) ?: return@mapNotNull null
            val nearest = fingerPoints
                .mapNotNull { fingerPoint -> safeDivide(distanceBetween(thumbPoint, fingerPoint), palmWidth) }
                .sorted()
                .take(configuration.thumbNearestFingerPointCount)
            if (nearest.size < configuration.thumbNearestFingerPointCount) null else nearest.average().toFloat() to weight
        }
        val totalWeight = weightedDistances.sumOf { it.second.toDouble() }.toFloat()
        if (totalWeight <= 0f) return null
        return weightedDistances
            .sumOf { (distance, weight) -> (distance * weight).toDouble() }
            .toFloat()
            .let { it / totalWeight }
            .takeIf { it.isFinite() }
    }

    private fun thumbClosedScore(weightedDistanceRatio: Float?): Float? {
        val ratio = weightedDistanceRatio?.takeIf { it.isFinite() } ?: return null
        val range = configuration.thumbOpenDistanceRatio - configuration.thumbClosedDistanceRatio
        return (1f - ((ratio - configuration.thumbClosedDistanceRatio) / range)).coerceIn(0f, 1f)
    }

    private fun thumbTipLateralToPalmRatio(tip: Point3?, palm: PalmCoordinateSystem?): Float? {
        if (tip == null || palm == null) return null
        val lateral = (tip - palm.origin).dot(palm.xAxis) ?: return null
        return safeDivide(lateral, palm.palmWidth)
    }

    private fun thumbIndexBoundary(
        tip: Point3?,
        palm: PalmCoordinateSystem?,
        indexMcp: Point3?,
        indexPip: Point3?,
        middleMcp: Point3?,
        ringMcp: Point3?,
        littleMcp: Point3?,
    ): ThumbIndexBoundary? {
        val thumb = palmLocal(tip, palm) ?: return null
        val start = palmLocal(indexMcp, palm) ?: return null
        val end = palmLocal(indexPip, palm) ?: return null
        val averageFingerGap = averageNeighborFingerGap(palm, indexMcp, middleMcp, ringMcp, littleMcp) ?: return null
        val edgeOffset = averageFingerGap * 0.5f
        val centerBoundaryX = if (abs(end.y - start.y) > 1e-4f) {
            start.x + (thumb.y - start.y) * (end.x - start.x) / (end.y - start.y)
        } else {
            (start.x + end.x) / 2f
        }
        val shiftedBoundaryX = centerBoundaryX - edgeOffset
        val lineStart = palmPoint(Point2(start.x - edgeOffset, start.y), palm) ?: return null
        val lineEnd = palmPoint(Point2(end.x - edgeOffset, end.y), palm) ?: return null
        return ThumbIndexBoundary(
            tipInsideRatio = (thumb.x - shiftedBoundaryX).takeIf { it.isFinite() } ?: return null,
            line = ThumbBoundaryLine(lineStart, lineEnd),
        )
    }

    private fun averageNeighborFingerGap(
        palm: PalmCoordinateSystem?,
        indexMcp: Point3?,
        middleMcp: Point3?,
        ringMcp: Point3?,
        littleMcp: Point3?,
    ): Float? {
        val points = listOf(indexMcp, middleMcp, ringMcp, littleMcp)
            .map { palmLocal(it, palm) ?: return null }
        val gaps = points.zipWithNext().map { (first, second) -> abs(second.x - first.x) }
        if (gaps.size != 3 || gaps.any { !it.isFinite() || it <= 0f }) return null
        return gaps.average().toFloat().takeIf { it.isFinite() }
    }

    private fun palmLocal(point: Point3?, palm: PalmCoordinateSystem?): Point2? {
        if (point == null || palm == null) return null
        val relative = point - palm.origin
        val x = safeDivide(relative.dot(palm.xAxis), palm.palmWidth) ?: return null
        val y = safeDivide(relative.dot(palm.yAxis), palm.palmWidth) ?: return null
        return Point2(x, y)
    }

    private fun palmPoint(point: Point2, palm: PalmCoordinateSystem?): Point3? {
        if (palm == null || !point.x.isFinite() || !point.y.isFinite()) return null
        return palm.origin + palm.xAxis * (point.x * palm.palmWidth) + palm.yAxis * (point.y * palm.palmWidth)
    }

    private fun thumbCrossesPalmAxis(tip: Point3?, palm: PalmCoordinateSystem?): Boolean? {
        return thumbTipLateralToPalmRatio(tip, palm)?.let { it > 0f }
    }

    /** Average flexion heuristic: 0 at 170+ degrees, 1 at 70 degrees or less. */
    private fun curlScore(pipAngle: Float?, dipAngle: Float?): Float? {
        val angles = listOfNotNull(pipAngle, dipAngle)
        if (angles.isEmpty()) return null
        val average = angles.average().toFloat()
        return safeClamp((170f - average) / 100f)
    }

    private fun aggregate(values: List<Float>): Float? = if (values.size >= configuration.minimumAggregateFingerCount) values.average().toFloat() else null

    private fun quality(ids: List<HandLandmarkId>, frame: TrackedHandFrame): Float {
        if (ids.isEmpty()) return 0f
        return ids.map { configuration.sourceWeights[frame.landmarks[it]?.source ?: LandmarkSource.MISSING] ?: 0f }.average().toFloat().coerceIn(0f, 1f)
    }

    private fun ratio(frame: TrackedHandFrame, predicate: (LandmarkSource) -> Boolean): Float {
        val samples = HandLandmarkId.entries.map { frame.landmarks[it] ?: LandmarkSample(null, 0f, LandmarkSource.MISSING) }
        return samples.count { predicate(it.source) }.toFloat() / samples.size.toFloat()
    }

    private fun TrackedHandFrame.point(id: HandLandmarkId): Point3? = landmarks[id]?.position?.takeIf { it.isFinitePoint() && landmarks[id]?.source != LandmarkSource.MISSING }

    private data class Point2(val x: Float, val y: Float)
    private data class ThumbIndexBoundary(val tipInsideRatio: Float, val line: ThumbBoundaryLine)

    private companion object {
        private val thumbPointWeights = listOf(
            HandLandmarkId.THUMB_CMC to 0.10f,
            HandLandmarkId.THUMB_MCP to 0.20f,
            HandLandmarkId.THUMB_IP to 0.30f,
            HandLandmarkId.THUMB_TIP to 0.40f,
        )

        private val fourFingerPointIds = listOf(
            HandLandmarkId.INDEX_MCP,
            HandLandmarkId.INDEX_PIP,
            HandLandmarkId.INDEX_DIP,
            HandLandmarkId.INDEX_TIP,
            HandLandmarkId.MIDDLE_MCP,
            HandLandmarkId.MIDDLE_PIP,
            HandLandmarkId.MIDDLE_DIP,
            HandLandmarkId.MIDDLE_TIP,
            HandLandmarkId.RING_MCP,
            HandLandmarkId.RING_PIP,
            HandLandmarkId.RING_DIP,
            HandLandmarkId.RING_TIP,
            HandLandmarkId.LITTLE_MCP,
            HandLandmarkId.LITTLE_PIP,
            HandLandmarkId.LITTLE_DIP,
            HandLandmarkId.LITTLE_TIP,
        )
    }
}
