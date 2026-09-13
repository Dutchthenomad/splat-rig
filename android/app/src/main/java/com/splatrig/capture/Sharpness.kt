package com.splatrig.capture

import android.graphics.BitmapFactory
import kotlin.math.abs

object Sharpness {
    data class Stats(val sharpness: Double, val luma: Double)

    fun laplacianVar(path: String): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 320) }
        val bmp = BitmapFactory.decodeFile(path, sample) ?: return 0.0
        val w = bmp.width; val h = bmp.height
        val gray = IntArray(w * h)
        bmp.getPixels(gray, 0, w, 0, 0, w, h)
        bmp.recycle()
        for (i in gray.indices) {
            val c = gray[i]
            gray[i] = ((c shr 16 and 0xff) * 30 + (c shr 8 and 0xff) * 59 + (c and 0xff) * 11) / 100
        }
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = abs(4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
            sum += lap; sumSq += lap * lap; n++
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    fun stats(path: String): Stats {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 160) }
        val bmp = BitmapFactory.decodeFile(path, sample) ?: return Stats(0.0, 0.0)
        var lumaSum = 0L
        val n = bmp.width * bmp.height
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()
        for (c in px) lumaSum += ((c shr 16 and 0xff) * 30 + (c shr 8 and 0xff) * 59 + (c and 0xff) * 11) / 100
        return Stats(laplacianVar(path), lumaSum.toDouble() / n.coerceAtLeast(1))
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (w / s > target * 2 || h / s > target * 2) s *= 2
        return s
    }
}
