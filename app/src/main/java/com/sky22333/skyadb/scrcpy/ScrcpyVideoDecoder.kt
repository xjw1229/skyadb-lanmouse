package com.sky22333.skyadb.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.flyfishxu.kadb.stream.AdbStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScrcpyVideoDecoder(
    private val stream: AdbStream,
    private val codecId: Int,
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
) {
    @Volatile
    private var running = false

    private var codec: MediaCodec? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var configPacket: ByteArray? = null

    suspend fun start() = withContext(Dispatchers.IO) {
        running = true
        val header = ByteArray(ScrcpyProtocol.PacketHeaderLength)
        try {
            while (running) {
                stream.source.readFully(header)
                if (isSessionPacket(header)) {
                    readSessionPacket(header)
                    continue
                }

                val packetHeader = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
                val ptsAndFlags = packetHeader.long
                val size = packetHeader.int
                if (size !in 1..10_000_000) continue

                val data = stream.source.readByteArray(size.toLong())
                val isConfig = (ptsAndFlags and ScrcpyProtocol.PacketFlagConfig) != 0L
                val pts = ptsAndFlags and ScrcpyProtocol.PacketPtsMask
                if (isConfig) configPacket = data

                val decoder = codec ?: configureCodecOrSkip() ?: continue
                queuePacket(decoder, data, pts, isConfig)
                drain(decoder)
            }
        } finally {
            release()
        }
    }

    fun stop() {
        running = false
        runCatching { stream.close() }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    private fun isSessionPacket(header: ByteArray): Boolean {
        return (header[0].toInt() and 0x80) != 0
    }

    private fun readSessionPacket(header: ByteArray) {
        val width = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        val height = ByteBuffer.wrap(header, 8, 4).order(ByteOrder.BIG_ENDIAN).int
        if (width <= 0 || height <= 0) return
        if (width == videoWidth && height == videoHeight) return

        videoWidth = width
        videoHeight = height
        onVideoSize(width, height)
        release()
    }

    private fun configureCodecOrSkip(): MediaCodec? {
        val width = videoWidth
        val height = videoHeight
        if (width <= 0 || height <= 0 || !surface.isValid) return null

        val mime = when (codecId) {
            ScrcpyProtocol.CodecH264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            ScrcpyProtocol.CodecH265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
            ScrcpyProtocol.CodecAv1 -> "video/av01"
            else -> error("不支持的视频编码：0x${codecId.toString(16)}")
        }
        return MediaCodec.createDecoderByType(mime).also { decoder ->
            decoder.configure(MediaFormat.createVideoFormat(mime, width, height), surface, null, 0)
            decoder.start()
            codec = decoder
            configPacket?.let { queuePacket(decoder, it, 0L, isConfig = true) }
        }
    }

    private fun queuePacket(decoder: MediaCodec, data: ByteArray, pts: Long, isConfig: Boolean) {
        val index = decoder.dequeueInputBuffer(5_000)
        if (index < 0) return
        val inputBuffer = decoder.getInputBuffer(index) ?: return
        inputBuffer.clear()
        inputBuffer.put(data)
        decoder.queueInputBuffer(
            index,
            0,
            data.size,
            pts,
            if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
        )
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> decoder.releaseOutputBuffer(
                    index,
                    (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0,
                )
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return
            }
        }
    }
}
