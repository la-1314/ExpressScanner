package com.express.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
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

/**
 * 摄像头实时扫描 + 画面标注。
 *
 * 工作方式：持续识别画面中所有条形码，命中待找运单的条码在画面中实时高亮
 * （绿色框 + 运单号气泡），方便在驿站货架前一眼定位目标快递。
 * 找到的运单会自动标记为「已扫描」，回到主界面可看到进度。
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
    )

    /** 已触发过震动的运单号，避免持续震动 */
    private val vibrated = mutableSetOf<String>()

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
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeImage) }

        try {
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "摄像头启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
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
            .addOnFailureListener { proxy.close() }
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
        for (t in targets) {
            val w = t.waybill ?: continue
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
            if (newlyFound.isNotEmpty()) {
                vibrate()
                val first = newlyFound.first()
                Toast.makeText(
                    this,
                    getString(R.string.scan_found) + "：" + first,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
