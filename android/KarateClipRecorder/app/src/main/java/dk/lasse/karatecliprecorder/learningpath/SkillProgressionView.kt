package dk.lasse.karatecliprecorder.learningpath

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
import dk.lasse.karatecliprecorder.AppIcon
import dk.lasse.karatecliprecorder.AppIconView
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader
import dk.lasse.karatecliprecorder.learningartwork.LearningPathArtworkView

class SkillProgressionView(
    context: Context,
    private val path: LearningPath,
    onBack: () -> Unit,
    onStart: (LearningDestination) -> Unit,
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
        val timeline = ProgressionTimelineLayout(context, red, gray).apply {
            path.steps.forEach { step ->
                addProgressCard(
                    card = stepCard(step, onStart),
                    type = LearningStepType.REGULAR,
                    state = step.progressState,
                )
            }
            addProgressCard(
                card = milestoneCard(),
                type = LearningStepType.MILESTONE,
                state = path.milestone.progressState,
            )
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathHeader(), LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
            addView(label("Your learning path", 21f, Typeface.BOLD).apply {
                setPadding(2.dp(), 24.dp(), 0, 8.dp())
            })
            addView(timeline, LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
                1f,
            ))
        }

        val pageLayout = StickyHeaderPageLayout(
            context = context,
            header = SubPageHeader(context, "Skill Progression", onBack = onBack),
            body = content,
            horizontalContentPaddingDp = 16,
            topContentPaddingDp = 8,
            bottomContentClearanceDp = 28,
        )
        val scroller = pageLayout.scroller
        addView(pageLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val remainingViewportHeight = (
                scroller.height - timeline.top - pageLayout.content.paddingBottom
            ).coerceAtLeast(0)
            if (timeline.minimumHeight != remainingViewportHeight) {
                timeline.minimumHeight = remainingViewportHeight
            }
        }

    }

    private fun pathHeader() = card(strokeColor = border).apply {
        gravity = Gravity.CENTER_VERTICAL
        setCardContentPadding(10.dp(), 11.dp(), 15.dp(), 11.dp())
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

    private fun stepCard(step: LearningStep, onStart: (LearningDestination) -> Unit) =
        card(strokeColor = if (step.progressState == LearningProgressState.CURRENT) red else border).apply {
            setCardContentPadding(12.dp(), 12.dp(), 12.dp(), 12.dp())
            gravity = Gravity.CENTER_VERTICAL
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
            step.destination?.takeIf { step.progressState != LearningProgressState.LOCKED }?.let { destination ->
                isClickable = true
                isFocusable = true
                setOnClickListener { onStart(destination) }
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

    private fun milestoneCard() = card(strokeColor = border).apply {
        gravity = Gravity.CENTER_VERTICAL
        setCardContentPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Skill milestone", 12f).apply { setTextColor(muted) })
            addView(label(path.milestone.title, 18f, Typeface.BOLD))
            addView(label(path.milestone.description, 13f).apply {
                setTextColor(muted)
                setPadding(0, 2.dp(), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
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

    private fun card(strokeColor: Int) = ProgressCardView(
        context = context,
        surfaceColor = paper,
        borderColor = strokeColor,
    )

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

internal object ProgressionGapCalculator {
    fun calculate(
        availableHeight: Int?,
        totalCardHeight: Int,
        verticalPadding: Int,
        gapCount: Int,
        minimumGap: Int,
        targetGap: Int,
        maximumGap: Int,
    ): Int {
        if (gapCount <= 0) return 0
        if (availableHeight == null) return targetGap.coerceIn(minimumGap, maximumGap)
        val spaceForGaps = availableHeight - totalCardHeight - verticalPadding
        return (spaceForGaps / gapCount).coerceIn(minimumGap, maximumGap)
    }
}

/**
 * Cards own the geometry. This layout measures every card first, derives a clamped gap from the
 * remaining height, then anchors markers and connector segments to the measured card positions.
 */
internal class ProgressionTimelineLayout(
    context: Context,
    private val red: Int,
    private val gray: Int,
) : ViewGroup(context) {
    private data class Entry(
        val card: View,
        val marker: ProgressMarkerView,
        val type: LearningStepType,
        val state: LearningProgressState,
    )

    private val entries = mutableListOf<Entry>()
    private val markerCenters = mutableListOf<Float>()
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.dp().toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private var resolvedGap = TARGET_GAP_DP.dp()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        setPadding(0, TOP_PADDING_DP.dp(), 0, BOTTOM_PADDING_DP.dp())
    }

    fun addProgressCard(card: View, type: LearningStepType, state: LearningProgressState) {
        addProgressCard(card, type, state, animateCompletion = false)
    }

    fun addProgressCard(
        card: View,
        type: LearningStepType,
        state: LearningProgressState,
        animateCompletion: Boolean,
    ) {
        val marker = ProgressMarkerView(context).apply { setMarker(type, state, animateCompletion) }
        entries += Entry(card, marker, type, state)
        addView(marker)
        addView(card)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val cardWidth = (measuredWidth - MARKER_COLUMN_WIDTH_DP.dp()).coerceAtLeast(0)
        val cardWidthSpec = MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY)
        val naturalHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val markerSpec = MeasureSpec.makeMeasureSpec(MARKER_SIZE_DP.dp(), MeasureSpec.EXACTLY)

        entries.forEach { entry ->
            entry.card.measure(cardWidthSpec, naturalHeightSpec)
            entry.marker.measure(markerSpec, markerSpec)
        }

        val totalCardHeight = entries.sumOf { it.card.measuredHeight }
        val gapCount = (entries.size - 1).coerceAtLeast(0)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val availableHeight = when {
            heightMode != MeasureSpec.UNSPECIFIED -> MeasureSpec.getSize(heightMeasureSpec)
            suggestedMinimumHeight > 0 -> suggestedMinimumHeight
            else -> null
        }
        resolvedGap = ProgressionGapCalculator.calculate(
            availableHeight = availableHeight,
            totalCardHeight = totalCardHeight,
            verticalPadding = paddingTop + paddingBottom,
            gapCount = gapCount,
            minimumGap = MINIMUM_GAP_DP.dp(),
            targetGap = TARGET_GAP_DP.dp(),
            maximumGap = MAXIMUM_GAP_DP.dp(),
        )
        val desiredHeight = maxOf(
            paddingTop + paddingBottom + totalCardHeight + resolvedGap * gapCount,
            suggestedMinimumHeight,
        )
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        markerCenters.clear()
        val markerLeft = (MARKER_COLUMN_WIDTH_DP.dp() - MARKER_SIZE_DP.dp()) / 2
        val cardLeft = MARKER_COLUMN_WIDTH_DP.dp()
        var cardTop = paddingTop
        entries.forEach { entry ->
            val cardBottom = cardTop + entry.card.measuredHeight
            entry.card.layout(cardLeft, cardTop, width, cardBottom)
            val anchor = cardTop + minOf(CARD_ANCHOR_DP.dp(), entry.card.measuredHeight / 2)
            val markerTop = anchor - MARKER_SIZE_DP.dp() / 2
            entry.marker.layout(
                markerLeft,
                markerTop,
                markerLeft + MARKER_SIZE_DP.dp(),
                markerTop + MARKER_SIZE_DP.dp(),
            )
            markerCenters += anchor.toFloat()
            cardTop = cardBottom + resolvedGap
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val railX = MARKER_COLUMN_WIDTH_DP.dp() / 2f
        val markerRadius = MARKER_SIZE_DP.dp() * 0.43f
        entries.forEachIndexed { index, entry ->
            val centerY = markerCenters.getOrNull(index) ?: return@forEachIndexed
            railPaint.color = markerColor(entry)
            canvas.drawLine(
                railX + markerRadius,
                centerY,
                MARKER_COLUMN_WIDTH_DP.dp().toFloat() + CARD_SHADOW_INSET_DP.dp(),
                centerY,
                railPaint,
            )
            val nextCenter = markerCenters.getOrNull(index + 1) ?: return@forEachIndexed
            railPaint.color = if (entry.state == LearningProgressState.COMPLETED) red else gray
            canvas.drawLine(
                railX,
                centerY + markerRadius,
                railX,
                nextCenter - markerRadius,
                railPaint,
            )
        }
    }

    private fun markerColor(entry: Entry): Int = when {
        entry.type == LearningStepType.MILESTONE && entry.state != LearningProgressState.COMPLETED -> gray
        entry.state == LearningProgressState.LOCKED -> gray
        else -> red
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val MARKER_COLUMN_WIDTH_DP = 54
        private const val MARKER_SIZE_DP = 42
        private const val CARD_ANCHOR_DP = 28
        private const val CARD_SHADOW_INSET_DP = 4
        private const val TOP_PADDING_DP = 6
        private const val BOTTOM_PADDING_DP = 10
        private const val MINIMUM_GAP_DP = 28
        private const val TARGET_GAP_DP = 48
        private const val MAXIMUM_GAP_DP = 56
    }
}

/** Rounded card with a controlled low-opacity shadow offset below the independent state border. */
private class ProgressCardView(
    context: Context,
    private val surfaceColor: Int,
    private val borderColor: Int,
) : LinearLayout(context) {
    private val surfaceBounds = RectF()
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = surfaceColor
        setShadowLayer(8.dp().toFloat(), 0f, 3.dp().toFloat(), 0x14000000)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f.dp()
        color = borderColor
    }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        stateListAnimator = null
        elevation = 0f
    }

    fun setCardContentPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(
            left + HORIZONTAL_SHADOW_INSET_DP.dp(),
            top + SURFACE_TOP_INSET_DP.dp(),
            right + HORIZONTAL_SHADOW_INSET_DP.dp(),
            bottom + SHADOW_BOTTOM_SPACE_DP.dp(),
        )
    }

    override fun onDraw(canvas: Canvas) {
        surfaceBounds.set(
            HORIZONTAL_SHADOW_INSET_DP.dp().toFloat(),
            SURFACE_TOP_INSET_DP.dp().toFloat(),
            width - HORIZONTAL_SHADOW_INSET_DP.dp().toFloat(),
            height - SHADOW_BOTTOM_SPACE_DP.dp().toFloat(),
        )
        val radius = CORNER_RADIUS_DP.dp().toFloat()
        canvas.drawRoundRect(surfaceBounds, radius, radius, surfacePaint)
        canvas.drawRoundRect(surfaceBounds, radius, radius, borderPaint)
        super.onDraw(canvas)
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()
    private fun Float.dp() = this * resources.displayMetrics.density

    companion object {
        private const val CORNER_RADIUS_DP = 30
        private const val HORIZONTAL_SHADOW_INSET_DP = 4
        private const val SURFACE_TOP_INSET_DP = 2
        private const val SHADOW_BOTTOM_SPACE_DP = 9
    }
}
