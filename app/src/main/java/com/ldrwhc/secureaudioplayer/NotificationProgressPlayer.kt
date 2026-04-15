package com.ldrwhc.secureaudioplayer

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlin.math.max
import kotlin.math.min

class NotificationProgressPlayer(player: Player) : ForwardingPlayer(player) {
    var onSkipToNext: (() -> Unit)? = null
    var onSkipToPrevious: (() -> Unit)? = null
    var canSkipNextProvider: (() -> Boolean)? = null
    var canSkipPreviousProvider: (() -> Boolean)? = null

    @Volatile
    private var prefixDurationsMs: LongArray = LongArray(0)

    @Volatile
    private var totalDurationMs: Long = C.TIME_UNSET

    fun setTrackDurations(durationsMs: List<Long>) {
        if (durationsMs.isEmpty()) {
            prefixDurationsMs = LongArray(0)
            totalDurationMs = C.TIME_UNSET
            return
        }
        val prefix = LongArray(durationsMs.size + 1)
        var acc = 0L
        prefix[0] = 0L
        for (i in durationsMs.indices) {
            val d = durationsMs[i].coerceAtLeast(0L)
            acc += d
            prefix[i + 1] = acc
        }
        prefixDurationsMs = prefix
        totalDurationMs = if (acc > 0) acc else C.TIME_UNSET
    }

    override fun getDuration(): Long {
        val total = totalDurationMs
        return if (total != C.TIME_UNSET) total else super.getDuration()
    }

    override fun getContentDuration(): Long {
        return duration
    }

    override fun getCurrentMediaItemIndex(): Int {
        return if (totalDurationMs != C.TIME_UNSET && super.getMediaItemCount() > 0) 0 else super.getCurrentMediaItemIndex()
    }

    override fun getMediaItemCount(): Int {
        return if (totalDurationMs != C.TIME_UNSET && super.getMediaItemCount() > 0) 1 else super.getMediaItemCount()
    }

    override fun getCurrentPosition(): Long {
        return aggregatePosition(super.getCurrentPosition())
    }

    override fun getBufferedPosition(): Long {
        return aggregatePosition(super.getBufferedPosition())
    }

    override fun seekTo(positionMs: Long) {
        seekToAggregated(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (totalDurationMs != C.TIME_UNSET && mediaItemIndex <= 0) {
            seekToAggregated(positionMs)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun seekToNext() {
        if (totalDurationMs != C.TIME_UNSET) {
            onSkipToNext?.invoke() ?: super.seekToNext()
            return
        }
        super.seekToNext()
    }

    override fun seekToPrevious() {
        if (totalDurationMs != C.TIME_UNSET) {
            onSkipToPrevious?.invoke() ?: super.seekToPrevious()
            return
        }
        super.seekToPrevious()
    }

    override fun seekToNextMediaItem() {
        if (totalDurationMs != C.TIME_UNSET) {
            onSkipToNext?.invoke() ?: super.seekToNextMediaItem()
            return
        }
        super.seekToNextMediaItem()
    }

    override fun seekToPreviousMediaItem() {
        if (totalDurationMs != C.TIME_UNSET) {
            onSkipToPrevious?.invoke() ?: super.seekToPreviousMediaItem()
            return
        }
        super.seekToPreviousMediaItem()
    }

    override fun hasNextMediaItem(): Boolean {
        if (totalDurationMs != C.TIME_UNSET) {
            return canSkipNextProvider?.invoke() ?: super.hasNextMediaItem()
        }
        return super.hasNextMediaItem()
    }

    override fun hasPreviousMediaItem(): Boolean {
        if (totalDurationMs != C.TIME_UNSET) {
            return canSkipPreviousProvider?.invoke() ?: super.hasPreviousMediaItem()
        }
        return super.hasPreviousMediaItem()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (totalDurationMs != C.TIME_UNSET) {
            when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> return canSkipNextProvider?.invoke() ?: true

                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> return canSkipPreviousProvider?.invoke() ?: true
            }
        }
        return super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val base = super.getAvailableCommands()
        if (totalDurationMs == C.TIME_UNSET) return base

        val builder = base.buildUpon()
        val canNext = canSkipNextProvider?.invoke() ?: true
        val canPrevious = canSkipPreviousProvider?.invoke() ?: true

        val nextCommands = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
        val previousCommands = intArrayOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        nextCommands.forEach { cmd ->
            if (canNext) builder.add(cmd) else builder.remove(cmd)
        }
        previousCommands.forEach { cmd ->
            if (canPrevious) builder.add(cmd) else builder.remove(cmd)
        }

        return builder.build()
    }

    fun getTotalDurationMs(): Long {
        return duration
    }

    fun getAggregatedPositionMs(): Long {
        return currentPosition
    }

    fun seekToAggregated(positionMs: Long) {
        val prefix = prefixDurationsMs
        if (prefix.size <= 1 || totalDurationMs == C.TIME_UNSET) {
            super.seekTo(max(0L, positionMs))
            return
        }
        val target = min(max(0L, positionMs), totalDurationMs)
        var item = 0
        while (item < prefix.size - 1 && prefix[item + 1] <= target) item += 1
        val offset = (target - prefix[item]).coerceAtLeast(0L)
        super.seekTo(item, offset)
    }

    private fun aggregatePosition(positionInCurrentItemMs: Long): Long {
        val prefix = prefixDurationsMs
        if (prefix.size <= 1) return positionInCurrentItemMs
        val itemIndex = super.getCurrentMediaItemIndex().coerceIn(0, prefix.size - 2)
        val base = prefix[itemIndex]
        val merged = base + positionInCurrentItemMs.coerceAtLeast(0L)
        val total = totalDurationMs
        return if (total != C.TIME_UNSET) merged.coerceAtMost(total) else merged
    }
}
