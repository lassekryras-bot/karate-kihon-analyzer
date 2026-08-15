package dk.lasse.karatecliprecorder.enso

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
                contentDescription = "Close Enso gallery"
                setOnClickListener { onClose() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dp()))
            addView(TextView(context).apply {
                text = "Ensō library"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(24, 24, 24))
            })
            addView(TextView(context).apply {
                text = "20 variants · 15 tones · base ${EnsoThemeTokens.ensoBaseColor.toHexColor()}"
                textSize = 14f
                setTextColor(Color.rgb(92, 92, 92))
                setPadding(0, 4.dp(), 0, 16.dp())
            })
            addView(gallery())
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

    private fun gallery() = GridLayout(context).apply {
        columnCount = 2
        EnsoVariant.all.forEach { variant ->
            addView(variantCell(variant), GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4.dp(), 4.dp(), 4.dp(), 8.dp())
            })
        }
    }

    private fun variantCell(variant: EnsoVariant) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = "Ensō variant ${variant.debugLabel}"
        addView(EnsoBackgroundView(context).apply {
            setArtwork(variant, EnsoThemeTokens.ensoBaseColor)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160.dp()))
        addView(TextView(context).apply {
            text = variant.debugLabel
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(24, 24, 24))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun Int.toHexColor(): String = "#%06X".format(this and 0xFFFFFF)
}
