package com.example.dualspeed

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var notificationManager: NotificationManager

    private var wifiNetwork: Network? = null
    private var cellularNetwork: Network? = null

    private lateinit var tvWifiStatus: TextView
    private lateinit var tvCellularStatus: TextView
    private lateinit var tvNetworkHint: TextView
    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnSampleUrl: Button
    private lateinit var btnSampleLarge: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvSpeedWifi: TextView
    private lateinit var tvSpeedCellular: TextView
    private lateinit var tvSpeedTotal: TextView
    private lateinit var tvStatus: TextView

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var isDownloading = false

    private val CHANNEL_ID = "dualspeed_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvWifiStatus = findViewById(R.id.tvWifiStatus)
        tvCellularStatus = findViewById(R.id.tvCellularStatus)
        tvNetworkHint = findViewById(R.id.tvNetworkHint)
        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        btnSampleUrl = findViewById(R.id.btnSampleUrl)
        btnSampleLarge = findViewById(R.id.btnSampleLarge)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        tvSpeedWifi = findViewById(R.id.tvSpeedWifi)
        tvSpeedCellular = findViewById(R.id.tvSpeedCellular)
        tvSpeedTotal = findViewById(R.id.tvSpeedTotal)
        tvStatus = findViewById(R.id.tvStatus)

        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        requestNotificationPermission()
        registerNetworkChannels()

        // 25MB fast Cloudflare test file supporting HTTP Range
        btnSampleUrl.setOnClickListener {
            etUrl.setText("https://speed.cloudflare.com/__down?bytes=25000000")
        }

        // 50MB fast test file
        btnSampleLarge.setOnClickListener {
            etUrl.setText("https://speed.cloudflare.com/__down?bytes=50000000")
        }

        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter or select a download URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!isDownloading) {
                startDualDownload(url)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DualSpeed Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download speeds for Wi-Fi and Mobile SIM"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun registerNetworkChannels() {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                runOnUiThread { updateStatusUI() }
            }
            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                runOnUiThread { updateStatusUI() }
            }
        })

        val cellularRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Keeps cellular connection alive alongside Wi-Fi
        connectivityManager.requestNetwork(cellularRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cellularNetwork = network
                runOnUiThread { updateStatusUI() }
            }
            override fun onLost(network: Network) {
                if (cellularNetwork == network) cellularNetwork = null
                runOnUiThread { updateStatusUI() }
            }
        })
    }

    private fun updateStatusUI() {
        if (wifiNetwork != null) {
            tvWifiStatus.text = "📶 Wi-Fi: Connected ✓"
            tvWifiStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvWifiStatus.text = "📶 Wi-Fi: Disconnected ✗"
            tvWifiStatus.setTextColor(0xFFC62828.toInt())
        }

        if (cellularNetwork != null) {
            tvCellularStatus.text = "📶 Mobile Data (SIM): Connected ✓"
            tvCellularStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvCellularStatus.text = "📶 Mobile Data (SIM): Connecting/Inactive ✗"
            tvCellularStatus.setTextColor(0xFFC62828.toInt())
        }

        if (wifiNetwork != null && cellularNetwork != null) {
            tvNetworkHint.text = "⚡ Both channels active! Aggregation ready."
            tvNetworkHint.setTextColor(0xFF2E7D32.toInt())
            btnDownload.text = "START DUAL DOWNLOAD (AGGREGATED)"
        } else {
            tvNetworkHint.text = "Waiting for both Wi-Fi and SIM. Make sure Mobile Data is turned ON."
            tvNetworkHint.setTextColor(0xFFE65100.toInt())
            btnDownload.text = "START DOWNLOAD"
        }
    }

    private fun startDualDownload(fileUrl: String) {
        if (wifiNetwork == null && cellularNetwork == null) {
            Toast.makeText(this, "No active network! Please connect Wi-Fi or Mobile Data.", Toast.LENGTH_LONG).show()
            return
        }

        isDownloading = true
        btnDownload.isEnabled = false
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        tvStatus.text = "Connecting to server and splitting ranges..."

        scope.launch(Dispatchers.IO) {
            try {
                val tempClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .build()

                val headReq = Request.Builder().url(fileUrl).head().build()
                var totalBytes = -1L
                try {
                    val resp = tempClient.newCall(headReq).execute()
                    totalBytes = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                    resp.close()
                } catch (_: Exception) {}

                // If HEAD fails or gives no length, try GET range 0-0
                if (totalBytes <= 0) {
                    val rangeReq = Request.Builder().url(fileUrl).addHeader("Range", "bytes=0-0").build()
                    val rangeResp = tempClient.newCall(rangeReq).execute()
                    val cr = rangeResp.header("Content-Range")
                    if (cr != null && cr.contains("/")) {
                        totalBytes = cr.substringAfter("/").trim().toLongOrNull() ?: -1L
                    }
                    rangeResp.close()
                }

                val targetFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "dualspeed_${System.currentTimeMillis()}.bin")
                if (targetFile.exists()) targetFile.delete()

                val wifiCounter = AtomicLong(0)
                val cellCounter = AtomicLong(0)
                val startTime = System.currentTimeMillis()

                // Smoothed speed ticker (updates every 500ms without flickering)
                val speedJob = launch(Dispatchers.Main) {
                    var lastWifi = 0L
                    var lastCell = 0L
                    var lastCheckTime = System.currentTimeMillis()

                    var smoothWifi = 0.0
                    var smoothCell = 0.0

                    while (isActive) {
                        delay(500)
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - lastCheckTime) / 1000.0
                        if (elapsedSec < 0.1) continue

                        val curW = wifiCounter.get()
                        val curC = cellCounter.get()

                        val instWifi = ((curW - lastWifi) / elapsedSec) / (1024.0 * 1024.0)
                        val instCell = ((curC - lastCell) / elapsedSec) / (1024.0 * 1024.0)

                        lastWifi = curW
                        lastCell = curC
                        lastCheckTime = now

                        // Exponential smoothing filter to prevent UI flicker
                        smoothWifi = if (smoothWifi == 0.0) instWifi else (smoothWifi * 0.65 + instWifi * 0.35)
                        smoothCell = if (smoothCell == 0.0) instCell else (smoothCell * 0.65 + instCell * 0.35)
                        val smoothTotal = smoothWifi + smoothCell

                        tvSpeedWifi.text = String.format(Locale.US, "Wi-Fi Speed: %.2f MB/s", smoothWifi)
                        tvSpeedCellular.text = String.format(Locale.US, "Mobile SIM Speed: %.2f MB/s", smoothCell)
                        tvSpeedTotal.text = String.format(Locale.US, "Combined Speed: %.2f MB/s", smoothTotal)

                        val downloaded = curW + curC
                        var percent = 0
                        if (totalBytes > 0) {
                            percent = ((downloaded.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                            progressBar.progress = percent
                            tvProgress.text = String.format(Locale.US, "%d%% (%.1f MB / %.1f MB)", percent, downloaded / (1024.0 * 1024.0), totalBytes / (1024.0 * 1024.0))
                        } else {
                            tvProgress.text = String.format(Locale.US, "Downloaded: %.1f MB", downloaded / (1024.0 * 1024.0))
                        }

                        // Update Notification in Status Bar
                        updateNotification(percent, smoothWifi, smoothCell, smoothTotal)
                    }
                }

                val hasWifi = wifiNetwork != null
                val hasCell = cellularNetwork != null

                if (hasWifi && hasCell && totalBytes > 1024 * 1024) {
                    val mid = totalBytes / 2
                    val raf = RandomAccessFile(targetFile, "rw")
                    raf.setLength(totalBytes)
                    raf.close()

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "⚡ Dual Channel Active: Splitting file across Wi-Fi and SIM!"
                    }

                    // Parallel download: Wi-Fi gets Part 1, SIM gets Part 2
                    val job1 = async(Dispatchers.IO) {
                        downloadBoundChunk(fileUrl, wifiNetwork!!, 0, mid - 1, targetFile, wifiCounter)
                    }
                    val job2 = async(Dispatchers.IO) {
                        downloadBoundChunk(fileUrl, cellularNetwork!!, mid, totalBytes - 1, targetFile, cellCounter)
                    }
                    awaitAll(job1, job2)
                } else {
                    val activeNet = wifiNetwork ?: cellularNetwork!!
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Downloading through single available network..."
                    }
                    downloadBoundChunk(fileUrl, activeNet, 0, if (totalBytes > 0) totalBytes - 1 else -1, targetFile, if (activeNet == wifiNetwork) wifiCounter else cellCounter)
                }

                speedJob.cancel()
                val totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                val totalMB = targetFile.length() / (1024.0 * 1024.0)
                val avgSpeed = if (totalSeconds > 0) totalMB / totalSeconds else 0.0

                withContext(Dispatchers.Main) {
                    progressBar.progress = 100
                    tvProgress.text = "100% Download Completed"
                    tvStatus.text = String.format(Locale.US, "✅ Complete: %.1f MB in %.1fs (Avg: %.2f MB/s)", totalMB, totalSeconds, avgSpeed)
                }

                showCompleteNotification(totalMB, avgSpeed)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                }
                notificationManager.cancel(NOTIFICATION_ID)
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    // Explicitly binds both SocketFactory AND DNS to the specified network
    private fun downloadBoundChunk(url: String, network: Network, start: Long, end: Long, file: File, counter: AtomicLong) {
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .dns { hostname ->
                try {
                    network.getAllByName(hostname).toList()
                } catch (e: Exception) {
                    okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(url)
        if (start >= 0 && end > start) {
            reqBuilder.addHeader("Range", "bytes=$start-$end")
        }

        client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code} on ${resp.request.url}")
            val body = resp.body ?: throw Exception("Empty body")
            val input = body.byteStream()

            val raf = RandomAccessFile(file, "rw")
            if (start > 0) raf.seek(start)
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                raf.write(buffer, 0, read)
                counter.addAndGet(read.toLong())
            }
            raf.close()
        }
    }

    private fun updateNotification(percent: Int, wifiSpeed: Double, cellSpeed: Double, totalSpeed: Double) {
        val title = String.format(Locale.US, "⚡ DualSpeed: %d%% (%.1f MB/s)", percent, totalSpeed)
        val text = String.format(Locale.US, "Wi-Fi: %.1f MB/s | SIM: %.1f MB/s", wifiSpeed, cellSpeed)

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("📶 Wi-Fi: ${String.format(Locale.US, "%.2f", wifiSpeed)} MB/s\n📶 SIM: ${String.format(Locale.US, "%.2f", cellSpeed)} MB/s\n🚀 Combined: ${String.format(Locale.US, "%.2f", totalSpeed)} MB/s"))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    private fun showCompleteNotification(totalMB: Double, avgSpeed: Double) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ DualSpeed: Download Complete")
            .setContentText(String.format(Locale.US, "Downloaded %.1f MB at avg %.2f MB/s", totalMB, avgSpeed))
            .setProgress(0, 0, false)
            .setOngoing(false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationManager.cancel(NOTIFICATION_ID)
        scope.cancel()
    }
}
