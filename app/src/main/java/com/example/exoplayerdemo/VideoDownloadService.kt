package com.example.exoplayerdemo

import android.app.Notification
import com.google.android.exoplayer2.offline.Download
import com.google.android.exoplayer2.offline.DownloadManager
import com.google.android.exoplayer2.offline.DownloadService
import com.google.android.exoplayer2.scheduler.PlatformScheduler
import com.google.android.exoplayer2.scheduler.Scheduler
import com.google.android.exoplayer2.ui.DownloadNotificationHelper
import com.google.android.exoplayer2.util.NotificationUtil
import com.google.android.exoplayer2.util.Util

/**
 * Created by cnting on 2019-08-05
 * 下载
 */
class VideoDownloadService : DownloadService(
    1,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    "download_channel",
    R.string.download_channel_name
) {

    private val CHANNEL_ID = "download_channel"
    private val JOB_ID = 1
    private val FOREGROUND_NOTIFICATION_ID = 1
    private var nextNotificationId = FOREGROUND_NOTIFICATION_ID + 1

    lateinit var notificationHelper: DownloadNotificationHelper

    override fun onCreate() {
        super.onCreate()
        notificationHelper = DownloadNotificationHelper(this, CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager {
        return (application as App).videoDownloadManager.downloadManager
    }

    override fun getForegroundNotification(downloads: MutableList<Download>?): Notification {
        return notificationHelper.buildProgressNotification(R.mipmap.ic_download, null, null, downloads)
    }

    override fun getScheduler(): Scheduler? {
        return if (Util.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null
    }

    override fun onDownloadChanged(download: Download?) {
        val notification: Notification? = when {
            download?.state == Download.STATE_COMPLETED -> notificationHelper.buildDownloadCompletedNotification(
                R.mipmap.ic_download_done,
                /* contentIntent= */ null,
                Util.fromUtf8Bytes(download.request.data)
            )/* contentIntent= */
            download?.state == Download.STATE_FAILED -> notificationHelper.buildDownloadFailedNotification(
                android.R.drawable.stat_notify_error, null,
                Util.fromUtf8Bytes(download.request.data)
            )
            else -> return
        }
        NotificationUtil.setNotification(this, nextNotificationId++, notification)
    }
}
 