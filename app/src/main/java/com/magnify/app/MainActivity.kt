package com.magnify.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

// ---- palette copied from the reference screenshots ----
private val Surf = Color(0xFF1C1C1E)        // search pill
private val CircleBg = Color(0xFF36373A)     // round bottom-bar buttons
private val SheetBg = Color(0xFF181818)
private val Accent = Color(0xFFA8C7FA)       // light-blue selection
private val OnAccent = Color(0xFF062E6F)
private val TrackBg = Color(0xFF38393C)
private val IconCol = Color(0xFFE3E3E3)
private val Hint = Color(0xFF8E8E93)
private val Highlight = Color(0xFFFFE066)

class MainActivity : ComponentActivity() {
    private val vm = MagnifyState()
    private var granted by mutableStateOf(false)
    private val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query = it }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadPicked(this, vm, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) req.launch(Manifest.permission.CAMERA)
        setContent {
            MagnifyApp(
                vm, granted,
                requestPermission = { req.launch(Manifest.permission.CAMERA) },
                onMic = {
                    try {
                        speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                    } catch (_: Exception) {}
                },
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }
}

// ---------------- Tap helpers (haptics + press scale) ----------------
enum class H { Click, Heavy }

fun Modifier.tap(h: H, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")
    val cb by rememberUpdatedState(onClick)
    this.graphicsLayer { scaleX = s; scaleY = s }.pointerInput(Unit) {
        detectTapGestures(
            onPress = { pressed = true; try { awaitRelease() } finally { pressed = false } },
            onTap = { if (h == H.Heavy) Haptics.heavy(view) else Haptics.click(view); cb() }
        )
    }
}
fun Modifier.tap(onClick: () -> Unit): Modifier = tap(H.Click, onClick)

/** Press = one step, hold = repeats (used for zoom − / +). */
fun Modifier.holdRepeat(onStep: () -> Unit): Modifier = composed {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "hold")
    val cb by rememberUpdatedState(onStep)
    this.graphicsLayer { scaleX = s; scaleY = s }.pointerInput(Unit) {
        detectTapGestures(onPress = {
            pressed = true; Haptics.click(view); cb()
            coroutineScope {
                val job = launch { delay(350); while (true) { cb(); delay(70) } }
                try { awaitRelease() } finally { job.cancel(); pressed = false }
            }
        })
    }
}

fun Modifier.plainClick(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null) { onClick() }
}

// ---------------- Root ----------------
@Composable
fun MagnifyApp(vm: MagnifyState, granted: Boolean, requestPermission: () -> Unit, onMic: () -> Unit, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    SideEffect { vm.view = view; view.keepScreenOn = vm.keepOn; Haptics.enabled = vm.hapticsOn }
    val exec = remember { Executors.newSingleThreadExecutor() }
    val capture = { takePhoto(ctx, vm, exec) }
    val share = {
        vm.lastUri?.let {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, it); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share photo"))
        }; Unit
    }
    LaunchedEffect(vm.photo) { if (vm.photo != null) Haptics.confirm(view) }
    BackHandler(enabled = vm.settingsOpen || vm.sheetOpen || vm.photo != null) {
        when { vm.settingsOpen -> vm.settingsOpen = false; vm.sheetOpen -> vm.sheetOpen = false; else -> vm.closePhoto() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Viewfinder(vm, granted, requestPermission, capture, share, Modifier.weight(1f).fillMaxWidth())
            BottomBar(vm, onMic, onPick)
        }
        FiltersSheet(vm, Modifier.align(Alignment.BottomCenter))
        SettingsSheet(vm, Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------- Viewfinder ----------------
@Composable
fun Viewfinder(vm: MagnifyState, granted: Boolean, request: () -> Unit, capture: () -> Unit, share: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val pv = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    SideEffect { vm.previewView = pv }
    LaunchedEffect(granted, vm.front) { if (granted) startCamera(ctx, owner, pv, vm, exec) }

    val q = vm.query.trim()
    val count = vm.matches().size
    LaunchedEffect(count > 0 && q.isNotEmpty()) { if (count > 0 && q.isNotEmpty()) Haptics.confirm(vm.view) }

    Box(modifier.clipToBounds().background(Color.Black)) {
        if (!granted) {
            Text("Allow camera access", Modifier.align(Alignment.Center).tap(request).padding(24.dp), color = Color.White, fontSize = 18.sp)
        } else {
            AndroidView({ pv }, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f && vm.photo == null) vm.setZ(vm.zoom * zoom) }
            }, update = {
                val m = finalMatrix(vm.filter, vm.contrast, 0f)
                if (m == null) it.setLayerType(View.LAYER_TYPE_NONE, null)
                else it.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(m)) })
            })
        }
        if (vm.photo == null) LiveHighlights(vm) else PhotoViewer(vm)

        if (q.isNotEmpty()) {
            val label = if (count > 0) "$count found" else if (vm.photo != null || vm.ocrW > 0) "No matches" else "Searching…"
            Text(label, Modifier.align(Alignment.TopCenter).padding(top = 12.dp).clip(CircleShape)
                .background(Color(0xCC1C1C1E)).padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 14.sp)
        }

        // Controls overlay (exactly like the reference: round buttons, then − shutter +)
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 19.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 29.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (vm.photo == null) {
                    OverlayCircle({ vm.flip() }) { Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White, modifier = Modifier.size(24.dp)) }
                    OverlayCircle({ vm.toggleTorch() }) { FlashlightIcon(vm.torch, Color.White, 22.dp) }
                } else {
                    OverlayCircle(share) { Icon(Icons.Filled.Share, "Share", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.size(42.dp))
                }
            }
            Spacer(Modifier.height(36.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(33.dp)) {
                ZoomBtn(false) { vm.setZ(vm.z / 1.12f) }
                Shutter(vm.photo != null) { if (vm.photo != null) vm.closePhoto() else capture() }
                ZoomBtn(true) { vm.setZ(vm.z * 1.12f) }
            }
        }
    }
}

