package dk.lasse.karatecliprecorder.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Xml
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.R
import org.xmlpull.v1.XmlPullParser

/** Renders preprocessed semantic avatar paths with per-instance skin, hair, and belt colors. */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var avatarBaseId = Profile.AVATAR_BASE_IDS.first()
    private var skinTonePosition = 0.5f
    private var hairColorPosition = 0.35f
    private var beltRank = BeltRank.WHITE

    /** Set above zero for carousel thumbnails that should de-emphasize the belt. */
    var bottomCropFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 0.45f)
            invalidate()
        }

    fun setProfile(profile: Profile) {
        setAvatar(profile.avatarBaseId, profile.skinTonePosition, profile.hairColorPosition, profile.beltRank)
        contentDescription = "${profile.name}'s avatar, ${profile.beltRank.displayName} belt"
    }

    fun setAvatar(baseId: String, skinPosition: Float, hairPosition: Float, belt: BeltRank) {
        require(baseId in Profile.AVATAR_BASE_IDS)
        avatarBaseId = baseId
        skinTonePosition = skinPosition.coerceIn(0f, 1f)
        hairColorPosition = hairPosition.coerceIn(0f, 1f)
        beltRank = belt
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Always provide a fully opaque square canvas behind the circular avatar backdrop.
        canvas.drawColor(ContextCompat.getColor(context, R.color.app_card_surface))
        val asset = loadAsset(context, avatarBaseId)
        val visibleHeight = asset.height * (1f - bottomCropFraction)
        val scale = minOf(width / asset.width, height / visibleHeight)
        val dx = (width - asset.width * scale) / 2f
        val dy = (height - visibleHeight * scale) / 2f
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)
        asset.paths.forEach { avatarPath ->
            paint.color = colorFor(avatarPath)
            canvas.drawPath(avatarPath.path, paint)
        }
        canvas.restoreToCount(save)
    }

    private fun colorFor(path: AvatarPath): Int = when (path.role) {
        "skin" -> AvatarPalette.shade(AvatarPalette.skin(skinTonePosition), path.tone)
        "hair" -> AvatarPalette.shade(AvatarPalette.hair(hairColorPosition), path.tone)
        "belt" -> AvatarPalette.shade(AvatarPalette.belt(beltRank), path.tone)
        // Background paths form the inner circle. Keep every layer at one opaque color so
        // neither the avatar canvas nor its parent surface can show through.
        "background" -> ContextCompat.getColor(context, R.color.profile_avatar_background)
        else -> Color.rgb(path.red, path.green, path.blue)
    }

    private data class AvatarAsset(val width: Float, val height: Float, val paths: List<AvatarPath>)
    private data class AvatarPath(
        val role: String,
        val tone: Float,
        val red: Int,
        val green: Int,
        val blue: Int,
        val path: Path,
    )

    companion object {
        private val cache = mutableMapOf<String, AvatarAsset>()

        @Synchronized
        private fun loadAsset(context: Context, baseId: String): AvatarAsset = cache.getOrPut(baseId) {
            context.assets.open("avatars/$baseId.xml").use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                var width = 1f
                var height = 1f
                val paths = mutableListOf<AvatarPath>()
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        "avatar" -> {
                            width = parser.attribute("width").toFloat()
                            height = parser.attribute("height").toFloat()
                        }
                        "path" -> paths += AvatarPath(
                            role = parser.attribute("role"),
                            tone = parser.attribute("tone").toFloat(),
                            red = parser.attribute("red").toInt(),
                            green = parser.attribute("green").toInt(),
                            blue = parser.attribute("blue").toInt(),
                            path = requireNotNull(PathParser.createPathFromPathData(parser.attribute("data"))),
                        )
                    }
                }
                AvatarAsset(width, height, paths)
            }
        }

        private fun XmlPullParser.attribute(name: String) = requireNotNull(getAttributeValue(null, name))
    }
}
