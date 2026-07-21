package io.legado.app.ui.tomato

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class TomatoWebUiService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var serverProcess: Process? = null
    private var serverJob: Job? = null
    private var openBrowserWhenReady = false
    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN -> openBrowserWhenReady = true
        }
        startForeground(NotificationId.TomatoWebUiService, buildNotification(false))
        if (isPortReady()) {
            updateReadyNotification()
            if (openBrowserWhenReady) openBrowser()
        } else if (serverJob?.isActive != true) {
            startWebUiProcess()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopping = true
        serverJob?.cancel()
        serverProcess?.destroy()
        runCatching { serverProcess?.waitFor(2, TimeUnit.SECONDS) }
        if (serverProcess?.isAlive == true) serverProcess?.destroyForcibly()
        serverProcess = null
        serviceScope.cancel()
        isRunning = false
        super.onDestroy()
    }

    private fun startWebUiProcess() {
        serverJob = serviceScope.launch {
            try {
                val dataDir = getDir(DATA_DIR_NAME, MODE_PRIVATE)
                val executable = applicationInfo.nativeLibraryDir + "/" + NATIVE_EXECUTABLE_NAME
                val process = ProcessBuilder(
                    executable,
                    "--server",
                    "--data-dir",
                    dataDir.absolutePath,
                )
                    .directory(dataDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["TOMATO_WEB_ADDR"] = WEB_BIND_ADDRESS
                        environment()["HOME"] = dataDir.absolutePath
                    }
                    .start()
                serverProcess = process
                isRunning = true
                launch {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { Log.i(LOG_TAG, it) }
                        }
                    } catch (e: IOException) {
                        // Destroying the native process interrupts a blocking read. This is an
                        // expected part of stopping the service, not a fatal coroutine failure.
                        if (!stopping && process.isAlive) {
                            Log.w(LOG_TAG, "Unable to read WebUI process output", e)
                        }
                    }
                }
                launch {
                    waitUntilReady(process)
                }
                val exitCode = process.waitFor()
                if (!stopping) {
                    Log.e(LOG_TAG, "WebUI process exited with code $exitCode")
                    notificationManager.notify(
                        NotificationId.TomatoWebUiService,
                        buildErrorNotification(exitCode),
                    )
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Unable to start Tomato WebUI", e)
                notificationManager.notify(
                    NotificationId.TomatoWebUiService,
                    buildErrorNotification(null),
                )
                stopSelf()
            }
        }
    }

    private suspend fun waitUntilReady(process: Process) {
        repeat(PORT_CHECK_ATTEMPTS) {
            if (!process.isAlive) return
            if (isPortReady()) {
                updateReadyNotification()
                if (openBrowserWhenReady) openBrowser()
                return
            }
            delay(PORT_CHECK_INTERVAL_MS)
        }
        Log.e(LOG_TAG, "WebUI did not open $WEB_URL in time")
    }

    private fun isPortReady(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(WEB_HOST, WEB_PORT), SOCKET_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private fun updateReadyNotification() {
        notificationManager.notify(
            NotificationId.TomatoWebUiService,
            buildNotification(true),
        )
    }

    private fun buildNotification(ready: Boolean): Notification {
        val text = getString(
            if (ready) R.string.tomato_webui_running else R.string.tomato_webui_starting,
        )
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(getString(R.string.tomato_webui_title))
            .setContentText(text)
            .setContentIntent(openWebPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (!ready) setProgress(0, 0, true)
                addAction(
                    R.drawable.ic_stop_black_24dp,
                    getString(R.string.stop),
                    stopPendingIntent(),
                )
            }
            .build()
    }

    private fun buildErrorNotification(exitCode: Int?): Notification {
        val text = if (exitCode == null) {
            getString(R.string.tomato_webui_start_failed)
        } else {
            getString(R.string.tomato_webui_exited, exitCode)
        }
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_web_service_noti)
            .setContentTitle(getString(R.string.tomato_webui_title))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
    }

    private fun openWebPendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL))
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, TomatoWebUiService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openBrowser() {
        openBrowserWhenReady = false
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(WEB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.e(LOG_TAG, "Unable to open browser", it) }
    }

    companion object {
        const val WEB_URL = "http://127.0.0.1:18423/"
        private const val WEB_HOST = "127.0.0.1"
        private const val WEB_PORT = 18423
        private const val WEB_BIND_ADDRESS = "$WEB_HOST:$WEB_PORT"
        private const val DATA_DIR_NAME = "tomato_webui"
        private const val NATIVE_EXECUTABLE_NAME = "libtomato_webui.so"
        private const val LOG_TAG = "TomatoWebUI"
        private const val ACTION_OPEN = "io.legado.app.action.OPEN_TOMATO_WEBUI"
        private const val ACTION_STOP = "io.legado.app.action.STOP_TOMATO_WEBUI"
        private const val PORT_CHECK_ATTEMPTS = 40
        private const val PORT_CHECK_INTERVAL_MS = 250L
        private const val SOCKET_TIMEOUT_MS = 200

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TomatoWebUiService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startAndOpen(context: Context) {
            val intent = Intent(context, TomatoWebUiService::class.java).setAction(ACTION_OPEN)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TomatoWebUiService::class.java))
        }
    }
}
