package com.autovice.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavProcessor {

    const val WAV_HEADER_SIZE = 44

    /**
     * Canonical synthesis format. The Android TTS engine may serve different segments from
     * different voices (network vs embedded) with differing sample rates / channel counts;
     * normalising every segment to this format keeps per-segment WAVs concatenable.
     */
    const val CANONICAL_SAMPLE_RATE = 24000
    const val CANONICAL_CHANNELS = 1
    const val CANONICAL_BITS = 16

    /**
     * Downmixes 16-bit PCM to mono (if needed) and linearly resamples it to [targetRate].
     * Input/output are raw little-endian 16-bit PCM byte arrays (no WAV header).
     */
    fun normalisePcm16(
        pcm: ByteArray,
        sourceRate: Int,
        sourceChannels: Int,
        targetRate: Int = CANONICAL_SAMPLE_RATE,
    ): ByteArray {
        if (pcm.size < 2 || sourceRate <= 0 || sourceChannels <= 0) return pcm
        val inBuf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val inSamples = ShortArray(pcm.size / 2) { inBuf.short }

        // Downmix interleaved channels to mono.
        val mono: ShortArray = if (sourceChannels == 1) inSamples else {
            val frames = inSamples.size / sourceChannels
            ShortArray(frames) { f ->
                var acc = 0
                for (c in 0 until sourceChannels) acc += inSamples[f * sourceChannels + c]
                (acc / sourceChannels).toShort()
            }
        }

        // Resample mono to the target rate.
        val resampled: ShortArray = if (sourceRate == targetRate || mono.isEmpty()) mono else {
            val outLen = ((mono.size.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
            if (sourceRate > targetRate) {
                // Downsampling: average all input samples in each output window (box low-pass filter).
                // Plain linear interpolation here causes aliasing that sounds like crackling.
                val ratio = sourceRate.toDouble() / targetRate
                ShortArray(outLen) { i ->
                    val winStart = (i * ratio).toInt().coerceIn(0, mono.size - 1)
                    val winEnd = ((i + 1) * ratio).toInt().coerceAtMost(mono.size - 1)
                    var acc = 0L
                    for (j in winStart..winEnd) acc += mono[j]
                    (acc / (winEnd - winStart + 1)).toShort()
                }
            } else {
                // Upsampling: linear interpolation is fine (no aliasing risk).
                ShortArray(outLen) { i ->
                    val srcPos = i.toDouble() * sourceRate / targetRate
                    val i0 = srcPos.toInt().coerceIn(0, mono.size - 1)
                    val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
                    val frac = srcPos - i0
                    (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
                }
            }
        }

        val out = ByteArray(resampled.size * 2)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in resampled) outBuf.putShort(s)
        return out
    }

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

    /**
     * Applies a short linear fade in/out at the edges of a 16-bit PCM buffer. Silence-trimming
     * cuts mid-waveform, so abutting segments would otherwise click; fading the edges to zero
     * removes those boundary discontinuities.
     */
    fun applyEdgeFades(pcm: ByteArray, sampleRate: Int, fadeMs: Int = 5): ByteArray {
        if (pcm.size < 4 || sampleRate <= 0) return pcm
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(pcm.size / 2) { buf.short }
        val fade = (sampleRate.toLong() * fadeMs / 1000).toInt().coerceIn(1, samples.size / 2)
        for (i in 0 until fade) {
            val g = i.toDouble() / fade
            samples[i] = (samples[i] * g).toInt().toShort()
            samples[samples.size - 1 - i] = (samples[samples.size - 1 - i] * g).toInt().toShort()
        }
        val out = ByteArray(samples.size * 2)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) ob.putShort(s)
        return out
    }

    /** Appends [ms] milliseconds of PCM silence (16-bit, all zeros) to [pcm]. */
    fun appendSilence(pcm: ByteArray, sampleRate: Int, ms: Int): ByteArray {
        val silenceBytes = (sampleRate.toLong() * ms / 1000).toInt() * 2 // 16-bit = 2 bytes/sample
        return pcm + ByteArray(silenceBytes)
    }

    fun concatenate(wavFiles: List<File>): ByteArray {
        if (wavFiles.isEmpty()) return ByteArray(0)
        val infos = wavFiles.map { readInfo(it) }
        val first = infos.first()
        require(infos.all { it.sampleRate == first.sampleRate && it.channels == first.channels && it.bitsPerSample == first.bitsPerSample }) {
            "WAV files have incompatible formats"
        }

        // Stream raw PCM byte ranges into a buffer. (Using flatMap + bytes.drop here would box
        // every byte into a Byte object — ~16x blow-up that OOMs on multi-minute chapters.)
        val totalPcm = wavFiles.indices.sumOf { (infos[it].dataBytes).toLong() }
        val out = java.io.ByteArrayOutputStream(totalPcm.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        for (wavFile in wavFiles) {
            val bytes = wavFile.readBytes()
            val dataStart = findDataStart(bytes)
            out.write(bytes, dataStart, bytes.size - dataStart)
        }
        return out.toByteArray()
    }

    /** Returns the byte offset of the PCM payload (just past the "data" chunk header). */
    private fun findDataStart(bytes: ByteArray): Int {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            buf.position(pos + 4)
            val chunkSize = buf.int
            if (chunkId == "data") return pos + 8
            pos += 8 + chunkSize
        }
        return WAV_HEADER_SIZE
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
