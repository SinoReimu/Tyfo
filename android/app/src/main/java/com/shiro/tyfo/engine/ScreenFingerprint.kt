package com.shiro.tyfo.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlin.math.abs

/**
 * Small perceptual fingerprint for comparing screenshots across adjacent steps.
 *
 * This is intentionally coarse: cursor blink, tiny loading animations, and minor
 * compression artifacts should not make two screens look meaningfully different.
 *
 * The 8x8 average hash alone is too coarse for screens that grow by one line per tap
 * (e.g. Duolingo stories, where "继续" reveals the next sentence): the hash stays the
 * same and the repeat guard blocked every legitimate tap. [grid] holds box-averaged
 * luminance on a finer grid; when both fingerprints have one, a handful of cells
 * changing clearly is enough to call the screen different.
 */
class ScreenFingerprint(
    val averageHash: Long,
    internal val grid: ByteArray? = null
) {

    fun isSimilarTo(other: ScreenFingerprint, maxHammingDistance: Int = DEFAULT_MAX_DISTANCE): Boolean {
        if (java.lang.Long.bitCount(averageHash xor other.averageHash) > maxHammingDistance) return false
        val a = grid
        val b = other.grid
        if (a == null || b == null || a.size != b.size) return true
        return changedCells(a, b) < MIN_CHANGED_CELLS
    }

    companion object {
        private const val TAG = "ScreenFingerprint"
        private const val HASH_SIZE = 8
        private const val DEFAULT_MAX_DISTANCE = 8

        internal const val GRID_WIDTH = 32
        internal const val GRID_HEIGHT = 64
        /** A cell counts as changed when its mean luminance moves by more than this (0–255). */
        internal const val CELL_DELTA = 12
        /** A single new line of text touches dozens of cells; a cursor blink or clock tick only a few. */
        internal const val MIN_CHANGED_CELLS = 6

        internal fun changedCells(a: ByteArray, b: ByteArray): Int {
            var changed = 0
            for (i in a.indices) {
                if (abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)) > CELL_DELTA) changed++
            }
            return changed
        }

        fun fromBase64(screenshotBase64: String): ScreenFingerprint? {
            return try {
                val bytes = Base64.decode(screenshotBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val fingerprint = fromBitmap(bitmap)
                bitmap.recycle()
                fingerprint
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute screen fingerprint", e)
                null
            }
        }

        fun fromBitmap(bitmap: Bitmap): ScreenFingerprint {
            val scaled = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
            val luminance = IntArray(HASH_SIZE * HASH_SIZE)
            var sum = 0

            for (y in 0 until HASH_SIZE) {
                for (x in 0 until HASH_SIZE) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val gray = (r * 299 + g * 587 + b * 114) / 1000
                    val index = y * HASH_SIZE + x
                    luminance[index] = gray
                    sum += gray
                }
            }

            if (scaled !== bitmap) {
                scaled.recycle()
            }

            val average = sum / luminance.size
            var bits = 0L
            for (i in luminance.indices) {
                if (luminance[i] >= average) {
                    bits = bits or (1L shl i)
                }
            }
            return ScreenFingerprint(bits, luminanceGrid(bitmap))
        }

        /** Box-averages the whole bitmap (createScaledBitmap only samples a few pixels per cell). */
        private fun luminanceGrid(bitmap: Bitmap): ByteArray? {
            val width = bitmap.width
            val height = bitmap.height
            if (width < GRID_WIDTH || height < GRID_HEIGHT) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val sums = LongArray(GRID_WIDTH * GRID_HEIGHT)
            val counts = IntArray(GRID_WIDTH * GRID_HEIGHT)
            for (y in 0 until height) {
                val rowBase = (y * GRID_HEIGHT / height) * GRID_WIDTH
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    val r = (pixel shr 16) and 0xff
                    val g = (pixel shr 8) and 0xff
                    val b = pixel and 0xff
                    val cell = rowBase + x * GRID_WIDTH / width
                    sums[cell] += ((r * 299 + g * 587 + b * 114) / 1000).toLong()
                    counts[cell]++
                }
            }
            return ByteArray(sums.size) { (sums[it] / counts[it]).toInt().toByte() }
        }
    }
}
