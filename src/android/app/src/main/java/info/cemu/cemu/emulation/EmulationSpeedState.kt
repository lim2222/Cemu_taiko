package info.cemu.cemu.emulation

import info.cemu.cemu.common.settings.DEFAULT_FAST_FORWARD_SPEED
import info.cemu.cemu.common.settings.DEFAULT_SLOW_MOTION_DIVISOR
import info.cemu.cemu.nativeinterface.NativeEmulation

// Fast forward / slow motion on/off plus the multipliers used while they are active.
//
// The on/off states are deliberately not persisted: a game always starts at normal speed, and
// leaving the emulation screen switches both back off (the app process is shared with the game
// list). Only the multipliers are remembered, in EmulationSettings.
//
// The multipliers themselves are applied by the native side: they scale the emulated vsync pacing
// and the guest clock, and the audio is resampled by the same factor so sound stays in sync.
//
// The two features are mutually exclusive. Turning one on turns the other off, and the native side
// also lets fast forward win if both were to be requested at once.
object EmulationSpeedState {
    var isFastForwardEnabled: Boolean = false
        private set
    var fastForwardSpeed: Int = DEFAULT_FAST_FORWARD_SPEED
        private set
    var isSlowMotionEnabled: Boolean = false
        private set
    var slowMotionDivisor: Int = DEFAULT_SLOW_MOTION_DIVISOR
        private set

    fun toggleFastForward() = setFastForwardEnabled(!isFastForwardEnabled)

    fun setFastForwardEnabled(enabled: Boolean) {
        if (isFastForwardEnabled == enabled) {
            return
        }
        isFastForwardEnabled = enabled
        if (enabled) {
            isSlowMotionEnabled = false
        }
        apply()
    }

    fun toggleSlowMotion() = setSlowMotionEnabled(!isSlowMotionEnabled)

    fun setSlowMotionEnabled(enabled: Boolean) {
        if (isSlowMotionEnabled == enabled) {
            return
        }
        isSlowMotionEnabled = enabled
        if (enabled) {
            isFastForwardEnabled = false
        }
        apply()
    }

    fun setFastForwardSpeed(speed: Int) {
        if (fastForwardSpeed == speed) {
            return
        }
        fastForwardSpeed = speed
        apply()
    }

    fun setSlowMotionDivisor(divisor: Int) {
        if (slowMotionDivisor == divisor) {
            return
        }
        slowMotionDivisor = divisor
        apply()
    }

    // Called when a new title starts: normal speed with the stored multipliers.
    fun reset(fastForwardSpeed: Int, slowMotionDivisor: Int) {
        isFastForwardEnabled = false
        isSlowMotionEnabled = false
        this.fastForwardSpeed = fastForwardSpeed
        this.slowMotionDivisor = slowMotionDivisor
        apply()
    }

    // Leaving the emulation screen must never leave the shared process at another speed.
    fun disableAll() {
        isFastForwardEnabled = false
        isSlowMotionEnabled = false
        apply()
    }

    private fun apply() = NativeEmulation.setEmulationSpeed(
        isFastForwardEnabled,
        fastForwardSpeed,
        isSlowMotionEnabled,
        slowMotionDivisor,
    )
}
