package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent

// On-screen button that performs an action when tapped (pause, open the side menu, ...). Like the
// speed toggles it is not an emulated input, so it is a plain Input: a tap fires immediately and
// being a plain Input keeps it out of the button slide handling.
class ActionButton(
    private val alpha: Int,
    rect: Rect,
    private val bitmap: Bitmap,
    private val onTap: () -> Unit,
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
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    override fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInside(event.x, event.y)) {
                    return false
                }
                onTap()
                return true
            }

            // The action already happened on the way down, but the up event still belongs to this
            // input so it is not treated as a fresh press somewhere else.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun isInside(x: Float, y: Float): Boolean = x in rect.left.toFloat()..<rect.right.toFloat() &&
        y in rect.top.toFloat()..<rect.bottom.toFloat()
}
