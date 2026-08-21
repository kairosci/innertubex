package com.metrolist.innertubex.sabr

internal object SabrProtoCodec {
    private const val DISCARD_SENTINEL = Int.MAX_VALUE
    private const val MAX_CONTEXT_POLICY_ENTRIES = 256

    fun encodeRequest(
        bootstrap: SabrBootstrap,
        playerTimeMs: Long,
        initialized: Boolean,
        bufferedRanges: List<SabrBufferedRange>,
        playbackCookie: ByteArray?,
        contexts: Collection<SabrContext>,
        activeContextTypes: Set<Int> = emptySet(),
        mediaType: SabrMediaType = SabrMediaType.AUDIO,
    ): ByteArray {
        val videoFormat =
            if (mediaType == SabrMediaType.VIDEO) {
                requireNotNull(bootstrap.selectedVideoFormat) { "SABR video format is not selected" }
            } else {
                bootstrap.discardVideoFormat
            }
        val videoHeight =
            if (mediaType == SabrMediaType.VIDEO) {
                requireNotNull(bootstrap.selectedVideoHeight) { "SABR video height is not selected" }
            } else {
                bootstrap.discardVideoHeight
            }
        return ProtoWriter()
            .apply {
                message(1) {
                    if (videoHeight != 0) int32(21, videoHeight)
                    if (playerTimeMs != 0L) int64(28, playerTimeMs)
                    int32(34, 1)
                    float(35, 1f)
                    int32(40, if (mediaType == SabrMediaType.AUDIO) 1 else 2)
                    if (bootstrap.isDrc) bool(46, true)
                    bootstrap.audioTrackId?.takeIf(String::isNotEmpty)?.let { string(69, it) }
                }

                if (initialized) {
                    message(2) {
                        formatId(if (mediaType == SabrMediaType.AUDIO) bootstrap.audioFormat else videoFormat)
                    }
                }
                message(2) {
                    formatId(if (mediaType == SabrMediaType.AUDIO) videoFormat else bootstrap.audioFormat)
                }
                bufferedRanges.forEach { range -> message(3) { bufferedRange(range) } }
                message(3) {
                    discardRange(if (mediaType == SabrMediaType.AUDIO) videoFormat else bootstrap.audioFormat)
                }
                bytes(5, bootstrap.videoPlaybackUstreamerConfig)
                message(16) { formatId(bootstrap.audioFormat) }
                message(17) { formatId(videoFormat) }
                message(19) {
                    message(1) {
                        int32(16, bootstrap.clientName)
                        string(17, bootstrap.clientVersion)
                    }
                    bootstrap.poToken?.let { bytes(2, it) }
                    playbackCookie?.let { bytes(3, it) }
                    contexts.filter { it.type in activeContextTypes }.forEach { context ->
                        message(5) {
                            int32(1, context.type)
                            bytes(2, context.value)
                        }
                    }
                    packedInt32(6, contexts.filterNot { it.type in activeContextTypes }.map(SabrContext::type))
                }
            }.toByteArray()
    }

