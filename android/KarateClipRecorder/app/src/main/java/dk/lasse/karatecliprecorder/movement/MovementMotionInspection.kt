package dk.lasse.karatecliprecorder.movement

import dk.lasse.karatecliprecorder.training.*
import dk.lasse.karateanalyzer.geometry.CanonicalGeometryCodec
import org.json.JSONObject

/** Renders retained analytical evidence; never recomputes motion from playback frames. */
object MovementMotionInspection {
    fun read(evidence: MovementEvidence): JSONObject? = runCatching {
        val analysis = selectMovementAnalysis(evidence.analyses, MovementMotionAnalysis.policy, true) ?: return null
        val j = JSONObject(requireNotNull(analysis.geometryJson))
        val geometry = requireNotNull(CanonicalGeometryCodec.decode(j.getString("geometry")))
        require(j.getString("contract") == "movement-motion-v1" && j.getString("movementId") == evidence.movement.movementId)
        require(j.getString("trackId") == analysis.landmarkTrackId && geometry.landmarkTrackId == analysis.landmarkTrackId)
        require(geometry.recordingId == evidence.recording?.recordingId)
        require(j.getLong("logicalStartUs") == evidence.movement.startUs && j.getLong("logicalEndUs") == evidence.movement.endUs)
        j
    }.getOrNull()

    fun rows(j: JSONObject): List<String> = buildList {
        fun value(key: String) = if (j.isNull(key)) "Unavailable" else j.optString(key, "Unavailable")
        add("Provisional measurements — no technique score")
        add("Activity: ${value("activity")} · side: ${value("side")}")
        add("Side evidence: ${value("sideReason")} · alternation: ${value("alternationStatus")}")
        add("Terminal analysis: ${value("impactStatus")} · ${value("impactReason")}")
        val calibration = j.optJSONObject("calibration")
        add(if (calibration == null) "Image height: not calibrated" else "Image height reference: frame ${calibration.optInt("frameIndex") + 1} · ${calibration.optString("method")}")
        val profiles = j.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            fun number(key: String, unit: String) = if (p.isNull(key)) "unavailable" else "%.1f%s".format(p.getDouble(key), unit)
            fun time(key: String) = if (p.isNull(key)) "unconfirmed" else "%.3f s".format(p.getLong(key) / 1_000_000.0)
            add("${p.getString("limb")}: ${p.getString("pattern")}\nTravel ${number("travelDeg", "°")} · net ${number("netDeg", "°")}\nOnset ${time("onsetUs")} · peak ${time("peakUs")} · settling ${time("settlingUs")}\nSample coverage ${"%.0f".format(p.optDouble("coverage") * 100)}% · ${if (p.isNull("abstention")) p.optJSONArray("qualityFlags") else p.optString("abstention")}")
        }
    }

    fun plots(j: JSONObject): Map<String, MovementPlotDefinition> = buildMap {
        val profiles = j.getJSONArray("profiles")
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            val samples = p.getJSONArray("samples")
            val blocks = linkedMapOf<Int, MutableList<PlotSample>>()
            for (k in 0 until samples.length()) {
                val s = samples.getJSONObject(k)
                if (!s.isNull("angleDeg") && !s.isNull("block")) blocks.getOrPut(s.getInt("block")) { mutableListOf() }
                    .add(PlotSample(s.getLong("timeUs"), s.getDouble("angleDeg")))
            }
            blocks.forEach { (block, points) ->
                if (points.size >= 2) {
                    val key = "${p.getString("limb")}_angle_$block"
                    put(key, MovementPlotDefinition(key, "${p.getString("limb")} angle · block ${block + 1}", "°", points,
                        minValue = 0.0, maxValue = 180.0, accessibleSummary = "Retained joint angles. Evidence gaps use separate plots."))
                }
            }
        }
    }
}
