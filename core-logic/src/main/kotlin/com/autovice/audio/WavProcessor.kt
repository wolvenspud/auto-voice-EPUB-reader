package com.autovice.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavProcessor {

    const val WAV_HEADER_SIZE = 44

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Int,
    )

    fun readInfo(wavFile: File): WavInfo {
        val bytes = wavFile.readBytes()
        require(bytes.size >= WAV_HEADER_SIZE) { "File too small to be a valid WAV: ${wavFile.name}" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Verify RIFF header
        val riff = String(bytes, 0, 4)
        require(riff == "RIFF") { "Not a RIFF file: $riff" }
        val wave = String(bytes, 8, 4)
        require(wave == "WAVE") { "Not a WAVE file: $wave" }

        buf.position(22)
        val channels = buf.short.toInt() and 0xFFFF
        val sampleRate = buf.int
        buf.position(34)
        val bitsPerSample = buf.short.toInt() and 0xFFFF

        // Find data chunk
        var pos = 12
        var dataBytes = 0
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            buf.position(pos + 4)
            val chunkSize = buf.int
            if (chunkId == "data") {
                dataBytes = chunkSize
                break
            }
            pos += 8 + chunkSize
        }

        return WavInfo(sampleRate, channels, bitsPerSample, dataBytes)
    }

    fun trimSilence(pcmData: ByteArray, threshold: Short = 256): ByteArray {
        if (pcmData.isEmpty()) return pcmData
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(pcmData.size / 2) { buf.short }

        var start = 0
        while (start < samples.size && kotlin.math.abs(samples[start].toInt()) <= threshold) {
            start++
        }
        var end = samples.size - 1
        while (end > start && kotlin.math.abs(samples[end].toInt()) <= threshold) {
            end--
        }
        if (start >= end) return pcmData

        val trimmed = samples.copyOfRange(start, end + 1)
        val result = ByteArray(trimmed.size * 2)
        val outBuf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        for (s in trimmed) outBuf.putShort(s)
        return result
    }

    fun concatenate(wavFiles: List<File>): ByteArray {
        if (wavFiles.isEmpty()) return ByteArray(0)
        val infos = wavFiles.map { readInfo(it) }
        val first = infos.first()
        require(infos.all { it.sampleRate == first.sampleRate && it.channels == first.channels && it.bitsPerSample == first.bitsPerSample }) {
            "WAV files have incompatible formats"
        }

        val allPcm = wavFiles.flatMap { wavFile ->
            val bytes = wavFile.readBytes()
            var dataStart = WAV_HEADER_SIZE
            // find actual data chunk offset
            var pos = 12
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4)
                buf.position(pos + 4)
                val chunkSize = buf.int
                if (chunkId == "data") {
                    dataStart = pos + 8
                    break
                }
                pos += 8 + chunkSize
            }
            bytes.drop(dataStart)
        }

        return allPcm.toByteArray()
    }

    fun durationMs(wavInfo: WavInfo): Long {
        if (wavInfo.sampleRate == 0 || wavInfo.channels == 0 || wavInfo.bitsPerSample == 0) return 0L
        val bytesPerSample = wavInfo.bitsPerSample / 8
        val bytesPerSecond = wavInfo.sampleRate.toLong() * wavInfo.channels * bytesPerSample
        return if (bytesPerSecond == 0L) 0L else (wavInfo.dataBytes.toLong() * 1000L) / bytesPerSecond
    }

    fun buildWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buf = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        return buf.array()
    }
}
