package dk.lasse.karatecliprecorder.learningactivity

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.Xml
import android.view.View
import androidx.annotation.RawRes
import androidx.core.graphics.PathParser
import dk.lasse.karatecliprecorder.R
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

enum class KarateFaceVariant { MALE, FEMALE }

enum class KarateBeltRank(val tonesDarkToLight: List<Int>) {
    WHITE(listOf("#4C4B49", "#85837F", "#BBB9B4", "#E4E2DD", "#FCFBF7").map(Color::parseColor)),
    YELLOW(listOf("#6E5000", "#9F7500", "#D2A51B", "#F0CB55", "#FFE99A").map(Color::parseColor)),
    ORANGE(listOf("#713000", "#A84800", "#DB6B12", "#F39742", "#FFC182").map(Color::parseColor)),
    GREEN(listOf("#123D20", "#1D6434", "#31864A", "#62AA70", "#9BD0A4").map(Color::parseColor)),
    BLUE(listOf("#112F55", "#1D4E82", "#3474AE", "#6B9DCA", "#A6C7E2").map(Color::parseColor)),
    BROWN(listOf("#321B12", "#583023", "#7C4A36", "#A67860", "#CEAA94").map(Color::parseColor)),
    BLACK(listOf("#050505", "#1C1C1C", "#353535", "#555555", "#777777").map(Color::parseColor)),
}

data class KarateCharacterPlacement(
    val faceMale: RectF = RectF(178f, -97f, 518f, 271f),
    val faceFemale: RectF = RectF(165f, -92f, 531f, 220f),
    val belt: RectF = RectF(198f, 456f, 462f, 733f),
    val beltOpticalScale: Float = 0.929575f,
)

/** Layered, reusable SVG character renderer with independent face and five-tone belt styling. */
@SuppressLint("ViewConstructor")
class KarateCharacterView(
    context: Context,
    faceVariant: KarateFaceVariant = KarateFaceVariant.MALE,
    beltRank: KarateBeltRank = KarateBeltRank.WHITE,
    private val placement: KarateCharacterPlacement = KarateCharacterPlacement(),
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var faceVariant = faceVariant
    private var beltRank = beltRank
    private var bodyArtwork: ToneSvgArtwork? = null
    private var faceArtwork: ToneSvgArtwork? = null
    private var beltArtwork: ToneSvgArtwork? = null
    private var loadGeneration = 0

    init {
        contentDescription = "Karate learner wearing a ${beltRank.name.lowercase()} belt"
        loadArtwork()
    }

    fun setFaceVariant(variant: KarateFaceVariant) {
        if (faceVariant == variant) return
        faceVariant = variant
        loadArtwork()
    }

    fun setBeltRank(rank: KarateBeltRank) {
        if (beltRank == rank) return
        beltRank = rank
        contentDescription = "Karate learner wearing a ${rank.name.lowercase()} belt"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val body = bodyArtwork ?: return
        val face = faceArtwork ?: return
        val belt = beltArtwork ?: return
        if (width == 0 || height == 0) return

        val masterScale = min(width / MASTER_WIDTH, height / MASTER_HEIGHT)
        val masterLeft = (width - MASTER_WIDTH * masterScale) / 2f
        val masterTop = (height - MASTER_HEIGHT * masterScale) / 2f

        drawLayer(canvas, body, MASTER_RECT, masterLeft, masterTop, masterScale)
        drawLayer(
            canvas,
            face,
            if (faceVariant == KarateFaceVariant.MALE) placement.faceMale else placement.faceFemale,
            masterLeft,
            masterTop,
            masterScale,
        )
        drawLayer(
            canvas,
            belt,
            placement.belt.scaledAroundCenter(placement.beltOpticalScale),
            masterLeft,
            masterTop,
            masterScale,
            tonePalette = beltRank.tonesDarkToLight,
        )
    }

    private fun drawLayer(
        canvas: Canvas,
        artwork: ToneSvgArtwork,
        destination: RectF,
        masterLeft: Float,
        masterTop: Float,
        masterScale: Float,
        tonePalette: List<Int>? = null,
    ) {
        canvas.save()
        canvas.translate(
            masterLeft + destination.left * masterScale,
            masterTop + destination.top * masterScale,
        )
        canvas.scale(
            destination.width() * masterScale / artwork.viewportWidth,
            destination.height() * masterScale / artwork.viewportHeight,
        )
        artwork.paths.forEach { svgPath ->
            paint.color = tonePalette
                ?.getOrNull((svgPath.toneNumber ?: 1) - 1)
                ?: svgPath.sourceColor
            canvas.drawPath(svgPath.path, paint)
        }
        canvas.restore()
    }

    private fun loadArtwork() {
        loadGeneration += 1
        val generation = loadGeneration
        val faceResource = when (faceVariant) {
            KarateFaceVariant.MALE -> R.raw.karate_character_face_male
            KarateFaceVariant.FEMALE -> R.raw.karate_character_face_female
        }
        loadOne(R.raw.karate_character_body_base, generation) { bodyArtwork = it }
        loadOne(faceResource, generation) { faceArtwork = it }
        loadOne(R.raw.karate_character_belt_template, generation) { beltArtwork = it }
    }

    private fun loadOne(@RawRes resourceId: Int, generation: Int, assign: (ToneSvgArtwork) -> Unit) {
        ToneSvgRepository.loadAsync(resources, resourceId) { result ->
            post {
                if (generation != loadGeneration) return@post
                result.onSuccess {
                    assign(it)
                    invalidate()
                }.onFailure { error ->
                    Log.e(TAG, "Unable to render karate character layer $resourceId.", error)
                }
            }
        }
    }

    private fun RectF.scaledAroundCenter(scale: Float): RectF {
        val halfWidth = width() * scale / 2f
        val halfHeight = height() * scale / 2f
        return RectF(centerX() - halfWidth, centerY() - halfHeight, centerX() + halfWidth, centerY() + halfHeight)
    }

    private companion object {
        const val TAG = "KarateCharacter"
        const val MASTER_WIDTH = 620f
        const val MASTER_HEIGHT = 1077f
        val MASTER_RECT = RectF(0f, 0f, MASTER_WIDTH, MASTER_HEIGHT)
    }
}

