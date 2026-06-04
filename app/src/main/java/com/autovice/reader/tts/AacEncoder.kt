package com.autovice.reader.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.autovice.audio.WavProcessor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AacEncoder {

    fun encodeWavToAac(wavFile: File, outputFile: File, bitRate: Int = 128_000) {
        val wavInfo = WavProcessor.readInfo(wavFile)
        val wavBytes = wavFile.readBytes()

        val dataStart = findDataChunkOffset(wavBytes)
        val pcmData = wavBytes.copyOfRange(dataStart, wavBytes.size)

        val sampleRate = wavInfo.sampleRate
        val channelCount = wavInfo.channels

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false

        val pcmBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val inputChunkSize = 2048
        val bytesPerFrame = (channelCount * wavInfo.bitsPerSample / 8).coerceAtLeast(1)
        var framesEncoded = 0L
        var inputDone = false
        var outputDone = false
        val bufferInfo = MediaCodec.BufferInfo()

        while (!outputDone) {
            if (!inputDone) {
                val inputIdx = codec.dequeueInputBuffer(10_000)
                if (inputIdx >= 0) {
                    val buf = codec.getInputBuffer(inputIdx) ?: continue
                    buf.clear()
                    val remaining = pcmBuffer.remaining()
                    if (remaining > 0) {
                        val toRead = minOf(remaining, inputChunkSize)
                        val chunk = ByteArray(toRead)
                        pcmBuffer.get(chunk)
                        buf.put(chunk)
                        // Presentation timestamp must advance by the number of audio frames consumed,
                        // otherwise the muxed AAC has broken timing (crackle) and ~zero duration.
                        val ptsUs = framesEncoded * 1_000_000L / sampleRate
                        codec.queueInputBuffer(inputIdx, 0, toRead, ptsUs, 0)
                        framesEncoded += toRead / bytesPerFrame
                    } else {
                        val ptsUs = framesEncoded * 1_000_000L / sampleRate
                        codec.queueInputBuffer(inputIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }

            val outputIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    muxerTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIdx >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outputIdx)
                    if (outBuf != null && muxerStarted && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSampleData(muxerTrackIndex, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }

    private fun findDataChunkOffset(bytes: ByteArray): Int {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            buf.position(pos + 4)
            val chunkSize = buf.int
            if (chunkId == "data") return pos + 8
            pos += 8 + chunkSize
        }
        return WavProcessor.WAV_HEADER_SIZE
    }
}
