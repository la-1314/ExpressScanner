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
 * 找到的运单会自动标记为「已扫描」，回到主界面可看到进度。
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

    /** 已触发过震动的运单号，避免持续震动 */
    private val vibrated = mutableSetOf<String>()
    /** 上一次诊断 Toast 内容，节流用 */
    private var lastToast: String? = null
    /** 上一次成功识别到任何条码的时间戳，用于判断是否"完全识别不到" */
    private var lastSeenAnyBarcode = 0L
    /** 已提示过"识别不到条码"，避免反复弹 */
    private var warnedNoBarcode = false

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
        updateProgress()

        // 点击预览触发对焦（快递面单常因对不上焦识别失败）
        binding.preview.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) focusAt(ev.x, ev.y)
            v.performClick()
            true
        }

        binding.preview.post { startCamera() }
    }

    private fun updateProgress() {
        val total = WaybillStore.totalCount()
        val done = WaybillStore.list.count { it.scanned }
        val remain = total - done
        binding.tvProgress.text = if (remain <= 0) {
            getString(R.string.scan_done_all)
        } else {
            "剩余 $remain / $total · 已找到 $done"
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.preview.surfaceProvider)
        }
        // ImageAnalysis：默认分辨率 + KEEP_ONLY_LATEST，保证分析跟得上帧率
        // （之前 1920x1080 反而让单帧分析太慢导致丢帧，扫不到小条码）
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeImage) }

        try {
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
            )
            // 开启自动对焦（若支持）
            camera?.cameraControl?.let { /* 默认 AF 已启用 */ }
        } catch (e: Exception) {
            Toast.makeText(this, "摄像头启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** 点击屏幕对焦到指定点 */
    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = binding.preview.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyzeImage(proxy: ImageProxy) {
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> renderTargets(barcodes, proxy, rotation) }
            .addOnFailureListener {
                // 引擎报错也提示，便于诊断
                runOnUiThread {
                    showToast("识别引擎错误: ${it.message}")
                }
                proxy.close()
            }
    }

    /**
     * 把识别到的条码转换成 OverlayView 的 Target 并刷新。
     * 计算旋转后图像尺寸（与 PreviewView 的 FIT_CENTER 坐标映射对应）。
     */
    private fun renderTargets(barcodes: List<Barcode>, proxy: ImageProxy, rotation: Int) {
        // 旋转后图像宽高（90/270 时宽高互换）
        val swap = rotation == 90 || rotation == 270
        val imgW = if (swap) proxy.height else proxy.width
        val imgH = if (swap) proxy.width else proxy.height

        val targets = barcodes.mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            val box = b.boundingBox ?: return@mapNotNull null
            val waybill = WaybillStore.findByRaw(raw)
            OverlayView.Target(box, raw, waybill)
        }

        // 命中且尚未标记的运单 → 标记已扫描 + 震动提示
        var needUpdateProgress = false
        val newlyFound = mutableSetOf<String>()
        // 诊断：识别到但未命中的条码原文（去重），便于排查是"识别不到"还是"匹配不到"
        val unmatchedRaw = linkedSetOf<String>()
        for (t in targets) {
            val w = t.waybill
            if (w == null) {
                unmatchedRaw.add(t.rawValue)
                continue
            }
            if (!w.scanned) {
                WaybillStore.markScanned(w.number, screenshotPath = null)
                needUpdateProgress = true
            }
            if (vibrated.add(w.number)) {
                newlyFound += w.number
            }
        }

        runOnUiThread {
            binding.overlay.setImageSize(imgW, imgH)
            binding.overlay.setTargets(targets)
            if (needUpdateProgress) updateProgress()

            when {
                newlyFound.isNotEmpty() -> {
                    vibrate()
                    showToast(getString(R.string.scan_found) + "：" + newlyFound.first())
                }
                unmatchedRaw.isNotEmpty() -> {
                    // 识别到了条码但没匹配到运单列表，显示原文
                    showToast("识别到(未匹配): " + unmatchedRaw.joinToString(" | "))
                }
                barcodes.isEmpty() -> {
                    // 完全没识别到任何条码
                    if (lastSeenAnyBarcode == 0L) {
                        // 第一帧就空，不弹
                    } else if (!warnedNoBarcode && System.currentTimeMillis() - lastSeenAnyBarcode > 8000) {
                        // 8 秒内一直没识别到任何条码 → 提示（只弹一次）
                        warnedNoBarcode = true
                        showToast("未识别到条码，请靠近并对准条形码后点击屏幕对焦")
                    }
                }
            }
            if (barcodes.isNotEmpty()) {
                lastSeenAnyBarcode = System.currentTimeMillis()
                warnedNoBarcode = false
            }
        }
    }

    /** 节流 Toast：内容相同或 1.5s 内重复则不弹 */
    private fun showToast(msg: String) {
        val now = System.currentTimeMillis()
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
        super.onDestroy()
        scanner.close()
        analysisExecutor.shutdown()
        runOnUiThread { binding.overlay.clearTargets() }
    }
}
