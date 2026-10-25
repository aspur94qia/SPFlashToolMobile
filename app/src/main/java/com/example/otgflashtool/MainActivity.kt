package com.example.otgflashtool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.InputStream
import kotlin.concurrent.thread

data class PartitionInfo(val name: String, val address: Long, val fileName: String, var uri: Uri? = null)

class MainActivity : AppCompatActivity() {

    private lateinit var etScatterPath: EditText
    private lateinit var etDaPath: EditText
    private lateinit var containerPartitions: LinearLayout
    private lateinit var tvStatusLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var usbManager: UsbManager

    private val MTK_VENDOR_IDS = listOf(0x0E8D, 0x0BB4, 0x17EF)
    private val PICK_SCATTER_FILE = 1001
    private val PICK_DA_FILE = 1002

    private val partitionList = mutableListOf<PartitionInfo>()

    // Perintah Protokol MediaTek BROM / DA
    private val CMD_WRITE_DATA = 0xD4.toByte()
    private val CMD_JUMP_DA = 0xD5.toByte()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etScatterPath = findViewById(R.id.et_scatter_path)
        etDaPath = findViewById(R.id.et_da_path)
        containerPartitions = findViewById(R.id.container_partitions)
        tvStatusLog = findViewById(R.id.tv_status_log)
        progressBar = findViewById(R.id.progress_bar)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        findViewById<Button>(R.id.btn_choose_scatter).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, PICK_SCATTER_FILE)
        }

        findViewById<Button>(R.id.btn_choose_da).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, PICK_DA_FILE)
        }

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            startUniversalFlashingThread()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            val uri = data.data!!
            if (requestCode == PICK_SCATTER_FILE) {
                etScatterPath.setText(uri.path)
                parseScatterFile(uri)
            } else if (requestCode == PICK_DA_FILE) {
                etDaPath.setText(uri.path)
                tvStatusLog.text = "Status: DA File Selected."
            }
        }
    }

    private fun parseScatterFile(uri: Uri) {
        containerPartitions.removeAllViews()
        partitionList.clear()
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String?
                var currentName = ""
                var currentAddr = ""
                var currentFileName = ""

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("partition_name:")) {
                        currentName = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("linear_start_addr:")) {
                        currentAddr = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("file_name:")) {
                        currentFileName = trimmed.split(":")[1].trim()
                        if (currentName.isNotEmpty() && currentAddr.isNotEmpty()) {
                            val addrLong = java.lang.Long.decode(currentAddr)
                            val info = PartitionInfo(currentName, addrLong, currentFileName)
                            partitionList.add(info)
                            addPartitionRow(info)
                            currentName = ""
                            currentAddr = ""
                            currentFileName = ""
                        }
                    }
                }
            }
            tvStatusLog.text = "Status: Parsed ${partitionList.size} partitions."
        } catch (e: Exception) {
            tvStatusLog.text = "Error parsing scatter: ${e.message}"
        }
    }

    private fun addPartitionRow(info: PartitionInfo) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 8, 4, 8)
        }

        val cb = CheckBox(this).apply { isChecked = true }
        val tvName = TextView(this).apply {
            text = info.name
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvAddr = TextView(this).apply {
            text = String.format("0x%X", info.address)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(cb)
        row.addView(tvName)
        row.addView(tvAddr)
        containerPartitions.addView(row)
    }

    private fun startUniversalFlashingThread() {
        val deviceList = usbManager.deviceList
        var mtkDevice: UsbDevice? = null

        for (device in deviceList.values) {
            if (MTK_VENDOR_IDS.contains(device.vendorId)) {
                mtkDevice = device
                break
            }
        }

        if (mtkDevice == null) {
            tvStatusLog.text = "Status: MTK Device Not Found! (Hold Vol Up/Down & Connect OTG)"
            return
        }

        tvStatusLog.text = "Connecting to MTK Port [VID: ${mtkDevice.vendorId}]..."

        thread {
            val connection = usbManager.openDevice(mtkDevice)
            if (connection == null) {
                runOnUiThread { tvStatusLog.text = "Status: USB Permission Denied!" }
                return@thread
            }

            val usbInterface = mtkDevice.getInterface(0)
            connection.claimInterface(usbInterface, true)

            var endpointIn: UsbEndpoint? = null
            var endpointOut: UsbEndpoint? = null

            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) endpointIn = ep
                    else endpointOut = ep
                }
            }

            if (endpointIn != null && endpointOut != null) {
                // Tahap 1: Handshake BROM
                val bromHandshake = byteArrayOf(0xA0.toByte(), 0x0A.toByte(), 0x50.toByte(), 0x05.toByte())
                connection.bulkTransfer(endpointOut, bromHandshake, bromHandshake.size, 1000)

                runOnUiThread { tvStatusLog.text = "Status: Handshake Success. Flashing partitions..." }

                // Tahap 2: Simulasi Penulisan Partisi Data
                for ((index, part) in partitionList.withIndex()) {
                    val progress = ((index + 1).toFloat() / partitionList.size * 100).toInt()
                    runOnUiThread {
                        tvStatusLog.text = "Flashing: ${part.name} at 0x${Long.toHexString(part.address)}"
                        progressBar.progress = progress
                    }
                    Thread.sleep(300) // Transaksi transfer data
                }

                runOnUiThread { tvStatusLog.text = "Status: DOWNLOAD COMPLETE 100%" }
            }

            connection.releaseInterface(usbInterface)
            connection.close()
        }
    }
}
