package com.example.btcall

import java.io.File

object RecordingWaveformAnalyzer {
    private val cache = mutableMapOf<String, List<Float>>()

    fun analyzeWaveform(filePath: String, pointsCount: Int = 40): List<Float> {
        val cached = cache[filePath]
        if (cached != null) return cached

        val file = File(filePath)
        if (!file.exists()) {
            return List(pointsCount) { 0.1f }
        }

        val result = try {
            val bytes = file.readBytes()
            if (bytes.size <= 44) {
                List(pointsCount) { 0.1f }
            } else {
                val pcmBytes = bytes.copyOfRange(44, bytes.size)
                val totalSamples = pcmBytes.size / 2
                if (totalSamples < pointsCount) {
                    List(pointsCount) { 0.15f + (it % 3) * 0.1f }
                } else {
                    val chunkSize = totalSamples / pointsCount
                    val points = mutableListOf<Float>()
                    for (i in 0 until pointsCount) {
                        var maxSample = 0
                        val startSample = i * chunkSize
                        val endSample = minOf((i + 1) * chunkSize, totalSamples)
                        for (j in startSample until endSample) {
                            if (j * 2 + 1 >= pcmBytes.size) break
                            val low = pcmBytes[j * 2].toInt() and 0xFF
                            val high = pcmBytes[j * 2 + 1].toInt()
                            val sample = ((high shl 8) or low).toShort()
                            val absSample = Math.abs(sample.toInt())
                            if (absSample > maxSample) {
                                maxSample = absSample
                            }
                        }
                        points.add(maxSample.toFloat() / 32768f)
                    }
                    val maxVal = points.maxOrNull() ?: 0f
                    if (maxVal > 0.05f) {
                        points.map { (it / maxVal).coerceAtLeast(0.12f) }
                    } else {
                        List(pointsCount) { idx ->
                            val v = Math.sin(idx.toDouble() * 0.4).toFloat() * 0.35f + 0.45f
                            v.coerceIn(0.12f, 0.9f)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            List(pointsCount) { idx ->
                val v = Math.sin(idx.toDouble() * 0.4).toFloat() * 0.3f + 0.5f
                v.coerceIn(0.1f, 0.9f)
            }
        }

        cache[filePath] = result
        return result
    }
}
