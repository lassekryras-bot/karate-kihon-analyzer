package dk.lasse.karateanalyzer.core

/** Converts stabilized landmarks into hand-size-independent, analyzer-neutral features. */
class HandFeatureExtractor(
    private val configuration: HandFeatureExtractorConfiguration = HandFeatureExtractorConfiguration(),
) {
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
        val xAxis = (little ?: return null).minus(index ?: return null).normalized() ?: return null
        val yAxis = (middle ?: return null).minus(wrist ?: return null).normalized() ?: return null
        val zAxis = xAxis.cross(yAxis)?.normalized()
        return PalmCoordinateSystem(origin, xAxis, yAxis, zAxis, width)
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
        val indexMcp = frame.point(HandLandmarkId.INDEX_MCP); val middleMcp = frame.point(HandLandmarkId.MIDDLE_MCP)
        return ThumbFeatures(
            cmcAngleDegrees = angleBetweenThreePoints(wrist, cmc, mcp),
            mcpAngleDegrees = angleBetweenThreePoints(cmc, mcp, ip),
            ipAngleDegrees = angleBetweenThreePoints(mcp, ip, tip),
            tipToPalmRatio = safeDivide(distanceBetween(tip, palmCenter), palmWidth),
            tipToIndexMcpRatio = safeDivide(distanceBetween(tip, indexMcp), palmWidth),
            tipToMiddleMcpRatio = safeDivide(distanceBetween(tip, middleMcp), palmWidth),
            crossesPalmAxis = thumbCrossesPalmAxis(tip, palm, frame.handedness),
            quality = quality(listOf(HandLandmarkId.THUMB_CMC, HandLandmarkId.THUMB_MCP, HandLandmarkId.THUMB_IP, HandLandmarkId.THUMB_TIP), frame),
        )
    }

    private fun thumbCrossesPalmAxis(tip: Point3?, palm: PalmCoordinateSystem?, handedness: Handedness): Boolean? {
        if (tip == null || palm == null || handedness == Handedness.UNKNOWN) return null
        val lateral = (tip - palm.origin).dot(palm.xAxis) ?: return null
        return if (handedness == Handedness.RIGHT) lateral > 0f else lateral < 0f
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
}
