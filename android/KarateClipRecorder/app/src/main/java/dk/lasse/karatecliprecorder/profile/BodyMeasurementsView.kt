package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dk.lasse.karatecliprecorder.R
import dk.lasse.karatecliprecorder.StickyHeaderPageLayout
import dk.lasse.karatecliprecorder.SubPageHeader

/** Optional profile measurements; these do not perform camera calibration. */
class BodyMeasurementsView(
    context: Context,
    private val repository: ProfileRepository,
    private val profile: Profile,
    onBack: () -> Unit,
) : FrameLayout(context) {
    private val saveButtons = mutableListOf<android.view.View>()

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_background))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(field("Height (cm)", "Standing barefoot, from the floor to the top of your head.", profile.heightCm))
            addView(field("Forearm length (cm)", "From your elbow to your wrist.", profile.forearmLengthCm))
            addView(field("Lower-leg length (cm)", "From your knee to your ankle.", profile.lowerLegLengthCm))
        }
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(StickyHeaderPageLayout(context, SubPageHeader(context, "Body measurements", onBack = onBack), body = content),
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        }
        addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars()).bottom
            page.setPadding(0, 0, 0, bottom)
            insets
        }
    }

    private fun field(label: String, help: String, value: Float?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(20), 0, 0)
        val input = EditText(context).apply {
            id = generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            hint = "Optional"
            setText(value?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }.orEmpty())
            contentDescription = label
        }
        addView(context.profileText(label, 16f, bold = true).apply { labelFor = input.id })
        addView(context.profileText(help, 13f))
        val index = saveButtons.size
        val button = context.primaryProfileButton("Save") { save(input, index) }.apply {
            visibility = android.view.View.INVISIBLE
            contentDescription = "Save $label"
        }
        saveButtons += button
        input.setOnFocusChangeListener { _, focused ->
            if (focused) saveButtons.forEach { it.visibility = if (it === button) android.view.View.VISIBLE else android.view.View.INVISIBLE }
        }
        addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, context.dp(52), 1f))
            addView(button, LinearLayout.LayoutParams(context.dp(80), context.dp(48)).apply { marginStart = context.dp(12) })
        })
    }

    private fun save(input: EditText, index: Int) {
        val text = input.text.toString().trim().replace(',', '.')
        val value = if (text.isEmpty()) null else text.toFloatOrNull()
        if (text.isNotEmpty() && (value == null || !value.isFinite() || value <= 0f)) {
            input.error = "Enter a number greater than zero"
            input.requestFocus()
            return
        }
        val current = repository.listProfiles().firstOrNull { it.id == profile.id } ?: return
        val updated = when (index) {
            0 -> current.copy(heightCm = value)
            1 -> current.copy(forearmLengthCm = value)
            else -> current.copy(lowerLegLengthCm = value)
        }
        repository.updateProfile(updated)
        Toast.makeText(context, "Measurement saved", Toast.LENGTH_SHORT).show()
    }
}
