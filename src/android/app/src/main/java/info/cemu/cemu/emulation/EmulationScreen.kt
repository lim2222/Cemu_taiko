package info.cemu.cemu.emulation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import info.cemu.cemu.R
import info.cemu.cemu.common.android.display.DisplayUtils
import info.cemu.cemu.common.settings.CustomScreensLayout
import info.cemu.cemu.common.settings.HotkeyAction
import info.cemu.cemu.common.settings.InputOverlaySlideMode
import info.cemu.cemu.common.settings.MAX_SCREENS_GAP
import info.cemu.cemu.common.settings.MAX_SCREENS_Y_OFFSET
import info.cemu.cemu.common.settings.MIN_SCREENS_Y_OFFSET
import info.cemu.cemu.common.settings.SCREEN_RECT_MAX
import info.cemu.cemu.common.settings.SCREEN_RECT_MIN_SIZE
import info.cemu.cemu.common.settings.ScreenOrientationMode
import info.cemu.cemu.common.settings.ScreenRect
import info.cemu.cemu.common.settings.ScreensLayout
import info.cemu.cemu.common.settings.inputOverlayAlphaKey
import info.cemu.cemu.common.settings.inputOverlayKey
import info.cemu.cemu.common.settings.inputOverlayOrientationKey
import info.cemu.cemu.common.settings.OverlayInputConfig
import info.cemu.cemu.common.ui.components.SingleSelection
import info.cemu.cemu.common.ui.components.Slider
import info.cemu.cemu.common.ui.extensions.showMessage
import info.cemu.cemu.common.ui.localization.controllerTypeToString
import info.cemu.cemu.common.ui.localization.tr
import info.cemu.cemu.emulation.emulatedusbdevices.EmulatedUSBDevicesDialog
import info.cemu.cemu.emulation.input.HotkeyManager
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurface
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView
import info.cemu.cemu.emulation.inputoverlay.overlayInputConfigsForControllerType
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.DEFAULT
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_POSITION
import info.cemu.cemu.emulation.inputoverlay.InputOverlaySurfaceView.InputMode.EDIT_SIZE
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun EmulationScreen(
    gamePath: String,
    titleId: Long?,
    setMotionSensorEnabled: (Boolean) -> Unit,
    setInputListeningEnabled: (Boolean) -> Unit,
    onQuit: () -> Unit,
    viewModel: EmulationViewModel = viewModel(
        factory = EmulationViewModel.Factory, extras = MutableCreationExtras().apply {
            set(EmulationViewModel.LAUNCH_PATH_KEY, gamePath)
            if (titleId != null) {
                set(EmulationViewModel.TITLE_ID_KEY, titleId)
            }
        }),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuitConfirmationDialog by remember { mutableStateOf(false) }
    var inputOverlayInputMode by rememberSaveable { mutableStateOf(DEFAULT) }
    var showEmulatedUSBDevices by remember { mutableStateOf(false) }
    // Size of the area the TV / GamePad screens are drawn in. Only used to prefill the numeric
    // layout, so switching to it starts exactly where the automatic layout put the screens.
    var emulationAreaSize by remember { mutableStateOf(IntSize.Zero) }
    var showInputVisibilityDialog by remember { mutableStateOf(false) }
    // The gesture based screen layout editor. While it is open the screens can be dragged around
    // with a finger and the touches go to the editor instead of the game.
    var isScreenLayoutEditMode by remember { mutableStateOf(false) }
    var screenEditMode by remember { mutableStateOf(ScreenLayoutEditMode.MOVE) }
    var selectedScreenIsTv by remember { mutableStateOf(true) }
    // Live layout while a screen is dragged. It is only written to the settings once the finger is
    // lifted, so dragging does not resize the surfaces or hit the data store on every frame.
    var screenLayoutPreview by remember { mutableStateOf<CustomScreensLayout?>(null) }

    val emulationError by viewModel.emulationError.collectAsState()
    val isEmulationInitialized by viewModel.isEmulationInitialized.collectAsState()
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val isInputOverlayVisible by viewModel.isInputOverlayVisible.collectAsState()

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val rawInputOverlaySettings by viewModel.inputOverlaySettings.collectAsState()
    // The emulated controller type of the overlay's controller. It comes from the view model so
    // it also updates when the type is changed from the side menu while the game is running.
    val inputOverlayControllerType by viewModel.controllerType.collectAsState()
    // The layout is stored per game, controller type *and* screen orientation, so a game played
    // with e.g. a GamePad and a Pro Controller keeps a separate arrangement for each, and moving
    // the buttons in portrait never affects the landscape arrangement (or the other way around).
    val inputOverlaySettings =
        remember(rawInputOverlaySettings, inputOverlayControllerType, isPortrait) {
            rawInputOverlaySettings.resolvedForGame(
                gamePath,
                inputOverlayKey(gamePath, inputOverlayControllerType),
                inputOverlayOrientationKey(gamePath, inputOverlayControllerType, isPortrait),
                inputOverlayAlphaKey(gamePath, isPortrait),
            )
        }

    // Display orientation and the TV/GamePad layout. Both are stored per game with a global
    // fallback so a title can deviate from the global default.
    val perGameDisplaySettings by viewModel.perGameDisplaySettings.collectAsState()
    val globalDisplaySettings by viewModel.globalDisplaySettings.collectAsState()
    val isDisplaySettingsLoaded by viewModel.isDisplaySettingsLoaded.collectAsState()
    val fastForwardSpeed by viewModel.fastForwardSpeed.collectAsState()
    val slowMotionDivisor by viewModel.slowMotionDivisor.collectAsState()

    // The emulated process is shared with the rest of the app, so fast forward / slow motion must
    // not stay enabled after the game is closed.
    DisposableEffect(Unit) {
        onDispose { EmulationSpeedState.disableAll() }
    }

    val screenOrientationMode =
        perGameDisplaySettings?.screenOrientation ?: globalDisplaySettings.screenOrientation

    val screensLayout = if (isPortrait) {
        perGameDisplaySettings?.screensLayoutPortrait
            ?: globalDisplaySettings.screensLayoutPortrait
    } else {
        perGameDisplaySettings?.screensLayoutLandscape
            ?: globalDisplaySettings.screensLayoutLandscape
    }

    val screensGap = perGameDisplaySettings?.screensGap
        ?: globalDisplaySettings.screensGap

    // Vertical offset of the screens in portrait only, so the landscape arrangement is unaffected.
    val screensYOffsetPortrait = perGameDisplaySettings?.screensYOffsetPortrait
        ?: globalDisplaySettings.screensYOffsetPortrait

    // Numeric screen layout. When set, the user placed the screens by hand and the automatic
    // layout above is ignored. Each orientation keeps its own arrangement.
    val customScreensLayout = if (isPortrait) {
        perGameDisplaySettings?.customScreensLayoutPortrait
            ?: globalDisplaySettings.customScreensLayoutPortrait
    } else {
        perGameDisplaySettings?.customScreensLayoutLandscape
            ?: globalDisplaySettings.customScreensLayoutLandscape
    }

    // Whether the GamePad screen is shown. It is stored per game and per orientation, like the
    // TV/GamePad layout, because a screen that is hidden in portrait (where both would not fit)
    // can still be shown in landscape, and games can differ.
    val isPadVisible = if (isPortrait) {
        perGameDisplaySettings?.isPadVisiblePortrait ?: globalDisplaySettings.isPadVisiblePortrait
    } else {
        perGameDisplaySettings?.isPadVisibleLandscape ?: globalDisplaySettings.isPadVisibleLandscape
    }

    // A portrait/landscape mode may differ from how the activity was created, which makes the
    // window rotate right after launch. Creating the emulation surface during that rotation makes
    // some titles (and the Vulkan renderer) crash, so emulation is only started once the window
    // has actually reached the requested orientation. Once it started, the surfaces are kept
    // alive through later rotations (they are only resized) so those never recreate the
    // SurfaceView.
    val isOrientationSettled = isDisplaySettingsLoaded && when (screenOrientationMode) {
        ScreenOrientationMode.PORTRAIT -> isPortrait
        ScreenOrientationMode.LANDSCAPE -> !isPortrait
        ScreenOrientationMode.AUTO -> true
    }
    var hasSurfacesBeenCreated by remember { mutableStateOf(isOrientationSettled) }
    LaunchedEffect(isOrientationSettled) {
        if (isOrientationSettled) {
            hasSurfacesBeenCreated = true
        }
    }

    // The game is stretched into a 4:3 area in portrait and a 16:9 area in landscape.
    val targetAspectRatio = if (isPortrait) 4f / 3f else 16f / 9f

    val activity = LocalContext.current as? Activity
    LaunchedEffect(activity, screenOrientationMode, isDisplaySettingsLoaded) {
        // Nothing is applied until the stored settings are known: the first composition still sees
        // the default (landscape) and rotating the window there and back again on every launch is
        // exactly what made the game start landscape. The activity itself already requests the
        // stored orientation in onCreate, so only later changes need to be applied here.
        if (isDisplaySettingsLoaded) {
            activity?.requestedOrientation = screenOrientationMode.toRequestedOrientation()
        }
    }

    // The GamePad screen is either shown in the emulation area or on an external display. Both the
    // surfaces and the layout editor need the same answer, so it is decided here.
    val padDisplay = if (activity != null) rememberPadDisplay(activity) else null
    val isPadVisibleEffective = isPadVisible && isEmulationInitialized
    val usePadPresentation =
        isPadVisibleEffective && sideMenuState.isPadOnExternalDisplay && padDisplay != null
    val isPadShownInContainer = isPadVisibleEffective && !usePadPresentation

    // The surface is 4:3 in portrait and 16:9 in landscape. In landscape that already matches
    // most titles, so the user's fullscreen scaling setting is kept; in portrait the game has to
    // be stretched (non proportionally) to fill the 4:3 area. A numeric layout can have any shape,
    // so the game is stretched to fill exactly the rect that was entered instead of leaving black
    // bars inside of it.
    val storedFullscreenScaling = remember { NativeSettings.getFullscreenScaling() }
    LaunchedEffect(isPortrait, customScreensLayout) {
        NativeSettings.setFullscreenScaling(
            if (isPortrait || customScreensLayout != null) {
                NativeSettings.FullscreenScaling.STRETCH
            } else {
                storedFullscreenScaling
            }
        )
    }

    fun closeDrawer() {
        scope.launch {
            drawerState.close()
        }
    }

    suspend fun toggleMenu() {
        drawerState.apply {
            if (isClosed) {
                open()
            } else {
                close()
            }
        }
    }

    // Leaving the editor accepts nothing new; the last committed layout stays.
    fun stopScreenLayoutEditing() {
        isScreenLayoutEditMode = false
        screenLayoutPreview = null
    }

    // Opens the gesture editor. A game that still uses the automatic arrangement first gets a
    // numeric layout that matches it exactly, so the screens do not jump when editing starts.
    fun startScreenLayoutEditing() {
        if (customScreensLayout == null) {
            viewModel.setCustomScreensLayout(
                isPortrait,
                automaticCustomScreensLayout(
                    containerSize = emulationAreaSize,
                    layout = screensLayout,
                    gap = screensGap,
                    yOffsetPercent = if (isPortrait) screensYOffsetPortrait else 0,
                    targetAspectRatio = targetAspectRatio,
                    isMainTv = !sideMenuState.areScreensSwapped,
                    isPadTv = sideMenuState.areScreensSwapped,
                    hasPad = isPadShownInContainer,
                ),
            )
        }
        screenEditMode = ScreenLayoutEditMode.MOVE
        selectedScreenIsTv = true
        screenLayoutPreview = null
        isScreenLayoutEditMode = true
        closeDrawer()
    }

    BackHandler {
        if (drawerState.isAnimationRunning) {
            return@BackHandler
        }

        scope.launch {
            toggleMenu()
        }
    }

    // Registered after the menu handler on purpose: the latest enabled handler wins, so back
    // closes the editor before it opens the menu.
    BackHandler(enabled = isScreenLayoutEditMode) {
        stopScreenLayoutEditing()
    }

    // Once the editor's drag has been written to the settings, the preview is the same as the
    // stored layout again and can be dropped.
    LaunchedEffect(screenLayoutPreview, customScreensLayout) {
        if (screenLayoutPreview != null && screenLayoutPreview == customScreensLayout) {
            screenLayoutPreview = null
        }
    }

    LaunchedEffect(drawerState.isClosed) {
        setInputListeningEnabled(drawerState.isClosed)
    }

    LaunchedEffect(sideMenuState.areScreensSwapped) {
        NativeEmulation.setSwapScreens(sideMenuState.areScreensSwapped)
    }

    LaunchedEffect(sideMenuState.isExternalScreenRotatedLeft) {
        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)
    }

    LaunchedEffect(Unit) {
        HotkeyManager.actions.collect { action ->
            when (action) {
                HotkeyAction.QUIT -> showQuitConfirmationDialog = true
                HotkeyAction.TOGGLE_MENU -> toggleMenu()
                HotkeyAction.SHOW_EMULATED_USB_DEVICES_DIALOG -> showEmulatedUSBDevices = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(IntrinsicSize.Max)
                        .verticalScroll(rememberScrollState())
                ) {
                    EmulationSideMenuContent(
                        sideMenuState = sideMenuState,
                        controllerType = inputOverlayControllerType,
                        isEmulationInitialized = isEmulationInitialized,
                        screenOrientationMode = screenOrientationMode,
                        onScreenOrientationChanged = { viewModel.setScreenOrientation(it) },
                        screensLayout = screensLayout,
                        screensLayoutLabel = tr("TV / GamePad layout") + " — " +
                            tr(if (isPortrait) "Portrait" else "Landscape"),
                        onScreensLayoutChanged = {
                            if (isPortrait) viewModel.setScreensLayoutPortrait(it)
                            else viewModel.setScreensLayoutLandscape(it)
                        },
                        screensGap = screensGap,
                        onScreensGapChanged = { viewModel.setScreensGap(it) },
                        isPortrait = isPortrait,
                        screensYOffsetPortrait = screensYOffsetPortrait,
                        onScreensYOffsetPortraitChanged = {
                            viewModel.setScreensYOffsetPortrait(it)
                        },
                        customScreensLayout = customScreensLayout,
                        // Only an explicit reset clears a hand-placed layout. The old checkbox made
                        // it too easy to lose a finger-adjusted layout by accidentally unticking it.
                        onResetCustomScreensLayout = {
                            viewModel.setCustomScreensLayout(isPortrait, null)
                        },
                        onEditScreenLayout = { startScreenLayoutEditing() },
                        isPadVisible = isPadVisible,
                        onPadVisibleChanged = { viewModel.setPadVisible(isPortrait, it) },
                        onControllerTypeChanged = { controllerType ->
                            viewModel.setControllerType(
                                rawInputOverlaySettings.controllerIndex,
                                controllerType,
                            )
                            closeDrawer()
                        },
                        fastForwardSpeed = fastForwardSpeed,
                        onFastForwardSpeedChanged = { viewModel.setFastForwardSpeed(it) },
                        slowMotionDivisor = slowMotionDivisor,
                        onSlowMotionDivisorChanged = { viewModel.setSlowMotionDivisor(it) },
                        updateState = {
                            viewModel.updateSideMenuState(it)
                            setMotionSensorEnabled(it.isMotionEnabled)
                            NativeEmulation.setReplaceTVWithPadView(it.isTVReplacedWithPad)
                            closeDrawer()
                        },
                        onEditInputOverlay = {
                            snackbarHostState.showMessage(scope, tr("Edit input positions"))
                            inputOverlayInputMode = EDIT_POSITION
                            closeDrawer()
                        },
                        onResetInputOverlay = {
                            viewModel.resetInputOverlayLayout(
                                inputOverlayControllerType,
                                isPortrait,
                            )
                            closeDrawer()
                        },
                        onEditVisibleInputs = {
                            showInputVisibilityDialog = true
                            closeDrawer()
                        },
                        isVibrateOnTouchEnabled = inputOverlaySettings.isVibrateOnTouchEnabled,
                        overlayAlpha = inputOverlaySettings.alpha,
                        onVibrateChanged = { viewModel.setInputOverlayVibrateOnTouch(it) },
                        slideMode = inputOverlaySettings.slideMode,
                        onSlideModeChanged = { viewModel.setInputOverlaySlideMode(it) },
                        onAlphaChanged = { viewModel.setInputOverlayAlpha(it, isPortrait) },
                        onQuit = {
                            showQuitConfirmationDialog = true
                            closeDrawer()
                        },
                        onShowEmulatedUSBDevices = {
                            showEmulatedUSBDevices = true
                            closeDrawer()
                        },
                    )
                }
            }
        },
    ) {
        // The SurfaceViews stay behind this window, so the editor toolbar, input buttons and side
        // menu are never covered by a rendering surface. Opacity is applied to each surface's
        // SurfaceControl, which lets SurfaceFlinger blend the screens without raising their Z.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (hasSurfacesBeenCreated) {
                EmulationSurfaces(
                    viewModel = viewModel,
                    screensLayout = screensLayout,
                    screensGap = screensGap,
                    screensYOffsetPercent = if (isPortrait) screensYOffsetPortrait else 0,
                    isPadShownInContainer = isPadShownInContainer,
                    usePadPresentation = usePadPresentation,
                    padDisplay = padDisplay,
                    targetAspectRatio = targetAspectRatio,
                    customScreensLayout = customScreensLayout,
                    screenLayoutPreview = screenLayoutPreview,
                    containerSize = emulationAreaSize,
                    isScreenLayoutEditMode = isScreenLayoutEditMode,
                    onContainerSizeChanged = { emulationAreaSize = it },
                )
            }

            InputOverlaySurface(
                isVisible = isInputOverlayVisible && !isScreenLayoutEditMode,
                inputOverlaySettings = inputOverlaySettings,
                controllerType = inputOverlayControllerType,
                inputMode = inputOverlayInputMode,
                onEditFinished = {
                    viewModel.saveInputOverlayRectangles(inputOverlayControllerType, isPortrait, it)
                },
                onMenuRequested = {
                    scope.launch { toggleMenu() }
                },
            )

            if (isScreenLayoutEditMode && customScreensLayout != null) {
                ScreenLayoutEditor(
                    containerSize = emulationAreaSize,
                    layout = screenLayoutPreview ?: customScreensLayout,
                    areScreensSwapped = sideMenuState.areScreensSwapped,
                    isPadShown = isPadShownInContainer,
                    editMode = screenEditMode,
                    selectedScreenIsTv = selectedScreenIsTv,
                    onSelectScreen = { selectedScreenIsTv = it },
                    onModeChanged = { screenEditMode = it },
                    onLayoutChanged = { screenLayoutPreview = it },
                    onOpacityChanged = { opacity ->
                        val current = screenLayoutPreview ?: customScreensLayout
                        val updated = if (selectedScreenIsTv) {
                            current.copy(tv = current.tv.copy(opacity = opacity))
                        } else {
                            current.copy(pad = current.pad.copy(opacity = opacity))
                        }
                        screenLayoutPreview = updated
                    },
                    onDragFinished = {
                        screenLayoutPreview?.let { viewModel.setCustomScreensLayout(isPortrait, it) }
                    },
                    onReset = {
                        val layout = automaticCustomScreensLayout(
                            containerSize = emulationAreaSize,
                            layout = screensLayout,
                            gap = screensGap,
                            yOffsetPercent = if (isPortrait) screensYOffsetPortrait else 0,
                            targetAspectRatio = targetAspectRatio,
                            isMainTv = !sideMenuState.areScreensSwapped,
                            isPadTv = sideMenuState.areScreensSwapped,
                            hasPad = isPadShownInContainer,
                        )
                        screenLayoutPreview = layout
                        viewModel.setCustomScreensLayout(isPortrait, layout)
                    },
                    onDone = { stopScreenLayoutEditing() },
                )
            }

            if (inputOverlayInputMode != DEFAULT) {
                EditInputsLayout(
                    inputMode = inputOverlayInputMode,
                    onFinishClick = {
                        snackbarHostState.showMessage(scope, tr("Exited input edit mode"))
                        inputOverlayInputMode = DEFAULT
                    },
                    onMoveClick = {
                        snackbarHostState.showMessage(scope, tr("Edit input positions"))
                        inputOverlayInputMode = EDIT_POSITION
                    },
                    onResizeClick = {
                        snackbarHostState.showMessage(scope, tr("Edit input size"))
                        inputOverlayInputMode = EDIT_SIZE
                    },
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    emulationError?.let {
        EmulationErrorDialog(it, onQuit)
    }

    if (!isEmulationInitialized) {
        EmulationLoadingDialog()
    }

    if (showQuitConfirmationDialog) {
        EmulationQuitConfirmationDialog(
            onQuit = onQuit,
            onDismiss = { showQuitConfirmationDialog = false },
        )
    }

    if (showEmulatedUSBDevices) {
        EmulatedUSBDevicesDialog(
            onDismiss = { showEmulatedUSBDevices = false },
        )
    }

    if (showInputVisibilityDialog) {
        InputOverlayVisibilityDialog(
            controllerType = inputOverlayControllerType,
            inputVisibilityMap = inputOverlaySettings.inputVisibilityMap,
            onToggle = { input, visible ->
                viewModel.setInputOverlayVisibility(
                    inputOverlayControllerType,
                    isPortrait,
                    input,
                    visible,
                )
            },
            onDismiss = { showInputVisibilityDialog = false },
        )
    }

    EmulationTextInputDialog()
}

@Composable
private fun EditInputsLayout(
    inputMode: InputOverlaySurfaceView.InputMode,
    onFinishClick: () -> Unit,
    onMoveClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onFinishClick) { Text(tr("Done")) }

            Row(
                modifier = Modifier
                    .wrapContentSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(enabled = inputMode != EDIT_POSITION, onClick = onMoveClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_move),
                        contentDescription = tr("Move")
                    )
                }

                FilledIconButton(enabled = inputMode != EDIT_SIZE, onClick = onResizeClick) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_resize),
                        contentDescription = tr("Resize"),
                    )
                }
            }
        }
    }
}

