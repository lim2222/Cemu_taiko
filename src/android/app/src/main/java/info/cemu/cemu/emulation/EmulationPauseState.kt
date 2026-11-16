package info.cemu.cemu.emulation

import info.cemu.cemu.nativeinterface.NativeEmulation

// Emulation pause state. The native side suspends/resumes the guest threads (CafeSystem::PauseTitle
// / ResumeTitle), so the whole emulated console stops and continues exactly where it was.
//
// Losing the main surface (putting the app in the background) pauses the title natively as well,
// but that is not a user pause: it must not be remembered as one, otherwise coming back would
// silently unpause a game the user paused on purpose. Only the on-screen pause button changes
// userPaused; the surface callbacks use resumeAfterSurfaceRecreated().
object EmulationPauseState {
    private var userPaused = false

    val isPaused: Boolean
        get() = userPaused

    // Returns the new state so the caller can report it to the user.
    fun toggle(): Boolean {
        userPaused = !userPaused
        if (userPaused) {
            NativeEmulation.pauseTitle()
        } else {
            NativeEmulation.resumeTitle()
        }
        return userPaused
    }

    // The title was paused natively when its surface was destroyed. Resuming is only wanted when
    // the pause came from the surface loss and not from the pause button.
    fun resumeAfterSurfaceRecreated() {
        if (!userPaused) {
            NativeEmulation.resumeTitle()
        }
    }

    fun reset() {
        userPaused = false
    }
}
