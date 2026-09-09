package dk.lasse.karatecliprecorder.learning

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded app-private text diagnostics for the user's Osu troubleshooting request. */
class ReadyOsuDiagnostics(context: Context, private val command: ShortVoiceCommand = ShortVoiceCommand.OSU) {
    private val file = File(context.noBackupFilesDir, if (command == ShortVoiceCommand.OSU) "ready-osu-diagnostics.json" else "stop-session-diagnostics.json")

    @Synchronized fun record(event: String, phase: String, alternatives: List<SpeechRecognitionAlternative> = emptyList(), error: String? = null) {
        runCatching {
            val previous = if (file.exists()) JSONArray(file.readText()) else JSONArray()
            val retained = JSONArray()
            for (i in maxOf(0, previous.length() - 99) until previous.length()) retained.put(previous.get(i))
            retained.put(JSONObject()
                .put("timestamp_ms", System.currentTimeMillis())
                .put("event", event)
                .put("phase", phase)
                .put("command", command.name)
                .put("language", (if (command == ShortVoiceCommand.OSU) ShortVoiceRecognitionConfig.OSU else ShortVoiceRecognitionConfig.STOP).languageTag)
                .put("matched_command", ShortVoiceCommandMatcher.matches(command, alternatives.map { it.transcript }))
                .put("alternatives", JSONArray().apply {
                    alternatives.take(5).forEach { alternative ->
                        put(JSONObject().put("text", alternative.transcript.take(500))
                            .put("confidence", alternative.confidence ?: JSONObject.NULL))
                    }
                })
                .put("error", error?.take(500) ?: JSONObject.NULL))
            file.writeText(retained.toString(2))
        }.onFailure { android.util.Log.w("ReadyOsuDiagnostics", "Could not save diagnostic event") }
    }
}
