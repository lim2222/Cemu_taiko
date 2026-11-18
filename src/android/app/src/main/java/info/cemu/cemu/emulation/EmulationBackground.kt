package info.cemu.cemu.emulation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import info.cemu.cemu.common.settings.ScreenBackground
import kotlin.math.max

// Decodes a picked wallpaper at a bounded size. Content providers are not always happy to hand out
// the same stream twice, so a file descriptor is tried as a fallback before giving up.
object EmulationBackgroundLoader {
    fun decode(context: Context, uri: Uri, maxSide: Int = 2048): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        } catch (_: Exception) {
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            try {
                resolver.openFileDescriptor(uri, "r")?.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
                }
            } catch (_: Exception) {
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        }
        try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }?.let { return it }
        } catch (_: Exception) {
        }
        return try {
            resolver.openFileDescriptor(uri, "r")?.use {
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxSide) sample *= 2
        return sample
    }
}

// Draws the wallpaper around the rendering surfaces. It is called by the input overlay because
// that SurfaceView is above the emulation surfaces but still below the Compose UI, so the
// wallpaper can never cover the buttons, the editor toolbar or the side menu.
class EmulationBackgroundRenderer {
    var bitmap: Bitmap? = null
    var holes: List<Rect> = emptyList()
    var transform: ScreenBackground = ScreenBackground()

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, width: Int, height: Int) {
        val image = bitmap ?: return
        if (image.isRecycled || width <= 0 || height <= 0) return

        val scaleX = transform.scaleX.coerceIn(
            ScreenBackground.MIN_SCALE,
            ScreenBackground.MAX_SCALE,
        )
        val scaleY = transform.scaleY.coerceIn(
            ScreenBackground.MIN_SCALE,
            ScreenBackground.MAX_SCALE,
        )
        val cover = max(width / image.width.toFloat(), height / image.height.toFloat())
        val drawnWidth = image.width * cover * scaleX
        val drawnHeight = image.height * cover * scaleY
        // Only allow panning while the image is larger than the view. A smaller image stays
        // centered so it can never be dragged out of sight.
        val maxX = max(0f, (drawnWidth - width) / 2f)
        val maxY = max(0f, (drawnHeight - height) / 2f)
        val offsetX = transform.translateX.coerceIn(-maxX, maxX)
        val offsetY = transform.translateY.coerceIn(-maxY, maxY)

        matrix.reset()
        matrix.postScale(cover * scaleX, cover * scaleY)
        matrix.postTranslate(
            (width - drawnWidth) / 2f + offsetX,
            (height - drawnHeight) / 2f + offsetY,
        )

        val saved = canvas.save()
        for (area in visibleAreas(width, height)) {
            if (area.isEmpty) continue
            val areaSaved = canvas.save()
            canvas.clipRect(area)
            canvas.drawBitmap(image, matrix, paint)
            canvas.restoreToCount(areaSaved)
        }
        canvas.restoreToCount(saved)
    }

    // Splits the view into bands separated by the screen edges, then into the strips left and right
    // of the screens in each band. clipOutRect() is avoided because it produced empty clips on some
    // devices and made the wallpaper disappear completely.
    private fun visibleAreas(width: Int, height: Int): List<RectF> {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val edges = sortedSetOf(0f, viewHeight)
        for (hole in holes) {
            edges.add(hole.top.toFloat().coerceIn(0f, viewHeight))
            edges.add(hole.bottom.toFloat().coerceIn(0f, viewHeight))
        }
        val sortedEdges = edges.toList()
        val areas = mutableListOf<RectF>()

        for (index in 0 until sortedEdges.size - 1) {
            val top = sortedEdges[index]
            val bottom = sortedEdges[index + 1]
            if (bottom <= top) continue

            val blocked = mutableListOf<Pair<Float, Float>>()
            for (hole in holes) {
                if (hole.bottom > top && hole.top < bottom) {
                    blocked.add(
                        hole.left.toFloat().coerceIn(0f, viewWidth) to
                            hole.right.toFloat().coerceIn(0f, viewWidth),
                    )
                }
            }
            blocked.sortBy { it.first }

            var x = 0f
            for ((left, right) in blocked) {
                if (left > x) areas.add(RectF(x, top, left, bottom))
                x = max(x, right)
            }
            if (x < viewWidth) areas.add(RectF(x, top, viewWidth, bottom))
        }
        return areas
    }
}
