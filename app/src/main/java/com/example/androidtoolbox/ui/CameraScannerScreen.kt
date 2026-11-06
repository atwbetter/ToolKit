package com.example.androidtoolbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CanvasSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.androidtoolbox.core.CameraQrAnalyzer
import com.example.androidtoolbox.core.CodeResult
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun CameraScannerScreen(
    otpOnly: Boolean, close: () -> Unit, onImage: () -> Unit, onManual: () -> Unit,
    onScan: (CodeResult) -> String?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    var hasFlash by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var maxZoom by remember { mutableFloatStateOf(1f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    LaunchedEffect(Unit) {
        if (!permission && !requested) { requested = true; launcher.launch(Manifest.permission.CAMERA) }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = hasPermission()
            if (event == Lifecycle.Event.ON_PAUSE) torch = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFADB2FF))) {
        Surface(color = Color.Black) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(close) { Text("关闭") }
                    Text(if (otpOnly) "扫描 2FA 二维码" else "扫描二维码 / 条形码", color = Color.White,
                        modifier = Modifier.weight(1f), fontSize = 18.sp)
                    Text("本机识别", color = Color(0xFFA8DCC7), fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (permission && !cameraError) {
                        CameraPreview(Modifier.fillMaxSize(), torch, zoom, scanError == null, retryToken, otpOnly,
                            onReady = { flash, maximum -> hasFlash = flash; maxZoom = maximum; zoom = zoom.coerceIn(1f, maximum) },
                            onError = { cameraError = true; hasFlash = false; maxZoom = 1f },
                            onResult = { scanError = onScan(it) })
                        ScanGuide(Modifier.fillMaxSize())
                        Text(if (otpOnly) "将二维码放入框内 · 轻点画面对焦" else "将码图完整放入框内 · 条形码可横向或纵向", color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp).background(Color.Black.copy(alpha = 0.5f)).padding(8.dp))
                    } else {
                        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (!permission) "允许相机权限后即可扫码" else "暂时无法打开相机", color = Color.White)
                            Text(if (!permission) { if (otpOnly) "也可以选择二维码图片，或手动输入密钥。" else "也可以选择二维码或条形码图片。" } else "请检查相机是否被其他应用占用，也可改用图片导入。",
                                color = Color.LightGray, fontSize = 13.sp)
                            Button(onClick = { if (!permission) launcher.launch(Manifest.permission.CAMERA) else cameraError = false }) {
                                Text(if (!permission) "授予相机权限" else "重试相机")
                            }
                            if (!permission) TextButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                            }) { Text("打开应用设置") }
                        }
                    }
                    if (scanError != null) Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(scanError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                            Button(onClick = { scanError = null; retryToken++ }) { Text("继续扫描") }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (permission && !cameraError && maxZoom > 1f) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("缩放 ${"%.1f".format(zoom)}×", color = Color.White, fontSize = 12.sp)
                            Slider(zoom, { zoom = it }, valueRange = 1f..maxZoom, modifier = Modifier.weight(1f).padding(start = 16.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onImage, modifier = Modifier.weight(1f)) { Text("选择图片") }
                        if (otpOnly) TextButton(onManual, modifier = Modifier.weight(1f)) { Text("手动输入") }
                        if (permission && !cameraError) TextButton(onClick = { torch = !torch }, enabled = hasFlash, modifier = Modifier.weight(1f)) {
                            Text(if (torch) "关闭补光灯" else "开启补光灯")
                        }
                    }
                    Text(if (otpOnly) "支持普通 TOTP 与 Google 批量迁移二维码" else "无需联网，自动识别码制并返回工具页面",
                        color = Color.LightGray, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ScanGuide(modifier: Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.76f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2
        val shade = Color.Black.copy(alpha = 0.45f)
        drawRect(shade, size = CanvasSize(size.width, top))
        drawRect(shade, Offset(0f, top + side), CanvasSize(size.width, size.height - top - side))
        drawRect(shade, Offset(0f, top), CanvasSize(left, side))
        drawRect(shade, Offset(left + side, top), CanvasSize(left, side))
        drawRoundRect(Color.White, Offset(left, top), CanvasSize(side, side), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier, torch: Boolean, zoom: Float, scanEnabled: Boolean, retryToken: Int, qrOnly: Boolean,
    onReady: (Boolean, Float) -> Unit, onError: () -> Unit, onResult: (CodeResult) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val readyCallback by rememberUpdatedState(onReady)
    val errorCallback by rememberUpdatedState(onError)
    val resultCallback by rememberUpdatedState(onResult)
    val enabled by rememberUpdatedState(scanEnabled)
    val attached = remember { AtomicBoolean(false) }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val view = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val analyzer = remember(context, lifecycleOwner, qrOnly) {
        CameraQrAnalyzer(qrOnly = qrOnly) { result ->
            mainExecutor.execute {
                if (attached.get() && enabled && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resultCallback(result)
            }
        }
    }
    SideEffect { analyzer.active = resumed && scanEnabled }
    LaunchedEffect(retryToken) { analyzer.retry() }
    DisposableEffect(context, lifecycleOwner, view) {
        attached.set(true)
        val bindingActive = AtomicBoolean(true)
        val executor = Executors.newSingleThreadExecutor()
        val preview = Preview.Builder().build().apply { surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0).build()
        analysis.setAnalyzer(executor, analyzer)
        var provider: ProcessCameraProvider? = null
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            analyzer.active = resumed && enabled
            if (event == Lifecycle.Event.ON_RESUME) analyzer.retry()
            if (event == Lifecycle.Event.ON_PAUSE) camera?.cameraControl?.enableTorch(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        runCatching {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (bindingActive.get()) runCatching {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        errorCallback()
                        return@addListener
                    }
                    val currentProvider = future.get()
                    provider = currentProvider
                    val selector = if (currentProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA
                        else CameraSelector.DEFAULT_FRONT_CAMERA
                    camera = currentProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    val info = camera!!.cameraInfo
                    readyCallback(info.hasFlashUnit(), (info.zoomState.value?.maxZoomRatio ?: 1f).coerceIn(1f, 5f))
                }.onFailure { errorCallback() }
            }, mainExecutor)
        }.onFailure { errorCallback() }
        view.setOnTouchListener { touched, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focus(camera, view, event.x, event.y)
                touched.performClick()
            }
            true
        }
        onDispose {
            bindingActive.set(false); attached.set(false); analyzer.active = false
            lifecycleOwner.lifecycle.removeObserver(observer)
            analysis.clearAnalyzer()
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(preview, analysis)
            camera = null
            view.setOnTouchListener(null)
            executor.shutdown()
        }
    }
    LaunchedEffect(camera, torch, resumed) {
        camera?.let { current -> runCatching { current.cameraControl.enableTorch(torch && resumed && current.cameraInfo.hasFlashUnit()) } }
    }
    LaunchedEffect(camera, zoom) { camera?.cameraControl?.setZoomRatio(zoom) }
    LaunchedEffect(camera, lifecycleOwner, scanEnabled) {
        val current = camera ?: return@LaunchedEffect
        if (scanEnabled) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                focus(current, view, view.width / 2f, view.height / 2f)
                delay(3000)
            }
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun focus(camera: Camera?, view: PreviewView, x: Float, y: Float) {
    if (camera == null || view.width == 0 || view.height == 0) return
    runCatching {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
    }
}
