package com.example.exoplayerdemo

import android.os.SystemClock
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.source.TrackGroup
import com.google.android.exoplayer2.source.chunk.MediaChunk
import com.google.android.exoplayer2.trackselection.BaseTrackSelection
import com.google.android.exoplayer2.trackselection.TrackSelection
import com.google.android.exoplayer2.upstream.BandwidthMeter
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter


/**
 * Created by cnting on 2019-08-03
 *
 */
enum class HLSQuality {
    Auto, Quality1080, Quality720, Quality480, NoValue
}

internal object HLSUtil {

    fun getQuality(format: Format): HLSQuality {
        return when (format.height) {
            1080 -> {
                HLSQuality.Quality1080
            }
            720 -> {
                HLSQuality.Quality720
            }
            480, 486 -> {
                HLSQuality.Quality480
            }
            else -> {
                HLSQuality.NoValue
            }
        }
    }

    fun isQualityPlayable(format: Format): Boolean {
        return format.height <= 1080
    }
}


class ClassAdaptiveTrackSelection private constructor(
    group: TrackGroup,
    tracks: IntArray,
    private val bandwidthMeter: BandwidthMeter,
    private val maxInitialBitrate: Int,
    minDurationForQualityIncreaseMs: Long,
    maxDurationForQualityDecreaseMs: Long,
    minDurationToRetainAfterDiscardMs: Long,
    private val bandwidthFraction: Float,
    private val bufferedFractionToLiveEdgeForQualityIncrease: Float
) : BaseTrackSelection(group, *tracks) {
    private val minDurationForQualityIncreaseUs: Long = minDurationForQualityIncreaseMs * 1000L
    private val maxDurationForQualityDecreaseUs: Long = maxDurationForQualityDecreaseMs * 1000L
    private val minDurationToRetainAfterDiscardUs: Long = minDurationToRetainAfterDiscardMs * 1000L

    private var selectedIndex: Int = 0
    private var reason: Int = 0

    class Factory(private val bandwidthMeter: BandwidthMeter) : TrackSelection.Factory {
        private val maxInitialBitrate = 2000000
        private val minDurationForQualityIncreaseMs = 10000
        private val maxDurationForQualityDecreaseMs = 25000
        private val minDurationToRetainAfterDiscardMs = 25000
        private val bandwidthFraction = 0.75f
        private val bufferedFractionToLiveEdgeForQualityIncrease = 0.75f


        fun createTrackSelection(group: TrackGroup, vararg tracks: Int): ClassAdaptiveTrackSelection {
            Log.d(ClassAdaptiveTrackSelection::class.java.simpleName, " Video player quality reset to Auto")
            sHLSQuality = HLSQuality.Auto

            return ClassAdaptiveTrackSelection(
                group,
                tracks,
                bandwidthMeter,
                maxInitialBitrate,
                minDurationForQualityIncreaseMs.toLong(),
                maxDurationForQualityDecreaseMs.toLong(),
                minDurationToRetainAfterDiscardMs.toLong(),
                bandwidthFraction,
                bufferedFractionToLiveEdgeForQualityIncrease
            )
        }
    }

    init {
        selectedIndex = determineIdealSelectedIndex(java.lang.Long.MIN_VALUE)
        reason = C.SELECTION_REASON_INITIAL
    }

    override fun updateSelectedTrack(playbackPositionUs: Long, bufferedDurationUs: Long, availableDurationUs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        // Stash the current selection, then make a new one.
        val currentSelectedIndex = selectedIndex
        selectedIndex = determineIdealSelectedIndex(nowMs)
        if (selectedIndex == currentSelectedIndex) {
            return
        }

        if (!isBlacklisted(currentSelectedIndex, nowMs)) {
            // Revert back to the current selection if conditions are not suitable for switching.
            val currentFormat = getFormat(currentSelectedIndex)
            val selectedFormat = getFormat(selectedIndex)
            if (selectedFormat.bitrate > currentFormat.bitrate && bufferedDurationUs < minDurationForQualityIncreaseUs(
                    availableDurationUs
                )
            ) {
                // The selected track is a higher quality, but we have insufficient buffer to safely switch
                // up. Defer switching up for now.
                selectedIndex = currentSelectedIndex
            } else if (selectedFormat.bitrate < currentFormat.bitrate && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
                // The selected track is a lower quality, but we have sufficient buffer to defer switching
                // down for now.
                selectedIndex = currentSelectedIndex
            }
        }
        // If we adapted, update the trigger.
        if (selectedIndex != currentSelectedIndex) {
            reason = C.SELECTION_REASON_ADAPTIVE
        }
    }

    override fun getSelectedIndex(): Int {
        return selectedIndex
    }

    override fun getSelectionReason(): Int {
        return reason
    }

    override fun getSelectionData(): Any? {
        return null
    }

    override fun evaluateQueueSize(playbackPositionUs: Long, queue: List<MediaChunk>): Int {
        if (queue.isEmpty()) {
            return 0
        }
        val queueSize = queue.size
        val bufferedDurationUs = queue[queueSize - 1].endTimeUs - playbackPositionUs
        if (bufferedDurationUs < minDurationToRetainAfterDiscardUs) {
            return queueSize
        }
        val idealSelectedIndex = determineIdealSelectedIndex(SystemClock.elapsedRealtime())
        val idealFormat = getFormat(idealSelectedIndex)
        // If the chunks contain video, discard from the first SD chunk beyond
        // minDurationToRetainAfterDiscardUs whose resolution and bitrate are both lower than the ideal
        // track.
        for (i in 0 until queueSize) {
            val chunk = queue[i]
            val format = chunk.trackFormat
            val durationBeforeThisChunkUs = chunk.startTimeUs - playbackPositionUs
            if (durationBeforeThisChunkUs >= minDurationToRetainAfterDiscardUs
                && format.bitrate < idealFormat.bitrate
                && format.height !== Format.NO_VALUE && format.height < 720
                && format.width !== Format.NO_VALUE && format.width < 1280
                && format.height < idealFormat.height
            ) {
                return i
            }
        }
        return queueSize
    }

    private fun determineIdealSelectedIndex(nowMs: Long): Int {
        if (sHLSQuality != HLSQuality.Auto) {
            Log.d(ClassAdaptiveTrackSelection::class.java.simpleName, " Video player quality seeking for $sHLSQuality")
            for (i in 0 until length) {
                val format = getFormat(i)
                if (HLSUtil.getQuality(format) == sHLSQuality) {
                    Log.d(
                        ClassAdaptiveTrackSelection::class.java.simpleName,
                        " Video player quality set to $sHLSQuality"
                    )
                    return i
                }
            }
        }

        Log.d(
            ClassAdaptiveTrackSelection::class.java.simpleName,
            " Video player quality seeking for auto quality $sHLSQuality"
        )
        val bitrateEstimate = bandwidthMeter.bitrateEstimate

        Log.d(ClassAdaptiveTrackSelection::class.java.simpleName, "===>bitrateEstimate:$bitrateEstimate")

        val effectiveBitrate: Float =
//        if (bitrateEstimate == BandwidthMeter.NO_ESTIMATE)
//            maxInitialBitrate.toFloat()
//        else
            bitrateEstimate * bandwidthFraction
        var lowestBitrateNonBlacklistedIndex = 0
        for (i in 0 until length) {
            if (nowMs == java.lang.Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
                val format = getFormat(i)
                if (format.bitrate <= effectiveBitrate && HLSUtil.isQualityPlayable(format)) {
                    Log.d(
                        ClassAdaptiveTrackSelection::class.java.simpleName,
                        " Video player quality auto quality found $sHLSQuality"
                    )
                    return i
                } else {
                    lowestBitrateNonBlacklistedIndex = i
                }
            }
        }
        return lowestBitrateNonBlacklistedIndex
    }

    private fun minDurationForQualityIncreaseUs(availableDurationUs: Long): Long {
        val isAvailableDurationTooShort =
            availableDurationUs != C.TIME_UNSET && availableDurationUs <= minDurationForQualityIncreaseUs
        return if (isAvailableDurationTooShort)
            (availableDurationUs * bufferedFractionToLiveEdgeForQualityIncrease).toLong()
        else
            minDurationForQualityIncreaseUs
    }

    companion object {

        private var sHLSQuality = HLSQuality.Auto

        internal fun setHLSQuality(HLSQuality: HLSQuality) {
            sHLSQuality = HLSQuality
        }
    }
}
