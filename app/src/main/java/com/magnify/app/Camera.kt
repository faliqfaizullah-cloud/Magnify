package com.magnify.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import kotlin.math.min
import kotlin.math.roundToInt

data class OcrWord(val text: String, val box: Rect)
data class OcrLine(val text: String, val box: Rect, val words: List<OcrWord>)

/** Everything the UI reads lives here. */
class MagnifyState {
    var zoom by mutableFloatStateOf(1f)
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(10f)

    var exposure by mutableFloatStateOf(0.5f)       // live exposure 0..1
    var brightness by mutableFloatStateOf(0.5f)     // photo brightness 0..1
    var autoBrightness by mutableStateOf(true)
    var filter by mutableIntStateOf(0)
    var contrast by mutableFloatStateOf(1f)

    var query by mutableStateOf("")
    var ocr by mutableStateOf(listOf<OcrLine>())
    var ocrW by mutableIntStateOf(0)
    var ocrH by mutableIntStateOf(0)

    var photo by mutableStateOf<Bitmap?>(null)
    var photoScale by mutableFloatStateOf(1f)
    var photoOff by mutableStateOf(Offset.Zero)

    var torch by mutableStateOf(false)
    var front by mutableStateOf(false)

    var sheetOpen by mutableStateOf(false)
    var sheetTab by mutableIntStateOf(0)
    var settingsOpen by mutableStateOf(false)
    var hapticsOn by mutableStateOf(true)
    var savePhotos by mutableStateOf(true)
    var keepOn by mutableStateOf(false)
    var thumb by mutableStateOf<Bitmap?>(null)

    var lastPhoto: Bitmap? = null
    var lastUri: Uri? = null
    var camera: Camera? = null
    var imageCapture: ImageCapture? = null
    var view: View? = null
    var previewView: PreviewView? = null

    val z: Float get() = if (photo != null) photoScale else zoom

    fun setZ(v: Float) {
        val old = z
        val inPhoto = photo != null
        val lo = if (inPhoto) 1f else minZoom
        val hi = if (inPhoto) 30f else maxZoom
        val c = v.coerceIn(lo, hi)
        if (inPhoto) photoScale = c else { zoom = c; camera?.cameraControl?.setZoomRatio(c) }
        if (c != old) {
            if (v < lo || v > hi) Haptics.limit(view)
            else if ((old * 2).toInt() != (c * 2).toInt()) Haptics.tick(view)
        }
    }

    fun applyBrightness(f: Float) {
        autoBrightness = false
        if (photo != null) { brightness = f; return }
        exposure = f
        val cam = camera ?: return
        val r = cam.cameraInfo.exposureState.exposureCompensationRange
        if (r.upper != r.lower) cam.cameraControl.setExposureCompensationIndex((r.lower + f * (r.upper - r.lower)).roundToInt())
    }

    fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        torch = !torch
        cam.cameraControl.enableTorch(torch)
    }

    fun flip() { front = !front; torch = false }

    fun closePhoto() { photo = null; ocr = emptyList(); photoScale = 1f; photoOff = Offset.Zero }
    fun openBitmap(b: Bitmap) {
        lastPhoto = b; photo = b; photoScale = 1f; photoOff = Offset.Zero; ocr = emptyList()
        runOcr(b, this)
    }

    /** Boxes (in OCR image coordinates) for the words/lines that match the search text. */
    fun matches(): List<Rect> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Rect>()
        for (l in ocr) {
            if (!l.text.contains(q, ignoreCase = true)) continue
            if (!q.contains(' ')) {
                val w = l.words.filter { it.text.contains(q, ignoreCase = true) }
                if (w.isNotEmpty()) { w.forEach { out += it.box }; continue }
            }
            out += l.box
        }
        return out
    }
}

// ---------- Filters ----------
val filterNames = listOf("None", "Navy on white", "Black on white", "Grayscale", "Black on yellow", "Yellow on black", "White on black", "Inverted")

private fun duo(dr: Int, dg: Int, db: Int, lr: Int, lg: Int, lb: Int, c: Float, invert: Boolean): FloatArray {
    val lum = floatArrayOf(.299f, .587f, .114f)
    val dark = intArrayOf(dr, dg, db); val light = intArrayOf(lr, lg, lb)
    val out = FloatArray(20)
    val t = (0.5f - 0.5f * c) * 255f
    val sign = if (invert) -1f else 1f
    for (k in 0..2) {
        val m = (light[k] - dark[k]) / 255f
        for (j in 0..2) out[k * 5 + j] = sign * m * c * lum[j]
        out[k * 5 + 4] = if (invert) dark[k] + m * 255f - m * t else dark[k] + m * t
    }
    out[18] = 1f
    return out
}

private fun filterMatrix(i: Int): FloatArray? = when (i) {
    1 -> duo(30, 70, 140, 255, 255, 255, 1.4f, false)
    2 -> duo(0, 0, 0, 255, 255, 255, 1.9f, false)
    3 -> duo(0, 0, 0, 255, 255, 255, 1.0f, false)
    4 -> duo(0, 0, 0, 255, 255, 0, 1.6f, false)
    5 -> duo(0, 0, 0, 255, 255, 0, 1.6f, true)
    6 -> duo(0, 0, 0, 255, 255, 255, 1.6f, true)
    7 -> floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)
    else -> null
}

