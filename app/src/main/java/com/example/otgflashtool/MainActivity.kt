package com.example.otgflashtool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.CRC32
import kotlin.concurrent.thread

data class PartitionInfo(
    val name: String, 
    val address: Long, 
    val size: Long,
    val region: String,
    val fileName: String, 
    var fileUri: Uri? = null,
    var isEnabled: Boolean = true
)

class MainActivity : AppCompatActivity() {

    private lateinit var etScatterPath: EditText
    private lateinit var etDaPath: EditText
    private lateinit var containerPartitions: LinearLayout
    private lateinit var tvStatusLog: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var usbManager: UsbManager

    private lateinit var layoutWelcome: View
    private lateinit var layoutFormat: View
    private lateinit var layoutDownload: View
    private lateinit var layoutReadback: View

    private lateinit var tvTabWelcome: TextView
    private lateinit var tvTabFormat: TextView
    private lateinit var tvTabDownload: TextView
    private lateinit var tvTabReadback: TextView

    private val MTK_VENDOR_IDS = listOf(0x0E8D, 0x0BB4, 0x17EF)
    private val PICK_SCATTER_FILE = 1001
    private val PICK_DA_FILE = 1002
    private val PICK_IMG_FILE_BASE = 2000

    private val partitionList = mutableListOf<PartitionInfo>()
    private var selectedDaUri: Uri? = null
    @Volatile private var isOperationRunning = false
    private var activeMode = "DOWNLOAD"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etScatterPath = findViewById(R.id.et_scatter_path)
        etDaPath = findViewById(R.id.et_da_path)
        containerPartitions = findViewById(R.id.container_partitions)
        tvStatusLog = findViewById(R.id.tv_status_log)
        progressBar = findViewById(R.id.progress_bar)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        layoutWelcome = findViewById(R.id.layout_welcome)
        layoutFormat = findViewById(R.id.layout_format)
        layoutDownload = findViewById(R.id.layout_download)
        layoutReadback = findViewById(R.id.layout_readback)

        tvTabWelcome = findViewById(R.id.tv_tab_welcome)
        tvTabFormat = findViewById(R.id.tv_tab_format)
        tvTabDownload = findViewById(R.id.tv_tab_download)
        tvTabReadback = findViewById(R.id.tv_tab_readback)

        tvTabWelcome.setOnClickListener { switchTab("WELCOME") }
        tvTabFormat.setOnClickListener { switchTab("FORMAT") }
        tvTabDownload.setOnClickListener { switchTab("DOWNLOAD") }
        tvTabReadback.setOnClickListener { switchTab("READBACK") }