@Composable
private fun InputOverlayVisibilityDialog(
    controllerType: Int,
    inputVisibilityMap: Map<OverlayInputConfig, Boolean>,
    onToggle: (OverlayInputConfig, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val inputs = overlayInputConfigsForControllerType(controllerType)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("Done")) }
        },
        title = { Text(tr("Visible Inputs")) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (input in inputs) {
                    CheckboxItem(
                        label = overlayInputLabel(input, controllerType),
                        checked = inputVisibilityMap[input] ?: true,
                        onCheckedChange = { onToggle(input, it) },
                    )
                }
            }
        },
    )
}

private fun overlayInputLabel(input: OverlayInputConfig, controllerType: Int): String = when (input) {
    OverlayInputConfig.BUTTON_A -> "A"
    OverlayInputConfig.BUTTON_B -> "B"
    OverlayInputConfig.BUTTON_X -> "X"
    OverlayInputConfig.BUTTON_Y -> "Y"
    OverlayInputConfig.BUTTON_ONE -> "1"
    OverlayInputConfig.BUTTON_TWO -> "2"
    OverlayInputConfig.BUTTON_C -> "C"
    OverlayInputConfig.BUTTON_Z -> "Z"
    OverlayInputConfig.BUTTON_HOME -> tr("Home")
    OverlayInputConfig.BUTTON_L -> "L"
    OverlayInputConfig.BUTTON_R -> "R"
    OverlayInputConfig.BUTTON_L_STICK_CLICK -> "L3"
    OverlayInputConfig.BUTTON_R_STICK_CLICK -> "R3"
    OverlayInputConfig.BUTTON_PLUS -> "+"
    OverlayInputConfig.BUTTON_MINUS -> "-"
    OverlayInputConfig.BUTTON_ZL -> "ZL"
    OverlayInputConfig.BUTTON_ZR -> "ZR"
    OverlayInputConfig.BUTTON_BLOW_MIC -> tr("Blow mic")
    OverlayInputConfig.JOYSTICK_LEFT -> tr("Left joystick")
    OverlayInputConfig.JOYSTICK_RIGHT ->
        if (controllerType == NativeInput.EmulatedControllerType.WIIMOTE) tr("Nunchuck stick")
        else tr("Right joystick")
    OverlayInputConfig.DPAD -> tr("D-pad")
    OverlayInputConfig.TAIKO_DON_LEFT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_DON_RIGHT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_KA_LEFT -> tr("Taiko drum")
    OverlayInputConfig.TAIKO_KA_RIGHT -> tr("Taiko drum")
    OverlayInputConfig.FAST_FORWARD -> tr("Fast forward")
    OverlayInputConfig.SLOW_MOTION -> tr("Slow motion")
    OverlayInputConfig.PAUSE -> tr("Pause")
    OverlayInputConfig.MENU -> tr("Menu")
}