/** Filter + contrast (+ brightness offset for photos) combined into one 4x5 colour matrix. */
fun finalMatrix(filter: Int, contrast: Float, brightnessOffset: Float): FloatArray? {
    if (filter == 0 && contrast == 1f && brightnessOffset == 0f) return null
    val m = ColorMatrix(filterMatrix(filter) ?: ColorMatrix().array)
    if (contrast != 1f) {
        val c = contrast; val t = (-0.5f * c + 0.5f) * 255f
        m.postConcat(ColorMatrix(floatArrayOf(c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f)))
    }
    if (brightnessOffset != 0f) {
        val b = brightnessOffset
        m.postConcat(ColorMatrix(floatArrayOf(1f,0f,0f,0f,b, 0f,1f,0f,0f,b, 0f,0f,1f,0f,b, 0f,0f,0f,1f,0f)))
    }
    return m.array
}

fun photoBrightnessOffset(vm: MagnifyState) = (vm.brightness - 0.5f) * 2f * 80f

fun squareThumb(b: Bitmap, size: Int = 160): Bitmap {
    val s = min(b.width, b.height)
    val sq = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
    return Bitmap.createScaledBitmap(sq, size, size, true)
}

// ---------- Camera ----------
fun startCamera(ctx: Context, owner: LifecycleOwner, pv: PreviewView, vm: MagnifyState, exec: ExecutorService) {
    val future = ProcessCameraProvider.getInstance(ctx)
    future.addListener({
        val provider = future.get()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(exec, FrameAnalyzer(vm))
        provider.unbindAll()
        val sel = if (vm.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val cam = try { provider.bindToLifecycle(owner, sel, preview, capture, analysis) } catch (e: Exception) { return@addListener }
        vm.camera = cam
        vm.imageCapture = capture
        cam.cameraInfo.zoomState.value?.let { vm.minZoom = it.minZoomRatio; vm.maxZoom = it.maxZoomRatio }
        vm.zoom = vm.zoom.coerceIn(vm.minZoom, vm.maxZoom)
        cam.cameraControl.setZoomRatio(vm.zoom)
    }, ContextCompat.getMainExecutor(ctx))
}

private fun toLines(t: com.google.mlkit.vision.text.Text): List<OcrLine> =
    t.textBlocks.flatMap { it.lines }.mapNotNull { l ->
        val b = l.boundingBox ?: return@mapNotNull null
        OcrLine(l.text, b, l.elements.mapNotNull { e -> e.boundingBox?.let { OcrWord(e.text, it) } })
    }

fun runOcr(bmp: Bitmap, vm: MagnifyState) {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(InputImage.fromBitmap(bmp, 0))
        .addOnSuccessListener { t -> vm.ocrW = bmp.width; vm.ocrH = bmp.height; vm.ocr = toLines(t) }
}

/** Auto-brightness (low light) + live text search. */
class FrameAnalyzer(private val vm: MagnifyState) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var busy = false
    private var lastOcr = 0L
    private var lastExp = 0L
    private var luma = 120.0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(img: ImageProxy) {
        val now = System.currentTimeMillis()
        val buf = img.planes[0].buffer
        var sum = 0L; var n = 0; var i = 0
        while (i < buf.limit()) { sum += buf.get(i).toInt() and 0xFF; n++; i += 997 }
        luma = luma * 0.85 + (sum.toDouble() / maxOf(n, 1)) * 0.15

        if (vm.autoBrightness && now - lastExp > 500) { lastExp = now; autoExposure() }

        val media = img.image
        if (vm.query.isNotBlank() && vm.photo == null && !busy && now - lastOcr > 700 && media != null) {
            busy = true; lastOcr = now
            val rot = img.imageInfo.rotationDegrees
            val upW = if (rot == 90 || rot == 270) img.height else img.width
            val upH = if (rot == 90 || rot == 270) img.width else img.height
            recognizer.process(InputImage.fromMediaImage(media, rot))
                .addOnSuccessListener { t -> vm.ocrW = upW; vm.ocrH = upH; vm.ocr = toLines(t) }
                .addOnCompleteListener { busy = false; img.close() }
        } else img.close()
    }

    private fun autoExposure() {
        val cam = vm.camera ?: return
        val st = cam.cameraInfo.exposureState
        val r = st.exposureCompensationRange
        if (r.upper == r.lower) return
        var idx = st.exposureCompensationIndex
        if (luma < 70 && idx < r.upper) idx++ else if (luma > 175 && idx > r.lower) idx--
        if (idx != st.exposureCompensationIndex) cam.cameraControl.setExposureCompensationIndex(idx)
        vm.exposure = (idx - r.lower).toFloat() / (r.upper - r.lower)
    }
}

// ---------- Photo capture / save / pick ----------
fun takePhoto(ctx: Context, vm: MagnifyState, exec: ExecutorService) {
    vm.imageCapture?.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees.toFloat()
            image.close()
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot) }, true)
            if (vm.savePhotos) save(ctx, vm, bmp)
            Handler(Looper.getMainLooper()).post { vm.openBitmap(bmp) }
        }
        override fun onError(exception: ImageCaptureException) {}
    })
}

private fun save(ctx: Context, vm: MagnifyState, bmp: Bitmap) {
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Magnify_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Magnify")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    vm.lastUri = uri
}

/** Opens a photo chosen in the system photo picker. */
fun loadPicked(ctx: Context, vm: MagnifyState, uri: Uri) {
    Thread {
        try {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val s = maxOf(info.size.width, info.size.height)
                if (s > 4096) { val f = 4096f / s; dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt()) }
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            vm.lastUri = uri
            Handler(Looper.getMainLooper()).post { vm.openBitmap(bmp) }
        } catch (_: Exception) {}
    }.start()
}
