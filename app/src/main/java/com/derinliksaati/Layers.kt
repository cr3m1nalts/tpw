package com.derinliksaati

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The two picture layers the wallpaper draws (background and person), plus the shared settings.
 * The activity builds them; the wallpaper service loads them.
 */
object Layers {
    const val KEY_VERSION = "layersVersion"
    const val KEY_DEPTH = "depth"            // 0..100: share of MAX_VIEW_DEG
    const val KEY_CLOCK_FRONT = "clockFront" // true: clock in front of the person
    const val KEY_CLOCK_HOME = "clockHome"   // false: no clock on the home screen
    const val KEY_CLOCK_ALPHA = "clockAlpha" // 0..100 % visible
    const val KEY_CLOCK_HINT_SHOWN = "clockHintShown"
    const val DEFAULT_DEPTH = 70
    const val DEFAULT_CLOCK_ALPHA = 93
    const val MAX_VIEW_DEG = 30f

    /** Layers are this much taller than the screen, so tilting forward/back has picture to show. */
    const val VERTICAL_ROOM = 1.15f
    /** Keep up to this much of the photo's width (vs. the screen) for turning left/right. */
    private const val MAX_WIDE = 1.8f

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("derinlik", Context.MODE_PRIVATE)
    fun bgFile(c: Context) = File(c.filesDir, "bg.jpg")
    fun subjectFile(c: Context) = File(c.filesDir, "subject.png")

    fun load(f: File): Bitmap? =
        if (!f.exists()) null
        else try { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) } catch (e: IOException) { null }

    /** Cuts the person out of the photo on the device and saves both layers. Slow: call off the main thread. */
    fun build(context: Context, uri: Uri, width: Int, height: Int) {
        val photo = decodeCropped(context, uri, width, height)
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
        val result = try {
            Tasks.await(segmenter.process(InputImage.fromBitmap(photo, 0)))
        } finally {
            segmenter.close()
        }
        val mask = maskBitmap(result.buffer, result.width, result.height, photo.width, photo.height)
        save(fillBehind(context, photo, mask), bgFile(context), Bitmap.CompressFormat.JPEG)
        save(cutOut(photo, mask), subjectFile(context), Bitmap.CompressFormat.PNG)
        // The wallpaper service listens for this key and reloads the files.
        prefs(context).edit().putLong(KEY_VERSION, System.currentTimeMillis()).apply()
    }

    /**
     * Decodes the photo (EXIF rotation applied) and center-crops it to a size a bit taller than the screen
     * and as wide as the photo allows, so turning the phone reveals parts that a screen-sized crop would lose.
     */
    private fun decodeCropped(c: Context, uri: Uri, screenW: Int, screenH: Int): Bitmap {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = minOf(info.size.width / screenW, info.size.height / screenH)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        val h = (screenH * VERTICAL_ROOM).roundToInt()
        val w = (src.width.toFloat() / src.height * h).roundToInt()
            .coerceIn((screenW * VERTICAL_ROOM).roundToInt(), (screenW * MAX_WIDE).roundToInt())
        val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * s
        val dh = src.height * s
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** ML Kit gives one float (0..1, "is a person") per pixel; turn it into an alpha mask. */
    private fun maskBitmap(buf: ByteBuffer, mw: Int, mh: Int, w: Int, h: Int): Bitmap {
        buf.rewind()
        val conf = buf.asFloatBuffer()
        val px = IntArray(mw * mh) { (conf.get(it).coerceIn(0f, 1f) * 255).toInt() shl 24 }
        val m = Bitmap.createBitmap(px, mw, mh, Bitmap.Config.ARGB_8888)
        return if (mw == w && mh == h) m else Bitmap.createScaledBitmap(m, w, h, true)
    }

    private fun cutOut(photo: Bitmap, mask: Bitmap): Bitmap {
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(mask, 0f, 0f, porterDuff(PorterDuff.Mode.DST_IN))
        return out
    }

    /**
     * Fills the person's spot: the person (mask grown a little) is cut away, the hole is first filled
     * smoothly from the surrounding colors, then the inpainting model paints real texture into it.
     * If the model fails on this device, the smooth fill is used as is.
     */
    private fun fillBehind(context: Context, photo: Bitmap, mask: Bitmap): Bitmap {
        val grown = grow(mask, 14f * photo.height / 1950f)
        val known = photo.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(known).drawBitmap(grown, 0f, 0f, porterDuff(PorterDuff.Mode.DST_OUT))
        val smooth = pushPull(known)
        return try {
            Inpainter.fill(context, smooth, grown)
        } catch (e: Exception) {
            Log.w("DerinlikSaati", "Inpainting failed, using the smooth fill", e)
            smooth
        }
    }

    /**
     * Smooth fill of transparent pixels from the colors around them ("push-pull"): halve the image again and
     * again (premultiplied alpha makes each pixel average only the known pixels under it), then lay each finer
     * level over the enlarged coarser one.
     */
    private fun pushPull(known: Bitmap): Bitmap {
        val levels = mutableListOf(known)
        while (levels.last().width > 1 || levels.last().height > 1) {
            val l = levels.last()
            levels += Bitmap.createScaledBitmap(l, max(1, l.width / 2), max(1, l.height / 2), true)
        }
        // The single coarsest pixel is the average background color; make it opaque as the base.
        var fill = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, levels.last().getPixel(0, 0) or Color.BLACK)
        }
        for (level in levels.asReversed().drop(1)) {
            val next = mutable(Bitmap.createScaledBitmap(fill, level.width, level.height, true))
            Canvas(next).drawBitmap(level, 0f, 0f, null)
            fill = next
        }
        return fill
    }

    /** Grows the mask by about [d] pixels, so the person's soft edge is not used as background. */
    private fun grow(mask: Bitmap, d: Float): Bitmap {
        val grown = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val gc = Canvas(grown)
        for (i in 0 until 12) {
            val t = i * Math.PI / 6
            gc.drawBitmap(mask, (cos(t) * d).toFloat(), (sin(t) * d).toFloat(), null)
        }
        gc.drawBitmap(mask, 0f, 0f, null)
        return grown
    }

    private fun mutable(b: Bitmap) = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)

    private fun porterDuff(mode: PorterDuff.Mode) = Paint().apply { xfermode = PorterDuffXfermode(mode) }

    /** Write to a temp file, then rename, so the wallpaper never reads a half-written file. */
    private fun save(b: Bitmap, f: File, format: Bitmap.CompressFormat) {
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { if (!b.compress(format, 92, it)) throw IOException("Could not encode $f") }
        if (!tmp.renameTo(f)) throw IOException("Could not replace $f")
    }
}
