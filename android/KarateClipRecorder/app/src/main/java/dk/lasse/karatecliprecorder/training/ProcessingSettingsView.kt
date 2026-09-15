package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.widget.*

class ProcessingSettingsView(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val preferences = ProcessingPreferences(context)
        addView(TextView(context).apply { text = "RECORDING PROCESSING"; textSize = 14f })
        addView(Switch(context).apply {
            text = "Background processing"; isChecked = preferences.background
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnCheckedChangeListener { _, checked -> preferences.background = checked; RecordingQueue.schedule(context) }
        })
        val label = TextView(context).apply { text = "Minimum battery: ${preferences.minimumBattery}% (charging overrides)" }
        addView(label)
        addView(SeekBar(context).apply {
            min = 5; max = 100; progress = preferences.minimumBattery
            contentDescription = "Minimum battery for new recording or processing, 5 to 100 percent"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) { preferences.minimumBattery = value; label.text = "Minimum battery: $value% (charging overrides)" }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { RecordingQueue.schedule(context) }
            })
        })
    }
}