@Composable
fun OverlayCircle(onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(42.dp).tap(onClick).background(Color(0x66000000), CircleShape).border(2.dp, Color.White, CircleShape), Alignment.Center) { content() }

@Composable
fun ZoomBtn(plus: Boolean, onStep: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.size(54.dp).holdRepeat(onStep).background(Color(0xE61B1B1D), shape).border(2.dp, Color.White, shape), Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val sw = 3.5.dp.toPx(); val s = size.width
            drawLine(Color.White, Offset(sw / 2, s / 2), Offset(s - sw / 2, s / 2), sw, StrokeCap.Round)
            if (plus) drawLine(Color.White, Offset(s / 2, sw / 2), Offset(s / 2, s - sw / 2), sw, StrokeCap.Round)
        }
    }
}

@Composable
fun Shutter(photoMode: Boolean, onClick: () -> Unit) =
    Box(Modifier.size(79.dp).tap(H.Heavy, onClick).background(Color(0xFF1B1B1D), CircleShape).border(2.5.dp, Color.White, CircleShape), Alignment.Center) {
        if (photoMode) Icon(Icons.Filled.Close, "Close photo", tint = Color.White, modifier = Modifier.size(30.dp))
        else Box(Modifier.size(58.dp).clip(CircleShape).background(Color.White))
    }

@Composable
fun FlashlightIcon(on: Boolean, tint: Color, size: Dp) = Canvas(Modifier.size(size)) {
    val u = this.size.width / 24f
    val p = Path().apply {
        moveTo(6 * u, 2 * u); lineTo(18 * u, 2 * u); lineTo(18 * u, 5 * u); lineTo(15 * u, 8 * u)
        lineTo(15 * u, 22 * u); lineTo(9 * u, 22 * u); lineTo(9 * u, 8 * u); lineTo(6 * u, 5 * u); close()
    }
    drawPath(p, tint, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (on) drawLine(tint, Offset(12 * u, 11 * u), Offset(12 * u, 15 * u), 2.2f * u, StrokeCap.Round)
    else drawLine(tint, Offset(3 * u, 3 * u), Offset(21 * u, 21 * u), 2.2f * u, StrokeCap.Round)
}

// ---------------- Search highlights ----------------
@Composable
fun LiveHighlights(vm: MagnifyState) {
    Canvas(Modifier.fillMaxSize()) {
        val ow = vm.ocrW; val oh = vm.ocrH
        if (ow == 0 || oh == 0) return@Canvas
        val s = max(size.width / ow, size.height / oh)          // FILL_CENTER
        val dx = (size.width - ow * s) / 2f; val dy = (size.height - oh * s) / 2f
        vm.matches().forEach { r ->
            var l = r.left * s + dx; var rr = r.right * s + dx
            if (vm.front) { val nl = size.width - rr; rr = size.width - l; l = nl }
            val t = r.top * s + dy; val b = r.bottom * s + dy
            drawRoundRect(Highlight.copy(alpha = 0.35f), Offset(l, t), Size(rr - l, b - t), CornerRadius(6f))
            drawRoundRect(Highlight, Offset(l, t), Size(rr - l, b - t), CornerRadius(6f), Stroke(3f))
        }
    }
}

@Composable
fun PhotoViewer(vm: MagnifyState) {
    val bmp = vm.photo ?: return
    val m = finalMatrix(vm.filter, vm.contrast, photoBrightnessOffset(vm))
    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { vm.setZ(if (vm.photoScale > 1.5f) 1f else 4f); vm.photoOff = Offset.Zero }) }
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                vm.setZ(vm.photoScale * zoom)
                vm.photoOff = if (vm.photoScale <= 1f) Offset.Zero else vm.photoOff + pan
            }
        }, Alignment.Center
    ) {
        Box(Modifier.aspectRatio(bmp.width.toFloat() / bmp.height)
            .graphicsLayer(scaleX = vm.photoScale, scaleY = vm.photoScale, translationX = vm.photoOff.x, translationY = vm.photoOff.y)) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                colorFilter = m?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
            Canvas(Modifier.fillMaxSize()) {
                if (vm.ocrW == 0) return@Canvas
                val s = size.width / vm.ocrW
                vm.matches().forEach { r ->
                    drawRoundRect(Highlight.copy(alpha = 0.35f), Offset(r.left * s, r.top * s), Size((r.right - r.left) * s, (r.bottom - r.top) * s), CornerRadius(4f))
                    drawRoundRect(Highlight, Offset(r.left * s, r.top * s), Size((r.right - r.left) * s, (r.bottom - r.top) * s), CornerRadius(4f), Stroke(2f))
                }
            }
        }
    }
}

