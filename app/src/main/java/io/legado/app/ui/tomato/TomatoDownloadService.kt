package io.legado.app.ui.tomato

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import kotlinx.coroutines.launch

class TomatoDownloadService : LifecycleService() {

    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "legado:TomatoDownload",
        ).apply { setReferenceCounted(false) }
    }
    private var observedActiveDownload = false

    override fun onCreate() {
        super.onCreate()
        wakeLock.acquire(MAX_WAKE_LOCK_DURATION_MS)
        lifecycleScope.launch {
            TomatoDownloadViewModel.sharedState.collect(::onStateChanged)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NotificationId.TomatoDownloadService,
            buildNotification(TomatoDownloadViewModel.sharedState.value),
        )
        if (intent?.action == ACTION_CANCEL) {
            TomatoDownloadViewModel.cancelActiveDownload()
        }
        return Service.START_NOT_STICKY
    }

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun onStateChanged(state: TomatoDownloadState) {
        val active = state.phase in ACTIVE_PHASES
        if (active) observedActiveDownload = true
        notificationManager.notify(
            NotificationId.TomatoDownloadService,
            buildNotification(state),
        )
        if (observedActiveDownload && !active) {
            detachForegroundNotification()
            stopSelf()
        }
    }

    private fun buildNotification(state: TomatoDownloadState): Notification {
        val title = state.bookInfo?.title ?: getString(R.string.tomato_novel_download)
        val text = when (state.phase) {
            TomatoPhase.DOWNLOADING -> getString(
                R.string.tomato_downloading,
                state.current,
                state.total,
                state.currentChapter,
            )
            TomatoPhase.GENERATING -> getString(R.string.tomato_generating_epub)
            TomatoPhase.COMPLETE -> if (state.failedCount == 0) {
                getString(R.string.tomato_download_complete)
            } else {
                getString(R.string.tomato_download_complete_with_failures, state.failedCount)
            }
            TomatoPhase.ERROR -> state.error ?: getString(R.string.download_error)
            TomatoPhase.READY -> if (observedActiveDownload) {
                getString(R.string.tomato_cancelled)
            } else {
                getString(R.string.tomato_background_preparing)
            }
            else -> getString(R.string.tomato_background_preparing)
        }
        val active = state.phase in ACTIVE_PHASES
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openActivityPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(active)
            .setAutoCancel(!active)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (state.phase == TomatoPhase.DOWNLOADING) {
                    setProgress(state.total.coerceAtLeast(1), state.current, false)
                } else if (active) {
                    setProgress(0, 0, true)
                }
                if (active) {
                    addAction(
                        R.drawable.ic_stop_black_24dp,
                        getString(R.string.cancel),
                        cancelPendingIntent(),
                    )
                }
            }
            .build()
    }

    private fun openActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, TomatoDownloadActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, TomatoDownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @Suppress("DEPRECATION")
    private fun detachForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(false)
        }
    }

    companion object {
        private const val ACTION_CANCEL = "io.legado.app.action.CANCEL_TOMATO_DOWNLOAD"
        private const val MAX_WAKE_LOCK_DURATION_MS = 6 * 60 * 60 * 1_000L
        private val ACTIVE_PHASES = setOf(TomatoPhase.DOWNLOADING, TomatoPhase.GENERATING)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TomatoDownloadService::class.java),
            )
        }
    }
}
