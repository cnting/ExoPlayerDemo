package com.example.exoplayerdemo

import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.get
import androidx.core.view.isEmpty
import androidx.viewpager.widget.PagerAdapter
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.offline.Download
import com.google.android.exoplayer2.offline.DownloadHelper
import com.google.android.exoplayer2.offline.DownloadRequest
import com.google.android.exoplayer2.offline.DownloadService
import com.google.android.exoplayer2.source.TrackGroupArray
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo.*
import com.google.android.exoplayer2.trackselection.TrackSelectionArray
import com.google.android.exoplayer2.ui.DebugTextViewHelper
import com.google.android.exoplayer2.ui.DefaultTrackNameProvider
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.CacheDataSourceFactory
import com.google.android.exoplayer2.util.Util
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.view.*
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity() {

    private val uris = arrayOf(
        Uri.parse("http://res.uquabc.com/HLS_Apple/all.m3u8"),
        Uri.parse("https://content.jwplatform.com/manifests/IPYHGrEj.m3u8")
    )
    private var downloadHelper: DownloadHelper? = null
    private var exoPlayer: SimpleExoPlayer? = null
    private var debugTextViewHelper: DebugTextViewHelper? = null
    private lateinit var downloadTracker: VideoDownloadTracker
    private var uriIndex = 0
    private var trackSelectContainer: LinearLayout? = null
    private var downloadContainer: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViewPager()
        downloadTracker = (application as App).videoDownloadManager.downloadTracker
        initExoPlayer()
        changeVideoBtn.setOnClickListener {
            changeVideoBtn.text = "切换视频$uriIndex"
            uriIndex = if (uriIndex == 0) {
                1
            } else {
                0
            }
            exoPlayer?.release()
            debugTextViewHelper?.stop()
            initExoPlayer()
        }
        loadHasDownloads()
    }

    private fun initViewPager() {
        val title = arrayOf("切换清晰度", "已下载列表")
        tabLayout.setupWithViewPager(viewPager)
        viewPager.adapter = object : PagerAdapter() {
            override fun isViewFromObject(view: View, `object`: Any): Boolean {
                return view == `object`
            }

            override fun getCount(): Int {
                return 2
            }

            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                val layout = LinearLayout(this@MainActivity)
                layout.orientation = LinearLayout.VERTICAL
                layout.layoutParams =
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                if (position == 0) {
                    trackSelectContainer = layout
                } else {
                    downloadContainer = layout
                }
                container.addView(layout)
                return layout
            }

            override fun getPageTitle(position: Int): CharSequence? {
                return title[position]
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadHelper?.release()
        exoPlayer?.release()
        debugTextViewHelper?.stop()
    }

    override fun onPause() {
        super.onPause()
        playerView.onPause()
        exoPlayer?.playWhenReady = false
    }

    override fun onResume() {
        super.onResume()
        playerView.onResume()
    }

    private fun initExoPlayer() {
        //轨道选择，包括音频轨道和视频轨道
        val trackSelector = DefaultTrackSelector()
        val dataSourceFactory = (application as App).videoDownloadManager.buildDataSourceFactory

        exoPlayer = ExoPlayerFactory.newSimpleInstance(this, trackSelector)
        playerView.player = exoPlayer
        exoPlayer!!.playWhenReady = true
        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)   //加速启动
            .createMediaSource(uris[uriIndex])

        exoPlayer!!.prepare(mediaSource)
        exoPlayer!!.addListener(object : Player.EventListener {
            override fun onPlayerError(error: ExoPlaybackException?) {
                error?.printStackTrace()
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playbackState == Player.STATE_READY && playWhenReady) {
                    initTrackSelectBtn(trackSelector, dataSourceFactory)
                }
            }

            override fun onTracksChanged(trackGroups: TrackGroupArray, trackSelections: TrackSelectionArray) {
                val format = trackSelections.get(RENDERER_SUPPORT_NO_TRACKS)?.selectedFormat
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "当前分辨率:${format?.width}x${format?.height}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        })

        debugTextViewHelper = DebugTextViewHelper(exoPlayer!!, debugText)
        debugTextViewHelper?.start()
    }

    /**
     * 获取分辨率列表，点击切换
     */
    private fun initTrackSelectBtn(trackSelector: DefaultTrackSelector, dataSourceFactory: DataSource.Factory) {
        trackSelectContainer?.removeAllViews()
        val defaultTrackNameProvider = DefaultTrackNameProvider(resources)   //获取分辨率的名字
        val parameters = trackSelector.parameters
        val currentMappedTrackInfo = trackSelector.currentMappedTrackInfo
        //RENDERER_SUPPORT_UNSUPPORTED_TRACK：:获取音频轨道
        //RENDERER_SUPPORT_NO_TRACKS：获取视频轨道
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
                        trackSelectContainer?.addView(layout)
                    }
            }
    }

    /**
     * 下载，一个uri只保存一种分辨率的文件
     */
    private fun download(btn: Button, dataSourceFactory: DataSource.Factory, groupIndex: Int, trackIndex: Int) {
        downloadHelper =
            DownloadHelper.forHls(uris[uriIndex], dataSourceFactory, DefaultRenderersFactory(this))
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
                val download = downloadTracker.getDownload(uris[uriIndex])

                when {
                    download?.state == Download.STATE_DOWNLOADING -> runOnUiThread {
                        btn.text = "${download?.percentDownloaded}%"
                    }
                    download?.state == Download.STATE_COMPLETED -> {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_LONG).show()
                            btn.text = "下载完成"
                            loadHasDownloads()
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
        downloadTracker.addListener(object : VideoDownloadTracker.Listener {
            override fun onDownloadsChanged() {
                if (!isRunTask) {
                    timer.schedule(timerTask, 1000, 1000)
                    isRunTask = true
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
    private fun playDownloadContent(uri: Uri) {
        val downloadRequest = downloadTracker.getDownloadRequest(uri)
        val mediaSource = DownloadHelper.createMediaSource(
            downloadRequest,
            (application as App).videoDownloadManager.buildDataSourceFactory
        )
        exoPlayer?.prepare(mediaSource)
    }

    private fun loadHasDownloads() {
        downloadContainer?.removeAllViews()
        uris.forEach { uri ->
            if (downloadTracker.isDownloaded(uri)) {
                val btn = Button(this)
                btn.text = uri.toString()
                btn.setOnClickListener { playDownloadContent(uri) }
                downloadContainer?.addView(btn)
            }
        }
    }
}
