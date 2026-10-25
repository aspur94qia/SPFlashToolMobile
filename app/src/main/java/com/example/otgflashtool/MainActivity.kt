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
import kotlin.concurrent.thread

data class PartitionInfo(
    val name: String, 
    val address: Long, 
    val fileName: String, 
    var fileUri: Uri? = null
)

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
    private val PICK_IMG_FILE_BASE = 2000

    private val partitionList = mutableListOf<PartitionInfo>()
    private var selectedDaUri: Uri? = null
    @Volatile private var isFlashingRunning = false

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
            if (!isFlashingRunning) {
                isFlashingRunning = true
                startFlashingProcess()
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            isFlashingRunning = false
            tvStatusLog.text = "Status: Flashing Cancelled."
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
                selectedDaUri = uri
                etDaPath.setText(uri.path)
                tvStatusLog.text = "Status: Custom DA Loaded."
            } else if (requestCode >= PICK_IMG_FILE_BASE) {
                val index = requestCode - PICK_IMG_FILE_BASE
                if (index < partitionList.size) {
                    partitionList[index].fileUri = uri
                    tvStatusLog.text = "File for ${partitionList[index].name} selected."
                }
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
                            addPartitionRow(info, partitionList.size - 1)
                            currentName = ""
                            currentAddr = ""
                            currentFileName = ""
                        }
                    }
                }
            }
            tvStatusLog.text = "Loaded ${partitionList.size} partitions."
        } catch (e: Exception) {
            tvStatusLog.text = "Error parsing scatter: ${e.message}"
        }
    }

    private fun addPartitionRow(info: PartitionInfo, index: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4, 8, 4, 8)
        }

        val cb = CheckBox(this).apply { isChecked = true }
        val tvName = TextView(this).apply {
            text = info.name
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnSelectFile = Button(this).apply {
            text = if (info.fileName.isNotEmpty()) info.fileName else "Select File"
            textSize = 10f
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                startActivityForResult(intent, PICK_IMG_FILE_BASE + index)
            }
        }

        row.addView(cb)
        row.addView(tvName)
        row.addView(btnSelectFile)
        containerPartitions.addView(row)
    }

    private fun startFlashingProcess() {
        thread {
            var mtkDevice: UsbDevice? = null
            val deviceList = usbManager.deviceList

            for (device in deviceList.values) {
                if (MTK_VENDOR_IDS.contains(device.vendorId)) {
                    mtkDevice = device
                    break
                }
            }

            if (mtkDevice == null) {
                runOnUiThread { tvStatusLog.text = "Status: MTK Device Not Found! Check OTG." }
                isFlashingRunning = false
                return@thread
            }

            val connection = usbManager.openDevice(mtkDevice)
            if (connection == null) {
                runOnUiThread { tvStatusLog.text = "Status: USB Permission Denied!" }
                isFlashingRunning = false
                return@thread
            }

            val usbInterface = mtkDevice.getInterface(0)
            connection.claimInterface(usbInterface, true)

            var endpointOut: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    endpointOut = ep
                    break
                }
            }

            if (endpointOut != null) {
                // Handshake BROM
                val bromHandshake = byteArrayOf(0xA0.toByte(), 0x0A.toByte(), 0x50.toByte(), 0x05.toByte())
                connection.bulkTransfer(endpointOut, bromHandshake, bromHandshake.size, 1000)

                runOnUiThread { tvStatusLog.text = "Status: BROM Connected. Writing firmware..." }

                val chunkSize = 65536
                val buffer = ByteArray(chunkSize)

                for ((index, part) in partitionList.withIndex()) {
                    if (!isFlashingRunning) break
                    val uri = part.fileUri ?: continue

                    try {
                        contentResolver.openInputStream(uri)?.use { inputStream ->
                            var bytesRead: Int
                            var totalWritten = 0L

                            while (inputStream.read(buffer).also { bytesRead = it } != -1 && isFlashingRunning) {
                                val sent = connection.bulkTransfer(endpointOut, buffer, bytesRead, 2000)
                                if (sent > 0) {
                                    totalWritten += sent
                                    val progress = ((index + 1).toFloat() / partitionList.size * 100).toInt()
                                    runOnUiThread {
                                        tvStatusLog.text = "Writing ${part.name}: ${totalWritten / 1024} KB"
                                        progressBar.progress = progress
                                    }
                                } else {
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }

                runOnUiThread {
                    if (isFlashingRunning) {
                        tvStatusLog.text = "Status: FLASHING COMPLETED SUCCESSFULLY!"
                        progressBar.progress = 100
                    }
                }
            }

            connection.releaseInterface(usbInterface)
            connection.close()
            isFlashingRunning = false
        }
    }
}
