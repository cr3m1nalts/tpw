package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the wallpaper. Shared by the wallpaper service and the in-app preview, so both look the same.
 *
 * The person and the clock stay in place. The background is a picture plane through the person's
 * position; tilting turns that plane in perspective around the person, so you see the scene
 * from an angle instead of watching it slide. The person turns the same way, by a smaller angle.
 */
class DepthRenderer(private val context: Context) {

    private val prefs = Layers.prefs(context)
    private var bg: Bitmap? = null
    private var subject: Bitmap? = null
    private var loadedVersion = -1L

    private val dst = RectF()
    private val camera = Camera()
    private val view = Matrix()
    private val inverse = Matrix()
    private val corners = FloatArray(8)
    private var bulge: FloatArray? = null     // relief shape
    private var volumeMap: FloatArray? = null // rounded shape, same grid
    private var depthMap: FloatArray? = null  // shape from the estimated depth, same grid
    private var bgDepth: FloatArray? = null   // background nearness relative to the object's spot, same grid
    private val bgFront = Matrix()
    private val bgFrontRest = Matrix()
    // Everything turns around the object's center (share of the layer), not the screen's, so the
    // background right behind the object stays put wherever the object is in the photo.
    private var pivotFx = 0.5f
    private var pivotFy = 0.5f
    private var pivotX = 0f // the same point on screen, in pixels, for the frame being drawn
    private var pivotY = 0f
    private val planeAt = Matrix()
    private val frontAt = Matrix()
    private val frontAtRest = Matrix()
    private val mesh = FloatArray((MESH_X + 1) * (MESH_Y + 1) * 2)
    private val meshBase = FloatArray(mesh.size)
    private val meshPlane = FloatArray(mesh.size)
    private val meshFront = FloatArray(mesh.size)
    private val meshRest = FloatArray(mesh.size)
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private val clock = ClockDrawer(context)
    private val clockArea = RectF()
    private val bgOnScreen = RectF()
    private val bgView = Matrix() // how the background is placed on screen in this frame
    private var blur: Bitmap? = null

    /** Reloads the picture layers if the app saved new ones. */
    fun loadIfChanged() {
        val version = prefs.getLong(Layers.KEY_VERSION, 0L)
        if (version == loadedVersion) return
        loadedVersion = version
        bg = Layers.load(Layers.bgFile(context))
        subject = Layers.load(Layers.subjectFile(context))
        bulge = subject?.let { bulgeOf(it) }
        centerOf(bulge)
        val relief = bulge
        val near = relief?.let { r -> Layers.load(Layers.depthFile(context))?.let { nearGrid(it, r) } }
        depthMap = if (near != null && relief != null) depthShapeOf(near, relief) else null
        bgDepth = if (near != null && relief != null) backgroundDepthOf(near, relief) else null
        blur = bg?.let { blurOf(it) }
    }

    /** A heavily blurred copy of the background for the frosted clock: shrunk hard, then partly enlarged to soften the steps. */
    private fun blurOf(bg: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(bg.copy(Bitmap.Config.ARGB_8888, false), max(1, bg.width / 32), max(1, bg.height / 32), true)
        return Bitmap.createScaledBitmap(small, max(1, bg.width / 8), max(1, bg.height / 8), true)
    }

    /** The clock's box on a w×h screen. */
    fun clockBox(w: Float, h: Float, out: RectF) = clock.box(w, h, out)

