package com.visionstudio.feature.video.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.visionstudio.feature.video.R
import com.visionstudio.feature.video.VideoDeepLink
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Everything the video worker needs to tell the user. A seam so tests can observe it. */
interface VideoNotifier {
    /** The ongoing notification that keeps the worker alive as a foreground service. */
    fun foregroundInfo(jobId: String, prompt: String, progress: Float?, message: String?): ForegroundInfo

    fun showCompleted(jobId: String, prompt: String)

    fun showFailed(jobId: String, prompt: String, reason: String)
}

@Singleton
internal class SystemVideoNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoNotifier {

    override fun foregroundInfo(jobId: String, prompt: String, progress: Float?, message: String?): ForegroundInfo {
        ensureChannels()
        val percent = progress?.let { (it * PERCENT).roundToInt().coerceIn(0, PERCENT.toInt()) }
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_video)
            .setContentTitle(context.getString(R.string.video_notif_rendering_title))
            .setContentText(listOfNotNull(percent?.let { "$it%" }, message ?: context.getString(R.string.video_notif_starting)).joinToString(" · "))
            .setSubText(prompt.shortened())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(PERCENT.toInt(), percent ?: 0, percent == null)
            .setContentIntent(openAppIntent())
            .build()
        val id = progressId(jobId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    override fun showCompleted(jobId: String, prompt: String) {
        post(
            resultId(jobId),
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification_video)
                .setContentTitle(context.getString(R.string.video_notif_done_title))
                .setContentText(prompt.shortened())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    override fun showFailed(jobId: String, prompt: String, reason: String) {
        post(
            resultId(jobId),
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification_video)
                .setContentTitle(context.getString(R.string.video_notif_failed_title))
                .setContentText(reason.shortened())
                .setStyle(NotificationCompat.BigTextStyle().bigText("${prompt.shortened()}\n$reason"))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(openAppIntent())
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // guarded by canNotify()
    private fun post(id: Int, notification: Notification) {
        ensureChannels()
        if (!canNotify()) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.video_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.video_channel_progress_desc) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.video_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.video_channel_done_desc) },
        )
    }

    private fun openAppIntent(): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.putExtra(VideoDeepLink.EXTRA_OPEN, VideoDeepLink.OPEN_VIDEO).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun String.shortened(): String = if (length <= MAX_TEXT) this else take(MAX_TEXT - 1).trimEnd() + "…"

    companion object {
        const val CHANNEL_PROGRESS: String = "video_progress"
        const val CHANNEL_DONE: String = "video_done"
        private const val MAX_TEXT = 120
        private const val PERCENT = 100f

        /** Distinct ids: WorkManager removes the foreground notification when the worker ends, which would also remove a result posted under the same id. */
        internal fun progressId(jobId: String): Int = (jobId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        internal fun resultId(jobId: String): Int = (progressId(jobId) xor RESULT_SALT) and Int.MAX_VALUE
        private const val RESULT_SALT = 0x5A5A5A5A
    }
}