// Fast forward multipliers and slow motion divisors offered in the side menu. The on/off states
// are toggled by the on-screen buttons; these only pick how much faster or slower than normal time
// runs while they are on.
private val FastForwardSpeeds = listOf(2, 3, 4)
private val SlowMotionDivisors = listOf(2, 3, 4)

// Controller types that can be selected from the in-game side menu.
private val SwitchableControllerTypes = listOf(
    NativeInput.EmulatedControllerType.VPAD,
    NativeInput.EmulatedControllerType.PRO,
    NativeInput.EmulatedControllerType.CLASSIC,
    NativeInput.EmulatedControllerType.WIIMOTE,
    NativeInput.EmulatedControllerType.TAIKO,
)

// Mirrors the checks done by the input settings so a switch cannot exceed the number of
// emulated controllers the console supports.
private fun isControllerTypeSwitchAllowed(currentType: Int, newType: Int): Boolean {
    if (newType == NativeInput.EmulatedControllerType.DISABLED) {
        return true
    }

    val counts = NativeInput.getControllersCount()
    if (newType == NativeInput.EmulatedControllerType.VPAD) {
        return currentType == NativeInput.EmulatedControllerType.VPAD ||
            counts.vpadCount < NativeInput.MAX_VPAD_CONTROLLERS
    }

    val isAlreadyWpad = currentType != NativeInput.EmulatedControllerType.VPAD &&
        currentType != NativeInput.EmulatedControllerType.DISABLED

    return isAlreadyWpad || counts.wpadCount < NativeInput.MAX_WPAD_CONTROLLERS
}