    /**
     * How far each mesh vertex sticks out toward the viewer, 0..1: 0 at the person's edge and outside,
     * rising quickly over RELIEF_EDGE with a rounded shoulder and then staying flat, like a figure on a coin.
     * Worked out on the mesh grid from the person's outline (chamfer distance to the nearest edge).
     * Also fills [volumeMap], the alternative shape: rising gradually to 1 at the thickest part, like a rounded body.
     */
    private fun bulgeOf(subject: Bitmap): FloatArray {
        val cols = MESH_X + 1
        val rows = MESH_Y + 1
        // The layer is a hardware bitmap (not readable); a scaled software copy is, and is tiny.
        val small = Bitmap.createScaledBitmap(subject.copy(Bitmap.Config.ARGB_8888, false), cols, rows, true)
        val px = IntArray(cols * rows).also { small.getPixels(it, 0, cols, 0, 0, cols, rows) }
        val cw = subject.width.toFloat() / MESH_X
        val ch = subject.height.toFloat() / MESH_Y
        val diag = sqrt(cw * cw + ch * ch)
        val far = Float.MAX_VALUE / 2
        val d = FloatArray(cols * rows) { if (Color.alpha(px[it]) > 128) far else 0f }
        fun relax(i: Int, x: Int, y: Int, dx: Int, dy: Int, step: Float) {
            val nx = x + dx
            val ny = y + dy
            val n = if (nx in 0 until cols && ny in 0 until rows) d[ny * cols + nx] else 0f
            if (n + step < d[i]) d[i] = n + step
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            if (d[i] == 0f) continue
            relax(i, x, y, -1, 0, cw); relax(i, x, y, 0, -1, ch); relax(i, x, y, -1, -1, diag); relax(i, x, y, 1, -1, diag)
        }
        for (y in rows - 1 downTo 0) for (x in cols - 1 downTo 0) {
            val i = y * cols + x
            if (d[i] == 0f) continue
            relax(i, x, y, 1, 0, cw); relax(i, x, y, 0, 1, ch); relax(i, x, y, 1, 1, diag); relax(i, x, y, -1, 1, diag)
        }
        val thickest = d.max()
        volumeMap = FloatArray(d.size) {
            val t = if (thickest > 0f) d[it] / thickest else 0f
            sqrt(t * (2 - t))
        }
        val edge = RELIEF_EDGE * subject.width
        for (i in d.indices) {
            val t = (d[i] / edge).coerceAtMost(1f)
            d[i] = sqrt(t * (2 - t))
        }
        return d
    }

