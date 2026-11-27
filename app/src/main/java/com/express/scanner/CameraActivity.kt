package com.express.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.express.scanner.databinding.ActivityCameraBinding
import com.express.scanner.util.WaybillStore
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 摄像头实时扫描 + 画面标注。
 *
 * 持续识别画面中所有条形码，命中待找运单的条码在画面中实时高亮（绿色框 + 运单号气泡）。
 * 找到的运单自动标记为「已扫描」，回到主界面可看到进度。
 *
 * 关键约束：每帧分析完成后必须 close(ImageProxy)，否则 CameraX 不再送新帧。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    // 全格式识别：覆盖快递面单所有码制（CODE_128 / PDF417 / QR / EAN_13 / UPC_A / ITF 等）
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
    )

    /** 形似运单号的条码：2~4 字母 + 8~15 数字，或纯数字 10~15 位 */
    private val waybillShape = Regex("^[A-Z]{2,4}\\d{8,15}$|^\\d{10,15}$")

    /** 已触发过震动的运单号，避免同一运单反复震动 */
    private val vibrated = mutableSetOf<String>()
    private var lastToast: String? = null
    private var lastUnmatchedToastAt = 0L
    private var startedAt = 0L
    private var lastSeenAnyBarcode = 0L
    private var warnedNoBarcode = false

    @Volatile
    private var destroyed = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.permission_camera_denied, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        // 点击预览触发对焦（面单条码小、距离近，自动对焦常对到背景上）
        binding.preview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) focusAt(ev.x, ev.y)
            v.performClick()
            true
        }

        startedAt = System.currentTimeMillis()
        updateProgress()
        showDiag(emptyList())

        binding.preview.post { startCamera() }
    }

    private fun updateProgress() {
        val total = WaybillStore.totalCount()
        val done = WaybillStore.list.count { it.scanned }
        binding.tvProgress.text = if (total - done <= 0) {
            getString(R.string.scan_done_all)
        } else {
            "剩余 ${total - done} / $total · 已找到 $done"
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.preview.surfaceProvider)
        }
        // 默认分辨率 + KEEP_ONLY_LATEST，保证分析跟得上帧率
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeImage) }

        try {
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "摄像头启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** 点击屏幕对焦到指定点 */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = binding.preview.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeImage(proxy: ImageProxy) {
        if (destroyed) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        // 尺寸必须在 close 之前取出：90/270 时宽高互换
        val swap = rotation == 90 || rotation == 270
        val imgW = if (swap) proxy.height else proxy.width
        val imgH = if (swap) proxy.width else proxy.height

        val image = InputImage.fromMediaImage(mediaImage, rotation)
        try {
            scanner.process(image)
                .addOnSuccessListener { barcodes -> renderTargets(barcodes, imgW, imgH) }
                .addOnFailureListener { e ->
                    runOnUiThread {
                        if (!destroyed) showToast("识别引擎错误: ${e.message}")
                    }
                }
                .addOnCompleteListener {
                    // 关键：帧处理完必须归还 ImageProxy，否则 CameraX 停止送新帧
                    proxy.close()
                }
        } catch (t: Throwable) {
            // Activity 销毁后 scanner 可能已 close，避免分析线程抛异常崩溃
            proxy.close()
        }
    }

    /** 把识别到的条码转换成 OverlayView 的 Target 并刷新（图像尺寸已按旋转处理） */
    private fun renderTargets(barcodes: List<Barcode>, imgW: Int, imgH: Int) {
        val targets = barcodes.mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            val box = b.boundingBox ?: return@mapNotNull null
            OverlayView.Target(box, raw, WaybillStore.findByRaw(raw))
        }

        var needUpdateProgress = false
        val newlyFound = mutableSetOf<String>()
        // 识别到但不在列表里的运单号（仅形似运单号的才提示，忽略货架上其他包裹的随机码）
        val unmatched = linkedSetOf<String>()

        for (t in targets) {
            val w = t.waybill
            if (w == null) {
                if (looksLikeWaybill(t.rawValue)) unmatched.add(t.rawValue)
                continue
            }
            if (!w.scanned) {
                WaybillStore.markScanned(w.number)
                needUpdateProgress = true
                if (vibrated.add(w.number)) newlyFound.add(w.number)
            }
        }

        runOnUiThread {
            if (destroyed) return@runOnUiThread
            binding.overlay.setImageSize(imgW, imgH)
            binding.overlay.setTargets(targets)
            if (needUpdateProgress) updateProgress()
            showDiag(targets)

            val now = System.currentTimeMillis()
            when {
                newlyFound.isNotEmpty() -> {
                    vibrate()
                    showToast(getString(R.string.scan_found) + "：" + newlyFound.first())
                }
                unmatched.isNotEmpty() -> {
                    val msg = "识别到疑似运单号但不在列表: " + unmatched.joinToString(" | ")
                    if (now - lastUnmatchedToastAt > 3000 && msg != lastToast) {
                        lastUnmatchedToastAt = now
                        showToast(msg)
                    }
                }
                else -> {
                    // 连续 8 秒读不到任何条码 → 提示一次
                    val gap = now - maxOf(startedAt, lastSeenAnyBarcode)
                    if (!warnedNoBarcode && gap > 8000) {
                        warnedNoBarcode = true
                        showToast("未识别到条码：请靠近并对准条形码后点击屏幕对焦")
                    }
                }
            }
            if (barcodes.isNotEmpty()) {
                lastSeenAnyBarcode = now
                warnedNoBarcode = false
            }
        }
    }

    private fun looksLikeWaybill(raw: String): Boolean =
        waybillShape.matches(raw.trim().uppercase())

    /** 底部提示：只在列表为空时显示引导，其余时间保持简洁 */
    private fun showDiag(targets: List<OverlayView.Target>) {
        val total = WaybillStore.totalCount()
        if (total == 0) {
            binding.tvHint.text = "运单列表为空：请先回到主界面粘贴文字并点「提取运单号」"
            return
        }
        val done = WaybillStore.list.count { it.scanned }
        if (done >= total) {
            binding.tvHint.text = getString(R.string.scan_done_all)
        } else {
            binding.tvHint.text = "移动摄像头扫视货架，匹配的运单会高亮标注"
        }
    }

    /** 节流 Toast：内容相同则不再弹出 */
    private fun showToast(msg: String) {
        if (msg == lastToast) return
        lastToast = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
        binding.overlay.clearTargets()
        scanner.close()
        analysisExecutor.shutdown()
    }
}
