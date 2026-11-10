package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** Fills the object's spot with the LaMa inpainting model (assets/lama_dilated.tflite), on the device. */
object Inpainter {
    private const val MODEL = "lama_dilated.tflite"
    private const val SIZE = 512 // the model's fixed input and output size
    /** Refinement tiles cover this many photo pixels, so they run at 2 photo pixels per model pixel. */
    private const val TILE = 1024
    private const val TAG = "DerinlikSaati"

    /**
     * Returns [base] with [hole] painted by the model in two passes. First the whole hole in one go, which is
     * squeezed into 512×512 and so comes out soft. Then [band], the part of the hole along its edge (the only
     * part tilting ever reveals), is painted again in overlapping tiles at much higher resolution.
     * [base] must already contain no object (the smooth fill), so nothing of it can come back.
     */
    fun fill(context: Context, base: Bitmap, hole: Bitmap, band: Bitmap): Bitmap {
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        val start = System.currentTimeMillis()
        withModel(context, MODEL) { model ->
            boundsOf(alphaOf(hole), hole.width, hole.height)?.let { paint(model, result, hole, hole, widen(it, hole.width, hole.height)) }
            val coarseMs = System.currentTimeMillis() - start
            val tiles = tilesOver(alphaOf(band), band.width, band.height)
            // The whole hole stays unknown to the model in every tile: shown the soft first pass as if it were
            // real background, it spreads that blur outward as haze. Only the edge band is pasted back.
            for (tile in tiles) paint(model, result, hole, band, tile)
            Log.i(TAG, "Inpainting: whole hole $coarseMs ms, ${tiles.size} edge tiles, total ${System.currentTimeMillis() - start} ms")
        }
        return result
    }

    /** Opens a TFLite model from assets (memory-mapped, so it must be stored uncompressed) for the length of [block]. */
    fun withModel(context: Context, file: String, block: (Interpreter) -> Unit) {
        context.assets.openFd(file).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val model = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                Interpreter(model, Interpreter.Options().setNumThreads(4)).use(block)
            }
        }
    }

    /**
     * Paints inside [box] of [dest], in place: crop, squeeze to 512×512, run with [unknown] as the area the model
     * must invent, scale back, and paste only inside [paste].
     */
    private fun paint(model: Interpreter, dest: Bitmap, unknown: Bitmap, paste: Bitmap, box: Rect) {
        val maskCrop = Bitmap.createBitmap(paste, box.left, box.top, box.width(), box.height())
        val image = Bitmap.createScaledBitmap(Bitmap.createBitmap(dest, box.left, box.top, box.width(), box.height()), SIZE, SIZE, true)
        val maskSmall = Bitmap.createScaledBitmap(Bitmap.createBitmap(unknown, box.left, box.top, box.width(), box.height()), SIZE, SIZE, true)

        val px = IntArray(SIZE * SIZE)
        val imageIn = floats(SIZE * SIZE * 3)
        image.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in px) imageIn.put(Color.red(p) / 255f).put(Color.green(p) / 255f).put(Color.blue(p) / 255f)
        val maskIn = floats(SIZE * SIZE)
        maskSmall.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in px) maskIn.put(if (Color.alpha(p) > 40) 1f else 0f)
        val out = floats(SIZE * SIZE * 3)

        // Feed inputs by name, not by position, so a re-export with another order still works.
        val inputs = arrayOfNulls<Any>(model.inputTensorCount)
        for (i in inputs.indices) {
            inputs[i] = if (model.getInputTensor(i).name().contains("mask")) maskIn.rewind() else imageIn.rewind()
        }
        model.runForMultipleInputsOutputs(inputs, mapOf(0 to out))

        out.rewind()
        for (i in px.indices) px[i] = Color.rgb(channel(out.get()), channel(out.get()), channel(out.get()))
        val painted = Bitmap.createScaledBitmap(Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888), box.width(), box.height(), true)
            .copy(Bitmap.Config.ARGB_8888, true)
        // Keep the model's pixels only inside the mask; its soft edge blends them in.
        Canvas(painted).drawBitmap(maskCrop, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        Canvas(dest).drawBitmap(painted, box.left.toFloat(), box.top.toFloat(), null)
    }

    private fun alphaOf(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun boundsOf(px: IntArray, w: Int, h: Int): Rect? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 40) {
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** The hole's box with some margin for context, widened toward a square so the 512×512 squeeze distorts less. */
    private fun widen(r: Rect, w: Int, h: Int): Rect {
        val side = max(r.width(), r.height()) * 1.15f
        val bw = min(w.toFloat(), max(side, r.width() * 1.3f))
        val bh = min(h.toFloat(), max(side, r.height() * 1.3f))
        val left = (r.exactCenterX() - bw / 2).coerceIn(0f, w - bw).toInt()
        val top = (r.exactCenterY() - bh / 2).coerceIn(0f, h - bh).toInt()
        return Rect(left, top, min(w, left + bw.toInt()), min(h, top + bh.toInt()))
    }

    /** Square tiles stepping over the band with a quarter overlap, keeping only those that touch it. */
    private fun tilesOver(px: IntArray, w: Int, h: Int): List<Rect> {
        val bounds = boundsOf(px, w, h) ?: return emptyList()
        val side = min(TILE, min(w, h))
        val step = side * 3 / 4
        val tiles = mutableListOf<Rect>()
        var y = bounds.top
        while (true) {
            val top = min(y, h - side)
            var x = bounds.left
            while (true) {
                val left = min(x, w - side)
                val tile = Rect(left, top, left + side, top + side)
                if (touches(px, w, tile)) tiles += tile
                if (left + side >= min(w, bounds.right)) break
                x += step
            }
            if (top + side >= min(h, bounds.bottom)) break
            y += step
        }
        return tiles
    }

    private fun touches(px: IntArray, w: Int, r: Rect): Boolean {
        for (y in r.top until r.bottom) for (x in r.left until r.right) if (Color.alpha(px[y * w + x]) > 40) return true
        return false
    }

    private fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
}
