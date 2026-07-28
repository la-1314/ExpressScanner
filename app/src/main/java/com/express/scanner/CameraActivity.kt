package com.express.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.express.scanner.databinding.ActivityCameraBinding
import com.express.scanner.model.Waybill
import com.express.scanner.util.WaybillStore
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null
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

    /** 是否处于“已命中展示”状态，避免重复触发 */
    private var locked = false
    /** 最近处理过的条码内容（节流，避免同一条码连续回调） */
    private var lastProcessed: String? = null
    private var lastProcessedAt = 0L

    private val timeFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

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
        binding.btnRescan.setOnClickListener { resetForRescan() }
        binding.btnDone.setOnClickListener { finish() }

        // 扫描线动画（在扫描框内来回移动）
        binding.scanLine.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.scan_line_anim)
        )
        showProgress()

        binding.preview.post { startCamera() }
    }

    private fun showProgress() {
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = "剩余 ${WaybillStore.remainingCount()}/${WaybillStore.totalCount()}"
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
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analysisExecutor, ::analyzeImage) }

        try {
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, imageCapture, imageAnalysis
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
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty() && !locked) {
                    val raw = barcodes.firstNotNullOfOrNull { it.rawValue }
                    raw?.let { onBarcodeScanned(it) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** 处理一次条码识别结果 */
    private fun onBarcodeScanned(raw: String) {
        val now = System.currentTimeMillis()
        // 1.5s 内同一内容不重复处理
        if (raw == lastProcessed && now - lastProcessedAt < 1500) return
        lastProcessed = raw
        lastProcessedAt = now

        val match = WaybillStore.findMatch(raw)
        if (match == null) {
            runOnUiThread {
                Toast.makeText(this, R.string.scan_not_found, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (match.scanned) {
            runOnUiThread {
                Toast.makeText(this, R.string.scan_duplicated, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // 命中：截图
        locked = true
        runOnUiThread { captureAndShow(match, raw) }
    }

    /** 通过 ImageCapture 拍下当前画面作为截图 */
    private fun captureAndShow(match: Waybill, raw: String) {
        val capture = imageCapture
        if (capture == null) {
            showResult(match, raw, null)
            return
        }
        val name = "WAYBILL_${timeFmt.format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ExpressScanner")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
        )
        if (uri == null) {
            showResult(match, raw, null)
            return
        }
        val output = ImageCapture.OutputFileOptions.Builder(resolver, uri).build()
        capture.takePicture(
            output,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    val path = uri.toString()
                    WaybillStore.markScanned(match.number, path)
                    showResult(match, raw, uri.toString())
                }

                override fun onError(exc: ImageCaptureException) {
                    WaybillStore.markScanned(match.number, null)
                    showResult(match, raw, null)
                    Toast.makeText(
                        this@CameraActivity,
                        "截图保存失败: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun showResult(match: Waybill, raw: String, screenshotUri: String?) {
        vibrate()
        binding.tvResultNumber.text = match.number
        binding.tvResultCarrier.text = match.carrier ?: "未知快递"
        binding.tvResultCarrier.visibility =
            if (match.carrier.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.resultCard.visibility = View.VISIBLE
        binding.resultCard.alpha = 0f
        binding.resultCard.animate().alpha(1f).setDuration(250).start()

        if (screenshotUri != null) {
            val resolver = contentResolver
            try {
                resolver.openInputStream(android.net.Uri.parse(screenshotUri))?.use { input ->
                    val bmp = BitmapFactory.decodeStream(input)
                    binding.ivScreenshot.setImageBitmap(bmp)
                }
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.ivScreenshot.setImageDrawable(null)
            }
        } else {
            binding.ivScreenshot.setImageDrawable(null)
        }
        showProgress()
    }

    private fun resetForRescan() {
        locked = false
        lastProcessed = null
        binding.resultCard.visibility = View.GONE
        binding.ivScreenshot.setImageDrawable(null)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        analysisExecutor.shutdown()
    }
}