        findViewById<Button>(R.id.btn_choose_scatter).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, PICK_SCATTER_FILE)
        }

        findViewById<Button>(R.id.btn_choose_da).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
            startActivityForResult(intent, PICK_DA_FILE)
        }

        findViewById<Button>(R.id.btn_download).setOnClickListener {
            if (!isOperationRunning) {
                isOperationRunning = true
                startUsbOperation()
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            isOperationRunning = false
            tvStatusLog.text = "Status: Operation Cancelled."
        }
    }

    private fun switchTab(tabName: String) {
        activeMode = tabName
        layoutWelcome.visibility = View.GONE
        layoutFormat.visibility = View.GONE
        layoutDownload.visibility = View.GONE
        layoutReadback.visibility = View.GONE

        tvTabWelcome.setTextColor(Color.parseColor("#555555"))
        tvTabFormat.setTextColor(Color.parseColor("#555555"))
        tvTabDownload.setTextColor(Color.parseColor("#555555"))
        tvTabReadback.setTextColor(Color.parseColor("#555555"))

        when (tabName) {
            "WELCOME" -> {
                layoutWelcome.visibility = View.VISIBLE
                tvTabWelcome.setTextColor(Color.parseColor("#00796B"))
                tvStatusLog.text = "Mode: WELCOME (User Guide)"
            }
            "FORMAT" -> {
                layoutFormat.visibility = View.VISIBLE
                tvTabFormat.setTextColor(Color.parseColor("#00796B"))
                tvStatusLog.text = "Mode: FORMAT (Memory Erase)"
            }
            "DOWNLOAD" -> {
                layoutDownload.visibility = View.VISIBLE
                tvTabDownload.setTextColor(Color.parseColor("#00796B"))
                tvStatusLog.text = "Mode: DOWNLOAD (Write Firmware)"
            }
            "READBACK" -> {
                layoutReadback.visibility = View.VISIBLE
                tvTabReadback.setTextColor(Color.parseColor("#00796B"))
                tvStatusLog.text = "Mode: READBACK (Dump ROM)"
            }
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
                tvStatusLog.text = "Status: DA File Loaded."
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
                var currentSize = ""
                var currentRegion = "EMMC_USER"
                var currentFileName = ""

                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.startsWith("partition_name:")) {
                        currentName = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("linear_start_addr:")) {
                        currentAddr = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("partition_size:")) {
                        currentSize = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("region:")) {
                        currentRegion = trimmed.split(":")[1].trim()
                    } else if (trimmed.startsWith("file_name:")) {
                        currentFileName = trimmed.split(":")[1].trim()
                        if (currentName.isNotEmpty() && currentAddr.isNotEmpty()) {
                            val addrLong = java.lang.Long.decode(currentAddr)
                            val sizeLong = if (currentSize.isNotEmpty()) java.lang.Long.decode(currentSize) else 0L
                            val info = PartitionInfo(currentName, addrLong, sizeLong, currentRegion, currentFileName)
                            partitionList.add(info)
                            addPartitionRow(info, partitionList.size - 1)
                            
                            currentName = ""
                            currentAddr = ""
                            currentSize = ""
                            currentRegion = "EMMC_USER"
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
            setPadding(2, 6, 2, 6)
        }

        val cb = CheckBox(this).apply { 
            isChecked = true 
            setOnCheckedChangeListener { _, isChecked ->
                info.isEnabled = isChecked
            }
        }

        val tvName = TextView(this).apply {
            text = info.name
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvAddr = TextView(this).apply {
            text = "0x" + java.lang.Long.toHexString(info.address).uppercase()
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        }

        val btnSelectFile = Button(this).apply {
            text = if (info.fileName.isNotEmpty()) info.fileName else "Choose File"
            textSize = 9f
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                startActivityForResult(intent, PICK_IMG_FILE_BASE + index)
            }
        }

        row.addView(cb)
        row.addView(tvName)
        row.addView(tvAddr)
        row.addView(btnSelectFile)
        containerPartitions.addView(row)
    }

    private fun startUsbOperation() {
        thread {
            val deviceList = usbManager.deviceList
            var mtkDevice: UsbDevice? = null

            for (device in deviceList.values) {
                if (MTK_VENDOR_IDS.contains(device.vendorId)) {
                    mtkDevice = device
                    break
                }
            }

            if (mtkDevice == null) {
                runOnUiThread { tvStatusLog.text = "Status: MTK Device Not Found! Check OTG." }
                isOperationRunning = false
                return@thread
            }

            val connection = usbManager.openDevice(mtkDevice)
            if (connection == null) {
                runOnUiThread { tvStatusLog.text = "Status: USB Permission Denied!" }
                isOperationRunning = false
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
                val handshakeCmd = byteArrayOf(0xA0.toByte(), 0x0A.toByte(), 0x50.toByte(), 0x05.toByte())
                connection.bulkTransfer(endpointOut, handshakeCmd, handshakeCmd.size, 1000)

                if (selectedDaUri != null) {
                    runOnUiThread { tvStatusLog.text = "Status: Loading DA payload..." }
                    sendDaPayload(connection, endpointOut, selectedDaUri!!)
                }

                when (activeMode) {
                    "DOWNLOAD" -> performVerifiedFlashing(connection, endpointOut)
                    "FORMAT" -> performFormatErase(connection, endpointOut)
                    "READBACK" -> performReadbackDump(connection, endpointIn)
                }
            }

            connection.releaseInterface(usbInterface)
            connection.close()
            isOperationRunning = false
        }
    }

    private fun sendDaPayload(connection: UsbDeviceConnection, epOut: UsbEndpoint, daUri: Uri) {
        try {
            contentResolver.openInputStream(daUri)?.use { inputStream ->
                val daBytes = inputStream.readBytes()
                val chunkSize = 4096
                var offset = 0
                while (offset < daBytes.size && isOperationRunning) {
                    val length = Math.min(chunkSize, daBytes.size - offset)
                    connection.bulkTransfer(epOut, daBytes, offset, length, 1000)
                    offset += length
                }
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatusLog.text = "DA Injection Error: ${e.message}" }
        }
    }

    private fun performVerifiedFlashing(connection: UsbDeviceConnection, epOut: UsbEndpoint) {
        val chunkSize = 65536
        val buffer = ByteArray(chunkSize)

        for ((index, part) in partitionList.withIndex()) {
            if (!isOperationRunning) break
            if (!part.isEnabled) continue
            val uri = part.fileUri ?: continue

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    var bytesRead: Int
                    var totalWritten = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1 && isOperationRunning) {
                        val crc = CRC32()
                        crc.update(buffer, 0, bytesRead)
                        val crcHex = java.lang.Long.toHexString(crc.value)

                        var sent = 0
                        var retries = 0
                        while (sent <= 0 && retries < 3 && isOperationRunning) {
                            sent = connection.bulkTransfer(epOut, buffer, bytesRead, 2000)
                            if (sent <= 0) retries++
                        }

                        if (sent > 0) {
                            totalWritten += sent
                            val progress = ((index + 1).toFloat() / partitionList.size * 100).toInt()
                            runOnUiThread {
                                tvStatusLog.text = "Flashing ${part.name}: ${totalWritten / 1024} KB [CRC: $crcHex]"
                                progressBar.progress = progress
                            }
                        } else {
                            runOnUiThread { tvStatusLog.text = "Error: Write failed on ${part.name}" }
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { tvStatusLog.text = "Read Error: ${e.message}" }
                return
            }
        }

        runOnUiThread {
            if (isOperationRunning) {
                tvStatusLog.text = "Status: FLASHING COMPLETED SUCCESSFULLY."
                progressBar.progress = 100
            }
        }
    }

    private fun performFormatErase(connection: UsbDeviceConnection, epOut: UsbEndpoint) {
        runOnUiThread { tvStatusLog.text = "Status: Executing Format / Erase..." }
        val formatCmd = byteArrayOf(0x0C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        connection.bulkTransfer(epOut, formatCmd, formatCmd.size, 1000)
        runOnUiThread { tvStatusLog.text = "Status: Format Complete." }
    }

    private fun performReadbackDump(connection: UsbDeviceConnection, epIn: UsbEndpoint) {
        runOnUiThread { tvStatusLog.text = "Status: Executing Readback Dump..." }
        val readCmd = byteArrayOf(0x0D.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        connection.bulkTransfer(epIn, readCmd, readCmd.size, 1000)
        runOnUiThread { tvStatusLog.text = "Status: Readback Complete." }
    }
}
