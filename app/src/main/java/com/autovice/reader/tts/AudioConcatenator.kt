package com.autovice.reader.tts

import com.autovice.audio.WavProcessor
import java.io.File

object AudioConcatenator {

    fun concatenateWavs(wavFiles: List<File>, outputFile: File) {
        if (wavFiles.isEmpty()) {
            outputFile.createNewFile()
            return
        }
        val firstInfo = WavProcessor.readInfo(wavFiles.first())
        val pcmData = WavProcessor.concatenate(wavFiles)
        val header = WavProcessor.buildWavHeader(
            sampleRate = firstInfo.sampleRate,
            channels = firstInfo.channels,
            bitsPerSample = firstInfo.bitsPerSample,
            dataSize = pcmData.size,
        )
        outputFile.writeBytes(header + pcmData)
    }
}
