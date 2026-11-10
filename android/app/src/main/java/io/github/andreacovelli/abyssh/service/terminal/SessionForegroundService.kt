package io.github.andreacovelli.abyssh.service.terminal

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.andreacovelli.abyssh.MainActivity
import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.data.repository.SettingsRepository
import io.github.andreacovelli.abyssh.ui.theme.GhosttyThemeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionForegroundService : Service() {
    private val scope = MainScope()
    private var currentLabel: String? = null
    private var endsSeveral = false
    private var colorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_DISCONNECT -> {
                val app = applicationContext as Application
                runCatching {
                    TerminalSessionRepository.getInstance(app).disconnect()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val label = intent?.getStringExtra(EXTRA_LABEL)
                if (!label.isNullOrBlank()) {
                    currentLabel = label
                }
                endsSeveral = intent?.getBooleanExtra(EXTRA_ENDS_SEVERAL, endsSeveral) ?: endsSeveral
                if (!showNotification()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Sessions die with the process, so a restarted service would show a
                // session that no longer exists and keep the process in the foreground.
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Puts the service in the foreground with the current notification; false when the system refuses. */
    // The system can refuse with several exception types, such as
    // ForegroundServiceStartNotAllowedException, depending on the Android version.
    @Suppress("TooGenericExceptionCaught")
    private fun showNotification(): Boolean {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(currentLabel ?: getString(R.string.session_notification_fallback)),
                type,
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start refused", e)
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_sessions),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_sessions_description)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String): Notification {
        // The action ends every session, so say so once there is more than one.
        val disconnectLabel =
            if (endsSeveral) R.string.session_notification_disconnect_all else R.string.server_list_disconnect_confirm
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        val disconnectIntent = Intent(this, SessionForegroundService::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            pendingFlags,
        )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_connected_title))
            .setContentText(label)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(tapPending)
            .addAction(0, getString(disconnectLabel), disconnectPending)

        resolveNotificationColor()?.let { argb ->
            notificationBuilder
                .setColor(argb)
                .setColorized(true)
        }

        val notification = notificationBuilder.build()
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    /**
     * The theme's background, if the theme was already read. Otherwise it is read off the
     * main thread, and the notification is shown again with the color once it is.
     */
    private fun resolveNotificationColor(): Int? {
        val selectedTheme = SettingsRepository.getInstance(applicationContext).theme.darkThemeName.value
        GhosttyThemeRegistry.cachedTheme(selectedTheme)?.let { return it.background.toArgb() }
        if (colorJob?.isActive != true) {
            colorJob = scope.launch {
                val theme = withContext(Dispatchers.IO) {
                    GhosttyThemeRegistry.init(applicationContext)
                    GhosttyThemeRegistry.getTheme(applicationContext, selectedTheme)
                }
                if (theme != null) showNotification()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "SessionService"
        private const val CHANNEL_ID = "abyssh_terminal_session"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_START = "io.github.andreacovelli.abyssh.action.START_SESSION_SERVICE"
        private const val ACTION_STOP = "io.github.andreacovelli.abyssh.action.STOP_SESSION_SERVICE"
        private const val ACTION_DISCONNECT = "io.github.andreacovelli.abyssh.action.DISCONNECT_SESSION"
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_ENDS_SEVERAL = "extra_ends_several"

        /**
         * Shows [label]; [endsSeveral] says the Disconnect action ends more than one
         * session. Returns false when the system refused to start the service.
         */
        fun start(context: Context, label: String, endsSeveral: Boolean = false): Boolean {
            val intent = Intent(context, SessionForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ENDS_SEVERAL, endsSeveral)
            return runCatching { context.startForegroundService(intent) }.onFailure { Log.e(TAG, "Session service start refused", it) }.isSuccess
        }

        fun stop(context: Context) {
            val stopIntent = Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(stopIntent) }
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
