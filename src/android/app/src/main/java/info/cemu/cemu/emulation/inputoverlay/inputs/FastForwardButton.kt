package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import info.cemu.cemu.emulation.FastForwardState

// On-screen fast forward toggle. Unlike the controller buttons this is not an emulated input, so
// it is a plain Input: a tap switches fast forward on or off immediately and the icon shows the
// current state (the two drawables the user supplied). Being a plain Input also keeps it out of
// the button slide handling, where a toggle would be triggered by accident.
class FastForwardButton(
    private val alpha: Int,
    rect: Rect,
    private val disabledBitmap: Bitmap,
    private val enabledBitmap: Bitmap,
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
            if (FastForwardState.isEnabled) enabledBitmap else disabledBitmap,
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
                FastForwardState.toggle()
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
