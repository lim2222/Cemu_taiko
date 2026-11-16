package info.cemu.cemu.emulation

import info.cemu.cemu.common.settings.DEFAULT_FAST_FORWARD_SPEED
import info.cemu.cemu.nativeinterface.NativeEmulation

// Fast forward on/off plus the multiplier used while it is on.
//
// The on/off state is deliberately not persisted: a game always starts at normal speed, and
// leaving the emulation screen switches it back off (the app process is shared with the game
// list). Only the multiplier is remembered, in EmulationSettings.fastForwardSpeed.
//
// The multiplier itself is applied by the native side: it scales the emulated vsync pacing and
// the guest clock, and the audio is resampled by the same factor so sound stays in sync.
object FastForwardState {
    var isEnabled: Boolean = false
        private set
    var speed: Int = DEFAULT_FAST_FORWARD_SPEED
        private set

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) {
            return
        }
        isEnabled = enabled
        apply()
    }

    fun toggle() = setEnabled(!isEnabled)

    fun setSpeed(speed: Int) {
        if (this.speed == speed) {
            return
        }
        this.speed = speed
        apply()
    }

    // Called when a new title starts: starts at normal speed with the stored multiplier.
    fun reset(speed: Int) {
        isEnabled = false
        this.speed = speed
        apply()
    }

    private fun apply() = NativeEmulation.setFastForward(isEnabled, speed)
}