private fun screenOrientationModeToString(mode: ScreenOrientationMode): String = when (mode) {
    ScreenOrientationMode.PORTRAIT -> tr("Portrait")
    ScreenOrientationMode.AUTO -> tr("Auto")
    ScreenOrientationMode.LANDSCAPE -> tr("Landscape")
}

private fun screensLayoutToString(layout: ScreensLayout): String = when (layout) {
    ScreensLayout.TOP_DOWN -> tr("Top / down")
    ScreensLayout.SIDE_BY_SIDE -> tr("Side by side")
}

private fun slideModeToString(mode: InputOverlaySlideMode): String = when (mode) {
    InputOverlaySlideMode.HOLD -> tr("Hold first button")
    InputOverlaySlideMode.SWITCH -> tr("Switch button")
}

@Composable
private fun EmulationSideMenuContent(
    sideMenuState: SideMenuState,
    controllerType: Int,
    isEmulationInitialized: Boolean,
    screenOrientationMode: ScreenOrientationMode,
    onScreenOrientationChanged: (ScreenOrientationMode) -> Unit,
    screensLayout: ScreensLayout,
    screensLayoutLabel: String,
    onScreensLayoutChanged: (ScreensLayout) -> Unit,
    screensGap: Int,
    onScreensGapChanged: (Int) -> Unit,
    isPortrait: Boolean,
    screensYOffsetPortrait: Int,
    onScreensYOffsetPortraitChanged: (Int) -> Unit,
    customScreensLayout: CustomScreensLayout?,
    onResetCustomScreensLayout: () -> Unit,
    onEditScreenLayout: () -> Unit,
    isPadVisible: Boolean,
    onPadVisibleChanged: (Boolean) -> Unit,
    onControllerTypeChanged: (Int) -> Unit,
    fastForwardSpeed: Int,
    onFastForwardSpeedChanged: (Int) -> Unit,
    slowMotionDivisor: Int,
    onSlowMotionDivisorChanged: (Int) -> Unit,
    updateState: (SideMenuState) -> Unit,
    onShowEmulatedUSBDevices: () -> Unit,
    onEditInputOverlay: () -> Unit,
    onResetInputOverlay: () -> Unit,
    onEditVisibleInputs: () -> Unit,
    isVibrateOnTouchEnabled: Boolean,
    overlayAlpha: Int,
    onVibrateChanged: (Boolean) -> Unit,
    slideMode: InputOverlaySlideMode,
    onSlideModeChanged: (InputOverlaySlideMode) -> Unit,
    onAlphaChanged: (Int) -> Unit,
    onQuit: () -> Unit,
) {
    SingleSelection(
        label = tr("Controller type"),
        choice = controllerType,
        choices = SwitchableControllerTypes,
        choiceToString = { controllerTypeToString(it) },
        enabled = isEmulationInitialized,
        isChoiceEnabled = { isControllerTypeSwitchAllowed(controllerType, it) },
        onChoiceChanged = onControllerTypeChanged,
    )

    SingleSelection(
        label = tr("Orientation"),
        choice = screenOrientationMode,
        choices = ScreenOrientationMode.entries,
        choiceToString = { screenOrientationModeToString(it) },
        onChoiceChanged = onScreenOrientationChanged,
    )

    SingleSelection(
        label = screensLayoutLabel,
        choice = screensLayout,
        choices = ScreensLayout.entries,
        choiceToString = { screensLayoutToString(it) },
        onChoiceChanged = onScreensLayoutChanged,
        // The numeric layout places the screens by hand, so the automatic arrangement is ignored.
        enabled = customScreensLayout == null,
    )

    Slider(
        label = tr("Screen gap"),
        value = screensGap,
        valueFrom = 0,
        valueTo = MAX_SCREENS_GAP,
        labelFormatter = { "$it%" },
        onValueChange = onScreensGapChanged,
        enabled = customScreensLayout == null,
    )

    Slider(
        label = tr("Portrait Y offset"),
        value = screensYOffsetPortrait,
        valueFrom = MIN_SCREENS_Y_OFFSET,
        valueTo = MAX_SCREENS_Y_OFFSET,
        labelFormatter = { "$it%" },
        onValueChange = onScreensYOffsetPortraitChanged,
        enabled = isPortrait && customScreensLayout == null,
    )

    TextButtonItem(
        label = tr("Edit screen layout"),
        onClick = onEditScreenLayout,
    )

    if (customScreensLayout != null) {
        TextButtonItem(
            label = tr("Reset screen layout"),
            onClick = onResetCustomScreensLayout,
        )
    }

    CheckboxItem(
        label = tr("Enable motion"),
        checked = sideMenuState.isMotionEnabled,
        onCheckedChange = { updateState(sideMenuState.copy(isMotionEnabled = it)) },
    )

    CheckboxItem(
        label = tr("Replace TV with PAD"),
        checked = sideMenuState.isTVReplacedWithPad,
        onCheckedChange = { updateState(sideMenuState.copy(isTVReplacedWithPad = it)) },
    )

    CheckboxItem(
        label = tr("Show PAD"),
        checked = isPadVisible,
        onCheckedChange = onPadVisibleChanged,
    )

    CheckboxItem(
        label = tr("External PAD screen"),
        checked = sideMenuState.isPadOnExternalDisplay,
        onCheckedChange = { updateState(sideMenuState.copy(isPadOnExternalDisplay = it)) },
        enabled = isPadVisible,
    )

    CheckboxItem(
        label = tr("Swap screens"),
        checked = sideMenuState.areScreensSwapped,
        onCheckedChange = { updateState(sideMenuState.copy(areScreensSwapped = it)) },
    )

    CheckboxItem(
        label = tr("Rotate external screen left"),
        checked = sideMenuState.isExternalScreenRotatedLeft,
        onCheckedChange = { updateState(sideMenuState.copy(isExternalScreenRotatedLeft = it)) },
        enabled = sideMenuState.isPadOnExternalDisplay,
    )

    SingleSelection(
        label = tr("Fast forward speed"),
        choice = fastForwardSpeed,
        choices = FastForwardSpeeds,
        choiceToString = { "${it}x" },
        onChoiceChanged = onFastForwardSpeedChanged,
    )

    SingleSelection(
        label = tr("Slow motion speed"),
        choice = slowMotionDivisor,
        choices = SlowMotionDivisors,
        choiceToString = { "1/${it}x" },
        onChoiceChanged = onSlowMotionDivisorChanged,
    )

    TextButtonItem(
        label = tr("Emulated USB Devices"),
        onClick = onShowEmulatedUSBDevices,
    )

    CheckboxItem(
        label = tr("Show input overlay"),
        checked = sideMenuState.isInputOverlayVisible,
        onCheckedChange = { updateState(sideMenuState.copy(isInputOverlayVisible = it)) },
    )

    CheckboxItem(
        label = tr("Vibrate on touch"),
        checked = isVibrateOnTouchEnabled,
        onCheckedChange = onVibrateChanged,
        enabled = sideMenuState.isInputOverlayVisible,
    )

    SingleSelection(
        label = tr("Button slide"),
        choice = slideMode,
        choices = InputOverlaySlideMode.entries,
        choiceToString = { slideModeToString(it) },
        onChoiceChanged = onSlideModeChanged,
        enabled = sideMenuState.isInputOverlayVisible,
    )

    Slider(
        label = tr("Inputs opacity"),
        value = overlayAlpha,
        valueFrom = 0,
        steps = 16,
        valueTo = 255,
        onValueChange = onAlphaChanged,
        labelFormatter = { it.toString() },
        enabled = sideMenuState.isInputOverlayVisible,
    )

    TextButtonItem(
        label = tr("Edit inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditInputOverlay,
    )

    TextButtonItem(
        label = tr("Visible inputs"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onEditVisibleInputs,
    )

    TextButtonItem(
        label = tr("Reset input overlay"),
        enabled = sideMenuState.isInputOverlayVisible,
        onClick = onResetInputOverlay,
    )

    TextButtonItem(
        label = tr("Exit"),
        onClick = onQuit,
    )
}

@Composable
private fun CheckboxItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled) { onCheckedChange(!checked) }
            .padding(8.dp)
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            fontSize = 16.sp,
        )

        Checkbox(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun TextButtonItem(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
            .heightIn(min = 48.dp)
            .wrapContentHeight(align = Alignment.CenterVertically),
        fontSize = 16.sp,
    )
}

