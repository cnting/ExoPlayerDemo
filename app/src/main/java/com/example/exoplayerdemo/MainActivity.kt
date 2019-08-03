package com.example.exoplayerdemo

import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity;
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.Toast
import androidx.core.view.isEmpty
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.ExoPlayerFactory
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.*
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo.RENDERER_SUPPORT_NO_TRACKS
import com.google.android.exoplayer2.ui.DebugTextViewHelper
import com.google.android.exoplayer2.ui.DefaultTrackNameProvider
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.util.Util
import com.google.android.exoplayer2.video.VideoListener

import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        fab.setOnClickListener {
            initExoPlayer()
        }
    }

    private fun initExoPlayer() {
        //频带宽度统计
        val defaultBandwidthMeter = DefaultBandwidthMeter.Builder(this).build()

        //轨道选择
        val trackSelector = DefaultTrackSelector()

        val exoPlayer = ExoPlayerFactory.newSimpleInstance(this, trackSelector)
        playerView.player = exoPlayer

        val dataSourceFactory: DefaultDataSourceFactory =
            DefaultDataSourceFactory(this, Util.getUserAgent(this, "Exo"), defaultBandwidthMeter)

//        val url = "http://res.uquabc.com/HLS/playlist.m3u8"
        val url = "https://content.jwplatform.com/manifests/IPYHGrEj.m3u8"
        val uri = Uri.parse(url)

        val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(true)   //加速启动
            .createMediaSource(uri)

        exoPlayer.prepare(mediaSource)
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(object : Player.EventListener {
            override fun onPlayerError(error: ExoPlaybackException?) {
                error?.printStackTrace()
            }

            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playWhenReady && trackSelectContainer.isEmpty()) {
                    initTrackSelectBtn(trackSelector)
                }
            }
        })

        val debugTextViewHelper = DebugTextViewHelper(exoPlayer, debugText)
        debugTextViewHelper.start()
    }

    private fun initTrackSelectBtn(trackSelector: DefaultTrackSelector) {
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
                        trackSelectContainer.addView(btn)
                    }
            }
    }


}
