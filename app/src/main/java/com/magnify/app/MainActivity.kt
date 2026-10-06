package com.magnify.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val Orange = Color(0xFFEF5B25)
private val Blue = Color(0xFF1E4FD8)
private val GlassFill = Color.White.copy(alpha = 0.18f)
private val GlassBorder = Color.White.copy(alpha = 0.35f)
private val Gray = Color(0xFFEDEDEF)

class MainActivity : ComponentActivity() {
    private val vm = MagnifyState()
    private var granted by mutableStateOf(false)
    private val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!granted) req.launch(Manifest.permission.CAMERA)
        setContent { MagnifyApp(vm, granted) { req.launch(Manifest.permission.CAMERA) } }
    }
}

@Composable
fun MagnifyApp(vm: MagnifyState, granted: Boolean, requestPermission: () -> Unit) {
    val ctx = LocalContext.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val capture = { takePhoto(ctx, vm, exec) }
    val share = {
        vm.lastUri?.let {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, it); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share photo"))
        }; Unit
    }
    BackHandler(enabled = vm.photo != null) { vm.closePhoto() }

    val panelW by animateFloatAsState(if (vm.panelOpen) 0.9f else 0.0001f, tween(450), label = "pw")
    val panelA by animateFloatAsState(if (vm.panelOpen) 1f else 0f, tween(350), label = "pa")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFB9C7F5), Color(0xFF7F9BE0), Color(0xFF4B67B8), Color(0xFF2A3F86), Color(0xFF1A2B6B))))
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Color.White.copy(0.95f), Color.Transparent),
                    center = Offset(0f, size.height * 0.18f), radius = size.width * 0.9f))
            }
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Toolbar(vm, capture, share)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (panelA > 0.01f) {
                    GlassPanel(vm, capture, Modifier.weight(panelW).fillMaxHeight().alpha(panelA))
                    Spacer(Modifier.width(10.dp * panelA))
                }
                Grid(vm, granted, requestPermission, Modifier.weight(2.55f).fillMaxHeight())
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(0.7f)))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(60.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(0.55f)))
            }
        }
    }
}

