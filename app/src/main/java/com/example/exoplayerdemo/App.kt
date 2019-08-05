package com.example.exoplayerdemo

import android.app.Application

/**
 * Created by cnting on 2019-08-05
 *
 */
class App : Application() {

    lateinit var videoDownloadManager: VideoDownloadManager

    override fun onCreate() {
        super.onCreate()
        videoDownloadManager = VideoDownloadManager(this)
    }
}