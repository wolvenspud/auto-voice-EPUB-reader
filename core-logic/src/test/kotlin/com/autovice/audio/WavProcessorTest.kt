package com.autovice.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavProcessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createWavFile(
        sampleRate: Int = 22050,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        pcmSamples: ShortArray = ShortArray(22050) { (it % 100).toShort() },
    ): File {
        val pcmData = ByteArray(pcmSamples.size * 2)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcmSamples) buf.putShort(s)
        val header = WavProcessor.buildWavHeader(sampleRate, channels, bitsPerSample, pcmData.size)
        val file = tempFolder.newFile("test_${System.nanoTime()}.wav")
        file.writeBytes(header + pcmData)
        return file
    }

    @Test
    fun `correctly reads WAV header info`() {
        val file = createWavFile(sampleRate = 22050, channels = 1, bitsPerSample = 16)
        val info = WavProcessor.readInfo(file)
        assertEquals(22050, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
    }

    @Test
    fun `dataBytes is correct in WAV info`() {
        val samples = ShortArray(100)
        val file = createWavFile(pcmSamples = samples)
        val info = WavProcessor.readInfo(file)
        assertEquals(200, info.dataBytes)
    }

    @Test
    fun `duration calculation from WAV info`() {
        // 22050 samples at 22050 Hz mono 16-bit = 1 second
        val info = WavProcessor.WavInfo(sampleRate = 22050, channels = 1, bitsPerSample = 16, dataBytes = 44100)
        val durationMs = WavProcessor.durationMs(info)
        assertEquals(1000L, durationMs)
    }

    @Test
    fun `concatenation of two WAVs produces correct byte count`() {
        val samples1 = ShortArray(100) { 1000.toShort() }
        val samples2 = ShortArray(200) { 2000.toShort() }
        val file1 = createWavFile(pcmSamples = samples1)
        val file2 = createWavFile(pcmSamples = samples2)
        val pcm = WavProcessor.concatenate(listOf(file1, file2))
        assertEquals((100 + 200) * 2, pcm.size)
    }

    @Test
    fun `silence trimming removes leading silent samples`() {
        val samples = ShortArray(100) { i -> if (i < 20) 0 else 1000.toShort() }
        val pcmData = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        val trimmed = WavProcessor.trimSilence(pcmData, threshold = 10)
        assertTrue("Trimmed should be smaller than original", trimmed.size < pcmData.size)
    }

    @Test
    fun `silence trimming removes trailing silent samples`() {
        val samples = ShortArray(100) { i -> if (i >= 80) 0 else 1000.toShort() }
        val pcmData = ByteArray(samples.size * 2)
        val buf = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        val trimmed = WavProcessor.trimSilence(pcmData, threshold = 10)
        assertTrue("Trimmed should be smaller than original", trimmed.size < pcmData.size)
    }

    @Test
    fun `build WAV header produces 44-byte header`() {
        val header = WavProcessor.buildWavHeader(22050, 1, 16, 1000)
        assertEquals(WavProcessor.WAV_HEADER_SIZE, header.size)
    }

    @Test
    fun `build WAV header contains RIFF marker`() {
        val header = WavProcessor.buildWavHeader(22050, 1, 16, 1000)
        assertEquals("RIFF", String(header, 0, 4))
    }

    @Test
    fun `build WAV header contains WAVE marker`() {
        val header = WavProcessor.buildWavHeader(22050, 1, 16, 1000)
        assertEquals("WAVE", String(header, 8, 4))
    }

    @Test
    fun `roundtrip WAV header can be read back`() {
        val header = WavProcessor.buildWavHeader(44100, 2, 16, 8000)
        val pcm = ByteArray(8000)
        val file = tempFolder.newFile("roundtrip.wav")
        file.writeBytes(header + pcm)
        val info = WavProcessor.readInfo(file)
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(8000, info.dataBytes)
    }

    @Test
    fun `empty WAV concatenation returns empty array`() {
        val pcm = WavProcessor.concatenate(emptyList())
        assertEquals(0, pcm.size)
    }
}
