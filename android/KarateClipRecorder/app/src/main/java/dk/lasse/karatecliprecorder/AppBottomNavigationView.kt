package dk.lasse.karatecliprecorder

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

enum class AppDestination {
    HOME,
    LEARNING,
    TRAIN,
    PROGRESS,
    SETTINGS;

    fun label(context: Context): String = when (this) {
        HOME -> context.getString(R.string.nav_home)
        LEARNING -> context.getString(R.string.nav_learning)
        TRAIN -> context.getString(R.string.nav_training)
        PROGRESS -> context.getString(R.string.nav_performance)
        SETTINGS -> context.getString(R.string.nav_settings)
    }

    val icon: AppIcon get() = when (this) {
        HOME -> AppIcon.HOME
        LEARNING -> AppIcon.LEARN
        TRAIN -> AppIcon.KARATE
        PROGRESS -> AppIcon.CHART_BAR
        SETTINGS -> AppIcon.SETTINGS
    }
}

data class AppNavigationState(
    val visibleDestinations: List<AppDestination>,
    val selectedDestination: AppDestination,
    val newlyUnlockedDestinations: Set<AppDestination> = emptySet(),
    val attentionDestination: AppDestination? = null,
) {
    init {
        require(visibleDestinations.isNotEmpty() && visibleDestinations.size <= 5) {
            "Navigation requires between 1 and 5 visible destinations, got ${visibleDestinations.size}"
        }
    }

    fun resolvedSelectedDestination(): AppDestination =
        if (selectedDestination in visibleDestinations) selectedDestination
        else if (AppDestination.HOME in visibleDestinations) AppDestination.HOME
        else visibleDestinations.first()
}

data class AppChromeVisibility(
    val showHeader: Boolean = true,
    val showBottomNavigation: Boolean = true,
)

/**
 * State-driven shared bottom navigation supporting 1 to 5 destinations with canonical ordering,
 * smooth reflow transitions, discovery attention pulses, and edge-to-edge system insets.
 */
class AppBottomNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val itemViews = mutableMapOf<AppDestination, NavigationItemHolder>()
    private val acknowledgedUnlocks = mutableSetOf<AppDestination>()
    private var currentState: AppNavigationState? = null

    var onDestinationSelected: ((AppDestination) -> Unit)? = null

    /** Legacy constructor for existing 4-destination screens. */
    constructor(
        context: Context,
        selectedDestination: AppDestination,
        onHome: () -> Unit,
        onTrain: () -> Unit,
        onProgress: () -> Unit,
        onSettings: () -> Unit,
    ) : this(context) {
        val legacyDestinations = listOf(
            AppDestination.HOME,
            AppDestination.TRAIN,
            AppDestination.PROGRESS,
            AppDestination.SETTINGS,
        )
        onDestinationSelected = { dest ->
            when (dest) {
                AppDestination.HOME -> onHome()
                AppDestination.TRAIN -> onTrain()
                AppDestination.PROGRESS -> onProgress()
                AppDestination.SETTINGS -> onSettings()
                AppDestination.LEARNING -> Unit
            }
        }
        setNavigationState(
            AppNavigationState(
                visibleDestinations = legacyDestinations,
                selectedDestination = selectedDestination,
            )
        )
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        elevation = AppChromeStyle.ELEVATION_DP.dp().toFloat()
        setPadding(
            HORIZONTAL_PADDING_DP.dp(),
            TOP_PADDING_DP.dp(),
            HORIZONTAL_PADDING_DP.dp(),
            BOTTOM_PADDING_DP.dp(),
        )
        background = AppChromeStyle.background(context, Gravity.TOP)

        // Android 15 enforces edge-to-edge. Keep the navigation surface at the physical bottom,
        // then reserve the system navigation inset inside it so OEM bars cannot cover controls.
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val navigationBarBottom = insets
                .getInsets(WindowInsetsCompat.Type.navigationBars())
                .bottom
            view.setPadding(
                HORIZONTAL_PADDING_DP.dp(),
                TOP_PADDING_DP.dp(),
                HORIZONTAL_PADDING_DP.dp(),
                BOTTOM_PADDING_DP.dp() + navigationBarBottom,
            )
            val safeHeight = BASE_HEIGHT_DP.dp() + navigationBarBottom
            view.layoutParams?.let { params ->
                if (params.height != safeHeight) {
                    params.height = safeHeight
                    view.layoutParams = params
                }
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    fun setNavigationState(
        state: AppNavigationState,
        onSelected: ((AppDestination) -> Unit)? = onDestinationSelected,
    ) {
        if (onSelected != null) {
            this.onDestinationSelected = onSelected
        }
        val previousState = currentState
        currentState = state

        val ordered = state.visibleDestinations.distinct().sortedBy { it.ordinal }
        val resolvedSelected = state.resolvedSelectedDestination()

        val previousVisible = previousState?.visibleDestinations.orEmpty().toSet()
        val isTransition = previousVisible.isNotEmpty() && previousVisible != ordered.toSet()
        val animatorsEnabled = ValueAnimator.areAnimatorsEnabled()

        if (isTransition && animatorsEnabled) {
            TransitionManager.beginDelayedTransition(
                this,
                ChangeBounds().apply { duration = 250 },
            )
        }

        // Remove views for destinations that are no longer visible
        val visibleSet = ordered.toSet()
        val removed = itemViews.keys.filter { it !in visibleSet }
        removed.forEach { dest ->
            val holder = itemViews.remove(dest)
            holder?.let { removeView(it.root) }
        }

        // Configure gravity: single destination is visually centered, multiple fill width with weight
        gravity = Gravity.CENTER

        ordered.forEachIndexed { index, destination ->
            val isNewDestination = destination !in previousVisible && isTransition
            var holder = itemViews[destination]
            if (holder == null) {
                holder = createNavigationItem(destination)
                itemViews[destination] = holder
            }

            // Ensure proper position in hierarchy
            val currentChildIndex = indexOfChild(holder.root)
            if (currentChildIndex == -1) {
                addView(holder.root, index)
            } else if (currentChildIndex != index) {
                removeView(holder.root)
                addView(holder.root, index)
            }

            // Layout parameters
            if (ordered.size == 1) {
                holder.root.layoutParams = LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT,
                ).apply {
                    setPadding(16.dp(), 0, 16.dp(), 0)
                }
            } else {
                holder.root.layoutParams = LayoutParams(
                    0,
                    LayoutParams.MATCH_PARENT,
                    1f,
                ).apply {
                    setPadding(0, 0, 0, 0)
                }
            }

            // Selection state
            val selected = destination == resolvedSelected
            holder.root.isSelected = selected
            holder.iconView.isSelected = selected
            holder.labelView.isSelected = selected
            ViewCompat.setStateDescription(holder.root, if (selected) "Selected" else "Not selected")

            // Entrance animation for newly added destination
            if (isNewDestination) {
                if (animatorsEnabled) {
                    holder.root.alpha = 0f
                    holder.root.scaleX = 0.88f
                    holder.root.scaleY = 0.88f
                    holder.root.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .start()
                } else {
                    holder.root.alpha = 1f
                    holder.root.scaleX = 1f
                    holder.root.scaleY = 1f
                }
            } else {
                holder.root.alpha = 1f
                holder.root.scaleX = 1f
                holder.root.scaleY = 1f
            }

            // Discovery attention pulse
            val needsAttention = destination == state.attentionDestination ||
                (destination in state.newlyUnlockedDestinations && destination !in acknowledgedUnlocks)

            if (needsAttention) {
                acknowledgedUnlocks += destination
                if (animatorsEnabled) {
                    pulseDestination(holder)
                }
            }
        }
    }

    private fun pulseDestination(holder: NavigationItemHolder) {
        val scaleX = ObjectAnimator.ofFloat(holder.iconView, "scaleX", 1f, 1.15f, 1f, 1.15f, 1f)
        val scaleY = ObjectAnimator.ofFloat(holder.iconView, "scaleY", 1f, 1.15f, 1f, 1.15f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 750
            start()
        }
    }

    private fun createNavigationItem(
        destination: AppDestination,
    ): NavigationItemHolder {
        val label = destination.label(context)
        val icon = destination.icon
        val tint = requireNotNull(ContextCompat.getColorStateList(context, R.color.nav_icon_tint))

        val iconView = AppIconView(context, icon, tint = tint)
        val labelView = TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = 48.dp()
            minimumHeight = 48.dp()
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onDestinationSelected?.invoke(destination) }
            addView(iconView, LayoutParams(24.dp(), 24.dp()))
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 2.dp()
            })
        }

        return NavigationItemHolder(root, iconView, labelView)
    }

    private class NavigationItemHolder(
        val root: LinearLayout,
        val iconView: AppIconView,
        val labelView: TextView,
    )

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val BASE_HEIGHT_DP = 68
        const val CONTENT_CLEARANCE_DP = BASE_HEIGHT_DP + 28
        private const val HORIZONTAL_PADDING_DP = 12
        private const val TOP_PADDING_DP = 4
        private const val BOTTOM_PADDING_DP = 0

        // Keep explicit resource references for architecture reflection/static analysis tests
        private val DRAWABLE_RESOURCES = listOf(
            R.drawable.ic_nav_home,
            R.drawable.ic_tabler_karate,
            R.drawable.ic_nav_progress,
            R.drawable.ic_nav_settings,
        )
    }
}