private data class ToneSvgPath(
    val path: Path,
    val sourceColor: Int,
    val toneNumber: Int?,
)

private data class ToneSvgArtwork(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val paths: List<ToneSvgPath>,
)

private object ToneSvgRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<Int, ToneSvgArtwork>()

    fun loadAsync(resources: Resources, @RawRes resourceId: Int, callback: (Result<ToneSvgArtwork>) -> Unit) {
        cache[resourceId]?.let { callback(Result.success(it)); return }
        executor.execute {
            callback(runCatching {
                cache[resourceId] ?: resources.openRawResource(resourceId).use { stream ->
                    parse(stream.reader()).also { cache[resourceId] = it }
                }
            })
        }
    }

    private fun parse(reader: java.io.Reader): ToneSvgArtwork {
        val parser = Xml.newPullParser().apply { setInput(reader) }
        var viewportWidth = 1f
        var viewportHeight = 1f
        val paths = mutableListOf<ToneSvgPath>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfter(':')) {
                    "svg" -> parser.attribute("viewBox")?.let { viewBox ->
                        val values = viewBox.trim().split(Regex("\\s+")).map(String::toFloat)
                        require(values.size == 4 && values[2] > 0f && values[3] > 0f)
                        viewportWidth = values[2]
                        viewportHeight = values[3]
                    }
                    "path" -> {
                        val path = requireNotNull(
                            PathParser.createPathFromPathData(requireNotNull(parser.attribute("d"))),
                        )
                        path.fillType = if (parser.attribute("fill-rule") == "evenodd") {
                            Path.FillType.EVEN_ODD
                        } else {
                            Path.FillType.WINDING
                        }
                        paths += ToneSvgPath(
                            path = path,
                            sourceColor = Color.parseColor(requireNotNull(parser.attribute("fill"))),
                            toneNumber = parser.attribute("data-tone")?.toIntOrNull(),
                        )
                    }
                }
            }
            parser.next()
        }
        require(paths.isNotEmpty()) { "Character SVG contains no paths." }
        return ToneSvgArtwork(viewportWidth, viewportHeight, paths)
    }

    private fun XmlPullParser.attribute(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).substringAfter(':') == name) return getAttributeValue(index)
        }
        return null
    }
}