// ---------------- Toolbar ----------------
@Composable
fun Toolbar(vm: MagnifyState, capture: () -> Unit, share: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassGroup {
            GlassBtn({ vm.panelOpen = !vm.panelOpen }) { SidebarIcon() }
            GlassBtn({ vm.closePhoto() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
            GlassBtn({ vm.openLastPhoto() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Row(
            Modifier.weight(1f).height(36.dp).clip(CircleShape).background(GlassFill).border(1.dp, GlassBorder, CircleShape).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AA", color = Color.White, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(10.dp))
            Text(" Magnify", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Refresh, null, tint = Color.White, modifier = Modifier.size(14.dp).tap { vm.setZ(1f) })
        }
        GlassGroup {
            GlassBtn(share) { Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
            GlassBtn(capture) { Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            GlassBtn({ vm.effect = (vm.effect + 1) % effectNames.size }) { TabsIcon() }
        }
    }
}

@Composable fun GlassGroup(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.clip(CircleShape).background(GlassFill).border(1.dp, GlassBorder, CircleShape).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable fun GlassBtn(onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.16f)).tap(onClick), contentAlignment = Alignment.Center) { content() }

@Composable fun SidebarIcon() = Canvas(Modifier.size(15.dp)) {
    drawRoundRect(Color.White, style = Stroke(2f), cornerRadius = CornerRadius(4f))
    drawRect(Color.White, Offset(size.width * 0.12f, size.height * 0.12f), Size(size.width * 0.26f, size.height * 0.76f))
}
@Composable fun TabsIcon() = Canvas(Modifier.size(15.dp)) {
    drawRoundRect(Color.White, Offset(size.width * .28f, 0f), Size(size.width * .72f, size.height * .72f), CornerRadius(4f), Stroke(2f))
    drawRoundRect(Color.White, Offset(0f, size.height * .28f), Size(size.width * .72f, size.height * .72f), CornerRadius(4f), Stroke(2f))
}

fun Modifier.tap(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

// ---------------- Left glass panel ----------------
@Composable
fun GlassPanel(vm: MagnifyState, capture: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(36.dp)
    Column(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(0.22f), Color.White.copy(0.05f))))
            .border(1.dp, GlassBorder, shape).padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.Center, Alignment.CenterVertically) {
            Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(8.dp))
            Text("Close", color = Color.Black, fontSize = 10.sp,
                modifier = Modifier.clip(CircleShape).background(Color.White).tap { (ctx as Activity).finishAffinity() }.padding(horizontal = 14.dp, vertical = 3.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(3.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Section("Camera", listOf("Live zoom" to { vm.closePhoto() }, "Take photo" to capture, "Flashlight" to { vm.toggleTorch() }))
            Section("Find text", listOf("Search words" to {}, "Clear search" to { vm.query = "" }))
            Section("Made for", listOf("Menus" to {}, "Street signs" to {}, "Departure boards" to {}, "Labels" to {}))
        }
        Row(Modifier.fillMaxWidth().tap(capture), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(30.dp).clip(CircleShape).background(Color.White)) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF0A55D6), Color(0xFF1A9BFF), Color(0xFF7FD8FF))), radius = size.minDimension * 0.34f)
            }
            Spacer(Modifier.width(8.dp))
            Text("Take photo", color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
fun Section(title: String, items: List<Pair<String, () -> Unit>>) {
    Text(title, color = Color.White.copy(0.55f), fontSize = 9.sp)
    Spacer(Modifier.height(4.dp))
    items.forEach { (t, a) -> Text(t, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp).tap(a)) }
    Spacer(Modifier.height(16.dp))
}

// ---------------- Grid of white cards ----------------
@Composable
fun Grid(vm: MagnifyState, granted: Boolean, request: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1.3f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CameraCard(vm, granted, request, Modifier.weight(1.55f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrightnessCard(vm, Modifier.weight(1f).fillMaxWidth())
                ZoomRulerCard(vm, Modifier.weight(1f).fillMaxWidth())
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SearchCard(vm, Modifier.weight(1.55f).fillMaxHeight())
            EffectsCard(vm, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
fun WhiteCard(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Box(modifier.shadow(10.dp, shape, ambientColor = Color(0xFF1A2B6B), spotColor = Color(0xFF1A2B6B)).clip(shape).background(Color.White), content = content)
}

@Composable
fun CameraCard(vm: MagnifyState, granted: Boolean, request: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val pv = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    LaunchedEffect(granted) { if (granted) startCamera(ctx, owner, pv, vm, exec) }

    WhiteCard(modifier) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(34.dp).clip(CircleShape).background(Color(0xFFF4F4F6)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("−", fontSize = 20.sp, modifier = Modifier.tap { vm.setZ(vm.z / 1.25f) })
                Spacer(Modifier.weight(1f))
                Text("%.1fx".format(vm.z), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("+", fontSize = 20.sp, modifier = Modifier.tap { vm.setZ(vm.z * 1.25f) })
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFFEDEDEF))) {
                if (!granted) {
                    Text("Tap to allow camera", Modifier.align(Alignment.Center).tap(request), fontSize = 12.sp, color = Color.DarkGray)
                } else {
                    AndroidView({ pv }, Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f && vm.photo == null) vm.setZ(vm.zoom * zoom) }
                    }, update = {
                        val m = effectMatrix(vm.effect)
                        if (m == null) it.setLayerType(View.LAYER_TYPE_NONE, null)
                        else it.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(m)) })
                    })
                }
                ZoomRings(vm.zoomFraction)
                vm.photo?.let { PhotoViewer(vm) }
            }
        }
    }
}

@Composable
fun ZoomRings(fraction: Float) {
    val f by animateFloatAsState(fraction, label = "ring")
    Canvas(Modifier.fillMaxSize()) {
        val base = min(size.width, size.height) * 0.11f
        for (k in 0..2) {
            val r = base * (1.7f + k * 0.75f)
            val tl = Offset(center.x - r, center.y - r)
            drawArc(Color.White.copy(0.22f), -90f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(9f, cap = StrokeCap.Round))
            drawArc(Color.White.copy(0.85f), -90f, 360f * (f * (1f - k * 0.12f)).coerceIn(0.02f, 1f), false, tl, Size(r * 2, r * 2), style = Stroke(9f, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun PhotoViewer(vm: MagnifyState) {
    val bmp = vm.photo ?: return
    val m = effectMatrix(vm.effect)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
            colorFilter = m?.let { ColorFilter.colorMatrix(ColorMatrix(it)) },
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { vm.setZ(if (vm.photoScale > 1.5f) 1f else 4f); vm.photoOff = Offset.Zero }) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        vm.setZ(vm.photoScale * zoom)
                        vm.photoOff = if (vm.photoScale <= 1f) Offset.Zero else vm.photoOff + pan
                    }
                }
                .graphicsLayer(scaleX = vm.photoScale, scaleY = vm.photoScale, translationX = vm.photoOff.x, translationY = vm.photoOff.y)
        )
        ZoomRings(vm.zoomFraction)
    }
}

