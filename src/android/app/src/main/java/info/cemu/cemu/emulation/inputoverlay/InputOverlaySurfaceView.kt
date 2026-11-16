package info.cemu.cemu.emulation.inputoverlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import info.cemu.cemu.R
import info.cemu.cemu.common.android.context.getDeviceVibrator
import info.cemu.cemu.common.settings.InputOverlayRect
import info.cemu.cemu.common.settings.InputOverlaySettings
import info.cemu.cemu.common.settings.InputOverlaySlideMode
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.common.settings.getDefaultRectangle
import info.cemu.cemu.emulation.inputoverlay.inputs.Button
import info.cemu.cemu.emulation.inputoverlay.inputs.DPad
import info.cemu.cemu.emulation.inputoverlay.inputs.FastForwardButton
import info.cemu.cemu.emulation.inputoverlay.inputs.Input
import info.cemu.cemu.emulation.inputoverlay.inputs.Joystick
import info.cemu.cemu.emulation.inputoverlay.inputs.RectangleButton
import info.cemu.cemu.emulation.inputoverlay.inputs.RoundButton
import info.cemu.cemu.emulation.inputoverlay.inputs.TaikoDrum
import info.cemu.cemu.emulation.inputoverlay.inputs.innerdrawing.BlowButtonInnerDrawing
import info.cemu.cemu.emulation.inputoverlay.inputs.innerdrawing.ButtonInnerDrawing
import info.cemu.cemu.emulation.inputoverlay.inputs.innerdrawing.HomeButtonInnerDrawing
import info.cemu.cemu.emulation.inputoverlay.inputs.innerdrawing.TextButtonInnerDrawing
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.getControllerType
import info.cemu.cemu.nativeinterface.NativeInput.isControllerDisabled
import info.cemu.cemu.nativeinterface.NativeInput.onOverlayAxis
import info.cemu.cemu.nativeinterface.NativeInput.onOverlayButton
import kotlin.math.roundToInt

class InputOverlaySurfaceView(context: Context) : SurfaceView(context), OnTouchListener {
    enum class InputMode {
        DEFAULT, EDIT_POSITION, EDIT_SIZE,
    }

    var onEditFinishedListener: ((Map<OverlayInputConfig, InputOverlayRect>) -> Unit)? =
        null

    private var inputMode = InputMode.DEFAULT
    private var pixelDensity = 1
    private var currentConfiguredInput: Input? = null
    // Offset between the finger and the input center when the drag started, so the input does not
    // jump to the finger position as soon as it is touched.
    private var dragPointerId = MotionEvent.INVALID_POINTER_ID
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var nativeControllerType = NativeInput.EmulatedControllerType.DISABLED
    // the controller type the inputs were last built for, used to rebuild them when it changes
    private var lastAppliedControllerType = Int.MIN_VALUE
    private var onJoystickChange: (OverlayInput, Float, Float, Float, Float) -> Unit =
        { _, _, _, _, _ -> }
    private var overlyButtonToNativeButton: (OverlayInput) -> Int = { _ -> -1 }
    private var inputs: MutableList<Pair<OverlayInput, Input>> = mutableListOf()

