package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import info.cemu.cemu.emulation.inputoverlay.OverlayTaiko
import info.cemu.cemu.emulation.inputoverlay.Colors
import kotlin.math.abs
import kotlin.math.sqrt

class TaikoDrum(
    private val onButtonStateChange: (button: OverlayTaiko, state: Boolean) -> Unit,
    private val alpha: Int,
    rect: Rect,
    private val drumBitmap: Bitmap,
    private val excludeRects: List<Rect> = emptyList(),
) : Input(rect) {
    private var backgroundFillColor: Int = 0
    private var backgroundStrokeColor: Int = 0

    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var halfWidth: Float = 0f
    private var halfHeight: Float = 0f
    private var donRatioX2: Float = 0f
    private var donRatioY2: Float = 0f

    private val activePointers = mutableMapOf<Int, Int>()

    // A same-button re-hit can only happen across a release gap (see retriggerSameButton), so keep
    // a per-pointer token to cancel stale presses and the time of the last one.
    private val handler = Handler(Looper.getMainLooper())
    private val retriggerSeq = mutableMapOf<Int, Int>()
    private val lastRetriggerTime = mutableMapOf<Int, Long>()

    override fun configure() {
        backgroundFillColor = Colors.backgroundFill(alpha)
        backgroundStrokeColor = Colors.backgroundStroke(alpha)
        configureColors(alpha)

        centerX = rect.exactCenterX()
        centerY = rect.exactCenterY()
        halfWidth = rect.width() * 0.5f
        halfHeight = rect.height() * 0.5f
		donRatioX2 = 0.28f * 0.28f
        donRatioY2 = 0.35f * 0.35f  // Don ( in between 0-1),adjustable
    }

    override fun moveInput(x: Int, y: Int, maxWidth: Int, maxHeight: Int) {
        val width = rect.width()
        val height = rect.height()
        val left = x - width / 2
        val top = y - height / 2
        rect = Rect(left, top, left + width, top + height)
        configure()
    }

    override fun resize(
        diffX: Int,
        diffY: Int,
        maxWidth: Int,
        maxHeight: Int,
        minWidthHeight: Int
    ) {
        val newRight = rect.right + diffX
        val newBottom = rect.bottom + diffY
        if (newRight - rect.left < minWidthHeight || newBottom - rect.top < minWidthHeight) {
            return
        }
        rect = Rect(rect.left, rect.top, newRight, newBottom)
        configure()
    }

    init {
        configure()
    }
	
	private fun isExcluded(x: Float, y: Float): Boolean {
		return excludeRects.any { it.contains(x.toInt(), y.toInt()) }
	}
	
    private fun getZoneByPosition(x: Float, y: Float, previousZone: Int = NONE): Int {
		if (isExcluded(x, y)) return NONE
		
		val dx = (x - centerX) / halfWidth
		val dy = (y - centerY) / halfHeight
		val norm2 = dx * dx + dy * dy

		if (norm2 > 1.0f) return NONE

        val donCheck = (dx * dx) / donRatioX2 + (dy * dy) / donRatioY2

        if (donCheck >= 1.0f) {
            // Rim: unchanged, Ka is still only split left / right.
            return if (dx < 0f) KA_LEFT else KA_RIGHT
        }

        // Drum face, four quadrants. The top and bottom quadrant of the same side both map to that
        // side's Don button, so crossing the horizontal centre line retriggers a Don hit: sliding
        // in a circle over the face keeps firing instead of only switching left / right twice.
        val isLeft = dx < 0f
        val isTop = isTopHalf(dy, isLeft, previousZone)
        return when {
            isLeft && isTop -> DON_LEFT_TOP
            isLeft -> DON_LEFT_BOTTOM
            isTop -> DON_RIGHT_TOP
            else -> DON_RIGHT_BOTTOM
        }
    }

    // Dead band around the horizontal centre line of the drum face. While the finger stays inside
    // it, it keeps its current half, so a finger resting near the middle does not machine-gun on
    // touch jitter while a deliberate up / down pass still retriggers.
    private fun isTopHalf(dy: Float, isLeft: Boolean, previousZone: Int): Boolean {
        val previousIsTop = when (previousZone) {
            DON_LEFT_TOP, DON_RIGHT_TOP -> true
            DON_LEFT_BOTTOM, DON_RIGHT_BOTTOM -> false
            else -> null
        }
        val previousIsLeft = previousZone == DON_LEFT_TOP || previousZone == DON_LEFT_BOTTOM
        if (previousIsTop != null && previousIsLeft == isLeft && abs(dy) < DON_SPLIT_DEAD_BAND) {
            return previousIsTop
        }
        return dy < 0f
    }

    private fun isDonZone(zoneBit: Int): Boolean =
        zoneBit == DON_LEFT_TOP || zoneBit == DON_RIGHT_TOP ||
            zoneBit == DON_LEFT_BOTTOM || zoneBit == DON_RIGHT_BOTTOM

    private fun zoneToButton(zoneBit: Int): OverlayTaiko? = when (zoneBit) {
        DON_LEFT_TOP, DON_LEFT_BOTTOM -> OverlayTaiko.DON_LEFT
        DON_RIGHT_TOP, DON_RIGHT_BOTTOM -> OverlayTaiko.DON_RIGHT
        KA_LEFT -> OverlayTaiko.KA_LEFT
        KA_RIGHT -> OverlayTaiko.KA_RIGHT
        else -> null
    }

    private fun updateZone(zoneBit: Int, pressed: Boolean) {
        zoneToButton(zoneBit)?.let { onButtonStateChange(it, pressed) }
    }

    // The tatacon is a WPAD device that the game samples once per frame, so a release and re-press
    // of the same button inside one touch event is invisible: it looks like one continuous hold
    // and produces no new hit. That is why sliding from the top to the bottom quadrant used to do
    // nothing. Release now and press again a couple of frames later, so the game definitely reads
    // the released state in between and the re-press counts as a fresh hit.
    private fun retriggerSameButton(pointerId: Int, button: OverlayTaiko) {
        val token = (retriggerSeq[pointerId] ?: 0) + 1
        retriggerSeq[pointerId] = token
        lastRetriggerTime[pointerId] = SystemClock.uptimeMillis()

        onButtonStateChange(button, false)
        handler.postDelayed({
            if (retriggerSeq[pointerId] != token) return@postDelayed
            val currentZone = activePointers[pointerId] ?: return@postDelayed
            if (zoneToButton(currentZone) != button) return@postDelayed
            onButtonStateChange(button, true)
        }, RETRIGGER_RELEASE_GAP_MS)
    }

    private fun cancelRetrigger(pointerId: Int) {
        retriggerSeq[pointerId] = (retriggerSeq[pointerId] ?: 0) + 1
    }

    override fun onTouch(event: MotionEvent): Boolean {
        var handled = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (isInside(x, y)) {
                    val zone = getZoneByPosition(x, y)
                    if (zone != NONE) {
                        cancelRetrigger(pointerId)
                        activePointers[pointerId] = zone
                        updateZone(zone, true)
                        handled = true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val previousZone = activePointers[pointerId] ?: continue
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val newZone = getZoneByPosition(x, y, previousZone)
                    if (newZone != previousZone) {
                        val previousButton = zoneToButton(previousZone)
                        val newButton = zoneToButton(newZone)
                        activePointers[pointerId] = newZone
                        if (previousButton != null && previousButton == newButton) {
                            // Same side, other quadrant: keep the finger on the new zone and re-hit
                            // the button, but not faster than the game can register it.
                            val now = SystemClock.uptimeMillis()
                            if (now - (lastRetriggerTime[pointerId] ?: 0L) >=
                                RETRIGGER_MIN_INTERVAL_MS
                            ) {
                                retriggerSameButton(pointerId, previousButton)
                            }
                        } else {
                            cancelRetrigger(pointerId)
                            if (newZone == NONE) {
                                activePointers.remove(pointerId)
                            }
                            if (previousButton != null) {
                                onButtonStateChange(previousButton, false)
                            }
                            if (newButton != null) {
                                onButtonStateChange(newButton, true)
                            }
                        }
                    }
                    handled = true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerId = event.getPointerId(event.actionIndex)
                cancelRetrigger(pointerId)
                val zone = activePointers.remove(pointerId)
                if (zone != null) {
                    updateZone(zone, false)
                    handled = true
                }
            }
        }
        return handled
    }

    override fun resetInput() {
        handler.removeCallbacksAndMessages(null)
        retriggerSeq.clear()
        lastRetriggerTime.clear()
        for ((_, zone) in activePointers) {
            updateZone(zone, false)
        }
        activePointers.clear()
    }

    override fun isInside(x: Float, y: Float): Boolean {
        val dx = (x - centerX) / halfWidth
        val dy = (y - centerY) / halfHeight
        return dx * dx + dy * dy <= 1.0f
    }

    override fun drawInput(canvas: Canvas) {
        paint.alpha = alpha

        val isPressed = activePointers.isNotEmpty()
        val isDonPressed = activePointers.values.any { isDonZone(it) }
        if (isPressed) {
            // Drum press effect
            val pressOffsetY = rect.height() * 0.01f
            val pressedRect = Rect(
                rect.left,
                (rect.top + pressOffsetY).toInt(),
                rect.right,
                (rect.bottom + pressOffsetY).toInt()
            )
            paint.alpha = minOf((alpha * 1.15f).toInt(), 255)
            canvas.drawBitmap(drumBitmap, null, pressedRect, paint)
        } else {
            canvas.drawBitmap(drumBitmap, null, rect, paint)
        }
        if (isDonPressed) {
			val offsetY = -halfHeight * 0.00f
			val glowCenterX = centerX
			val glowCenterY = centerY + offsetY

			val glowWidth = halfWidth * 0.46f * 1.1f
			val glowHeight = halfHeight * 0.60f * 1.1f
			val glowLeft = glowCenterX - glowWidth * 0.5f
			val glowTop = glowCenterY - glowHeight * 0.5f

			val glowPaint = Paint().apply {
				isAntiAlias = true
				shader = RadialGradient(
					glowCenterX, glowCenterY,
					glowWidth * 0.5f,
					intArrayOf(
						0xFFFBAD96.toInt(),
						0x00FBAD96,
					),
					floatArrayOf(0f, 1f),
					Shader.TileMode.CLAMP
				)
				alpha = minOf((this@TaikoDrum.alpha * 1.45f).toInt(), 255)
			}

			canvas.drawOval(glowLeft, glowTop, glowLeft + glowWidth, glowTop + glowHeight, glowPaint)
        }
    }

    companion object {
        private const val NONE = 0

        // Drum face quadrants. Top and bottom of the same side share one button, but they are
        // separate zones so a vertical crossing is a zone change and fires a new Don hit.
        private const val DON_LEFT_TOP = 1 shl 0
        private const val DON_RIGHT_TOP = 1 shl 1
        private const val DON_LEFT_BOTTOM = 1 shl 2
        private const val DON_RIGHT_BOTTOM = 1 shl 3

        // Ka rim, still only split left / right.
        private const val KA_LEFT = 1 shl 4
        private const val KA_RIGHT = 1 shl 5

        // Normalised (dy) half width of the dead band around the drum face's horizontal centre
        // line, used to keep touch jitter from retriggering the same side.
        private const val DON_SPLIT_DEAD_BAND = 0.05f

        // How long the button stays released before a same-side re-hit is pressed again. The game
        // samples the drum once per frame (~16.7 ms), so this has to be longer than one frame or
        // the gap is never seen and no new hit is counted.
        private const val RETRIGGER_RELEASE_GAP_MS = 30L

        // Upper bound for slide rolls, in hits per second terms. Also keeps a finger resting on the
        // horizontal centre line from machine-gunning.
        private const val RETRIGGER_MIN_INTERVAL_MS = 45L
    }
}