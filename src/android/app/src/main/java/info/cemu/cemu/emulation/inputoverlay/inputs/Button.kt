package info.cemu.cemu.emulation.inputoverlay.inputs

import android.graphics.Rect
import android.view.MotionEvent

abstract class Button(
    private val onButtonStateChange: (state: Boolean) -> Unit,
    boundingRect: Rect,
) : Input(boundingRect) {
    protected var state = false
    private var currentPointerId: Int = -1

    val isPressed: Boolean
        get() = state

    // Used by InputOverlaySurfaceView for buttons that are driven by its slide handling instead of
    // onTouch(): pressing a button the finger slid onto, and releasing the previous one.
    fun press(pointerId: Int) {
        if (state) {
            return
        }
        currentPointerId = pointerId
        updateState(true)
    }

    fun release() {
        if (!state) {
            return
        }
        currentPointerId = -1
        updateState(false)
    }

    override fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (isInside(x, y)) {
                    currentPointerId = event.getPointerId(pointerIndex)
                    updateState(true)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == currentPointerId) {
                    currentPointerId = -1
                    updateState(false)
                    return true
                }
            }
        }
        return false
    }

    override fun resetInput() {
        updateState(false)
    }

    private fun updateState(state: Boolean) {
        this.state = state
        onButtonStateChange(state)
    }
}