    /** Draws into the w×h area at the canvas origin. [tiltX]/[tiltY] are -1..1. */
    fun render(c: Canvas, w: Float, h: Float, tiltX: Float, tiltY: Float, showClock: Boolean) {
        val bg = bg
        val subject = subject
        if (bg == null || subject == null) {
            c.drawColor(Color.rgb(27, 39, 87))
            if (showClock) clock.draw(c, w, h, null, null, null, null, null, tiltX, tiltY)
            hintPaint.textSize = w * 40f / 900f
            c.drawText(Layers.localized(context).getString(R.string.wallpaper_no_photo), w / 2, h * 0.62f, hintPaint)
            return
        }

        val depth = prefs.getInt(Layers.KEY_DEPTH, Layers.DEFAULT_DEPTH) / 100f
        val maxDeg = Layers.MAX_VIEW_DEG * depth
        val motion = prefs.getInt(Layers.KEY_MOTION, Layers.MOTION_TURN)
        val slide = motion == Layers.MOTION_SLIDE
        val window = motion == Layers.MOTION_WINDOW
        // Layers were built VERTICAL_ROOM times the screen height; place them centered at that size.
        val u = h * Layers.VERTICAL_ROOM / bg.height
        dst.set(w / 2 - bg.width * u / 2, h / 2 - bg.height * u / 2, w / 2 + bg.width * u / 2, h / 2 + bg.height * u / 2)
        // One fixed scale for both layers, so the object stays lined up with its spot in the background.
        val baseScale = if (window) {
            windowScale(w, h, depth, maxDeg, tiltX, tiltY)
        } else if (slide) {
            // Slide: the background only shifts, by up to SLIDE_MAX of the screen, so only that margin is cropped.
            val m = SLIDE_MAX * depth
            bgView.setTranslate(-tiltX * m * w, -tiltY * m * h)
            val scale = max(w * (1 + 2 * m) / dst.width(), h * (1 + 2 * m) / dst.height())
            applyFraming(scale)
            scale
        } else {
            // Turn: just big enough that the background still covers the screen at the widest angle.
            pivotX = w / 2
            pivotY = h / 2
            viewMatrix(w, h, maxDeg, maxDeg * PITCH_SHARE)
            val first = coverScale(w, h)
            applyFraming(first)
            // The object's center on screen moves a little with the scale, so place it, then redo the scale once for that pivot.
            pivotX = w / 2 + (dst.left + dst.width() * pivotFx - w / 2) * first
            pivotY = h / 2 + (dst.top + dst.height() * pivotFy - h / 2) * first
            viewMatrix(w, h, maxDeg, maxDeg * PITCH_SHARE)
            // Zooming in only lowers the scale coverage needs, so the larger one keeps both the zoom and full cover.
            val scale = max(first, coverScale(w, h))
            viewMatrix(w, h, tiltX * maxDeg, tiltY * maxDeg * PITCH_SHARE)
            bgView.set(view)
            scale
        }
        val bgDepthAmount = prefs.getInt(Layers.KEY_BG_DEPTH, Layers.DEFAULT_BG_DEPTH) / 100f
        val bgNear = bgDepth?.takeIf { bgDepthAmount > 0f }
        // Near parts of the background move further than the plane does, so zoom in a little more to keep the edges covered.
        val s = if (bgNear != null) baseScale * (1 + BG_MARGIN * bgDepthAmount) else baseScale
        bgOnScreen.set(w / 2 + (dst.left - w / 2) * s, h / 2 + (dst.top - h / 2) * s, w / 2 + (dst.right - w / 2) * s, h / 2 + (dst.bottom - h / 2) * s)
        val clockFront = showClock && when (prefs.getInt(Layers.KEY_CLOCK_DEPTH, Layers.DEPTH_AUTO)) {
            Layers.DEPTH_BEHIND -> false
            Layers.DEPTH_FRONT -> true
            else -> clockCovered(w, h, s) > CLOCK_FRONT_SHARE
        }

        c.drawColor(Color.BLACK)
        if (bgNear != null) {
            val m = SLIDE_MAX * depth
            val shifting = slide || window
            val turnDeg = when {
                window -> maxDeg * WINDOW_TURN
                slide -> 0f
                else -> maxDeg
            }
            drawBackgroundMesh(
                c, w, h, s, bg, bgNear, bgDepthAmount, tiltX * turnDeg, tiltY * turnDeg * PITCH_SHARE,
                if (shifting) -tiltX * m * w else 0f, if (shifting) -tiltY * m * h else 0f,
            )
        } else {
            c.save()
            c.concat(bgView)
            c.scale(s, s, w / 2, h / 2)
            c.drawBitmap(bg, null, dst, layerPaint)
            c.restore()
        }
        // The clock moves with the plane it sits on: the background's when behind the object, the object's when in front.
        if (showClock && !clockFront) drawClockOn(c, bgView, w, h, tiltX, tiltY)
        if (window) {
            // Window: the object sits a little behind the glass, so it shifts the same way as the background, but far less.
            // It also turns a little, far less than the background, so it keeps a hint of being seen from an angle.
            val amount = prefs.getInt(Layers.KEY_SUBJECT_TURN, Layers.DEFAULT_SUBJECT_TURN) / 100f
            val m = SLIDE_MAX * depth
            val dx = -tiltX * m * w * WINDOW_SUBJECT * amount
            val dy = -tiltY * m * h * WINDOW_SUBJECT * amount
            val deg = maxDeg * WINDOW_TURN * WINDOW_SUBJECT_TURN * amount
            c.save()
            c.translate(dx, dy)
            drawSubject(c, w, h, s, subject, tiltX * deg, tiltY * deg * PITCH_SHARE)
            c.restore()
            planeAt.postTranslate(dx, dy) // so a clock in front follows the object exactly
        } else if (slide) {
            // The object stays put and flat; volume and angle only apply to turning.
            planeAt.reset()
            c.save()
            c.scale(s, s, w / 2, h / 2)
            c.drawBitmap(subject, null, dst, layerPaint)
            c.restore()
        } else {
            val turn = prefs.getInt(Layers.KEY_SUBJECT_TURN, Layers.DEFAULT_SUBJECT_TURN) / 100f
            drawSubject(c, w, h, s, subject, tiltX * maxDeg * turn, tiltY * maxDeg * PITCH_SHARE * turn)
        }
        if (showClock && clockFront) drawClockOn(c, planeAt, w, h, tiltX, tiltY)
    }

