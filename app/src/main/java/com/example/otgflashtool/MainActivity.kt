package com.example.otgflashtool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var etScatterPath: EditText
    private lateinit var containerPartitions: LinearLayout
    private lateinit var tvStatusLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var usbManager: UsbManager

    private val MTK_VENDOR_ID = 3725 // 0x0E8D MediaTek
    private val PICK_SCATTER_FILE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etScatterPath = findViewById(R.id.et_scatter_path)
        containerPartitions = findViewById(R.id.container_partitions)
        tvStatusLog = findViewById(R.id.tv_status_log)
        progressBar = findViewById(R.id.progress_bar)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        findViewById<Button>(R.id.btn_choose_scatter).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
            }
            startActivityForResult(intent, PICK_SCATTER_FILE)
        }

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            startFlashingProcess()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_SCATTER_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                etScatterPath.setText(uri.path)
                parseScatterFile(uri)
            }
        }
    }

    private fun parseScatterFile(uri: Uri) {
        containerPartitions.removeAllViews()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                var currentPartition = ""

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("partition_name:")) {
                        currentPartition = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("linear_start_addr:")) {
                        val addr = trimmed.split(":")[1].trim()
                        addPartitionRow(currentPartition, addr)
                    }
                }
            }
            tvStatusLog.text = "Status: Scatter Loaded Successfully."
        } catch (e: Exception) {
            tvStatusLog.text = "Error parsing scatter: ${e.message}"
        }
    }

    private fun addPartitionRow(name: String, addr: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 8, 4, 8)
        }

        val cb = CheckBox(this).apply { isChecked = true }
        val tvName = TextView(this).apply {
            text = name
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvAddr = TextView(this).apply {
            text = addr
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(cb)
        row.addView(tvName)
        row.addView(tvAddr)
        containerPartitions.addView(row)
    }

    private fun startFlashingProcess() {
        val deviceList: HashMap<String, UsbDevice> = usbManager.deviceList
        var mtkDevice: UsbDevice? = null

        for (device in deviceList.values) {
            if (device.vendorId == MTK_VENDOR_ID) {
                mtkDevice = device
                break
            }
        }

        if (mtkDevice == null) {
            tvStatusLog.text = "Status: Vivo Y12 Not Found! (Hold Vol Up+Down & reconnect OTG)"
            return
        }

        tvStatusLog.text = "Connecting to BROM Port [VID: ${mtkDevice.vendorId}]..."
        progressBar.progress = 10
    }
}
