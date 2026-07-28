package com.express.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.express.scanner.adapter.WaybillAdapter
import com.express.scanner.databinding.ActivityMainBinding
import com.express.scanner.model.Waybill
import com.express.scanner.util.WaybillExtractor
import com.express.scanner.util.WaybillStore

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = WaybillAdapter()

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 扫描结束后刷新列表（状态、计数可能有变化）
        refreshList()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvList.adapter = adapter

        binding.btnExtract.setOnClickListener { extract() }
        binding.btnClear.setOnClickListener {
            binding.etInput.setText("")
        }
        binding.btnScan.setOnClickListener { checkCameraAndStart() }

        // 恢复之前可能存留的数据
        refreshList()
    }

    private fun extract() {
        val text = binding.etInput.text.toString()
        val waybills: List<Waybill> = WaybillExtractor.extract(text)
        WaybillStore.setAll(waybills)
        refreshList()
        if (waybills.isEmpty()) {
            toast(R.string.empty_result)
        } else {
            toast(getString(R.string.result_count, waybills.size))
        }
    }

    private fun refreshList() {
        val list = WaybillStore.list
        adapter.submitList(list.toList())
        binding.tvCount.text = getString(R.string.result_count, list.size)
        binding.tvScannedCount.text = "已扫描 ${list.count { it.scanned }}/${list.size}"
        binding.tvEmpty.visibility =
            if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun checkCameraAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startCameraActivity()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraActivity() {
        cameraLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun toast(resId: Int) =
        android.widget.Toast.makeText(this, resId, android.widget.Toast.LENGTH_SHORT).show()
}