// One screen of the numeric layout: position (X/Y) and size (W/H) in percent, typed by the user.
// The callback receives a change of the whole rect instead of a new rect, so it can be applied to
// the value that is stored at that moment.
@Composable
private fun ScreenRectEditor(
    label: String,
    rect: ScreenRect,
    onRectChanged: ((ScreenRect) -> ScreenRect) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text = label, fontSize = 14.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PercentField(
                label = "X",
                permille = rect.x,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onRectChanged { it.copy(x = value) } },
            )
            PercentField(
                label = "Y",
                permille = rect.y,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onRectChanged { it.copy(y = value) } },
            )
            PercentField(
                label = "W",
                permille = rect.width,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onRectChanged { it.copy(width = value) } },
            )
            PercentField(
                label = "H",
                permille = rect.height,
                modifier = Modifier.weight(1f),
                onValueChange = { value -> onRectChanged { it.copy(height = value) } },
            )
        }
    }
}

private val PERCENT_INPUT_REGEX = Regex("""\d{0,3}([.,]\d?)?""")

// The value is stored in tenths of a percent but the user types percent, e.g. 42.5. Only the text
// is kept while the field is focused, so typing "42." does not get rewritten to "42" under the
// cursor; when the field loses focus it is normalized to the value that was applied.
@Composable
private fun PercentField(
    label: String,
    permille: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(percentToString(permille)) }

    LaunchedEffect(permille, isFocused) {
        if (!isFocused) {
            text = percentToString(permille)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BasicTextField(
            value = text,
            onValueChange = { input ->
                if (!PERCENT_INPUT_REGEX.matches(input)) {
                    return@BasicTextField
                }

                text = input
                val percent = input.replace(',', '.').toFloatOrNull()
                    ?: return@BasicTextField

                onValueChange((percent * 10f).roundToInt().coerceIn(0, SCREEN_RECT_MAX))
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(vertical = 6.dp, horizontal = 4.dp),
            textStyle = TextStyle(
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

private fun percentToString(permille: Int): String =
    if (permille % 10 == 0) {
        (permille / 10).toString()
    } else {
        String.format(Locale.US, "%.1f", permille / 10f)
    }

private enum class ScreenLayoutEditMode { MOVE, RESIZE }

// Moves a rect by a drag in pixels and keeps it fully inside of the emulation area.
private fun ScreenRect.movedByPixels(
    dx: Float,
    dy: Float,
    containerWidth: Int,
    containerHeight: Int,
): ScreenRect {
    val maxX = (SCREEN_RECT_MAX - width).coerceAtLeast(0)
    val maxY = (SCREEN_RECT_MAX - height).coerceAtLeast(0)

    return copy(
        x = (x + dx * SCREEN_RECT_MAX / containerWidth).roundToInt().coerceIn(0, maxX),
        y = (y + dy * SCREEN_RECT_MAX / containerHeight).roundToInt().coerceIn(0, maxY),
    )
}

// Resizes a rect around its center: a horizontal drag moves the left and right edge apart by the
// same amount (the edge under the finger follows it) and a vertical drag does the same for the top
// and bottom edge. The result always stays inside of the emulation area.
private fun ScreenRect.resizedByPixels(
    dx: Float,
    dy: Float,
    containerWidth: Int,
    containerHeight: Int,
): ScreenRect {
    val dxPermille = (dx * SCREEN_RECT_MAX / containerWidth).roundToInt()
    val dyPermille = (dy * SCREEN_RECT_MAX / containerHeight).roundToInt()

    var newWidth = width + 2 * dxPermille
    var newX = x - dxPermille
    if (newX < 0) {
        newWidth += newX
        newX = 0
    }
    if (newX + newWidth > SCREEN_RECT_MAX) {
        newWidth = SCREEN_RECT_MAX - newX
    }
    newWidth = newWidth.coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX)
    newX = newX.coerceIn(0, SCREEN_RECT_MAX - newWidth)

    var newHeight = height + 2 * dyPermille
    var newY = y - dyPermille
    if (newY < 0) {
        newHeight += newY
        newY = 0
    }
    if (newY + newHeight > SCREEN_RECT_MAX) {
        newHeight = SCREEN_RECT_MAX - newY
    }
    newHeight = newHeight.coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX)
    newY = newY.coerceIn(0, SCREEN_RECT_MAX - newHeight)

    return copy(x = newX, y = newY, width = newWidth, height = newHeight)
}

private fun screenIsTv(index: Int, areScreensSwapped: Boolean): Boolean =
    (index == 0) != areScreensSwapped

// The editor needs a real Android touch target: Compose's interop dispatch forwards touches on an
// AndroidView (the emulation surfaces) to that View before Compose pointer input modifiers are
// reached. A transparent View above the surfaces is therefore used for the drag gestures.
@SuppressLint("ClickableViewAccessibility")
private class ScreenLayoutGestureListener(
    private val getLayout: () -> CustomScreensLayout,
    private val getRects: () -> List<ScreenRect>,
    private val getContainerSize: () -> IntSize,
    private val areScreensSwapped: () -> Boolean,
    private val getMode: () -> ScreenLayoutEditMode,
    private val onSelectScreen: (Boolean) -> Unit,
    private val onLayoutChanged: (CustomScreensLayout) -> Unit,
    private val onDragFinished: () -> Unit,
) : View.OnTouchListener {
    private var activePointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var startRect: ScreenRect? = null
    private var startIsTv = false
    private var startLayout: CustomScreensLayout? = null

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (activePointerId != -1) {
                    return false
                }

                val size = getContainerSize()
                if (size.width <= 0 || size.height <= 0) {
                    return false
                }

                val rects = getRects()
                var hitIndex = -1
                for (index in rects.indices.reversed()) {
                    val box = screenRectToBox(rects[index], size.width, size.height)
                    if (event.x >= box.x && event.x <= box.x + box.width &&
                        event.y >= box.y && event.y <= box.y + box.height
                    ) {
                        hitIndex = index
                        break
                    }
                }
                if (hitIndex == -1) {
                    return false
                }

                activePointerId = event.getPointerId(0)
                startX = event.x
                startY = event.y
                startLayout = getLayout()
                startRect = rects[hitIndex]
                startIsTv = screenIsTv(hitIndex, areScreensSwapped())
                onSelectScreen(startIsTv)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == -1) {
                    return false
                }
                val index = event.findPointerIndex(activePointerId)
                if (index == -1) {
                    return true
                }

                val rect = startRect ?: return true
                val layout = startLayout ?: return true
                val size = getContainerSize()
                val dx = event.getX(index) - startX
                val dy = event.getY(index) - startY

                val updated = if (getMode() == ScreenLayoutEditMode.MOVE) {
                    rect.movedByPixels(dx, dy, size.width, size.height)
                } else {
                    rect.resizedByPixels(dx, dy, size.width, size.height)
                }

                onLayoutChanged(
                    if (startIsTv) layout.copy(tv = updated) else layout.copy(pad = updated)
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointerId == -1) {
                    return false
                }
                val index = event.findPointerIndex(activePointerId)
                if (index != -1 && event.getPointerId(index) != activePointerId) {
                    return true
                }

                activePointerId = -1
                startRect = null
                startLayout = null
                onDragFinished()
                return true
            }
        }

        return false
    }
}

