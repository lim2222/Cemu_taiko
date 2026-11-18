package info.cemu.cemu.common.settings

import kotlinx.serialization.Serializable

// Position, size and opacity of one emulation screen. The values are tenths of a percent, so
// 0..1000 means 0.0%..100.0% and the layout does not depend on the resolution of the device.
// "x"/"y" is the top left corner; "opacity" is independent of the screen area.
@Serializable
data class ScreenRect(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = SCREEN_RECT_MAX,
    val height: Int = SCREEN_RECT_MAX,
    // Opacity in permille: SCREEN_RECT_MAX is fully opaque, 0 is fully transparent. Stored with
    // the rect so each screen keeps its own value in every game and orientation.
    val opacity: Int = SCREEN_RECT_MAX,
) {
    // Keeps a screen big enough to still be usable and inside of the emulation area.
    fun coerced(): ScreenRect = ScreenRect(
        x = x.coerceIn(0, SCREEN_RECT_MAX),
        y = y.coerceIn(0, SCREEN_RECT_MAX),
        width = width.coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX),
        height = height.coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX),
        opacity = opacity.coerceIn(0, SCREEN_RECT_MAX),
    )
}

// 100% of the emulation area.
const val SCREEN_RECT_MAX: Int = 1000

// 5% of the emulation area. Smaller screens cannot be resized back comfortably.
const val SCREEN_RECT_MIN_SIZE: Int = 50

// Numeric layout of the two emulation screens, one value per screen and orientation. A non-null
// value means the user placed the screens by hand, so the automatic layout (TV / GamePad layout,
// gap and portrait offset) is ignored.
@Serializable
data class CustomScreensLayout(
    val tv: ScreenRect = ScreenRect(y = 0, height = SCREEN_RECT_MAX / 2),
    val pad: ScreenRect = ScreenRect(y = SCREEN_RECT_MAX / 2, height = SCREEN_RECT_MAX / 2),
)
