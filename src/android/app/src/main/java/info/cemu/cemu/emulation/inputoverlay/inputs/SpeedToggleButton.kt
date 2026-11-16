package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent

// On-screen toggle that switches an emulation speed feature on or off. Unlike the controller
// buttons this is not an emulated input, so it is a plain Input: a tap toggles the state
// immediately and the icon shows it (the two supplied drawables). Being a plain Input also keeps
// it out of the button slide handling, where a toggle would be triggered by accident.
class SpeedToggleButton(
    private val alpha: Int,
    rect: Rect,
    private val disabledBitmap: Bitmap,
    private val enabledBitmap: Bitmap,
    private val isEnabled: () -> Boolean,
    private val onToggle: () -> Unit,
) : Input(rect) {
    init {
        configure()
    }

    override fun configure() {
        paint.alpha = alpha
        paint.isFilterBitmap = true
    }

    override fun resetInput() {}

    override fun drawInput(canvas: Canvas) {
        canvas.drawBitmap(
            if (isEnabled()) enabledBitmap else disabledBitmap,
            null,
            rect,
            paint,
        )
    }

    override fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInside(event.x, event.y)) {
                    return false
                }
                onToggle()
                return true
            }

            // The toggle already happened on the way down, but the up event still belongs to this
            // input so it is not treated as a fresh press somewhere else.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun isInside(x: Float, y: Float): Boolean = x in rect.left.toFloat()..<rect.right.toFloat() &&
        y in rect.top.toFloat()..<rect.bottom.toFloat()
}