    /**
     * Window: the screen is a window onto the scene. The background both shifts and turns a little around the
     * object, the way a scene seen through glass does when you move your head. Returns the scale that still
     * covers the screen at the strongest tilt, and sets [bgView] for this frame.
     */
    private fun windowScale(w: Float, h: Float, depth: Float, maxDeg: Float, tiltX: Float, tiltY: Float): Float {
        val m = SLIDE_MAX * depth
        val deg = maxDeg * WINDOW_TURN
        pivotX = w / 2
        pivotY = h / 2
        val first = worstCover(w, h, deg, m)
        applyFraming(first)
        // Turn around the object, as in Turn mode, so the background right behind it moves least.
        pivotX = w / 2 + (dst.left + dst.width() * pivotFx - w / 2) * first
        pivotY = h / 2 + (dst.top + dst.height() * pivotFy - h / 2) * first
        val scale = max(first, worstCover(w, h, deg, m))
        viewMatrix(w, h, tiltX * deg, tiltY * deg * PITCH_SHARE)
        bgView.set(view)
        bgView.postTranslate(-tiltX * m * w, -tiltY * m * h)
        return scale
    }

    /** Scale needed at the four strongest tilts, each with its matching turn and shift. */
    private fun worstCover(w: Float, h: Float, deg: Float, m: Float): Float {
        var s = 0f
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            viewMatrix(w, h, sx * deg, sy * deg * PITCH_SHARE)
            view.postTranslate(-sx * m * w, -sy * m * h)
            s = max(s, coverScale(w, h))
        }
        return s
    }

    /**
     * The background as a bent mesh: each vertex moves like the background plane, plus extra in proportion to how
     * much nearer it is than the object's spot ([near]), both for the turn (as with the object's volume) and for
     * the shift. With the phone held still it matches the photo exactly.
     */
    private fun drawBackgroundMesh(
        c: Canvas, w: Float, h: Float, s: Float, bg: Bitmap, near: FloatArray, amount: Float,
        yawDeg: Float, pitchDeg: Float, shiftX: Float, shiftY: Float,
    ) {
        val front = BG_DEPTH_MAX * amount * w
        viewMatrix(w, h, yawDeg, pitchDeg, front)
        bgFront.set(view)
        bgFront.postTranslate(shiftX, shiftY)
        viewMatrix(w, h, 0f, 0f, front)
        bgFrontRest.set(view)
        val cols = MESH_X + 1
        for (y in 0..MESH_Y) for (x in 0..MESH_X) {
            val i = y * cols + x
            meshBase[2 * i] = w / 2 + (dst.left + dst.width() * x / MESH_X - w / 2) * s
            meshBase[2 * i + 1] = h / 2 + (dst.top + dst.height() * y / MESH_Y - h / 2) * s
        }
        bgView.mapPoints(meshPlane, meshBase)
        bgFront.mapPoints(meshFront, meshBase)
        bgFrontRest.mapPoints(meshRest, meshBase)
        val gain = BG_SHIFT_GAIN * amount
        for (i in near.indices) {
            val d = near[i]
            mesh[2 * i] = meshPlane[2 * i] + d * (meshFront[2 * i] - meshPlane[2 * i] - (meshRest[2 * i] - meshBase[2 * i]) + gain * shiftX)
            mesh[2 * i + 1] = meshPlane[2 * i + 1] + d * (meshFront[2 * i + 1] - meshPlane[2 * i + 1] - (meshRest[2 * i + 1] - meshBase[2 * i + 1]) + gain * shiftY)
        }
        c.drawBitmapMesh(bg, MESH_X, MESH_Y, mesh, 0, null, 0, layerPaint)
    }

    /** Screen pixels the framing zoom adds on each side; the framing editor turns finger moves into pan with it. */
    var frameMarginX = 0f
        private set
    var frameMarginY = 0f
        private set

    /** Applies the user's zoom and pan to the layers' rectangle. Panning stays inside the zoom's margin, so the photo still covers the screen. */
    private fun applyFraming(s: Float) {
        val zoom = prefs.getFloat(Layers.KEY_FRAME_ZOOM, 1f).coerceIn(1f, Layers.MAX_FRAME_ZOOM)
        val ex = dst.width() * (zoom - 1) / 2
        val ey = dst.height() * (zoom - 1) / 2
        dst.inset(-ex, -ey)
        dst.offset(prefs.getFloat(Layers.KEY_FRAME_X, 0f).coerceIn(-1f, 1f) * ex, prefs.getFloat(Layers.KEY_FRAME_Y, 0f).coerceIn(-1f, 1f) * ey)
        frameMarginX = ex * s
        frameMarginY = ey * s
    }

    private fun drawClockOn(c: Canvas, plane: Matrix, w: Float, h: Float, tiltX: Float, tiltY: Float) {
        val tilted = prefs.getBoolean(Layers.KEY_CLOCK_TILT, true)
        c.save()
        if (tilted) c.concat(plane)
        // The glass shows the background where it is in this frame, seen from whatever plane the clock is on,
        // so a clock held still still shows the background moving behind it.
        clock.draw(c, w, h, bg, blur, bgOnScreen, bgView, if (tilted) plane else null, tiltX, tiltY)
        c.restore()
    }

    /**
     * Share of the clock's box the selected object would hide if the clock sat behind it. The clock goes
     * behind when it stays readable and in front when too much of it would be covered.
     */
    private fun clockCovered(w: Float, h: Float, s: Float): Float {
        val b = bulge ?: return 0f
        clock.box(w, h, clockArea)
        var hidden = 0
        for (iy in 0 until COVER_SAMPLES) for (ix in 0 until COVER_SAMPLES) {
            val sx = clockArea.left + clockArea.width() * (ix + 0.5f) / COVER_SAMPLES
            val sy = clockArea.top + clockArea.height() * (iy + 0.5f) / COVER_SAMPLES
            val gx = ((w / 2 + (sx - w / 2) / s - dst.left) / dst.width() * MESH_X).roundToInt().coerceIn(0, MESH_X)
            val gy = ((h / 2 + (sy - h / 2) / s - dst.top) / dst.height() * MESH_Y).roundToInt().coerceIn(0, MESH_Y)
            if (b[gy * (MESH_X + 1) + gx] > 0f) hidden++
        }
        return hidden.toFloat() / (COVER_SAMPLES * COVER_SAMPLES)
    }

    /**
     * The person turns too, but less than the background, so the depth gap between them stays. On top of that
     * the person is a bent mesh, not a flat card: each vertex sits [bulge] × volume in front of the person's
     * plane, so the middle of the body turns more than its edges. The offset at rest is subtracted, so with
     * the phone held still the person looks exactly like the photo.
     */
    private fun drawSubject(c: Canvas, w: Float, h: Float, s: Float, subject: Bitmap, yawDeg: Float, pitchDeg: Float) {
        val bulge = when (prefs.getInt(Layers.KEY_SHAPE, Layers.SHAPE_RELIEF)) {
            Layers.SHAPE_VOLUME -> volumeMap
            Layers.SHAPE_DEPTH -> depthMap ?: bulge // photos picked before depth existed have none
            else -> bulge
        } ?: return
        val front = Layers.MAX_VOLUME * prefs.getInt(Layers.KEY_VOLUME, Layers.DEFAULT_VOLUME) / 100f * w
        viewMatrix(w, h, yawDeg, pitchDeg)
        planeAt.set(view)
        viewMatrix(w, h, yawDeg, pitchDeg, front)
        frontAt.set(view)
        viewMatrix(w, h, 0f, 0f, front)
        frontAtRest.set(view)

        val cols = MESH_X + 1
        for (y in 0..MESH_Y) for (x in 0..MESH_X) {
            val i = y * cols + x
            // Vertex on the layer (dst), scaled by s around the center like the background.
            meshBase[2 * i] = w / 2 + (dst.left + dst.width() * x / MESH_X - w / 2) * s
            meshBase[2 * i + 1] = h / 2 + (dst.top + dst.height() * y / MESH_Y - h / 2) * s
        }
        // One call per matrix for the whole mesh: per-vertex calls cost too much at this mesh density.
        planeAt.mapPoints(meshPlane, meshBase)
        frontAt.mapPoints(meshFront, meshBase)
        frontAtRest.mapPoints(meshRest, meshBase)
        for (i in bulge.indices) {
            val b = bulge[i]
            for (k in 2 * i..2 * i + 1) {
                mesh[k] = meshPlane[k] + b * (meshFront[k] - meshPlane[k] - (meshRest[k] - meshBase[k]))
            }
        }
        c.drawBitmapMesh(subject, MESH_X, MESH_Y, mesh, 0, null, 0, layerPaint)
    }

    /**
     * Perspective turn around the center of the w×h area (where the person stands). [front] moves the
     * plane that many pixels toward the viewer, in the plane's own direction, before it is turned.
     */
    private fun viewMatrix(w: Float, h: Float, yawDeg: Float, pitchDeg: Float, front: Float = 0f) {
        camera.save()
        camera.setLocation(0f, 0f, -CAMERA_DISTANCE * w / 72f) // Camera works in inches at 72 px each
        camera.rotateY(yawDeg)
        camera.rotateX(pitchDeg)
        camera.translate(0f, 0f, -front) // Camera's negative z is toward the viewer
        camera.getMatrix(view)
        camera.restore()
        view.preTranslate(-pivotX, -pivotY)
        view.postTranslate(pivotX, pivotY)
    }

    /**
     * Estimated depth on the mesh grid, 0 = far .. 1 = near. The model does not say which end is near; the
     * object is nearer than its surroundings, so the map is flipped if needed. Null if that cannot be told.
     */
    private fun nearGrid(depth: Bitmap, relief: FloatArray): FloatArray? {
        val cols = MESH_X + 1
        val rows = MESH_Y + 1
        val px = IntArray(cols * rows)
        Bitmap.createScaledBitmap(depth.copy(Bitmap.Config.ARGB_8888, false), cols, rows, true).getPixels(px, 0, cols, 0, 0, cols, rows)
        val d = FloatArray(px.size) { Color.red(px[it]) / 255f }
        val inside = d.indices.filter { relief[it] > 0f }
        val outside = d.indices.filter { relief[it] == 0f }
        if (inside.isEmpty() || outside.isEmpty()) return null
        if (inside.map { d[it] }.average() < outside.map { d[it] }.average()) for (i in d.indices) d[i] = 1 - d[i]
        return d
    }

    /**
     * Shape from the estimated depth: inside the object, how near each part is compared with the rest of the
     * object (nose forward, ears back), on top of a base lift. Multiplied by the relief ramp so the object's
     * outline still rises smoothly from the background instead of tearing.
     */
    private fun depthShapeOf(near: FloatArray, relief: FloatArray): FloatArray {
        val inside = near.indices.filter { relief[it] > 0f }
        val lo = inside.minOf { near[it] }
        val range = (inside.maxOf { near[it] } - lo).takeIf { it > 0f } ?: return relief
        return FloatArray(near.size) { i ->
            if (relief[i] == 0f) 0f else relief[i] * (DEPTH_BASE + (1 - DEPTH_BASE) * (near[i] - lo) / range)
        }
    }

    /**
     * Background nearness for the background mesh, relative to the object's spot (0 there, + nearer, - farther).
     * The object's own cells are filled from the background around them, or the filled area behind the object
     * would jump forward with the object's depth; then everything is softened so depth edges stretch gently.
     */
    private fun backgroundDepthOf(near: FloatArray, relief: FloatArray): FloatArray {
        val cols = MESH_X + 1
        val rows = MESH_Y + 1
        val known = BooleanArray(near.size) { relief[it] == 0f }
        val mean = near.indices.filter { known[it] }.map { near[it] }.average().toFloat()
        var v = FloatArray(near.size) { if (known[it]) near[it] else mean }
        repeat(FILL_PASSES) {
            val next = v.copyOf()
            for (y in 0 until rows) for (x in 0 until cols) {
                val i = y * cols + x
                if (known[i]) continue
                var sum = 0f
                var n = 0
                if (x > 0) { sum += v[i - 1]; n++ }
                if (x < cols - 1) { sum += v[i + 1]; n++ }
                if (y > 0) { sum += v[i - cols]; n++ }
                if (y < rows - 1) { sum += v[i + cols]; n++ }
                next[i] = sum / n
            }
            v = next
        }
        repeat(SOFTEN_PASSES) {
            val next = v.copyOf()
            for (y in 0 until rows) for (x in 0 until cols) {
                var sum = 0f
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until cols && yy in 0 until rows) { sum += v[yy * cols + xx]; n++ }
                }
                next[y * cols + x] = sum / n
            }
            v = next
        }
        val spot = near.indices.filter { !known[it] }
        val ref = if (spot.isEmpty()) mean else spot.map { v[it] }.average().toFloat()
        return FloatArray(v.size) { v[it] - ref }
    }

    /** Center of the selected object on the mesh grid (mean of its cells); the photo's center when nothing is selected. */
    private fun centerOf(shape: FloatArray?) {
        var sx = 0f
        var sy = 0f
        var n = 0
        shape?.forEachIndexed { i, v ->
            if (v > 0f) {
                sx += i % (MESH_X + 1)
                sy += i / (MESH_X + 1)
                n++
            }
        }
        pivotFx = if (n > 0) sx / n / MESH_X else 0.5f
        pivotFy = if (n > 0) sy / n / MESH_Y else 0.5f
    }

    /** Smallest scale at which the turned background (dst through [view]) still covers all four corners. */
    private fun coverScale(w: Float, h: Float): Float {
        view.invert(inverse)
        corners[0] = 0f; corners[1] = 0f; corners[2] = w; corners[3] = 0f
        corners[4] = 0f; corners[5] = h; corners[6] = w; corners[7] = h
        inverse.mapPoints(corners)
        var s = 0f
        for (i in 0 until 4) {
            s = max(s, abs(corners[2 * i] - w / 2) / (dst.width() / 2))
            s = max(s, abs(corners[2 * i + 1] - h / 2) / (dst.height() / 2))
        }
        return s
    }

    private companion object {
        /** Forward/back tilt turns the view this share of the left/right amount (the photo has less room vertically). */
        const val PITCH_SHARE = 0.6f
        /** Viewer distance in screen widths; smaller = stronger perspective. */
        const val CAMERA_DISTANCE = 1.2f
        /** Mesh cells across and down the person layer. */
        const val MESH_X = 64
        const val MESH_Y = 128
        /** Width of the relief's rising edge, as a share of the layer width. */
        const val RELIEF_EDGE = 0.05f
        /** In the Depth shape, the share of the full height every part of the object gets before its own depth is added. */
        const val DEPTH_BASE = 0.35f
        /** Background depth at full strength: how far a part one full depth step nearer sits in front, in screen widths. */
        const val BG_DEPTH_MAX = 0.3f
        /** Background depth at full strength: extra shift for a part one full depth step nearer, as a share of the shift. */
        const val BG_SHIFT_GAIN = 1.5f
        /** Extra zoom at full background depth, so parts that move more still keep the screen edges covered. */
        const val BG_MARGIN = 0.08f
        const val FILL_PASSES = 300
        const val SOFTEN_PASSES = 2
        /** Largest background shift in Slide mode, as a share of the screen, at full effect strength. */
        const val SLIDE_MAX = 0.06f
        /** Window mode turns the background this share of Turn mode's angle. */
        const val WINDOW_TURN = 0.33f
        /** In Window mode the object shifts up to this share of the background's shift (at the slider's maximum). */
        const val WINDOW_SUBJECT = 0.2f
        /** In Window mode the object turns up to this share of the background's turn (at the slider's maximum). */
        const val WINDOW_SUBJECT_TURN = 0.6f
        /** The clock moves in front of the object when more than this share of it would be hidden. */
        const val CLOCK_FRONT_SHARE = 0.35f
        const val COVER_SAMPLES = 16
    }
}
