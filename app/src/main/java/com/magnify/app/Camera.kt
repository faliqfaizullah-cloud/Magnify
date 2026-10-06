package com.magnify.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.camera.core.*
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

/** Everything the UI reads lives here. */
class MagnifyState {
    var zoom by mutableFloatStateOf(1f)
    var minZoom by mutableFloatStateOf(1f)
    var maxZoom by mutableFloatStateOf(10f)
    var exposure by mutableFloatStateOf(0.5f)       // 0..1, driven by auto-brightness
    var effect by mutableIntStateOf(0)
    var query by mutableStateOf("")
    var lines by mutableStateOf(listOf<String>())
    var photo by mutableStateOf<Bitmap?>(null)
    var photoScale by mutableFloatStateOf(1f)
    var photoOff by mutableStateOf(Offset.Zero)
    var torch by mutableStateOf(false)
    var panelOpen by mutableStateOf(true)
    var lastPhoto: Bitmap? = null
    var lastUri: Uri? = null
    var camera: Camera? = null
    var imageCapture: ImageCapture? = null

    val z: Float get() = if (photo != null) photoScale else zoom

    fun setZ(v: Float) {
        if (photo != null) photoScale = v.coerceIn(1f, 30f)
        else {
            val c = v.coerceIn(minZoom, maxZoom)
            zoom = c
            camera?.cameraControl?.setZoomRatio(c)
        }
    }
    val zoomFraction: Float
        get() = if (photo != null) (photoScale - 1f) / 29f
        else ((zoom - minZoom) / (maxZoom - minZoom).coerceAtLeast(0.01f))

    fun closePhoto() { photo = null; lines = emptyList(); photoScale = 1f; photoOff = Offset.Zero }
    fun openLastPhoto() { lastPhoto?.let { photo = it; photoScale = 1f; photoOff = Offset.Zero } }
    fun toggleTorch() { torch = !torch; camera?.cameraControl?.enableTorch(torch) }
}

// ---------- Visual effects for low-contrast text ----------
val effectNames = listOf("Normal", "High contrast", "Invert", "Yellow on black", "Grayscale")

private fun contrast(c: Float): FloatArray {
    val t = (-0.5f * c + 0.5f) * 255f
    return floatArrayOf(c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f)
}
fun effectMatrix(i: Int): FloatArray? = when (i) {
    1 -> contrast(1.9f)
    2 -> floatArrayOf(-1f,0f,0f,0f,255f, 0f,-1f,0f,0f,255f, 0f,0f,-1f,0f,255f, 0f,0f,0f,1f,0f)
    3 -> { val c = 2.2f; val t = (-0.5f * c + 0.5f) * 255f
        val r = floatArrayOf(.299f * c, .587f * c, .114f * c, 0f, t)
        floatArrayOf(*r, *r, 0f,0f,0f,0f,0f, 0f,0f,0f,1f,0f) }
    4 -> { val r = floatArrayOf(.299f * 1.6f, .587f * 1.6f, .114f * 1.6f, 0f, -0.3f * 255f)
        floatArrayOf(*r, *r, *r, 0f,0f,0f,1f,0f) }
    else -> null
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
        val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        vm.camera = cam
        vm.imageCapture = capture
        cam.cameraInfo.zoomState.value?.let { vm.minZoom = it.minZoomRatio; vm.maxZoom = it.maxZoomRatio }
        cam.cameraControl.setZoomRatio(vm.zoom.coerceIn(vm.minZoom, vm.maxZoom))
    }, ContextCompat.getMainExecutor(ctx))
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

        if (now - lastExp > 500) { lastExp = now; autoExposure() }

        val media = img.image
        if (vm.query.isNotBlank() && vm.photo == null && !busy && now - lastOcr > 900 && media != null) {
            busy = true; lastOcr = now
            recognizer.process(InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees))
                .addOnSuccessListener { t -> vm.lines = t.textBlocks.flatMap { it.lines }.map { it.text } }
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

// ---------- Photo capture / save / OCR ----------
fun takePhoto(ctx: Context, vm: MagnifyState, exec: ExecutorService) {
    vm.imageCapture?.takePicture(exec, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees.toFloat()
            image.close()
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot) }, true)
            save(ctx, vm, bmp)
            Handler(Looper.getMainLooper()).post {
                vm.lastPhoto = bmp; vm.photo = bmp; vm.photoScale = 1f; vm.photoOff = Offset.Zero; vm.lines = emptyList()
            }
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t -> vm.lines = t.textBlocks.flatMap { it.lines }.map { it.text } }
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
