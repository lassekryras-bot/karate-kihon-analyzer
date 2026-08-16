package dk.lasse.karatecliprecorder.learningpath

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.AppBottomNavigationView
import dk.lasse.karatecliprecorder.AppDestination
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView

class SkillProgressionView(
    context: Context,
    private val path: LearningPath,
    onBack: () -> Unit,
    onStart: (LearningDestination) -> Unit,
    onHome: () -> Unit,
    onTrain: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
) : FrameLayout(context) {
    private val red = ContextCompat.getColor(context, R.color.progress_red)
    private val gray = ContextCompat.getColor(context, R.color.progress_gray)
    private val ink = ContextCompat.getColor(context, R.color.app_text_primary)
    private val muted = ContextCompat.getColor(context, R.color.app_text_secondary)
    private val paper = ContextCompat.getColor(context, R.color.home_card_surface)
    private val border = ContextCompat.getColor(context, R.color.app_border)
    private val backgroundColor = ContextCompat.getColor(context, R.color.app_background)

    init {
        setBackgroundColor(backgroundColor)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 8.dp(), 16.dp(), 112.dp())
            addView(topBar(onBack))
            addView(pathHeader(), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = 8.dp() })
            addView(label("Your learning path", 21f, Typeface.BOLD).apply {
                setPadding(2.dp(), 24.dp(), 0, 8.dp())
            })
            path.steps.forEachIndexed { index, step ->
                val previous = path.steps.getOrNull(index - 1)?.progressState
                addView(stepRow(
                    step = step,
                    first = index == 0,
                    incomingRed = previous == LearningProgressState.COMPLETED,
                    onStart = onStart,
                ))
            }
            addView(milestoneRow(path.steps.lastOrNull()?.progressState))
        }

        addView(ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val navigation = AppBottomNavigationView(
            context = context,
            selectedDestination = AppDestination.TRAIN,
            onHome = onHome,
            onTrain = onTrain,
            onProgress = onProgress,
            onSettings = onSettings,
        )
        addView(navigation, LayoutParams(
            LayoutParams.MATCH_PARENT,
            AppBottomNavigationView.BASE_HEIGHT_DP.dp(),
            Gravity.BOTTOM,
        ))

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            content.setPadding(16.dp(), systemBars.top + 8.dp(), 16.dp(), navigationBars.bottom + 112.dp())
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    private fun topBar(onBack: () -> Unit) = FrameLayout(context).apply {
        addView(label("‹", 42f, Typeface.NORMAL, Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to Learn"
            setOnClickListener { onBack() }
        }, FrameLayout.LayoutParams(52.dp(), 54.dp(), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(label("Skill Progression", 21f, Typeface.BOLD, Gravity.CENTER), FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            54.dp(),
            Gravity.CENTER,
        ).apply {
            marginStart = 54.dp()
            marginEnd = 54.dp()
        })
    }

    private fun pathHeader() = card(strokeColor = border).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(10.dp(), 11.dp(), 15.dp(), 11.dp())
        addView(LearningPathArtworkView(context).apply {
            setPathArtwork(path.artwork, path.ensoVariant)
            contentDescription = "${path.title} artwork"
        }, LayoutParams(122.dp(), 122.dp()))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dp(), 0, 0, 0)
            addView(label(path.title, 23f, Typeface.BOLD))
            addView(label(path.subtitle, 15f).apply {
                setTextColor(muted)
                setPadding(0, 2.dp(), 0, 13.dp())
            })
            addView(label("${path.progressValue} of ${path.totalSteps} steps completed", 14f, Typeface.BOLD).apply {
                setTextColor(if (path.progressValue > 0) red else muted)
            })
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = path.totalSteps.coerceAtLeast(1)
                progress = path.progressValue.coerceIn(0, max)
                progressTintList = ColorStateList.valueOf(red)
                progressBackgroundTintList = ColorStateList.valueOf(border)
            }, LayoutParams(LayoutParams.MATCH_PARENT, 7.dp()).apply { topMargin = 6.dp() })
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun stepRow(
        step: LearningStep,
        first: Boolean,
        incomingRed: Boolean,
        onStart: (LearningDestination) -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = if (step.progressState == LearningProgressState.CURRENT) 176.dp() else 96.dp()
        addView(TimelineMarkerColumn(
            context = context,
            type = LearningStepType.REGULAR,
            state = step.progressState,
            showTop = !first,
            showBottom = true,
            topColor = if (incomingRed) red else gray,
            bottomColor = if (step.progressState == LearningProgressState.COMPLETED) red else gray,
        ), LayoutParams(54.dp(), LayoutParams.MATCH_PARENT))
        addView(stepCard(step, onStart), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = 6.dp()
            bottomMargin = 7.dp()
        })
    }

    private fun stepCard(step: LearningStep, onStart: (LearningDestination) -> Unit) =
        card(strokeColor = if (step.progressState == LearningProgressState.CURRENT) red else border).apply {
            setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            gravity = Gravity.CENTER_VERTICAL
            addView(label(step.number.toString(), 16f, Typeface.BOLD, Gravity.TOP or Gravity.CENTER_HORIZONTAL), LayoutParams(
                34.dp(),
                LayoutParams.MATCH_PARENT,
            ))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(step.title, 17f, Typeface.BOLD))
                addView(label(step.description, 14f).apply {
                    setTextColor(muted)
                    setPadding(0, 3.dp(), 0, if (step.progressState == LearningProgressState.CURRENT) 10.dp() else 0)
                })
                if (step.progressState == LearningProgressState.CURRENT) {
                    step.metadata?.estimatedMinutes?.let { minutes ->
                        addView(metadataRow(AppIcon.CLOCK, "Estimated time: $minutes min"))
                    }
                    if (step.metadata?.cameraRequired == true) {
                        addView(metadataRow(AppIcon.CAMERA, "Camera required"))
                    }
                    step.destination?.let { destination ->
                        addView(actionButton("Start") { onStart(destination) }, LayoutParams(108.dp(), 46.dp()).apply {
                            gravity = Gravity.END
                            topMargin = 5.dp()
                        })
                    }
                }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (step.progressState == LearningProgressState.COMPLETED) {
                addView(AppIconView(context, AppIcon.CHEVRON_RIGHT).apply { setIconColor(ink) }, LayoutParams(24.dp(), 42.dp()))
            }
        }

    private fun metadataRow(icon: AppIcon, text: String) = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(AppIconView(context, icon).apply { setIconColor(muted) }, LayoutParams(20.dp(), 20.dp()))
        addView(label(text, 13f).apply {
            setTextColor(muted)
            setPadding(6.dp(), 0, 0, 0)
        })
    }

    private fun milestoneRow(previousState: LearningProgressState?) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = 112.dp()
        addView(TimelineMarkerColumn(
            context = context,
            type = LearningStepType.MILESTONE,
            state = path.milestone.progressState,
            showTop = true,
            showBottom = false,
            topColor = if (previousState == LearningProgressState.COMPLETED) red else gray,
            bottomColor = gray,
        ), LayoutParams(54.dp(), LayoutParams.MATCH_PARENT))
        addView(card(strokeColor = border).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Skill milestone", 12f).apply { setTextColor(muted) })
                addView(label(path.milestone.title, 18f, Typeface.BOLD))
                addView(label(path.milestone.description, 13f).apply {
                    setTextColor(muted)
                    setPadding(0, 2.dp(), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = 6.dp()
            bottomMargin = 7.dp()
        })
    }

    private fun actionButton(text: String, onClick: () -> Unit) = label(
        text,
        16f,
        Typeface.BOLD,
        Gravity.CENTER,
    ).apply {
        setTextColor(android.graphics.Color.WHITE)
        background = GradientDrawable().apply {
            setColor(red)
            cornerRadius = 11.dp().toFloat()
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun card(strokeColor: Int) = LinearLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(paper)
            cornerRadius = 15.dp().toFloat()
            setStroke(1.dp(), strokeColor)
        }
        elevation = 1.dp().toFloat()
    }

    private fun label(text: String, size: Float, style: Int = Typeface.NORMAL, gravity: Int = Gravity.START) =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", style)
            this.gravity = gravity
        }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}

/** Paints the connector behind the marker so the line never crosses the SVG. */
private class TimelineMarkerColumn(
    context: Context,
    type: LearningStepType,
    state: LearningProgressState,
    private val showTop: Boolean,
    private val showBottom: Boolean,
    private val topColor: Int,
    private val bottomColor: Int,
) : FrameLayout(context) {
    private val markerSize = 42.dp()
    private val markerTop = 10.dp()
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.dp().toFloat()
    }

    init {
        setWillNotDraw(false)
        addView(ProgressMarkerView(context).apply {
            setMarker(type, state)
        }, LayoutParams(markerSize, markerSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = markerTop
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val markerCenter = markerTop + markerSize / 2f
        if (showTop) {
            railPaint.color = topColor
            canvas.drawLine(centerX, 0f, centerX, markerCenter, railPaint)
        }
        if (showBottom) {
            railPaint.color = bottomColor
            canvas.drawLine(centerX, markerCenter, centerX, height.toFloat(), railPaint)
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
}