// Full screen editor drawn above the emulation surfaces. A finger down on a screen selects it; in
// MOVE the screen follows the finger, in RESIZE a horizontal drag changes its width and a vertical
// drag its height. The bar at the top switches the mode, resets the automatic arrangement and
// closes the editor; it can be dragged out of the way with the grip.
@Composable
private fun ScreenLayoutEditor(
    containerSize: IntSize,
    layout: CustomScreensLayout,
    areScreensSwapped: Boolean,
    isPadShown: Boolean,
    editMode: ScreenLayoutEditMode,
    selectedScreenIsTv: Boolean,
    onSelectScreen: (Boolean) -> Unit,
    onModeChanged: (ScreenLayoutEditMode) -> Unit,
    onLayoutChanged: (CustomScreensLayout) -> Unit,
    onOpacityChanged: (Int) -> Unit,
    onDragFinished: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    // The gesture handler is not restarted while a screen is dragged, so it has to read everything
    // through updated states: the preview changes on every drag event.
    val currentLayout by rememberUpdatedState(layout)
    val currentEditMode by rememberUpdatedState(editMode)
    val currentSwapped by rememberUpdatedState(areScreensSwapped)
    val currentPadShown by rememberUpdatedState(isPadShown)
    val currentSize by rememberUpdatedState(containerSize)
    val currentOnSelectScreen by rememberUpdatedState(onSelectScreen)
    val currentOnLayoutChanged by rememberUpdatedState(onLayoutChanged)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)

    // Created once and kept across recompositions so a drag is not interrupted by the preview
    // updates it produces. All values are read through the updated states above.
    val gestureListener = remember {
        ScreenLayoutGestureListener(
            getLayout = { currentLayout },
            getRects = {
                currentLayout.visibleScreenRects(
                    isMainTv = !currentSwapped,
                    isPadTv = currentSwapped,
                    isPadShown = currentPadShown,
                )
            },
            getContainerSize = { currentSize },
            areScreensSwapped = { currentSwapped },
            getMode = { currentEditMode },
            onSelectScreen = { currentOnSelectScreen(it) },
            onLayoutChanged = { currentOnLayoutChanged(it) },
            onDragFinished = { currentOnDragFinished() },
        )
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = Color.White.copy(alpha = 0.75f)
    var barOffset by remember { mutableStateOf(Offset.Zero) }

    val hasSize = containerSize.width > 0 && containerSize.height > 0
    val boxes = if (hasSize) {
        layout.visibleScreenRects(
            isMainTv = !areScreensSwapped,
            isPadTv = areScreensSwapped,
            isPadShown = isPadShown,
        ).map { screenRectToBox(it, containerSize.width, containerSize.height) }
    } else {
        emptyList()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            boxes.forEachIndexed { index, box ->
                val isSelected = screenIsTv(index, areScreensSwapped) == selectedScreenIsTv
                drawRect(
                    color = if (isSelected) primaryColor else outlineColor,
                    topLeft = Offset(box.x.toFloat(), box.y.toFloat()),
                    size = Size(box.width.toFloat(), box.height.toFloat()),
                    style = Stroke(width = if (isSelected) 4.dp.toPx() else 2.dp.toPx()),
                )
            }
        }

        // A SurfaceView's interop touch filter consumes touches before Compose pointer input sees
        // them, so the screens need a real View hit target. This transparent View sits above the
        // surfaces and below the control bar, so the bar buttons still receive their own clicks.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                View(context).apply {
                    setOnTouchListener(gestureListener)
                }
            },
        )

        val selectedRect = if (selectedScreenIsTv) layout.tv else layout.pad

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(barOffset.x.roundToInt(), barOffset.y.roundToInt()) }
                .padding(8.dp)
                .background(Color(0xCC101010), RoundedCornerShape(10.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⠿",
                        color = Color.White,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    barOffset += dragAmount
                                }
                            }
                            .padding(horizontal = 10.dp),
                    )

                    TextButton(onClick = { onModeChanged(ScreenLayoutEditMode.MOVE) }) {
                        Text(
                            text = tr("Move"),
                            fontSize = 14.sp,
                            color = if (editMode == ScreenLayoutEditMode.MOVE) primaryColor
                            else Color.White,
                        )
                    }

                    TextButton(onClick = { onModeChanged(ScreenLayoutEditMode.RESIZE) }) {
                        Text(
                            text = tr("Size"),
                            fontSize = 14.sp,
                            color = if (editMode == ScreenLayoutEditMode.RESIZE) primaryColor
                            else Color.White,
                        )
                    }

                    TextButton(onClick = onReset) {
                        Text(text = tr("Reset"), fontSize = 14.sp, color = Color.White)
                    }

                    TextButton(onClick = onDone) {
                        Text(text = tr("Done"), fontSize = 14.sp, color = Color.White)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = tr("Opacity"),
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                    MaterialSlider(
                        value = selectedRect.opacity / SCREEN_RECT_MAX.toFloat(),
                        valueRange = 0f..1f,
                        onValueChange = {
                            onOpacityChanged((it * SCREEN_RECT_MAX).roundToInt())
                        },
                        onValueChangeFinished = onDragFinished,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = percentToString(selectedRect.opacity) + "%",
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }

                Text(
                    text = tr(if (selectedScreenIsTv) "TV" else "GamePad") +
                        ":  X " + percentToString(selectedRect.x) +
                        "  Y " + percentToString(selectedRect.y) +
                        "  W " + percentToString(selectedRect.width) +
                        "  H " + percentToString(selectedRect.height),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        Text(
            text = tr(
                if (editMode == ScreenLayoutEditMode.MOVE) {
                    "Drag the TV or GamePad screen to move it"
                } else {
                    "Drag horizontally to change the width, vertically to change the height"
                }
            ),
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(Color(0xAA000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmulationSurfaces(
    viewModel: EmulationViewModel,
    screensLayout: ScreensLayout,
    screensGap: Int,
    screensYOffsetPercent: Int,
    isPadShownInContainer: Boolean,
    usePadPresentation: Boolean,
    padDisplay: Display?,
    targetAspectRatio: Float,
    customScreensLayout: CustomScreensLayout?,
    screenLayoutPreview: CustomScreensLayout?,
    containerSize: IntSize,
    isScreenLayoutEditMode: Boolean,
    onContainerSizeChanged: (IntSize) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sideMenuState by viewModel.sideMenuState.collectAsState()
    val mainSurfaceDimensions by viewModel.mainSurfaceDimensions.collectAsState()
    val padSurfaceDimensions by viewModel.padSurfaceDimensions.collectAsState()

    val mainTouchListener = remember { CanvasOnTouchListener() }
    val padTouchListener = remember { CanvasOnTouchListener() }
    val padPresentationTouchListener = remember { CanvasOnTouchListener() }

    val isMainTargetingTV = !sideMenuState.areScreensSwapped
    val mainTargetDimensions =
        if (isMainTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val isPadTargetingTV = sideMenuState.areScreensSwapped
    val padTargetDimensions =
        if (isPadTargetingTV) mainSurfaceDimensions else padSurfaceDimensions

    val rotatePresentationTouch = usePadPresentation && sideMenuState.isExternalScreenRotatedLeft

    LaunchedEffect(isPadTargetingTV, padTargetDimensions, rotatePresentationTouch) {
        padPresentationTouchListener.updateConfiguration(
            isTv = isPadTargetingTV,
            surfaceWidth = padTargetDimensions.width,
            surfaceHeight = padTargetDimensions.height,
            rotateLeft = rotatePresentationTouch,
        )
    }

    DisposableEffect(activity, padDisplay, usePadPresentation, sideMenuState.isExternalScreenRotatedLeft) {
        val activityNonNull = activity ?: return@DisposableEffect onDispose {}
        if (!usePadPresentation) {
            return@DisposableEffect onDispose {}
        }
        val padDisplayNonNull = padDisplay ?: return@DisposableEffect onDispose {}

        NativeEmulation.setExternalScreenRotatedLeft(sideMenuState.isExternalScreenRotatedLeft)

        val padPresentation = PadPresentation(
            context = activityNonNull,
            display = padDisplayNonNull,
            rotateLeft = sideMenuState.isExternalScreenRotatedLeft,
            holderCallback = viewModel.padHolderCallback,
            touchListener = padPresentationTouchListener,
        )

        padPresentation.show()

        onDispose { padPresentation.dismiss() }
    }

    // Rects of the screens that are in the emulation area, in the order of the layout container.
    val storedRects = customScreensLayout?.visibleScreenRects(
        isMainTv = isMainTargetingTV,
        isPadTv = isPadTargetingTV,
        isPadShown = isPadShownInContainer,
    )
    val previewRects = screenLayoutPreview?.visibleScreenRects(
        isMainTv = isMainTargetingTV,
        isPadTv = isPadTargetingTV,
        isPadShown = isPadShownInContainer,
    )
    val displayedRects = previewRects ?: storedRects
    val mainScreenRect = displayedRects?.getOrNull(0)
    val padScreenRect = displayedRects?.getOrNull(1)
    val mainOpacity = (mainScreenRect?.opacity ?: SCREEN_RECT_MAX) / SCREEN_RECT_MAX.toFloat()
    val padOpacity = (padScreenRect?.opacity ?: SCREEN_RECT_MAX) / SCREEN_RECT_MAX.toFloat()

    // While a screen is dragged only a View transform is applied: the SurfaceView keeps its size
    // (so the native surface is not resized on every frame) and is just scaled/translated. The
    // real layout is written down once the finger is lifted.
    fun screenViewTransform(index: Int): ScreenViewTransform? {
        if (screenLayoutPreview == null || containerSize.width <= 0 ||
            containerSize.height <= 0
        ) {
            return null
        }

        val stored = storedRects?.getOrNull(index) ?: return null
        val preview = previewRects?.getOrNull(index) ?: return null
        if (stored == preview) {
            return null
        }

        val storedBox = screenRectToBox(stored, containerSize.width, containerSize.height)
        val previewBox = screenRectToBox(preview, containerSize.width, containerSize.height)

        return ScreenViewTransform(
            scaleX = previewBox.width.toFloat() / storedBox.width,
            scaleY = previewBox.height.toFloat() / storedBox.height,
            translationX = (previewBox.x + previewBox.width / 2f) -
                (storedBox.x + storedBox.width / 2f),
            translationY = (previewBox.y + previewBox.height / 2f) -
                (storedBox.y + storedBox.height / 2f),
        )
    }

    ScreensLayoutContainer(
        isVertical = screensLayout.isVertical(),
        gapPercent = screensGap,
        yOffsetPercent = screensYOffsetPercent,
        targetAspectRatio = targetAspectRatio,
        customRects = storedRects,
        onContainerSizeChanged = onContainerSizeChanged,
    ) {
        EmulationSurface(
            holderCallback = viewModel.mainHolderCallback,
            touchListener = mainTouchListener,
            touchIsTv = isMainTargetingTV,
            touchSurfaceWidth = mainTargetDimensions.width,
            touchSurfaceHeight = mainTargetDimensions.height,
            isEnabled = !isScreenLayoutEditMode,
            viewTransform = screenViewTransform(0),
            opacity = mainOpacity,
            afterInit = { viewModel.initializeEmulation() },
        )

        if (isPadShownInContainer) {
            EmulationSurface(
                holderCallback = viewModel.padHolderCallback,
                touchListener = padTouchListener,
                touchIsTv = isPadTargetingTV,
                touchSurfaceWidth = padTargetDimensions.width,
                touchSurfaceHeight = padTargetDimensions.height,
                isEnabled = !isScreenLayoutEditMode,
                viewTransform = screenViewTransform(1),
                opacity = padOpacity,
            )
        }
    }
}

// Lays out the TV / GamePad surfaces without ever swapping the composable that contains them.
// Switching between a Column and a Row when the orientation or the layout changes would dispose
// and recreate the SurfaceViews, which destroys and recreates their native surfaces and can crash
// the renderer. A single custom layout instead just measures its children differently.
@Composable
private fun ScreensLayoutContainer(
    isVertical: Boolean,
    gapPercent: Int,
    yOffsetPercent: Int,
    targetAspectRatio: Float,
    customRects: List<ScreenRect>? = null,
    onContainerSizeChanged: (IntSize) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged(onContainerSizeChanged),
    ) { measurables, constraints ->
        val containerWidth = constraints.maxWidth
        val containerHeight = constraints.maxHeight
        val count = measurables.size
        if (count == 0) {
            return@Layout layout(containerWidth, containerHeight) {}
        }

        // A numeric layout puts the screens exactly where the user placed them, the automatic one
        // arranges them from the layout, gap and offset settings.
        val boxes = if (customRects != null) {
            List(count) { index ->
                val rect = customRects.getOrNull(index) ?: ScreenRect()
                screenRectToBox(rect, containerWidth, containerHeight)
            }
        } else {
            automaticScreenBoxes(
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                count = count,
                isVertical = isVertical,
                gapPercent = gapPercent,
                yOffsetPercent = yOffsetPercent,
                targetAspectRatio = targetAspectRatio,
            )
        }

        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(boxes[index].width, boxes[index].height))
        }

        layout(containerWidth, containerHeight) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(IntOffset(boxes[index].x, boxes[index].y))
            }
        }
    }
}

private data class ScreenBox(val x: Int, val y: Int, val width: Int, val height: Int)

// The automatic arrangement. Each screen keeps its aspect ratio: in portrait (vertical) a full
// width screen may be too tall, so its height is capped; in landscape (side by side) the width is
// capped.
private fun automaticScreenBoxes(
    containerWidth: Int,
    containerHeight: Int,
    count: Int,
    isVertical: Boolean,
    gapPercent: Int,
    yOffsetPercent: Int,
    targetAspectRatio: Float,
): List<ScreenBox> {
    val gap = if (count > 1) {
        (min(containerWidth, containerHeight) * gapPercent / 100f).roundToInt()
    } else {
        0
    }

    // Vertical shift of the whole arrangement, as a percentage of the container height. Positive
    // moves the screens down. Only used in portrait, where the screens are stacked.
    val yOffset = (containerHeight * yOffsetPercent / 100f).roundToInt()

    val sizes = if (isVertical) {
        val share = (containerHeight - gap * (count - 1)) / count
        val boxWidth = min(containerWidth, (share * targetAspectRatio).roundToInt())
        val boxHeight = (boxWidth / targetAspectRatio).roundToInt()
        List(count) { boxWidth to boxHeight }
    } else {
        val share = (containerWidth - gap * (count - 1)) / count
        val boxHeight = min(containerHeight, (share / targetAspectRatio).roundToInt())
        val boxWidth = (boxHeight * targetAspectRatio).roundToInt()
        List(count) { boxWidth to boxHeight }
    }

    return if (isVertical) {
        // Stacked from the top (plus the user's offset) so a lone screen is not floating in the
        // middle.
        var y = yOffset
        sizes.map { (width, height) ->
            ScreenBox(x = (containerWidth - width) / 2, y = y, width = width, height = height)
                .also { y += height + gap }
        }
    } else {
        val totalWidth = sizes.sumOf { it.first } + gap * (count - 1)
        var x = (containerWidth - totalWidth) / 2
        sizes.map { (width, height) ->
            ScreenBox(
                x = x,
                y = (containerHeight - height) / 2 + yOffset,
                width = width,
                height = height,
            ).also { x += width + gap }
        }
    }
}

// Rects of the screens that are actually drawn in the emulation area, in the order they are added
// to ScreensLayoutContainer: the main surface (TV unless the screens are swapped) first, then the
// GamePad surface when it is shown next to it.
private fun CustomScreensLayout.visibleScreenRects(
    isMainTv: Boolean,
    isPadTv: Boolean,
    isPadShown: Boolean,
): List<ScreenRect> = buildList {
    add(if (isMainTv) tv else pad)
    if (isPadShown) {
        add(if (isPadTv) tv else pad)
    }
}

// Live transform of a SurfaceView while a screen is dragged in the layout editor. The view keeps
// its laid out size and is only scaled/translated around its center.
private data class ScreenViewTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

// Converts a stored percentage rect to pixels and keeps it inside of the container, so a screen is
// never measured to nothing or drawn outside of the emulation area.
private fun screenRectToBox(
    rect: ScreenRect,
    containerWidth: Int,
    containerHeight: Int,
): ScreenBox {
    val containerWidthLong = containerWidth.toLong()
    val containerHeightLong = containerHeight.toLong()
    val x = (containerWidthLong * rect.x / SCREEN_RECT_MAX).toInt()
        .coerceIn(0, containerWidth - 1)
    val y = (containerHeightLong * rect.y / SCREEN_RECT_MAX).toInt()
        .coerceIn(0, containerHeight - 1)

    return ScreenBox(
        x = x,
        y = y,
        width = (containerWidthLong * rect.width / SCREEN_RECT_MAX).toInt()
            .coerceIn(1, containerWidth - x),
        height = (containerHeightLong * rect.height / SCREEN_RECT_MAX).toInt()
            .coerceIn(1, containerHeight - y),
    )
}

// Builds the numeric layout that matches the current automatic arrangement, so enabling the
// numeric layout does not move the screens until the user changes a value.
private fun automaticCustomScreensLayout(
    containerSize: IntSize,
    layout: ScreensLayout,
    gap: Int,
    yOffsetPercent: Int,
    targetAspectRatio: Float,
    isMainTv: Boolean,
    isPadTv: Boolean,
    hasPad: Boolean,
): CustomScreensLayout {
    if (containerSize.width <= 0 || containerSize.height <= 0) {
        return CustomScreensLayout()
    }

    val boxes = automaticScreenBoxes(
        containerWidth = containerSize.width,
        containerHeight = containerSize.height,
        count = if (hasPad) 2 else 1,
        isVertical = layout.isVertical(),
        gapPercent = gap,
        yOffsetPercent = yOffsetPercent,
        targetAspectRatio = targetAspectRatio,
    )

    fun boxToRect(box: ScreenBox) = ScreenRect(
        x = (box.x.toLong() * SCREEN_RECT_MAX / containerSize.width).toInt()
            .coerceIn(0, SCREEN_RECT_MAX),
        y = (box.y.toLong() * SCREEN_RECT_MAX / containerSize.height).toInt()
            .coerceIn(0, SCREEN_RECT_MAX),
        width = (box.width.toLong() * SCREEN_RECT_MAX / containerSize.width).toInt()
            .coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX),
        height = (box.height.toLong() * SCREEN_RECT_MAX / containerSize.height).toInt()
            .coerceIn(SCREEN_RECT_MIN_SIZE, SCREEN_RECT_MAX),
    )

    val mainRect = boxToRect(boxes[0])
    val padRect = if (hasPad) boxToRect(boxes[1]) else mainRect

    return CustomScreensLayout(
        tv = if (isMainTv) mainRect else padRect,
        pad = if (isPadTv) mainRect else padRect,
    )
}

