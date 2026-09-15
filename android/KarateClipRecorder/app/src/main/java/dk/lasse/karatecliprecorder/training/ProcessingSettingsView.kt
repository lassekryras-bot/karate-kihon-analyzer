package dk.lasse.karatecliprecorder.training

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.SettingsCardView
import dk.lasse.karatecliprecorder.SettingsRowView

/**
 * Recorder processing settings conforming to Section 6.
 * Uses the shared app settings pattern: section heading outside card,
 * standard rounded card, SettingsRowView toggle, and custom styled slider row.
 */
class ProcessingSettingsView(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        val preferences = ProcessingPreferences(context)

        // Section heading outside and above card
        addView(TextView(context).apply {
            text = "RECORDING PROCESSING"
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
            setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
            ViewCompat.setAccessibilityHeading(this, true)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 22.dp()
            bottomMargin = 8.dp()
            marginStart = 2.dp()
        })

        // Standard rounded settings card
        val card = SettingsCardView(context)

        // Row 1: Allow background processing
        val backgroundRow = SettingsRowView(
            context = context,
            icon = AppIcon.SETTINGS,
            title = "Allow background processing",
            description = "Continue analyzing recordings while using other apps or when the screen is locked.",
        ).apply {
            configureAsToggle(preferences.background) { checked ->
                preferences.background = checked
                RecordingQueue.schedule(context)
            }
        }
        card.addSettingsRow(backgroundRow)

        // Divider
        card.addView(View(context).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.app_divider))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 1.dp()).apply {
            marginStart = 56.dp()
            marginEnd = 16.dp()
        })

        // Row 2: Minimum battery slider row
        val sliderRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 16.dp())

            // Leading icon container matching SettingsRowView layout
            val leadingIconContainer = FrameLayout(context).apply {
                addView(AppIconView(context, AppIcon.CHART_BAR), FrameLayout.LayoutParams(24.dp(), 24.dp()))
            }
            addView(leadingIconContainer, LayoutParams(24.dp(), 24.dp()).apply {
                marginEnd = 16.dp()
                gravity = Gravity.TOP
                topMargin = 2.dp()
            })

            // Content column
            val contentCol = LinearLayout(context).apply {
                orientation = VERTICAL

                // Header row with title on left and value on right
                val headerRow = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    val titleView = TextView(context).apply {
                        text = "Minimum battery"
                        textSize = 16f
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.app_text_primary))
                    }
                    addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

                    val valueView = TextView(context).apply {
                        text = "${preferences.minimumBattery}%"
                        textSize = 15f
                        typeface = Typeface.create("sans-serif", Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(context, R.color.app_accent))
                    }
                    addView(valueView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
                    tag = valueView
                }
                addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

                val descView = TextView(context).apply {
                    text = "Pause recording and processing below this level (charging overrides)."
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.app_text_secondary))
                    setLineSpacing(1.dp().toFloat(), 1f)
                }
                addView(descView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 2.dp()
                    bottomMargin = 8.dp()
                })

                val accentColor = ContextCompat.getColor(context, R.color.app_accent)
                val seekBar = SeekBar(context).apply {
                    min = 5
                    max = 100
                    progress = preferences.minimumBattery
                    contentDescription = "Minimum battery for new recording or processing, 5 to 100 percent"
                    progressTintList = ColorStateList.valueOf(accentColor)
                    thumbTintList = ColorStateList.valueOf(accentColor)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                            if (fromUser) {
                                preferences.minimumBattery = value
                                (headerRow.tag as? TextView)?.text = "$value%"
                            }
                        }
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) {
                            RecordingQueue.schedule(context)
                        }
                    })
                }
                addView(seekBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            addView(contentCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        card.addView(sliderRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
