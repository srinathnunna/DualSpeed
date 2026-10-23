package com.example.dualspeed

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private var wifiNetwork: Network? = null
    private var cellularNetwork: Network? = null

    private lateinit var tvWifiStatus: TextView
    private lateinit var tvCellularStatus: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnSampleUrl: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvSpeedWifi: TextView
    private lateinit var tvSpeedCellular: TextView
    private lateinit var tvSpeedTotal: TextView
    private lateinit var tvStatus: TextView

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvCellularStatus = findViewById(R.id.tvCellularStatus)
        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        btnSampleUrl = findViewById(R.id.btnSampleUrl)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvSpeedWifi = findViewById(R.id.tvSpeedWifi)
        tvSpeedCellular = findViewById(R.id.tvSpeedCellular)
        tvSpeedTotal = findViewById(R.id.tvSpeedTotal)
        tvStatus = findViewById(R.id.tvStatus)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setupNetworks()

        btnSampleUrl.setOnClickListener {
            etUrl.setText("https://proof.ovh.net/files/10Mb.dat")
        }

        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty() && !isDownloading) {
                startDownload(url)
            }
        }
    }

    private fun setupNetworks() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                runOnUiThread { refreshStatus() }
            }
            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                runOnUiThread { refreshStatus() }
            }
        })

        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Keep cellular alive even when Wi-Fi is active
        connectivityManager.requestNetwork(cellularRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                runOnUiThread { refreshStatus() }
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
                runOnUiThread { refreshStatus() }
            }
        })
    }

    private fun refreshStatus() {
        if (wifiNetwork != null) {
            tvWifiStatus.text = "Wi-Fi: Connected ✓"
            tvWifiStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvWifiStatus.text = "Wi-Fi: Disconnected ✗"
            tvWifiStatus.setTextColor(0xFFC62828.toInt())
        }

        if (cellularNetwork != null) {
            tvCellularStatus.text = "Mobile Data (SIM): Connected ✓"
            tvCellularStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvCellularStatus.text = "Mobile Data (SIM): Disconnected ✗"
            tvCellularStatus.setTextColor(0xFFC62828.toInt())
        }
    }

    private fun startDownload(fileUrl: String) {
        isDownloading = true
        btnDownload.isEnabled = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Contacting server..."

        scope.launch(Dispatchers.IO) {
            try {
                val probeClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()

                val headReq = Request.Builder().url(fileUrl).head().build()
                val headResp = probeClient.newCall(headReq).execute()
                val totalLength = headResp.header("Content-Length")?.toLongOrNull() ?: -1L
                headResp.close()

                val targetFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "dual_download_${System.currentTimeMillis()}.dat")
                if (targetFile.exists()) targetFile.delete()

                val wifiCounter = AtomicLong(0)
                val cellCounter = AtomicLong(0)
                val startTime = System.currentTimeMillis()

                val ticker = launch(Dispatchers.Main) {
                    var lastW = 0L
                    var lastC = 0L
                    while (isActive) {
                        delay(1000)
                        val curW = wifiCounter.get()
                        val curC = cellCounter.get()

                        val speedW = (curW - lastW) / 1024.0 / 1024.0
                        val speedC = (curC - lastC) / 1024.0 / 1024.0
                        val speedTotal = speedW + speedC

                        lastW = curW
                        lastC = curC

                        tvSpeedWifi.text = String.format("Wi-Fi Speed: %.2f MB/s", speedW)
                        tvSpeedCellular.text = String.format("Mobile SIM Speed: %.2f MB/s", speedC)
                        tvSpeedTotal.text = String.format("Combined Speed: %.2f MB/s", speedTotal)

                        val totalBytes = curW + curC
                        if (totalLength > 0) {
                            val percent = ((totalBytes.toDouble() / totalLength) * 100).toInt()
                            progressBar.progress = percent
                            tvProgress.text = "$percent% (${totalBytes / (1024 * 1024)} MB / ${totalLength / (1024 * 1024)} MB)"
                        }
                    }
                }

                val hasWifi = wifiNetwork != null
                val hasCell = cellularNetwork != null

                if (hasWifi && hasCell && totalLength > 1024 * 1024) {
                    val mid = totalLength / 2
                    val raf = RandomAccessFile(targetFile, "rw")
                    raf.setLength(totalLength)
                    raf.close()

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "⚡ Dual Channel Active: Splitting file across Wi-Fi and Mobile Data!"
                    }

                    val jobWifi = launch {
                        pullChunk(fileUrl, wifiNetwork!!, 0, mid - 1, targetFile, wifiCounter)
                    }
                    val jobCell = launch {
                        pullChunk(fileUrl, cellularNetwork!!, mid, totalLength - 1, targetFile, cellCounter)
                    }
                    joinAll(jobWifi, jobCell)
                } else {
                    val activeNet = wifiNetwork ?: cellularNetwork
                    if (activeNet != null) {
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "Single channel mode (only 1 network available)..."
                        }
                        pullSingle(fileUrl, activeNet, targetFile, if (activeNet == wifiNetwork) wifiCounter else cellCounter)
                    } else {
                        throw Exception("No network available. Please connect Wi-Fi or Mobile Data.")
                    }
                }

                ticker.cancel()
                val duration = (System.currentTimeMillis() - startTime) / 1000.0
                val downloadedMB = targetFile.length() / (1024.0 * 1024.0)

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    tvProgress.text = "100% Completed"
                    tvStatus.text = String.format("✅ Finished! Size: %.2f MB in %.1fs (Avg: %.2f MB/s)", downloadedMB, duration, downloadedMB / duration)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    private fun pullChunk(url: String, network: Network, start: Long, end: Long, file: File, tracker: AtomicLong) {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=$start-$end")
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw Exception("Empty body")
            val raf = RandomAccessFile(file, "rw")
            raf.seek(start)
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                raf.write(buffer, 0, read)
                tracker.addAndGet(read.toLong())
            }
            raf.close()
        }
    }

    private fun pullSingle(url: String, network: Network, file: File, tracker: AtomicLong) {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: throw Exception("Empty body")
            file.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    tracker.addAndGet(read.toLong())
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