    fun decodeMediaHeader(data: ByteArray): SabrMediaHeader {
        val reader = ProtoReader(data)
        var headerId = 0
        var videoId: String? = null
        var formatId = SabrFormatId(0)
        var startRange = 0L
        var compressionAlgorithm = 0
        var isInitSegment = false
        var sequenceNumber = 0
        var startMs = 0L
        var durationMs = 0L
        var contentLength = 0L
        var timeRange: Pair<Long, Long>? = null
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> headerId = reader.varint().toInt()
                2 -> videoId = reader.string()
                3 -> formatId = formatId.copy(itag = reader.varint().toInt())
                4 -> formatId = formatId.copy(lastModified = reader.varint())
                5 -> formatId = formatId.copy(xtags = reader.string())
                6 -> startRange = reader.varint()
                7 -> compressionAlgorithm = reader.varint().toInt()
                8 -> isInitSegment = reader.bool()
                9 -> sequenceNumber = reader.varint().toInt()
                11 -> startMs = reader.varint()
                12 -> durationMs = reader.varint()
                13 -> formatId = decodeFormatId(reader.bytes())
                14 -> contentLength = reader.varint()
                15 -> timeRange = decodeTimeRange(reader.bytes())
                else -> reader.skip(tag)
            }
        }
        if (headerId !in 0..255) throw SabrProtocolException("Invalid SABR media header ID $headerId")
        if (formatId.itag <= 0) throw SabrProtocolException("SABR media header has no format ID")
        if (startRange < 0 || contentLength < 0 || startMs < 0 || durationMs < 0) {
            throw SabrProtocolException("SABR media header contains an overflowing unsigned value")
        }
        if (startMs == 0L) startMs = timeRange?.first ?: 0L
        if (durationMs == 0L) durationMs = timeRange?.second ?: 0L
        return SabrMediaHeader(
            headerId = headerId,
            videoId = videoId,
            formatId = formatId,
            startRange = startRange,
            compressionAlgorithm = compressionAlgorithm,
            isInitSegment = isInitSegment,
            sequenceNumber = sequenceNumber,
            startMs = startMs,
            durationMs = durationMs,
            contentLength = contentLength,
        )
    }

    fun decodeFormatInitialization(data: ByteArray): SabrFormatInitialization? {
        val reader = ProtoReader(data)
        var formatId: SabrFormatId? = null
        var endTimeMs: Long? = null
        var endSegmentNumber: Int? = null
        var durationUnits: Long? = null
        var durationTimescale: Long? = null
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                2 -> {
                    formatId = decodeFormatId(reader.bytes())
                }

                3 -> {
                    endTimeMs = reader.varint().takeIf { it >= 0 }
                }

                4 -> {
                    val value = reader.varint()
                    if (value in 0..Int.MAX_VALUE.toLong()) endSegmentNumber = value.toInt()
                }

                9 -> {
                    durationUnits = reader.varint().takeIf { it >= 0 }
                }

                10 -> {
                    durationTimescale = reader.varint().takeIf { it in 1..Int.MAX_VALUE.toLong() }
                }

                else -> {
                    reader.skip(tag)
                }
            }
        }
        val id = formatId ?: return null
        val durationMs =
            if (durationUnits != null && durationTimescale != null) {
                ticksToMs(durationUnits, durationTimescale)
            } else {
                endTimeMs
            }
        return SabrFormatInitialization(id, endTimeMs, endSegmentNumber, durationMs)
    }

    fun decodeNextRequestPolicy(data: ByteArray): SabrEvent.NextRequestPolicy {
        val reader = ProtoReader(data)
        var targetAudioReadaheadMs = 0L
        var maxTimeSinceLastRequestMs = 0L
        var backoffTimeMs = 0L
        var minAudioReadaheadMs = 0L
        var playbackCookie: ByteArray? = null
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> targetAudioReadaheadMs = reader.varint()
                3 -> maxTimeSinceLastRequestMs = reader.varint()
                4 -> backoffTimeMs = reader.varint()
                5 -> minAudioReadaheadMs = reader.varint()
                7 -> playbackCookie = reader.bytes()
                else -> reader.skip(tag)
            }
        }
        if (
            targetAudioReadaheadMs < 0 ||
            maxTimeSinceLastRequestMs < 0 ||
            backoffTimeMs < 0 ||
            minAudioReadaheadMs < 0
        ) {
            throw SabrProtocolException("SABR next-request policy contains an overflowing unsigned value")
        }
        return SabrEvent.NextRequestPolicy(
            targetAudioReadaheadMs = targetAudioReadaheadMs,
            maxTimeSinceLastRequestMs = maxTimeSinceLastRequestMs,
            backoffTimeMs = backoffTimeMs,
            minAudioReadaheadMs = minAudioReadaheadMs,
            playbackCookie = playbackCookie,
        )
    }

    fun decodeRedirect(data: ByteArray): String? {
        val reader = ProtoReader(data)
        while (reader.hasRemaining) {
            val tag = reader.tag()
            if (tag.field == 1) return reader.string()
            reader.skip(tag)
        }
        return null
    }

    fun decodeError(data: ByteArray): SabrEvent.Error {
        val reader = ProtoReader(data)
        var type: String? = null
        var code = 0
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> type = reader.string()
                2 -> code = reader.varint().toInt()
                else -> reader.skip(tag)
            }
        }
        return SabrEvent.Error(type, code)
    }

    fun decodeContextUpdate(data: ByteArray): SabrContext? {
        val reader = ProtoReader(data)
        var type = 0
        var value: ByteArray? = null
        var sendByDefault = false
        var writePolicy = 0
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> type = reader.varint().toInt()
                3 -> value = reader.bytes()
                4 -> sendByDefault = reader.bool()
                5 -> writePolicy = reader.varint().toInt()
                else -> reader.skip(tag)
            }
        }
        return value?.let { SabrContext(type, it, sendByDefault, writePolicy) }
    }

    fun decodeStreamProtectionStatus(data: ByteArray): SabrEvent.StreamProtectionStatus {
        val reader = ProtoReader(data)
        var status = 0
        var maxRetries = 0
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> status = reader.varint().toInt()
                2 -> maxRetries = reader.varint().toInt()
                else -> reader.skip(tag)
            }
        }
        return SabrEvent.StreamProtectionStatus(status, maxRetries)
    }

    fun decodeContextSendingPolicy(data: ByteArray): SabrEvent.ContextSendingPolicy {
        val reader = ProtoReader(data)
        val start = mutableSetOf<Int>()
        val stop = mutableSetOf<Int>()
        val discard = mutableSetOf<Int>()
        var entryCount = 0
        while (reader.hasRemaining) {
            val tag = reader.tag()
            val target =
                when (tag.field) {
                    1 -> start
                    2 -> stop
                    3 -> discard
                    else -> null
                }
            if (target == null) {
                reader.skip(tag)
            } else if (tag.wireType == 2) {
                val packed = ProtoReader(reader.bytes())
                while (packed.hasRemaining) {
                    if (++entryCount > MAX_CONTEXT_POLICY_ENTRIES) {
                        throw SabrProtocolException("SABR context policy exceeded the entry limit")
                    }
                    target += packed.varint().toInt()
                }
            } else {
                if (++entryCount > MAX_CONTEXT_POLICY_ENTRIES) {
                    throw SabrProtocolException("SABR context policy exceeded the entry limit")
                }
                target += reader.varint().toInt()
            }
        }
        return SabrEvent.ContextSendingPolicy(start, stop, discard)
    }

    private fun decodeTimeRange(data: ByteArray): Pair<Long, Long>? {
        val reader = ProtoReader(data)
        var startTicks = 0L
        var durationTicks = 0L
        var timescale = 0L
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> startTicks = reader.varint()
                2 -> durationTicks = reader.varint()
                3 -> timescale = reader.varint()
                else -> reader.skip(tag)
            }
        }
        if (startTicks < 0 || durationTicks < 0 || timescale !in 1..Int.MAX_VALUE.toLong()) return null
        return ticksToMs(startTicks, timescale) to ticksToMs(durationTicks, timescale)
    }

    private fun ticksToMs(
        ticks: Long,
        timescale: Long,
    ): Long {
        val wholeSeconds = ticks / timescale
        if (wholeSeconds > Long.MAX_VALUE / 1000L) {
            throw SabrProtocolException("SABR time range exceeds the supported duration")
        }
        return wholeSeconds * 1000L + ticks % timescale * 1000L / timescale
    }

    private fun ProtoWriter.formatId(formatId: SabrFormatId) {
        int32(1, formatId.itag)
        if (formatId.lastModified != 0L) int64(2, formatId.lastModified)
        formatId.xtags?.takeIf(String::isNotEmpty)?.let { string(3, it) }
    }

    private fun ProtoWriter.bufferedRange(range: SabrBufferedRange) {
        message(1) { formatId(range.formatId) }
        if (range.startTimeMs != 0L) int64(2, range.startTimeMs)
        int64(3, range.durationMs)
        int32(4, range.startSegmentIndex)
        int32(5, range.endSegmentIndex)
        message(6) {
            if (range.startTimeMs != 0L) int64(1, range.startTimeMs)
            int64(2, range.durationMs)
            int32(3, 1000)
        }
    }

    private fun ProtoWriter.discardRange(formatId: SabrFormatId) {
        message(1) { formatId(formatId) }
        int64(3, DISCARD_SENTINEL.toLong())
        int32(4, DISCARD_SENTINEL)
        int32(5, DISCARD_SENTINEL)
        message(6) {
            int64(2, DISCARD_SENTINEL.toLong())
            int32(3, 1000)
        }
    }

    private fun decodeFormatId(data: ByteArray): SabrFormatId {
        val reader = ProtoReader(data)
        var itag = 0
        var lastModified = 0L
        var xtags: String? = null
        while (reader.hasRemaining) {
            val tag = reader.tag()
            when (tag.field) {
                1 -> itag = reader.varint().toInt()
                2 -> lastModified = reader.varint()
                3 -> xtags = reader.string()
                else -> reader.skip(tag)
            }
        }
        return SabrFormatId(itag, lastModified, xtags)
    }
}
