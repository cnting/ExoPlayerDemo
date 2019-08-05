package com.example.exoplayerdemo

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isEmpty
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.offline.Download
import com.google.android.exoplayer2.offline.DownloadHelper
import com.google.android.exoplayer2.offline.DownloadRequest
import com.google.android.exoplayer2.offline.DownloadService
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_NO_TRACKS
import com.google.android.exoplayer2.ui.DebugTextViewHelper
import com.google.android.exoplayer2.ui.DefaultTrackNameProvider
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    val uri = Uri.parse("http://res.uquabc.com/HLS/playlist.m3u8")
    //    val uri = Uri.parse("https://content.jwplatform.com/manifests/IPYHGrEj.m3u8")
    private var downloadHelper: DownloadHelper? = null
    private var exoPlayer: SimpleExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        fab.setOnClickListener {
            initExoPlayer()
        }
        playDownloadBtn.setOnClickListener {
            playDownloadContent()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadHelper?.release()
        exoPlayer?.release()
    }

    private fun initExoPlayer() {
        //轨道选择
        val trackSelector = DefaultTrackSelector()

        exoPlayer = ExoPlayerFactory.newSimpleInstance(this, trackSelector)
        playerView.player = exoPlayer

        val dataSourceFactory = DefaultDataSourceFactory(this, Util.getUserAgent(this, "ExoPlayerDemo"))

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)   //加速启动
            .createMediaSource(uri)

        exoPlayer!!.prepare(mediaSource)
        exoPlayer!!.playWhenReady = true
        exoPlayer!!.addListener(object : Player.EventListener {
            override fun onPlayerError(error: ExoPlaybackException?) {
                error?.printStackTrace()
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playWhenReady && trackSelectContainer.isEmpty()) {
                    initTrackSelectBtn(trackSelector, dataSourceFactory)
                }
            }
        })

        val debugTextViewHelper = DebugTextViewHelper(exoPlayer!!, debugText)
        debugTextViewHelper.start()
    }

    private fun initTrackSelectBtn(trackSelector: DefaultTrackSelector, dataSourceFactory: DefaultDataSourceFactory) {
        val defaultTrackNameProvider = DefaultTrackNameProvider(resources)   //获取分辨率的名字
        val parameters = trackSelector.parameters
        val currentMappedTrackInfo = trackSelector.currentMappedTrackInfo
        val trackGroups = currentMappedTrackInfo?.getTrackGroups(RENDERER_SUPPORT_NO_TRACKS)
        val length: Int = trackGroups?.length ?: 0
        (0 until length)
            .forEach { groupIndex ->
                val group = trackGroups?.get(groupIndex)
                val groupLength = group?.length ?: 0
                (0 until groupLength)
                    .forEach { trackIndex ->
                        val layout = LinearLayout(this)
                        layout.orientation = LinearLayout.HORIZONTAL
                        val btn = Button(this)
                        btn.text = defaultTrackNameProvider.getTrackName(group!!.getFormat(trackIndex))
                        btn.setOnClickListener {
                            Toast.makeText(this, "groupIndex:$groupIndex,trackIndex:$trackIndex", Toast.LENGTH_SHORT)
                                .show()
                            val builder: DefaultTrackSelector.ParametersBuilder? = parameters?.buildUpon()
                            builder?.clearSelectionOverrides()
                            val selectionOverride = DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
                            builder?.setSelectionOverride(RENDERER_SUPPORT_NO_TRACKS, trackGroups, selectionOverride)
                            trackSelector.setParameters(builder)
                        }
                        layout.addView(btn)
                        val btn1 = Button(this)
                        btn1.text = "点击下载"
                        btn1.setOnClickListener { download(btn1, dataSourceFactory, groupIndex, trackIndex) }
                        layout.addView(btn1)
                        trackSelectContainer.addView(layout)
                    }
            }
    }

    /**
     * 下载，一个uri只下载一种格式
     */
    private fun download(btn: Button, dataSourceFactory: DefaultDataSourceFactory, groupIndex: Int, trackIndex: Int) {
        downloadHelper =
            DownloadHelper.forHls(uri, dataSourceFactory, DefaultRenderersFactory(this))
        downloadHelper?.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper?) {
                val mappedTrackInfo = helper?.getMappedTrackInfo(0)
                (0 until downloadHelper!!.periodCount)
                    .forEach { periodIndex ->
                        downloadHelper!!.clearTrackSelections(periodIndex)
                        if (mappedTrackInfo != null) {
                            val selectionOverride = DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
                            downloadHelper!!.addTrackSelectionForSingleRenderer(
                                periodIndex,
                                0,
                                DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS,
                                listOf(selectionOverride)
                            )
                        }
                    }
                val downloadRequest = buildDownloadRequest(downloadHelper!!)
                startDownload(downloadRequest)
                runOnUiThread { btn.text = "开始下载" }
            }

            override fun onPrepareError(helper: DownloadHelper?, e: IOException?) {
            }
        })

        //更新进度
        val timer = Timer()
        val timerTask = object : TimerTask() {
            override fun run() {
                val download = (application as App).videoDownloadManager.downloadTracker.getDownload(uri)

                when {
                    download?.state == Download.STATE_DOWNLOADING -> runOnUiThread {
                        btn.text = "${download?.percentDownloaded}%"
                    }
                    download?.state == Download.STATE_COMPLETED -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_LONG).show()
                            btn.text = "下载完成"
                            playDownloadBtn.visibility = View.VISIBLE
                        }
                        timer.cancel()
                    }
                    download?.state == Download.STATE_FAILED -> {
                        runOnUiThread {
                            btn.text = "下载失败"
                        }
                        timer.cancel()
                    }
                }
            }
        }
        var isRunTask = false
        (application as App).videoDownloadManager.downloadTracker.addListener(object : VideoDownloadTracker.Listener {
            override fun onDownloadsChanged() {
                if (!isRunTask) {
                    timer.schedule(timerTask, 1000, 1000)
                    isRunTask = true
                } else {

                }
            }
        })
    }


    private fun buildDownloadRequest(downloadHelper: DownloadHelper): DownloadRequest {
        return downloadHelper.getDownloadRequest(Util.getUtf8Bytes("测试音频下载"))   //会显示在Notification上
    }

    private fun startDownload(downloadRequest: DownloadRequest) {
        DownloadService.sendAddDownload(this, VideoDownloadService::class.java, downloadRequest, false)
    }

    /**
     * 播放下载内容
     */
    private fun playDownloadContent() {
        val downloadRequest = (application as App).videoDownloadManager.downloadTracker.getDownloadRequest(uri)
        val dataSourceFactory =
            DefaultDataSourceFactory(this, Util.getUserAgent(this, "ExoPlayerDemo"))
        val mediaSource = DownloadHelper.createMediaSource(downloadRequest, dataSourceFactory)
        exoPlayer?.prepare(mediaSource)
        exoPlayer?.playWhenReady = true
    }
}