// View.setAlpha() is ignored for a SurfaceView behind its parent window. Setting the alpha on the
// SurfaceControl itself makes SurfaceFlinger blend the surface without changing its Z order, so the
// app UI stays above it.
private fun SurfaceView.applySurfaceControlAlpha(opacity: Float) {
    val control = surfaceControl ?: return
    runCatching {
        SurfaceControl.Transaction().use { transaction ->
            transaction.setAlpha(control, opacity.coerceIn(0f, 1f))
            transaction.apply()
        }
    }
}

@Composable
@SuppressLint("ClickableViewAccessibility")
private fun EmulationSurface(
    holderCallback: SurfaceHolder.Callback,
    touchListener: CanvasOnTouchListener,
    touchIsTv: Boolean,
    touchSurfaceWidth: Int,
    touchSurfaceHeight: Int,
    rotateLeft: Boolean = false,
    isEnabled: Boolean = true,
    viewTransform: ScreenViewTransform? = null,
    opacity: Float = 1f,
    afterInit: () -> Unit = {}
) {
    // While the layout editor is open the surface must not turn the drags into game touches.
    SideEffect {
        touchListener.isEnabled = isEnabled
    }

    // The surface callbacks can run after a recomposition, so they must not keep the opacity value
    // that was current when the View was first created.
    val currentOpacity = rememberUpdatedState(opacity)

    // The size (and aspect ratio) is decided by ScreensLayoutContainer.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                var firstChange = true

                fun applyCurrentOpacity() = applySurfaceControlAlpha(currentOpacity.value)

                applyCurrentOpacity()
                setOnTouchListener(touchListener)

                holder.addCallback(holderCallback)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceChanged(
                        holder: SurfaceHolder, format: Int, width: Int, height: Int
                    ) {
                        applyCurrentOpacity()
                        if (firstChange) {
                            afterInit()
                            firstChange = false
                        }
                    }

                    override fun surfaceCreated(holder: SurfaceHolder) {
                        applyCurrentOpacity()
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
            }
        },
        update = { view ->
            touchListener.updateConfiguration(
                isTv = touchIsTv,
                surfaceWidth = touchSurfaceWidth,
                surfaceHeight = touchSurfaceHeight,
                rotateLeft = rotateLeft,
            )

            // SurfaceViews ignore Compose canvas transforms, so the drag preview is applied to the
            // View itself. The default pivot is the center of the view, which matches the
            // center based boxes computed for the preview.
            view.scaleX = viewTransform?.scaleX ?: 1f
            view.scaleY = viewTransform?.scaleY ?: 1f
            view.translationX = viewTransform?.translationX ?: 0f
            view.translationY = viewTransform?.translationY ?: 0f
            view.applySurfaceControlAlpha(opacity)
        },
    )
}