    // The fast forward toggle uses the two supplied images for the off/on state.
    private val fastForwardDisabledBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.button_fast_forward_disabled)
    }
    private val fastForwardEnabledBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.button_fast_forward_enabled)
    }

    // Buttons currently held down by each pointer, together with the buttons it has slid over.
    // Sliding from one button to another without lifting is handled here because Button.onTouch
    // only sees the button the finger landed on. The mode decides whether the buttons the finger
    // passed stay pressed (HOLD) or are released (SWITCH).
    private val buttonsByPointer = mutableMapOf<Int, MutableSet<Button>>()
    // Pointers that started on a button and may therefore trigger further buttons while sliding.
    private val slidingPointers = mutableSetOf<Int>()
    // The button each sliding pointer landed on. In HOLD mode it stays pressed for the whole
    // gesture, while buttons the finger slides onto are pressed/released as it enters/leaves them.
    private val originButtons = mutableMapOf<Int, Button>()
    private val vibrator: Vibrator = context.getDeviceVibrator()
    private val inputsMinSize: Int
    private val isVisible: Boolean
        get() = visibility == VISIBLE

    private var settings: InputOverlaySettings = InputOverlaySettings()
    private val controllerIndex get() = settings.controllerIndex
    private val currentAlpha get() = settings.alpha
    private val slideMode get() = settings.slideMode
    private val isVibrateOnTouchEnabled: Boolean
        get() = settings.isVibrateOnTouchEnabled && vibrator.hasVibrator()
	private val vibrationAmplitude: Int
		get() = settings.vibrationAmplitude
    // Timestamp of the last haptic pulse, used to space out the pulses so a second finger does
    // not cancel a rumble that is already running.
    private var lastVibrateTime = 0L

    // Taiko "big note": two fingers landing almost together on opposite sides of the same part of
    // the drum (head with head, rim with rim) get a heavy thud with a fading aftershock instead of
    // the normal short pulse. Ported from the Vita3K/Azahar tatacon haptics.
    private var lastTaikoHitButton: OverlayTaiko? = null
    private var lastTaikoHitTime = 0L
    private var previousTaikoHitTime = 0L
    private var lastTaikoPairWasBig = false
    // Probed once, like Eden's lazy thudSupported.
    private var thudPrimitiveSupported: Boolean? = null
		
    init {
        visibility = GONE

        pixelDensity = context.resources.displayMetrics.densityDpi

        inputsMinSize =
            (INPUTS_MIN_SIZE_DP * pixelDensity.toFloat() / DisplayMetrics.DENSITY_DEFAULT).roundToInt()

        setOnTouchListener(this)
    }

    fun applySettings(inputOverlaySettings: InputOverlaySettings, controllerType: Int) {
        if (this.settings == inputOverlaySettings && lastAppliedControllerType == controllerType) {
            return
        }

        this.settings = inputOverlaySettings
        lastAppliedControllerType = controllerType

        setInputs()

        invalidate()
    }

    fun setVisible(isVisible: Boolean) {
        if (this.isVisible == isVisible) {
            return
        }

        // Hiding the overlay can swallow the finger-up event, so do not leave a button stuck down.
        if (!isVisible) {
            releaseAllButtons()
        }

        visibility = if (isVisible) VISIBLE else GONE

        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Default positions are derived from the view size, so they have to be recomputed whenever
        // the view is (re)sized, for example when the device rotates between portrait and
        // landscape. Custom positions/sizes are read from the settings by setInputs() and are
        // left untouched.
        if (w > 0 && h > 0 && (oldw == 0 || oldh == 0 || w != oldw || h != oldh)) {
            setInputs()
            invalidate()
        }
    }

    fun setInputMode(inputMode: InputMode) {
        if (this.inputMode == inputMode) {
            return
        }

        this.inputMode = inputMode

        if (this.inputMode != InputMode.DEFAULT) {
            // Editing the layout must not leave a gameplay button held down.
            releaseAllButtons()
            return
        }

        val inputsRectangles =
            inputs.associate { it.first.toConfig() to it.second.getBoundingRectangle() }

        onEditFinishedListener?.invoke(inputsRectangles)
    }

    private fun overlayButtonToVPADButton(button: OverlayInput): Int {
        return when (button) {
            OverlayButton.A -> NativeInput.VPADButton.A
            OverlayButton.B -> NativeInput.VPADButton.B
            OverlayButton.X -> NativeInput.VPADButton.X
            OverlayButton.Y -> NativeInput.VPADButton.Y
            OverlayButton.PLUS -> NativeInput.VPADButton.PLUS
            OverlayButton.MINUS -> NativeInput.VPADButton.MINUS
            OverlayDpad.DPAD_UP -> NativeInput.VPADButton.UP
            OverlayDpad.DPAD_DOWN -> NativeInput.VPADButton.DOWN
            OverlayDpad.DPAD_LEFT -> NativeInput.VPADButton.LEFT
            OverlayDpad.DPAD_RIGHT -> NativeInput.VPADButton.RIGHT
            OverlayButton.L_STICK_CLICK -> NativeInput.VPADButton.STICKL
            OverlayButton.R_STICK_CLICK -> NativeInput.VPADButton.STICKR
            OverlayButton.L -> NativeInput.VPADButton.L
            OverlayButton.R -> NativeInput.VPADButton.R
            OverlayButton.ZR -> NativeInput.VPADButton.ZR
            OverlayButton.ZL -> NativeInput.VPADButton.ZL
            OverlayButton.BLOW_MIC -> NativeInput.VPADButton.MIC
            else -> -1
        }
    }

    private fun overlayButtonToClassicButton(button: OverlayInput): Int {
        return when (button) {
            OverlayButton.A -> NativeInput.ClassicButton.A
            OverlayButton.B -> NativeInput.ClassicButton.B
            OverlayButton.X -> NativeInput.ClassicButton.X
            OverlayButton.Y -> NativeInput.ClassicButton.Y
            OverlayButton.PLUS -> NativeInput.ClassicButton.PLUS
            OverlayButton.MINUS -> NativeInput.ClassicButton.MINUS
            OverlayDpad.DPAD_UP -> NativeInput.ClassicButton.UP
            OverlayDpad.DPAD_DOWN -> NativeInput.ClassicButton.DOWN
            OverlayDpad.DPAD_LEFT -> NativeInput.ClassicButton.LEFT
            OverlayDpad.DPAD_RIGHT -> NativeInput.ClassicButton.RIGHT
            OverlayButton.L -> NativeInput.ClassicButton.L
            OverlayButton.R -> NativeInput.ClassicButton.R
            OverlayButton.ZR -> NativeInput.ClassicButton.ZR
            OverlayButton.ZL -> NativeInput.ClassicButton.ZL
            else -> -1
        }
    }

    private fun overlayButtonToProButton(button: OverlayInput): Int {
        return when (button) {
            OverlayButton.A -> NativeInput.ProButton.A
            OverlayButton.B -> NativeInput.ProButton.B
            OverlayButton.X -> NativeInput.ProButton.X
            OverlayButton.Y -> NativeInput.ProButton.Y
            OverlayButton.PLUS -> NativeInput.ProButton.PLUS
            OverlayButton.MINUS -> NativeInput.ProButton.MINUS
            OverlayDpad.DPAD_UP -> NativeInput.ProButton.UP
            OverlayDpad.DPAD_DOWN -> NativeInput.ProButton.DOWN
            OverlayDpad.DPAD_LEFT -> NativeInput.ProButton.LEFT
            OverlayDpad.DPAD_RIGHT -> NativeInput.ProButton.RIGHT
            OverlayButton.L_STICK_CLICK -> NativeInput.ProButton.STICKL
            OverlayButton.R_STICK_CLICK -> NativeInput.ProButton.STICKR
            OverlayButton.L -> NativeInput.ProButton.L
            OverlayButton.R -> NativeInput.ProButton.R
            OverlayButton.ZR -> NativeInput.ProButton.ZR
            OverlayButton.ZL -> NativeInput.ProButton.ZL
            else -> -1
        }
    }

    private fun overlayButtonToWiimoteButton(button: OverlayInput): Int {
        return when (button) {
            OverlayButton.A -> NativeInput.WiimoteButton.A
            OverlayButton.B -> NativeInput.WiimoteButton.B
            OverlayButton.ONE -> NativeInput.WiimoteButton.ONE
            OverlayButton.TWO -> NativeInput.WiimoteButton.TWO
            OverlayButton.PLUS -> NativeInput.WiimoteButton.PLUS
            OverlayButton.MINUS -> NativeInput.WiimoteButton.MINUS
            OverlayButton.HOME -> NativeInput.WiimoteButton.HOME
            OverlayDpad.DPAD_UP -> NativeInput.WiimoteButton.UP
            OverlayDpad.DPAD_DOWN -> NativeInput.WiimoteButton.DOWN
            OverlayDpad.DPAD_LEFT -> NativeInput.WiimoteButton.LEFT
            OverlayDpad.DPAD_RIGHT -> NativeInput.WiimoteButton.RIGHT
            OverlayButton.C -> NativeInput.WiimoteButton.NUNCHUCK_C
            OverlayButton.Z -> NativeInput.WiimoteButton.NUNCHUCK_Z
            else -> -1
        }
    }
	
	private fun overlayButtonToTaikoButton(button: OverlayInput): Int {
		return when (button) {
			OverlayTaiko.DON_LEFT -> NativeInput.TaikoButton.DON_LEFT
			OverlayTaiko.DON_RIGHT -> NativeInput.TaikoButton.DON_RIGHT
			OverlayTaiko.KA_LEFT -> NativeInput.TaikoButton.KA_LEFT
			OverlayTaiko.KA_RIGHT -> NativeInput.TaikoButton.KA_RIGHT
			OverlayButton.ONE -> NativeInput.TaikoButton.ONE
			OverlayButton.TWO -> NativeInput.TaikoButton.TWO
			OverlayButton.PLUS -> NativeInput.TaikoButton.PLUS
			OverlayButton.MINUS -> NativeInput.TaikoButton.MINUS
			else -> -1
		}
	}

    private fun onButtonStateChange(button: OverlayInput, state: Boolean) {
        val nativeButtonId = overlyButtonToNativeButton(button)

        if (nativeButtonId == -1) {
            return
        }

        if (state) {
            if (button is OverlayTaiko) {
                triggerTaikoHapticFeedback(button)
            } else {
                triggerHapticFeedback()
            }
        }

        onOverlayButton(controllerIndex, nativeButtonId, state)
    }

    // Ported from the classic Android overlay haptics: instead of one fixed pulse per touch, a
    // short tap stays crisp while rapid hits (drum rolls, mashing) become a softer, slightly
    // longer pulse so the motor keeps spinning instead of stopping and restarting with a clack.
    // A debounce window keeps a second finger from cancelling a pulse that is already running.
    private fun triggerHapticFeedback() {
        if (!isVibrateOnTouchEnabled) {
            return
        }

        val strength = vibrationAmplitude
        if (strength <= 0) {
            return
        }

        val now = SystemClock.uptimeMillis()
        val delta = now - lastVibrateTime

        if (delta < HAPTIC_DEBOUNCE_MS) {
            return
        }
        lastVibrateTime = now

        val norm = strength / 255f

        val duration: Long
        val amplitude: Int
        if (delta < HAPTIC_RAPID_WINDOW_MS) {
            // Rapid mode: soft, slightly longer pulse for a smooth roll.
            duration = 50L + (norm * 15f).roundToInt()
            amplitude = (strength * 0.33f).roundToInt().coerceAtLeast(1)
        } else {
            // Single tap: short, full-strength impact. The duration is the part that makes a tap
            // feel solid on devices that ignore the amplitude, so it is a bit longer than before.
            duration = 60L + (norm * 50f).roundToInt()
            amplitude = strength
        }

        vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255)))
    }

    private fun isTaikoHeadButton(button: OverlayTaiko) =
        button == OverlayTaiko.DON_LEFT || button == OverlayTaiko.DON_RIGHT

    private fun isTaikoLeftSideButton(button: OverlayTaiko) =
        button == OverlayTaiko.DON_LEFT || button == OverlayTaiko.KA_LEFT

    // Taiko hits keep their own timing so a two-finger big note can replace the first hit's pulse
    // without disturbing the shared button/dpad/joystick debounce.
    //
    // The first hit always vibrates immediately, so single notes stay responsive. When a second
    // finger lands on the opposite side of the same part (head with head, rim with rim) within
    // TATACON_BIG_HIT_PAIR_WINDOW_MS, and the pair is isolated from previous hits, it gets a heavy
    // thud (see vibrateTaikoBigHit) that replaces the first one. Both start at the configured
    // amplitude, so the replacement is seamless.
    private fun triggerTaikoHapticFeedback(button: OverlayTaiko) {
        val now = SystemClock.uptimeMillis()
        val previousButton = lastTaikoHitButton

        val isBigPair = previousButton != null &&
            now - lastTaikoHitTime in 0..TATACON_BIG_HIT_PAIR_WINDOW_MS &&
            isTaikoHeadButton(previousButton) == isTaikoHeadButton(button) &&
            isTaikoLeftSideButton(previousButton) != isTaikoLeftSideButton(button) &&
            !lastTaikoPairWasBig &&
            lastTaikoHitTime - previousTaikoHitTime >= TATACON_BIG_HIT_QUIET_BEFORE_MS

        previousTaikoHitTime = lastTaikoHitTime
        lastTaikoHitTime = now
        lastTaikoHitButton = button
        lastTaikoPairWasBig = isBigPair

        if (!isVibrateOnTouchEnabled) {
            return
        }

        val strength = vibrationAmplitude
        if (strength <= 0) {
            return
        }

        if (isBigPair) {
            // Bypasses the debounce on purpose; replaces the first hit's pulse.
            lastVibrateTime = now
            vibrateTaikoBigHit(strength)
            return
        }

        // Single hit: unchanged overlay haptics (crisp tap / soft rolling pulse).
        triggerHapticFeedback()
    }

    // Vibrates the taiko "big note" thud, ported from Eden's HID rumble pipeline
    // (ThudEffect + RumbleShaper). A big note arrives at full amplitude and the game keeps sending
    // rumble for a few hundred milliseconds, which is rendered as one hard thud refreshed for as
    // long as it lasts - the heavy attack, the audible motor "hum" and the fading aftershock:
    //
    // - PRIMITIVE_THUD composition primitives when the motor supports them: a chain of decaying
    //   thuds with small gaps, mirroring the shaper's refreshes.
    // - otherwise a waveform with the same envelope: full-strength attack, sustained mid section at
    //   60%, fading tail at 30%.
    private fun vibrateTaikoBigHit(strength: Int) {
        val amplitude = strength.coerceIn(1, 255)
        val scale = amplitude / 255f

        val supportsThud = thudPrimitiveSupported ?: run {
            val supported = vibrator.arePrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_THUD
            )
            (supported.firstOrNull() == true).also { thudPrimitiveSupported = it }
        }

        if (supportsThud) {
            vibrator.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_THUD,
                        scale * 0.6f,
                        30
                    )
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_THUD,
                        scale * 0.3f,
                        50
                    )
                    .compose()
            )
            return
        }

        // No thud primitive: the same envelope as a plain waveform.
        val amplitudes = IntArray(TATACON_BIG_HIT_THUD_SCALES.size) { index ->
            (amplitude * TATACON_BIG_HIT_THUD_SCALES[index]).roundToInt().coerceAtLeast(1)
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                TATACON_BIG_HIT_THUD_TIMINGS_MS,
                amplitudes,
                -1
            )
        )
    }

    private fun onOverlayAxis(axis: Int, value: Float) {
        onOverlayAxis(controllerIndex, axis, value)
    }

    private fun onVPADJoystickStateChange(
        joystick: OverlayInput,
        up: Float,
        down: Float,
        left: Float,
        right: Float,
    ) {
        if (joystick == OverlayJoystick.LEFT) {
            onOverlayAxis(NativeInput.VPADButton.STICKL_UP, up)
            onOverlayAxis(NativeInput.VPADButton.STICKL_DOWN, down)
            onOverlayAxis(NativeInput.VPADButton.STICKL_LEFT, left)
            onOverlayAxis(NativeInput.VPADButton.STICKL_RIGHT, right)
        } else if (joystick == OverlayJoystick.RIGHT) {
            onOverlayAxis(NativeInput.VPADButton.STICKR_UP, up)
            onOverlayAxis(NativeInput.VPADButton.STICKR_DOWN, down)
            onOverlayAxis(NativeInput.VPADButton.STICKR_LEFT, left)
            onOverlayAxis(NativeInput.VPADButton.STICKR_RIGHT, right)
        }
    }

    private fun onProJoystickStateChange(
        joystick: OverlayInput,
        up: Float,
        down: Float,
        left: Float,
        right: Float,
    ) {
        if (joystick == OverlayJoystick.LEFT) {
            onOverlayAxis(NativeInput.ProButton.STICKL_UP, up)
            onOverlayAxis(NativeInput.ProButton.STICKL_DOWN, down)
            onOverlayAxis(NativeInput.ProButton.STICKL_LEFT, left)
            onOverlayAxis(NativeInput.ProButton.STICKL_RIGHT, right)
        } else if (joystick == OverlayJoystick.RIGHT) {
            onOverlayAxis(NativeInput.ProButton.STICKR_UP, up)
            onOverlayAxis(NativeInput.ProButton.STICKR_DOWN, down)
            onOverlayAxis(NativeInput.ProButton.STICKR_LEFT, left)
            onOverlayAxis(NativeInput.ProButton.STICKR_RIGHT, right)
        }
    }

    private fun onClassicJoystickStateChange(
        joystick: OverlayInput,
        up: Float,
        down: Float,
        left: Float,
        right: Float,
    ) {
        if (joystick == OverlayJoystick.LEFT) {
            onOverlayAxis(NativeInput.ClassicButton.STICKL_UP, up)
            onOverlayAxis(NativeInput.ClassicButton.STICKL_DOWN, down)
            onOverlayAxis(NativeInput.ClassicButton.STICKL_LEFT, left)
            onOverlayAxis(NativeInput.ClassicButton.STICKL_RIGHT, right)
        } else if (joystick == OverlayJoystick.RIGHT) {
            onOverlayAxis(NativeInput.ClassicButton.STICKR_UP, up)
            onOverlayAxis(NativeInput.ClassicButton.STICKR_DOWN, down)
            onOverlayAxis(NativeInput.ClassicButton.STICKR_LEFT, left)
            onOverlayAxis(NativeInput.ClassicButton.STICKR_RIGHT, right)
        }
    }

    private fun onWiimoteJoystickStateChange(
        joystick: OverlayInput,
        up: Float,
        down: Float,
        left: Float,
        right: Float,
    ) {
        if (joystick == OverlayJoystick.RIGHT) {
            onOverlayAxis(NativeInput.WiimoteButton.NUNCHUCK_UP, up)
            onOverlayAxis(NativeInput.WiimoteButton.NUNCHUCK_DOWN, down)
            onOverlayAxis(NativeInput.WiimoteButton.NUNCHUCK_LEFT, left)
            onOverlayAxis(NativeInput.WiimoteButton.NUNCHUCK_RIGHT, right)
        }
    }

    private fun onJoystickStateChange(joystick: OverlayInput, x: Float, y: Float) {
        val (up, down) = if (y < 0) Pair(-y, 0f) else Pair(0f, y)
        val (left, right) = if (x < 0) Pair(-x, 0f) else Pair(0f, x)
        onJoystickChange(joystick, up, down, left, right)
    }

    private fun getBoundingRectangleForInput(input: OverlayInput): Rect {
        val rect = settings.inputOverlayRectMap[input.toConfig()]

        if (rect != null) {
            return Rect(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom
            )
        }

        return getDefaultRectangle(input.toConfig(), width, height, pixelDensity)
    }

    private fun MutableList<Pair<OverlayInput, Input>>.addRoundButton(
        button: OverlayButton,
        innerDrawing: ButtonInnerDrawing,
    ) {
        add(
            button to RoundButton(
                innerDrawing,
                { onButtonStateChange(button, it) },
                currentAlpha,
                getBoundingRectangleForInput(button),
            )
        )
    }

    private fun MutableList<Pair<OverlayInput, Input>>.addRoundButton(
        button: OverlayButton,
        buttonText: String = button.name,
    ) = addRoundButton(button, TextButtonInnerDrawing(buttonText))

    private fun MutableList<Pair<OverlayInput, Input>>.addJoystick(joystick: OverlayJoystick) {
        add(
            joystick to Joystick(
                { x, y -> onJoystickStateChange(joystick, x, y) },
                currentAlpha,
                getBoundingRectangleForInput(joystick)
            )
        )
    }

    private fun MutableList<Pair<OverlayInput, Input>>.addDpad() {
        add(
            OverlayDpad.DPAD_UP to DPad(
                ::onButtonStateChange,
                currentAlpha,
                getBoundingRectangleForInput(OverlayDpad.DPAD_UP)
            )
        )
    }
	
	private fun MutableList<Pair<OverlayInput, Input>>.addFastForwardButton() {
		add(
			OverlayFastForward to FastForwardButton(
				currentAlpha,
				getBoundingRectangleForInput(OverlayFastForward),
				fastForwardDisabledBitmap,
				fastForwardEnabledBitmap,
			)
		)
	}

	private fun MutableList<Pair<OverlayInput, Input>>.addTaikoDrum() {
		val drumBitmap = BitmapFactory.decodeResource(resources, R.drawable.tatacon)
		add(
			OverlayTaiko.DON_LEFT to TaikoDrum(
				::onButtonStateChange,
				currentAlpha,
				getBoundingRectangleForInput(OverlayTaiko.DON_LEFT),
				drumBitmap,
			)
		)
	}
	
    private fun MutableList<Pair<OverlayInput, Input>>.addRectangleButton(
        button: OverlayButton,
        buttonText: String = button.name,
    ) {
        add(
            button to RectangleButton(
                TextButtonInnerDrawing(buttonText),
                { onButtonStateChange(button, it) },
                currentAlpha,
                getBoundingRectangleForInput(button)
            )
        )
    }

    private fun setInputs() {
        // The input objects are rebuilt below, so any button the old ones pressed has to be
        // released first or it would stay held forever.
        releaseAllButtons()

        if (isControllerDisabled(controllerIndex)) {
            inputs = mutableListOf()
            return
        }

        nativeControllerType = getControllerType(controllerIndex)
        overlyButtonToNativeButton = when (nativeControllerType) {
            NativeInput.EmulatedControllerType.VPAD -> ::overlayButtonToVPADButton
            NativeInput.EmulatedControllerType.CLASSIC -> ::overlayButtonToClassicButton
            NativeInput.EmulatedControllerType.PRO -> ::overlayButtonToProButton
            NativeInput.EmulatedControllerType.WIIMOTE -> ::overlayButtonToWiimoteButton
			NativeInput.EmulatedControllerType.TAIKO -> ::overlayButtonToTaikoButton
            else -> { _ -> -1 }
        }
        onJoystickChange = when (nativeControllerType) {
            NativeInput.EmulatedControllerType.VPAD -> ::onVPADJoystickStateChange
            NativeInput.EmulatedControllerType.PRO -> ::onProJoystickStateChange
            NativeInput.EmulatedControllerType.CLASSIC -> ::onClassicJoystickStateChange
            NativeInput.EmulatedControllerType.WIIMOTE -> ::onWiimoteJoystickStateChange
            else -> { _, _, _, _, _ -> }
        }

        inputs = mutableListOf<Pair<OverlayInput, Input>>().apply {
            // Keep the set of inputs per controller type in sync with
            // overlayInputsForControllerType() in OverlayInputs.kt.
            if (nativeControllerType == NativeInput.EmulatedControllerType.TAIKO) {
                addFastForwardButton()
                addRoundButton(OverlayButton.MINUS, "-")
                addRoundButton(OverlayButton.PLUS, "+")
                addRoundButton(OverlayButton.ONE, "1")
                addRoundButton(OverlayButton.TWO, "2")

                val excludeRects = listOf(
                    getBoundingRectangleForInput(OverlayFastForward),
                    getBoundingRectangleForInput(OverlayButton.MINUS),
                    getBoundingRectangleForInput(OverlayButton.PLUS),
                    getBoundingRectangleForInput(OverlayButton.ONE),
                    getBoundingRectangleForInput(OverlayButton.TWO),
                )

                val drumBitmap = BitmapFactory.decodeResource(resources, R.drawable.tatacon)
                add(
                    OverlayTaiko.DON_LEFT to TaikoDrum(
                        ::onButtonStateChange,
                        currentAlpha,
                        getBoundingRectangleForInput(OverlayTaiko.DON_LEFT),
                        drumBitmap,
                        excludeRects,
                    )
                )
            } else {
                addFastForwardButton()
                addRoundButton(OverlayButton.MINUS, "-")
                addRoundButton(OverlayButton.PLUS, "+")
                addDpad()
                addRoundButton(OverlayButton.A)
                addRoundButton(OverlayButton.B)
                addJoystick(OverlayJoystick.RIGHT)
                if (nativeControllerType != NativeInput.EmulatedControllerType.WIIMOTE) {
                    addRoundButton(OverlayButton.X)
                    addRoundButton(OverlayButton.Y)
                    addRectangleButton(OverlayButton.ZL)
                    addRectangleButton(OverlayButton.ZR)
                    addRectangleButton(OverlayButton.L)
                    addRectangleButton(OverlayButton.R)
                    addJoystick(OverlayJoystick.LEFT)
                }
                if (nativeControllerType == NativeInput.EmulatedControllerType.WIIMOTE) {
                    addRoundButton(OverlayButton.ONE, "1")
                    addRoundButton(OverlayButton.TWO, "2")
                    addRoundButton(OverlayButton.C)
                    addRectangleButton(OverlayButton.Z)
                    addRoundButton(OverlayButton.HOME, HomeButtonInnerDrawing())
                }
                if (nativeControllerType != NativeInput.EmulatedControllerType.CLASSIC && nativeControllerType != NativeInput.EmulatedControllerType.WIIMOTE) {
                    addRoundButton(OverlayButton.L_STICK_CLICK, "L3")
                    addRoundButton(OverlayButton.R_STICK_CLICK, "R3")
                }

                if (nativeControllerType == NativeInput.EmulatedControllerType.VPAD) {
                    addRoundButton(OverlayButton.BLOW_MIC, BlowButtonInnerDrawing())
                }
			}
			
            removeAll { (overlayInput, _) -> !isInputVisible(overlayInput) }
        }
    }

    private fun isInputVisible(overlayInput: OverlayInput) =
        settings.inputVisibilityMap[overlayInput.toConfig()] ?: true

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        setWillNotDraw(false)
        requestFocus()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        for ((_, input) in inputs) {
            input.draw(canvas)
        }
    }

    private fun onEditPosition(event: MotionEvent): Boolean {
        val configuredInput = currentConfiguredInput
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (configuredInput != null) {
                return false
            }
            val x = event.x
            val y = event.y
            for ((_, input) in inputs) {
                if (input.isInside(x, y)) {
                    currentConfiguredInput = input
                    // Remember where the finger grabbed the input relative to its center so it can
                    // be dragged without snapping to the finger position.
                    val rect = input.getBoundingRectangle()
                    dragPointerId = event.getPointerId(event.actionIndex)
                    dragOffsetX = x - (rect.left + rect.right) / 2f
                    dragOffsetY = y - (rect.top + rect.bottom) / 2f
                    return true
                }
            }
        }

        if (configuredInput == null) {
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            currentConfiguredInput = null
            dragPointerId = MotionEvent.INVALID_POINTER_ID
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val pointerIndex = event.findPointerIndex(dragPointerId)
            if (pointerIndex < 0) {
                return true
            }
            val x = event.getX(pointerIndex) - dragOffsetX
            val y = event.getY(pointerIndex) - dragOffsetY
            configuredInput.moveInput(x.roundToInt(), y.roundToInt(), width, height)
            return true
        }

        return false
    }

    private fun onEditSize(event: MotionEvent): Boolean {
        val configuredInput = currentConfiguredInput

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.x
            val y = event.y
            for ((_, input) in inputs) {
                if (input.isInside(x, y)) {
                    currentConfiguredInput = input
                    return true
                }
            }
        }

        if (configuredInput == null) {
            return false
        }

        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            currentConfiguredInput = null
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val histSize = event.historySize
            if (event.historySize >= 2) {
                val x1 = event.getHistoricalX(0)
                val y1 = event.getHistoricalY(0)
                val x2 = event.getHistoricalX(histSize - 1)
                val y2 = event.getHistoricalY(histSize - 1)
                configuredInput.resize(
                    diffX = (x2 - x1).toInt(),
                    diffY = (y2 - y1).toInt(),
                    maxWidth = width,
                    maxHeight = height,
                    minWidthHeight = inputsMinSize
                )
            }
            return true
        }
        return false
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        var touchEventProcessed = false
        when (inputMode) {
            InputMode.DEFAULT -> {
                for ((_, input) in inputs) {
                    // Buttons are driven by onDefaultTouch() so a finger can slide from one
                    // button to another. DPad, joysticks and the taiko drum keep their own
                    // multi-touch handling.
                    if (input is Button) {
                        continue
                    }
                    if (input.onTouch(event)) {
                        touchEventProcessed = true
                    }
                }
                if (onDefaultTouch(event)) {
                    touchEventProcessed = true
                }
            }

            InputMode.EDIT_POSITION -> touchEventProcessed = onEditPosition(event)
            InputMode.EDIT_SIZE -> touchEventProcessed = onEditSize(event)
        }

        if (touchEventProcessed) {
            invalidate()
        }

        return touchEventProcessed
    }

    private fun findButtonAt(x: Float, y: Float): Button? {
        // Reversed so the button drawn on top wins when two buttons overlap.
        for ((_, input) in inputs.asReversed()) {
            if (input is Button && input.isInside(x, y)) {
                return input
            }
        }
        return null
    }

    private fun isButtonHeldByOtherPointer(button: Button, pointerId: Int): Boolean =
        buttonsByPointer.any { (id, held) -> id != pointerId && button in held }

    private fun pressButton(pointerId: Int, button: Button) {
        val held = buttonsByPointer.getOrPut(pointerId) { mutableSetOf() }
        if (!held.add(button)) {
            return
        }
        // A button already held by another finger is shared instead of pressed twice.
        if (!isButtonHeldByOtherPointer(button, pointerId)) {
            button.press(pointerId)
        }
    }

    private fun releaseButton(pointerId: Int, button: Button) {
        val held = buttonsByPointer[pointerId] ?: return
        if (!held.remove(button)) {
            return
        }
        if (held.isEmpty()) {
            buttonsByPointer.remove(pointerId)
        }
        if (!isButtonHeldByOtherPointer(button, pointerId)) {
            button.release()
        }
    }

    private fun releasePointer(pointerId: Int) {
        slidingPointers.remove(pointerId)
        originButtons.remove(pointerId)
        val held = buttonsByPointer.remove(pointerId) ?: return
        for (button in held) {
            if (!isButtonHeldByOtherPointer(button, pointerId)) {
                button.release()
            }
        }
    }

    private fun releaseAllButtons() {
        slidingPointers.clear()
        originButtons.clear()
        val held = buttonsByPointer.values.flatten().toSet()
        buttonsByPointer.clear()
        for (button in held) {
            button.release()
        }
    }

    private fun buttonsAt(x: Float, y: Float): List<Button> =
        inputs.mapNotNull { (_, input) ->
            (input as? Button)?.takeIf { it.isInside(x, y) }
        }

    private fun onButtonSlide(pointerId: Int, x: Float, y: Float) {
        if (pointerId !in slidingPointers) {
            return
        }

        if (slideMode == InputOverlaySlideMode.HOLD) {
            // HOLD: the button the finger landed on stays pressed until it lifts (e.g. holding B
            // to run), while a button the finger slides onto is only pressed while the finger is
            // over it. Sliding A→B triggers B, sliding back to A releases B, and sliding to B
            // again triggers it again - all without lifting the finger.
            val origin = originButtons[pointerId]
            val hitButtons = buttonsAt(x, y)
            val held = buttonsByPointer[pointerId]?.toList().orEmpty()
            for (button in held) {
                if (button !== origin && button !in hitButtons) {
                    releaseButton(pointerId, button)
                }
            }
            for (button in hitButtons) {
                pressButton(pointerId, button)
            }
            return
        }

        // SWITCH: only the button under the finger is held. Sliding from A to B releases A and
        // presses B, so sliding back and forth triggers A→B→A→B.
        val target = findButtonAt(x, y)
        val held = buttonsByPointer[pointerId]?.toList().orEmpty()
        for (button in held) {
            if (button !== target) {
                releaseButton(pointerId, button)
            }
        }
        if (target != null) {
            pressButton(pointerId, target)
        }
    }

    private fun onDefaultTouch(event: MotionEvent): Boolean {
        var touchEventProcessed = false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                val button = findButtonAt(event.getX(pointerIndex), event.getY(pointerIndex))
                if (button != null) {
                    // Only fingers that land on a button may trigger further buttons by sliding,
                    // so a touch that starts on empty space keeps behaving like before.
                    slidingPointers.add(pointerId)
                    originButtons[pointerId] = button
                    pressButton(pointerId, button)
                    touchEventProcessed = true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    if (pointerId in slidingPointers) {
                        onButtonSlide(pointerId, event.getX(pointerIndex), event.getY(pointerIndex))
                        touchEventProcessed = true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                if (pointerId in slidingPointers) {
                    releasePointer(pointerId)
                    touchEventProcessed = true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (slidingPointers.isNotEmpty() || buttonsByPointer.isNotEmpty()) {
                    releaseAllButtons()
                    touchEventProcessed = true
                }
            }
        }

        return touchEventProcessed
    }

    companion object {
        private const val INPUTS_MIN_SIZE_DP = 20

        // Haptic timing. A pulse within the debounce window is ignored (a second finger must not
        // cancel the running pulse); a pulse within the rapid window switches to the softer,
        // longer "rolling" pulse used for drum rolls / button mashing.
        private const val HAPTIC_DEBOUNCE_MS = 40L
        private const val HAPTIC_RAPID_WINDOW_MS = 180L

        // Taiko big note (two fingers on opposite sides of the same part of the drum).
        private const val TATACON_BIG_HIT_PAIR_WINDOW_MS = 40L
        private const val TATACON_BIG_HIT_QUIET_BEFORE_MS = 110L
        // The big-note pulse itself is Eden's heavy thud stretched over the whole note: full-strength
        // attack, a sustained mid section, then a fade-out. A flat one-shot buzzes at one speed, and
        // a single short thud ends before the motor is even heard.
        private val TATACON_BIG_HIT_THUD_TIMINGS_MS = longArrayOf(35L, 150L, 75L)
        private val TATACON_BIG_HIT_THUD_SCALES = floatArrayOf(1.0f, 0.6f, 0.3f)
    }
}

@Composable
fun InputOverlaySurface(
    isVisible: Boolean,
    inputOverlaySettings: InputOverlaySettings,
    controllerType: Int,
    inputMode: InputOverlaySurfaceView.InputMode,
    onEditFinished: (Map<OverlayInputConfig, InputOverlayRect>) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            InputOverlaySurfaceView(context).apply {
                setVisible(isVisible)
                setInputMode(inputMode)
                applySettings(inputOverlaySettings, controllerType)
                onEditFinishedListener = onEditFinished
            }
        },
        update = { view ->
            view.setVisible(isVisible)
            view.setInputMode(inputMode)
            view.applySettings(inputOverlaySettings, controllerType)
            view.onEditFinishedListener = onEditFinished
        }
    )
}