@Composable
fun BrightnessCard(vm: MagnifyState, modifier: Modifier) {
    val e by animateFloatAsState(vm.exposure, tween(600), label = "exp")
    WhiteCard(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Gray)) {
                Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val w = size.width; val h = size.height
                    fun y(t: Float) = h / 2f - sin(t * 2f * PI.toFloat()) * h * 0.28f
                    val p = Path()
                    for (i in 0..60) { val t = i / 60f; if (i == 0) p.moveTo(0f, y(0f)) else p.lineTo(w * t, y(t)) }
                    drawPath(p, Blue, style = Stroke(4f, cap = StrokeCap.Round))
                    drawCircle(Blue, 9f, Offset(w * e, y(e)))
                    drawCircle(Color.White, 4f, Offset(w * e, y(e)))
                }
            }
            Text("Auto light", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun ZoomRulerCard(vm: MagnifyState, modifier: Modifier) {
    val v = (vm.z * 10f).roundToInt()
    WhiteCard(modifier) {
        Column(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }) { _, d ->
                    acc += d; val s = (acc / 30f).toInt()
                    if (s != 0) { acc -= s * 30f; vm.setZ(vm.z - s * 0.5f) }
                }
            },
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Text("${maxOf(v - 5, 10)}", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.tap { vm.setZ(vm.z - 0.5f) })
                Box(Modifier.width(1.dp).height(18.dp).background(Color.LightGray))
                Text("$v", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Orange)
                Box(Modifier.width(1.dp).height(18.dp).background(Color.LightGray))
                Text("${v + 5}", fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.tap { vm.setZ(vm.z + 0.5f) })
            }
            Text("zoom ×10", fontSize = 8.sp, color = Orange)
        }
    }
}

@Composable
fun SearchCard(vm: MagnifyState, modifier: Modifier) {
    WhiteCard(modifier) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text("Find text", fontSize = 9.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(12.dp)).background(Gray).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (vm.query.isEmpty()) Text("Search a menu, sign or board", fontSize = 11.sp, color = Color.Gray, maxLines = 1)
                BasicTextField(vm.query, { vm.query = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.Black), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            val q = vm.query.trim()
            if (q.isEmpty()) {
                Text("Type a word to find it live in the camera view or in a photo.", fontSize = 11.sp, color = Color.Gray)
            } else {
                val hits = vm.lines.filter { it.contains(q, ignoreCase = true) }
                if (hits.isEmpty()) Text(if (vm.photo != null) "No match in photo" else "Searching…", fontSize = 11.sp, color = Color.Gray)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(hits) { line ->
                        Text(buildAnnotatedString {
                            var i = 0; val l = line.lowercase(); val k = q.lowercase()
                            while (i < line.length) {
                                val j = l.indexOf(k, i)
                                if (j < 0) { append(line.substring(i)); break }
                                append(line.substring(i, j))
                                withStyle(SpanStyle(background = Color(0xFFFFE066), fontWeight = FontWeight.Bold)) { append(line.substring(j, minOf(j + k.length, line.length))) }
                                i = j + k.length
                            }
                        }, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EffectsCard(vm: MagnifyState, modifier: Modifier) {
    WhiteCard(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("Effects", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Gray).verticalScroll(rememberScrollState()).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                effectNames.forEachIndexed { i, n ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).tap { vm.effect = i }.padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (vm.effect == i) Orange else Color.Transparent))
                        Spacer(Modifier.width(5.dp))
                        Text(n, fontSize = 10.sp, fontWeight = if (vm.effect == i) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}
