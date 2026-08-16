package dk.lasse.karatecliprecorder.enso

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.learningartwork.LearningActivityType
import dk.lasse.karatecliprecorder.learningartwork.LearningArtworkForeground
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView
import dk.lasse.karatecliprecorder.learningpath.LearningProgressState
import dk.lasse.karatecliprecorder.learningpath.LearningStepType
import dk.lasse.karatecliprecorder.learningpath.ProgressMarkerView

/** Debug-only gallery entry point; callers are responsible for guarding it with debuggable state. */
class EnsoDebugGalleryView(
    context: Context,
    onClose: () -> Unit,
) : FrameLayout(context) {
    init {
        setBackgroundColor(Color.rgb(252, 250, 247))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 24.dp())
            addView(TextView(context).apply {
                text = "‹ Back"
                textSize = 17f
                setTextColor(Color.rgb(190, 0, 12))
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = "Close learning UI gallery"
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp()))
            addView(TextView(context).apply {
                text = "Learning UI gallery"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(24, 24, 24))
            })
            addView(TextView(context).apply {
                text = "Path identities, all seven marker states, and the 20-variant Ensō library"
                textSize = 14f
                setTextColor(Color.rgb(92, 92, 92))
                setPadding(0, 4.dp(), 0, 8.dp())
            })
            addView(sectionHeading("PATH ARTWORK"))
            addView(pathArtworkPreview())
            addView(sectionHeading("PROGRESS MARKERS"))
            addView(markerPreview())
            addView(sectionHeading("ENSŌ ACTIVITY TREATMENTS"))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(columnHeading(
                    "PRACTICE\n${EnsoThemeTokens.ensoPracticeBaseColor.toHexColor()}",
                ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(columnHeading(
                    "TEST\n${EnsoThemeTokens.ensoTestBaseColor.toHexColor()}",
                ), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            EnsoVariant.all.forEach { variant -> addView(comparisonRow(variant)) }
        }
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(16.dp(), bars.top + 12.dp(), 16.dp(), bars.bottom + 24.dp())
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun sectionHeading(copy: String) = TextView(context).apply {
        text = copy
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(92, 92, 92))
        setPadding(0, 18.dp(), 0, 8.dp())
    }

    private fun pathArtworkPreview() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pathArtworkCell("Jōdan Punch", LearningArtworkForeground.JODAN_PUNCH, EnsoVariant.ENSO_04), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
        addView(pathArtworkCell("Japanese Counting", LearningArtworkForeground.JAPANESE_COUNTING, EnsoVariant.ENSO_11), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ))
    }

    private fun pathArtworkCell(
        title: String,
        foreground: LearningArtworkForeground,
        variant: EnsoVariant,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(LearningPathArtworkView(context).apply {
            setPathArtwork(foreground, variant)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140.dp()))
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 24, 24))
        })
    }

    private fun markerPreview() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        listOf(
            Triple("Completed", LearningStepType.REGULAR, LearningProgressState.COMPLETED),
            Triple("Current", LearningStepType.REGULAR, LearningProgressState.CURRENT),
            Triple("Available", LearningStepType.REGULAR, LearningProgressState.AVAILABLE),
            Triple("Locked", LearningStepType.REGULAR, LearningProgressState.LOCKED),
            Triple("Milestone", LearningStepType.MILESTONE, LearningProgressState.AVAILABLE),
            Triple("Completed milestone", LearningStepType.MILESTONE, LearningProgressState.COMPLETED),
            Triple("Locked milestone", LearningStepType.MILESTONE, LearningProgressState.LOCKED),
        ).chunked(2).forEach { pair ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                pair.forEach { (title, type, state) ->
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ProgressMarkerView(context).apply {
                            setMarker(type, state)
                        }, LinearLayout.LayoutParams(42.dp(), 42.dp()))
                        addView(TextView(context).apply {
                            text = title
                            textSize = 12f
                            setTextColor(Color.rgb(24, 24, 24))
                            setPadding(6.dp(), 0, 0, 0)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }, LinearLayout.LayoutParams(0, 54.dp(), 1f))
                }
            })
        }
    }

    private fun columnHeading(copy: String) = TextView(context).apply {
        text = copy
        textSize = 13f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(92, 92, 92))
        setPadding(0, 4.dp(), 0, 6.dp())
    }

    private fun comparisonRow(variant: EnsoVariant) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(variantCell(variant, LearningActivityType.PRACTICE), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginEnd = 4.dp() })
        addView(variantCell(variant, LearningActivityType.TEST), LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginStart = 4.dp() })
    }

    private fun variantCell(
        variant: EnsoVariant,
        activityType: LearningActivityType,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = "Ensō variant ${variant.debugLabel}, ${activityType.name.lowercase()}"
        addView(LearningPathArtworkView(context).apply {
            setArtwork(
                foreground = LearningArtworkForeground.JAPANESE_COUNTING,
                activityType = activityType,
                ensoVariant = variant,
            )
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 154.dp()))
        addView(TextView(context).apply {
            text = variant.debugLabel
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 24, 24))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 8.dp()
        })
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)
}
