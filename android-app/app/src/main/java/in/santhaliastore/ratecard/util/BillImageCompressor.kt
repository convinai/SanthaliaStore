package `in`.santhaliastore.ratecard.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Compresses a captured / picked bill image into [BillImageCache].
 *
 * Why this exists: a typical 2026-era budget phone camera produces
 * 12 MP JPEGs that decode to ~50 MB in ARGB_8888. On the 2 GB RAM
 * phones the kirana shop owner's family hands down to staff,
 * decoding even one full-resolution capture is enough to trip the
 * low-memory killer mid-capture. We therefore:
 *
 *   1. Two-pass decode: read width/height only first (`inJustDecodeBounds`),
 *      compute an [BitmapFactory.Options.inSampleSize] that already
 *      drops the bitmap close to the target dimensions, then decode
 *      for real. This means we never allocate the 50 MB version at
 *      all — the second pass lands at roughly the final size.
 *   2. EXIF normalisation: many phones store the image upright but
 *      record the camera orientation in EXIF instead of rotating
 *      pixels. The Drive viewer respects EXIF, but our own Compose
 *      `Image` composables decoding from disk do NOT — they paint the
 *      raw pixel buffer. We bake the rotation in here so the same
 *      bytes look right both online and in the offline cache.
 *   3. Fine-grained scale: after sample-size decode the bitmap can
 *      still be a touch oversized (sample size is restricted to
 *      powers of 2). A single `createScaledBitmap` brings the long
 *      edge to exactly [MAX_DIMENSION].
 *   4. JPEG quality 80: a sweet spot for bill photos — text and
 *      figures stay legible while the 1600 px output lands at roughly
 *      300–500 KB, well under the Apps Script body cap.
 *
 * All heavy work runs on [Dispatchers.IO]. Any decode / IO failure
 * propagates to the caller as a normal exception — the UI layer
 * surfaces it as a Hinglish toast.
 */
class BillImageCompressor(
    private val context: Context,
    private val cache: BillImageCache
) {

    /**
     * Read [sourceUri], compress, and write the result into
     * `cache.fileForCapture(billId, indexInBill)`. Returns the output
     * [File]. Overwrites any previous capture at the same index — a
     * retried capture replaces, never accumulates.
     */
    suspend fun compressIntoCache(
        sourceUri: Uri,
        billId: String,
        indexInBill: Int
    ): File = withContext(Dispatchers.IO) {
        val outFile = cache.fileForCapture(billId, indexInBill)

        // Pass 1 — bounds only. We open and close the stream to keep
        // total bytes pulled into memory at zero.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $sourceUri" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Could not decode image bounds from $sourceUri"
        }

        // Pass 2 — real decode at the smallest power-of-two sample size
        // that still leaves us above MAX_DIMENSION on the long edge.
        // The follow-up scale step then takes us the rest of the way
        // down, but starting close to the target keeps the temporary
        // bitmap small.
        val sampleOptions = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled = context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $sourceUri" }
            BitmapFactory.decodeStream(input, null, sampleOptions)
        } ?: error("Could not decode image from $sourceUri")

        // EXIF rotation. ExifInterface reads only the small metadata
        // block at the head of the JPEG, not the pixel data, so this
        // is cheap even on big files.
        val rotated = try {
            applyExifRotation(sourceUri, sampled)
        } catch (t: Throwable) {
            // EXIF parse failure is recoverable — fall back to the
            // un-rotated bitmap rather than failing the whole capture.
            sampled
        }

        // Final scale-down so the long edge is exactly MAX_DIMENSION.
        // No-op when the sampled bitmap already fits.
        val finalBitmap = scaleToMaxDimension(rotated, MAX_DIMENSION)

        FileOutputStream(outFile).use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.flush()
        }

        // Free intermediate bitmaps eagerly. The GC will get them
        // anyway, but on a 2 GB phone the sooner the better.
        if (rotated !== sampled) sampled.recycle()
        if (finalBitmap !== rotated) rotated.recycle()
        finalBitmap.recycle()

        outFile
    }

    /**
     * Largest power-of-two `inSampleSize` such that the resulting
     * width/height both remain `>= target`. The follow-up
     * [scaleToMaxDimension] step does the final fractional shrink —
     * this just gets us into the right neighbourhood without paying
     * for a full-resolution decode.
     */
    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW / sample >= target && halfH / sample >= target) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /**
     * Bake the EXIF orientation into the bitmap. Returns the same
     * bitmap unchanged when EXIF says "normal".
     */
    private fun applyExifRotation(sourceUri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open input stream for EXIF" }
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Final scale-down so neither dimension exceeds [maxDim]. Returns
     * the same bitmap when it already fits — Bitmap.createScaledBitmap
     * with the original dimensions copies, so we want to skip in that
     * case to save an allocation.
     */
    private fun scaleToMaxDimension(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longEdge.toFloat()
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, /* filter = */ true)
    }

    companion object {
        /**
         * Long-edge target in pixels. 1600 keeps bill text readable
         * (printed receipt fonts are ~10 px at this scale) while
         * dropping payload size by a factor of ~10 vs. the raw 12 MP
         * source.
         */
        const val MAX_DIMENSION = 1600

        /** JPEG quality for the final compress. 80 lands ~300–500 KB. */
        const val JPEG_QUALITY = 80
    }
}