// ---------------- Bottom bar: [filters] [Find text 🎤] [gallery] ----------------
@Composable
fun BottomBar(vm: MagnifyState, onMic: () -> Unit, onPick: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(94.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        BarCircle({ focus.clearFocus(); vm.sheetOpen = true; vm.sheetTab = 0 }) {
            Icon(Icons.Filled.Tune, "Filters", tint = IconCol, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(17.dp))
        Row(Modifier.weight(1f).height(58.dp).clip(CircleShape).background(Surf).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), Alignment.CenterStart) {
                if (vm.query.isEmpty()) Text("Find text", color = Hint, fontSize = 19.sp, maxLines = 1)
                BasicTextField(
                    vm.query, { vm.query = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 19.sp, color = Color.White),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Box(Modifier.size(58.dp).tap { if (vm.query.isNotEmpty()) { vm.query = "" } else onMic() }, Alignment.Center) {
                Icon(if (vm.query.isNotEmpty()) Icons.Filled.Close else Icons.Filled.Mic,
                    if (vm.query.isNotEmpty()) "Clear" else "Voice search", tint = IconCol, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.width(17.dp))
        BarCircle(onPick) { Icon(Icons.Filled.PhotoLibrary, "Photos", tint = IconCol, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
fun BarCircle(onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(44.dp).tap(onClick).background(CircleBg, CircleShape), Alignment.Center) { content() }

// ---------------- Filters bottom sheet ----------------
@Composable
fun FiltersSheet(vm: MagnifyState, modifier: Modifier) {
    LaunchedEffect(vm.sheetOpen) {
        if (vm.sheetOpen) {
            val src = vm.photo ?: vm.previewView?.bitmap
            vm.thumb = src?.let { squareThumb(it) }
        }
    }
    AnimatedVisibility(vm.sheetOpen, modifier, enter = slideInVertically(tween(300)) { it } + fadeIn(), exit = slideOutVertically(tween(250)) { it } + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)
                .plainClick {}.padding(horizontal = 25.dp).padding(bottom = 14.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(40.dp).pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 10f) vm.sheetOpen = false } }, Alignment.Center) {
                Box(Modifier.width(33.dp).height(4.dp).clip(CircleShape).background(Color(0xFFC4C7C5)))
            }
            Text(listOf("Filters", "Contrast", "Brightness")[vm.sheetTab], color = IconCol, fontSize = 26.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

            Row(Modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(TrackBg)) {
                for (t in 0..2) {
                    val sel = vm.sheetTab == t
                    val tint = if (sel) OnAccent else IconCol
                    Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (sel) Accent else Color.Transparent)
                        .tap { vm.sheetTab = t }, Alignment.Center) {
                        when (t) {
                            0 -> Canvas(Modifier.size(26.dp)) {
                                val w = size.width
                                drawCircle(tint, w * 0.28f, Offset(w * 0.36f, w * 0.64f), style = Stroke(3f))
                                drawCircle(tint, w * 0.30f, Offset(w * 0.62f, w * 0.38f))
                            }
                            1 -> Canvas(Modifier.size(26.dp)) {
                                val w = size.width
                                drawCircle(tint, w * 0.42f, center, style = Stroke(3f))
                                drawArc(tint, -90f, 180f, true, Offset(center.x - w * 0.42f, center.y - w * 0.42f), Size(w * 0.84f, w * 0.84f))
                            }
                            else -> Icon(Icons.Filled.BrightnessMedium, "Brightness", tint = tint, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Box(Modifier.fillMaxWidth().height(70.dp), Alignment.CenterStart) {
                when (vm.sheetTab) {
                    0 -> LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(filterNames) { i, _ -> FilterThumb(i, vm) }
                    }
                    1 -> SliderRow(vm.contrast, 0.5f..2.5f, "${(vm.contrast * 100).roundToInt()}%") { vm.contrast = it }
                    else -> {
                        val v = if (vm.photo != null) vm.brightness else vm.exposure
                        SliderRow(v, 0f..1f, "${(v * 100).roundToInt()}%") { vm.setBrightness(it) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF3C3C3E)))
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).tap { vm.sheetOpen = false }.background(Accent, CircleShape), Alignment.Center) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Collapse", tint = OnAccent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("More settings", color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.tap { vm.settingsOpen = true }.clip(CircleShape).border(1.5.dp, Color(0xFF8AB4F8), CircleShape).padding(horizontal = 24.dp, vertical = 15.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun SliderRow(value: Float, range: ClosedFloatingPointRange<Float>, label: String, onChange: (Float) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value, { v ->
                if ((v * 10).roundToInt() != (value * 10).roundToInt()) Haptics.tick(view)
                onChange(v)
            }, Modifier.weight(1f), valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = TrackBg)
        )
        Text(label, color = IconCol, fontSize = 15.sp, modifier = Modifier.width(52.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
fun FilterThumb(i: Int, vm: MagnifyState) {
    val selected = vm.filter == i
    val m = finalMatrix(i, 1f, 0f)
    Box(
        Modifier.size(70.dp).tap { vm.filter = i }
            .then(if (selected) Modifier.border(2.dp, Accent, RoundedCornerShape(20.dp)) else Modifier)
            .padding(5.dp).clip(RoundedCornerShape(15.dp)),
        Alignment.Center
    ) {
        val t = vm.thumb
        if (selected && i == 0 || t == null) Box(Modifier.fillMaxSize().background(Color(0xFFC9C9C9)))
        else Image(t.asImageBitmap(), filterNames[i], Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            colorFilter = m?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
        if (selected) Box(Modifier.size(36.dp).background(OnAccent, CircleShape), Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

// ---------------- More settings ----------------
@Composable
fun SettingsSheet(vm: MagnifyState, modifier: Modifier) {
    AnimatedVisibility(vm.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f)).plainClick { vm.settingsOpen = false })
    }
    AnimatedVisibility(vm.settingsOpen, modifier, enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(250)) { it }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg)
                .plainClick {}.padding(horizontal = 25.dp).padding(bottom = 18.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(40.dp).pointerInput(Unit) { detectVerticalDragGestures { _, d -> if (d > 10f) vm.settingsOpen = false } }, Alignment.Center) {
                Box(Modifier.width(33.dp).height(4.dp).clip(CircleShape).background(Color(0xFFC4C7C5)))
            }
            Text("More settings", color = IconCol, fontSize = 26.sp, modifier = Modifier.padding(bottom = 10.dp))
            SettingRow("Auto brightness", "Brighten automatically in low light", vm.autoBrightness) { vm.autoBrightness = it }
            SettingRow("Haptic feedback", "Vibrate on taps, zoom steps and results", vm.hapticsOn) { vm.hapticsOn = it }
            SettingRow("Save photos", "Store captured photos in Pictures/Magnify", vm.savePhotos) { vm.savePhotos = it }
            SettingRow("Keep screen on", "Prevent the screen from sleeping", vm.keepOn) { vm.keepOn = it }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.size(44.dp).tap { vm.settingsOpen = false }.background(Accent, CircleShape), Alignment.Center) {
                Icon(Icons.Filled.KeyboardArrowDown, "Close", tint = OnAccent, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun SettingRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().tap { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = IconCol, fontSize = 18.sp, fontWeight = FontWeight.Normal)
            Text(sub, color = Color(0xFF9AA0A6), fontSize = 13.sp)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = Accent, checkedThumbColor = OnAccent,
            uncheckedTrackColor = TrackBg, uncheckedThumbColor = Color(0xFF9AA0A6), uncheckedBorderColor = Color.Transparent))
    }
}