@Composable
private fun rememberPadDisplay(activity: Activity): Display? {
    val displayManager =
        remember(activity) { activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }
    var padDisplay by remember { mutableStateOf<Display?>(null) }

    fun updatePadDisplay() {
        padDisplay =
            if (activity.display.displayId == Display.DEFAULT_DISPLAY) {
                DisplayUtils.getExternalDisplay(activity)
            } else {
                DisplayUtils.getInternalDisplay(activity)
            }
    }

    DisposableEffect(displayManager, activity) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
            override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
            override fun onDisplayChanged(displayId: Int) = updatePadDisplay()
        }

        updatePadDisplay()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    return padDisplay
}

@Composable
private fun EmulationQuitConfirmationDialog(onQuit: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        title = { Text(tr("Exit confirmation")) },
        text = { Text(tr("Are you sure you want to exit?")) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Yes")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("No")) } },
        onDismissRequest = onDismiss,
    )
}

@Composable
private fun EmulationLoadingDialog() {
    AlertDialog(
        title = { Text(tr("Initializing emulation")) },
        text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
        onDismissRequest = {},
    )
}

@Composable
private fun EmulationErrorDialog(error: NativeError, onQuit: () -> Unit) {
    val errorMessage = remember(error) {
        when (error) {
            is NativeError.SystemInitializationError -> tr("Failed to initialize")

            is NativeError.RendererInitializationError ->
                tr("Failed creating renderer: {0}", error.message)

            is NativeError.SurfaceCreationError -> tr("Failed creating surface: {0}", error.message)

            NativeError.GameFilesNotFoundError -> tr("Unable to launch game because the base files were not found.")
            NativeError.NoDiscKeysError -> tr("Could not decrypt title. Make sure that keys.txt contains the correct disc key for this title.")
            NativeError.NoTitleTikError -> tr("Could not decrypt title because title.tik is missing.")
            is NativeError.UnknownTilePrepareError ->
                tr("Unable to launch game\nPath: {0}", error.launchPath)

            NativeError.LaunchingTitleError -> tr("Failed to launch title")
        }
    }
    AlertDialog(
        title = { Text(tr("Error")) },
        text = { Text(errorMessage) },
        confirmButton = { TextButton(onClick = onQuit) { Text(tr("Quit")) } },
        onDismissRequest = {},
    )
}
