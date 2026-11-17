package kireishelf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as NativeCanvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

// 书架的画面：场景几何与透视相机、每帧的 Canvas 绘制（玻璃台面、阴影、光泽）、
// Android 13+ 的逐像素书本材质（AGSL），以及影棚背景。

// ---------------------------------------------------------------- 几何与相机

/**
 * Design-space constants of the bunko shelf. Units are "design px" on a 284-wide portrait canvas
 * (the prototype's phone); [ShelfCamera] scales them to the real view.
 */
internal object ShelfModel {
    const val BOOK_H = 220f
    const val BOOK_D = 156f
    const val BOOK_W = 28f
    const val OBI_FRACTION = .3f
    const val RIG_ROT_X = -8f
    const val RIG_ROT_Y = 24f
    const val TURN_DEG = 56f
    const val RIG_Z = -90f
    const val GAP = BOOK_W + 4f
    const val PART = 18f
    const val PULL = 20f
    const val LIFT = 10f
    const val PERSPECTIVE = 900f
    const val ORIGIN_Y = .44f
    const val DESIGN_WIDTH = 284f
    const val DESIGN_HEIGHT = 600f
    const val SHELF_DEPTH = BOOK_D + 30f

    val obiHeight: Float get() = round(BOOK_H * OBI_FRACTION)

    /** Books further than this from the scroll position are neither drawn nor textured. */
    const val DRAW_RADIUS = 6
}

/**
 * Where the scene sits in the view. Portrait centres it under the title; landscape moves it right
 * of a left column that holds the title, and lets it use more of the height.
 */
internal data class ShelfFrame(
    val centerX: Float,
    val rigTop: Float,
    val designHeight: Float,
    /** Height of the perspective vanishing point, as a fraction of the view. */
    val originY: Float = ShelfModel.ORIGIN_Y,
) {
    val landscape: Boolean get() = centerX != .5f

    companion object {
        // Portrait sits a little above the middle so the empty space above and below the book balances.
        val PORTRAIT = ShelfFrame(centerX = .5f, rigTop = .595f, designHeight = ShelfModel.DESIGN_HEIGHT, originY = .375f)
        val LANDSCAPE = ShelfFrame(centerX = .62f, rigTop = .64f, designHeight = 480f)

        fun forView(widthPx: Float, heightPx: Float): ShelfFrame = if (widthPx > heightPx) LANDSCAPE else PORTRAIT
    }
}

internal data class ShelfPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val rotY: Float,
    /** 0..1, how close the book is to being the selected one. */
    val near: Float,
    /** 0..1, how far the selected book has turned to face the viewer. */
    val turn: Float,
    /**
     * Sideways lean in degrees (positive = the top tips towards +x). A book leans into the gap a
     * pulled-out neighbour leaves, pivoting on the bottom corner on that side, as real books do.
     */
    val lean: Float = 0f,
)

internal object ShelfMotion {
    /** How far a book tips into the gap its pulled-out neighbour leaves. */
    const val LEAN_DEG = 2.2f

    fun clamp01(v: Float) = v.coerceIn(0f, 1f)

    fun smooth(v: Float): Float {
        val a = clamp01(v)
        return a * a * (3f - 2f * a)
    }

    /** Smoother (C2) ease, so the pull-out starts and lands without a visible kink. */
    fun smoother(v: Float): Float {
        val a = clamp01(v)
        return a * a * a * (a * (a * 6f - 15f) + 10f)
    }

    /** Small, stable per-book irregularity in -1..1 (real rows are never perfectly flush). */
    private fun jitter(index: Int, salt: Int): Float {
        var h = index * 0x27D4EB2D + salt * 0x165667B1
        h = (h xor (h ushr 15)) * 0x2C1B3C6D
        h = h xor (h ushr 13)
        return ((h and 0xFFFF) / 32767.5f) - 1f
    }

    private fun nearness(index: Int, pos: Float, focus: Float): Float = smooth(1f - abs(index - pos) * 1.5f) * focus

    /** Pull-out progress (0 = in the row, 1 = fully out) for a given nearness. */
    private fun pullOf(a: Float) = smoother(a * 1.8f)

    /**
     * Pose of book [index] when the shelf scrolls to [pos]. The book first slides straight out of
     * the row along its own depth (phase 1) — it never drifts sideways while any part of it is
     * still between its neighbours, so it cannot cut through them — and only once it is clear does
     * it turn and glide to the middle of the screen (phase 2). Neighbours part a little and tip
     * into the gap it leaves.
     *
     * [focus] (0..1) scales the whole pull-out: while the shelf is flicked quickly it drops, so the
     * row glides by with only a hint of the passing books, and the chosen one slides out as the
     * shelf comes to rest.
     */
    fun pose(index: Int, pos: Float, timeSec: Float, reducedMotion: Boolean, focus: Float = 1f): ShelfPose {
        val d = index - pos
        val a = nearness(index, pos, focus)
        val p1 = pullOf(a)
        val p2 = smoother(a * 1.8f - .8f)
        val out = ShelfModel.BOOK_D + ShelfModel.PULL
        val z = p1 * out
        val tanY = tan(Math.toRadians(ShelfModel.RIG_ROT_Y.toDouble())).toFloat()
        // The sideways glide that brings the book back to the centre of the view happens only in
        // phase 2, when the book is in front of the row.
        val x = index * ShelfModel.GAP + d.coerceIn(-1f, 1f) * ShelfModel.PART - tanY * out * p2
        val bob = if (reducedMotion) 0f else p2 * sin(timeSec * 1.3f) * 1.8f
        val sway = if (reducedMotion) 0f else p2 * sin(timeSec * .9f + .7f) * 1.5f
        // Standing books: a hair out of line in depth and angle. Fades as the book is picked.
        val rest = 1f - clamp01(a * 2f)
        val zJitter = rest * jitter(index, 1) * 1.6f
        val rotJitter = rest * jitter(index, 2) * .7f
        // Lean into an emptied neighbour slot, once that neighbour has mostly left the row.
        fun gone(j: Int) = smooth((pullOf(nearness(j, pos, focus)) - .35f) / .55f)
        val lean = if (p1 > .05f) 0f else LEAN_DEG * (gone(index + 1) - gone(index - 1)) * (1f - p1 * 20f).coerceIn(0f, 1f)
        return ShelfPose(
            x = x,
            y = -ShelfModel.BOOK_H / 2f - p2 * ShelfModel.LIFT + bob,
            z = z + zJitter,
            rotY = p2 * ShelfModel.TURN_DEG + sway + rotJitter,
            near = a,
            turn = p2,
            lean = lean,
        )
    }
}

/**
 * Back-to-front order of the books, exact for boxes that do not intersect. Each book's footprint
 * on the shelf is a rectangle; two disjoint rectangles always have a separating line along one of
 * their edge normals, and of two books standing either side of a vertical plane, the one on the
 * eye's side is in front. Sorting by mean depth is not enough: while a book slides out of the row,
 * its centre comes nearer than its neighbour's long before the part of it still between them does,
 * and it would be painted over that neighbour. Allocation-free; one instance is reused per frame.
 */
internal class ShelfDrawOrder(capacity: Int = 2 * ShelfModel.DRAW_RADIUS + 1) {
    private var cap = capacity
    private var corners = FloatArray(cap * 8)
    private var axes = FloatArray(cap * 4)
    private var depth = FloatArray(cap)
    private var front = Array(cap) { BooleanArray(cap) }
    private var used = BooleanArray(cap)
    private var out = IntArray(cap)
    private val scratch = ArrayList<Pair<Int, ShelfBookQuads>>(cap)

    private fun ensure(n: Int) {
        if (n <= cap) return
        cap = n
        corners = FloatArray(cap * 8)
        axes = FloatArray(cap * 4)
        depth = FloatArray(cap)
        front = Array(cap) { BooleanArray(cap) }
        used = BooleanArray(cap)
        out = IntArray(cap)
    }

    /** Footprint corners (x, z) of a book in [pose], with a hair of margin. */
    private fun footprint(k: Int, pose: ShelfPose) {
        val r = Math.toRadians(pose.rotY.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val hw = ShelfModel.BOOK_W / 2f
        val hd = ShelfModel.BOOK_D / 2f
        var i = 0
        for ((lx, lz) in CORNERS) {
            corners[k * 8 + i] = lx * hw * c + lz * hd * s + pose.x
            corners[k * 8 + i + 1] = -lx * hw * s + lz * hd * c + pose.z
            i += 2
        }
        // Edge normals: the book's local x and z axes.
        axes[k * 4] = c
        axes[k * 4 + 1] = -s
        axes[k * 4 + 2] = s
        axes[k * 4 + 3] = c
    }

    /** True when book [b] is in front of book [a] as seen from ([ex], [ez]); null if undecided. */
    private fun bInFront(a: Int, b: Int, ex: Float, ez: Float): Boolean? {
        for (o in 0 until 2) {
            val owner = if (o == 0) a else b
            for (j in 0 until 2) {
                val nx = axes[owner * 4 + j * 2]
                val nz = axes[owner * 4 + j * 2 + 1]
                var minA = Float.MAX_VALUE
                var maxA = -Float.MAX_VALUE
                var minB = Float.MAX_VALUE
                var maxB = -Float.MAX_VALUE
                for (q in 0 until 4) {
                    val pa = corners[a * 8 + q * 2] * nx + corners[a * 8 + q * 2 + 1] * nz
                    val pb = corners[b * 8 + q * 2] * nx + corners[b * 8 + q * 2 + 1] * nz
                    minA = minOf(minA, pa)
                    maxA = maxOf(maxA, pa)
                    minB = minOf(minB, pb)
                    maxB = maxOf(maxB, pb)
                }
                val e = ex * nx + ez * nz
                if (maxA <= minB) return e > (maxA + minB) / 2f
                if (maxB <= minA) return e < (maxB + minA) / 2f
            }
        }
        return null
    }

    /** Reorders [items] (book index to its faces) back to front; [poses] holds each book's pose. */
    fun sort(items: MutableList<Pair<Int, ShelfBookQuads>>, poses: Map<Int, ShelfPose>, eye: FloatArray) {
        val n = items.size
        if (n < 2) return
        ensure(n)
        for (k in 0 until n) {
            footprint(k, poses.getValue(items[k].first))
            depth[k] = items[k].second.depth
            used[k] = false
        }
        for (a in 0 until n) {
            front[a][a] = false
            for (b in a + 1 until n) {
                val r = bInFront(a, b, eye[0], eye[1])
                // Undecided (touching or overlapping footprints): fall back to mean depth.
                val bf = r ?: (depth[b] > depth[a])
                front[a][b] = bf
                front[b][a] = !bf
            }
        }
        for (slot in 0 until n) {
            // Next to draw: the farthest book that no remaining book is behind.
            var pick = -1
            for (k in 0 until n) {
                if (used[k]) continue
                var blocked = false
                for (j in 0 until n) {
                    // front[j][k]: k is in front of j, so j has to be drawn first.
                    if (j != k && !used[j] && front[j][k]) {
                        blocked = true
                        break
                    }
                }
                if (!blocked && (pick < 0 || depth[k] < depth[pick])) pick = k
            }
            if (pick < 0) {
                // A cycle (cannot happen for disjoint boxes): take the farthest.
                for (k in 0 until n) if (!used[k] && (pick < 0 || depth[k] < depth[pick])) pick = k
            }
            used[pick] = true
            out[slot] = pick
        }
        scratch.clear()
        scratch.addAll(items)
        for (slot in 0 until n) items[slot] = scratch[out[slot]]
    }

    private companion object {
        val CORNERS = arrayOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
    }
}

/** A projected quad: four screen points (TL, TR, BR, BL as seen from outside) plus mean depth. */
internal class ShelfQuad(
    val pts: FloatArray,
    val depth: Float,
    /** Cosine between the face normal and the key light, for shading. */
    val light: Float,
    /** Cosine between the face normal and the view axis: 1 facing the viewer, 0 edge-on. */
    val facing: Float = 1f,
    /** The face normal's screen-space x and y (right, down), for the clear-coat sheen. */
    val nx: Float = 0f,
    val ny: Float = 0f,
    /**
     * The face in world space (design units, y down, z towards the viewer), for per-pixel
     * lighting: corner 0, the edge to corner 1 (texture u), the edge to corner 3 (texture v) and
     * the unit normal, three floats each.
     */
    val world: FloatArray? = null,
) {
    /** Positive when the face points at the viewer (clockwise on a y-down screen). */
    fun signedArea(): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return s / 2f
    }

    fun contains(x: Float, y: Float): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val cross = (pts[j * 2] - pts[i * 2]) * (y - pts[i * 2 + 1]) - (pts[j * 2 + 1] - pts[i * 2 + 1]) * (x - pts[i * 2])
            val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
            if (s == 0) continue
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}

/** Faces of one book, already projected. Null faces point away from the viewer. */
internal class ShelfBookQuads(
    val cover: ShelfQuad?,
    val spine: ShelfQuad?,
    val top: ShelfQuad?,
    val back: ShelfQuad?,
    /** Tight, dark occlusion right under the book. */
    val contact: ShelfQuad,
    /** Wide penumbra, pushed away from the key light (to the right and back). */
    val soft: ShelfQuad,
    val depth: Float,
) {
    /** False when every face and both shadows lie outside a [w]×[h] view (with a margin). */
    fun onScreen(w: Float, h: Float): Boolean {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (q in arrayOf(cover, spine, top, back, soft)) {
            val p = q?.pts ?: continue
            for (k in 0 until 4) {
                minX = minOf(minX, p[k * 2])
                maxX = maxOf(maxX, p[k * 2])
                minY = minOf(minY, p[k * 2 + 1])
                maxY = maxOf(maxY, p[k * 2 + 1])
            }
        }
        val m = 64f
        return maxX > -m && minX < w + m && maxY > -m && minY < h + m
    }
}

/**
 * Perspective camera of the prototype: a CSS-like `perspective` around an origin at 44% of the
 * view height, a rig at 66% rotated by [ShelfModel.RIG_ROT_X]/[ShelfModel.RIG_ROT_Y] and pushed back
 * by [ShelfModel.RIG_Z], and a track that slides by `pos * GAP`.
 */
internal class ShelfCamera(
    viewWidthPx: Float,
    viewHeightPx: Float,
    private val pos: Float,
    private val frame: ShelfFrame = ShelfFrame.forView(viewWidthPx, viewHeightPx),
) {
    val unit: Float = (min(viewWidthPx / ShelfModel.DESIGN_WIDTH, viewHeightPx / frame.designHeight)).coerceAtLeast(.01f)
    val designW = viewWidthPx / unit
    val designH = viewHeightPx / unit
    val originX = designW * frame.centerX
    val originY = designH * frame.originY
    val rigX = designW * frame.centerX
    val rigY = designH * frame.rigTop
    private val cx = cos(Math.toRadians(ShelfModel.RIG_ROT_X.toDouble())).toFloat()
    private val sx = sin(Math.toRadians(ShelfModel.RIG_ROT_X.toDouble())).toFloat()
    private val cy = cos(Math.toRadians(ShelfModel.RIG_ROT_Y.toDouble())).toFloat()
    private val sy = sin(Math.toRadians(ShelfModel.RIG_ROT_Y.toDouble())).toFloat()
    private val tmp = FloatArray(3)

    /** Track space → world space (rig applied), written into [out]. */
    private fun rig(x: Float, y: Float, z: Float, out: FloatArray) {
        val tx = x - pos * ShelfModel.GAP
        // rotateY then rotateX then translateZ, as in `translateZ() rotateX() rotateY()`.
        val x1 = tx * cy + z * sy
        val z1 = -tx * sy + z * cy
        val y2 = y * cx - z1 * sx
        val z2 = y * sx + z1 * cx
        out[0] = x1 + rigX
        out[1] = y2 + rigY
        out[2] = z2 + ShelfModel.RIG_Z
    }

    /**
     * The eye in track space (x, z): the point every face is seen from. Used to order the books
     * exactly ([ShelfDrawOrder]) instead of by their mean depth.
     */
    val eyeTrack: FloatArray by lazy {
        val dx = originX - rigX
        val dy = originY - rigY
        val dz = ShelfModel.PERSPECTIVE - ShelfModel.RIG_Z
        val z1 = -dy * sx + dz * cx
        val tx = dx * cy - z1 * sy
        val z = dx * sy + z1 * cy
        floatArrayOf(tx + pos * ShelfModel.GAP, z)
    }

    /** Rotates a direction by the rig only (for normals). */
    private fun rigDir(x: Float, y: Float, z: Float, out: FloatArray) {
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        out[0] = x1
        out[1] = y * cx - z1 * sx
        out[2] = y * sx + z1 * cx
    }

    /** World → screen px; returns depth (world z). */
    private fun project(w: FloatArray, out: FloatArray, at: Int): Float {
        val s = ShelfModel.PERSPECTIVE / (ShelfModel.PERSPECTIVE - w[2]).coerceAtLeast(1f)
        out[at] = (originX + (w[0] - originX) * s) * unit
        out[at + 1] = (originY + (w[1] - originY) * s) * unit
        return w[2]
    }

    /** Projects one track-space point to screen px. */
    fun point(x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(2)
        rig(x, y, z, tmp)
        project(tmp, out, 0)
        return out
    }

    /** Projects four track-space corners (x,y,z triples) into a quad. */
    fun quad(corners: FloatArray, normal: FloatArray): ShelfQuad {
        val pts = FloatArray(8)
        val world = FloatArray(12)
        var depth = 0f
        for (i in 0 until 4) {
            rig(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], tmp)
            when (i) {
                0 -> { world[0] = tmp[0]; world[1] = tmp[1]; world[2] = tmp[2] }
                1 -> { world[3] = tmp[0]; world[4] = tmp[1]; world[5] = tmp[2] }
                3 -> { world[6] = tmp[0]; world[7] = tmp[1]; world[8] = tmp[2] }
            }
            depth += project(tmp, pts, i * 2)
        }
        for (k in 0 until 3) {
            world[3 + k] -= world[k]
            world[6 + k] -= world[k]
        }
        rigDir(normal[0], normal[1], normal[2], tmp)
        world[9] = tmp[0]
        world[10] = tmp[1]
        world[11] = tmp[2]
        // Key light from the upper left, slightly in front.
        val light = (tmp[0] * -.35f + tmp[1] * -.55f + tmp[2] * .76f)
        return ShelfQuad(pts, depth / 4f, light, tmp[2], tmp[0], tmp[1], world)
    }

    /**
     * The book's faces. [mirror] gives its reflection in the glass shelf (y = 0 plane): the same
     * faces upside down below the board, with the texture flipped as a mirror flips it.
     */
    fun book(pose: ShelfPose, mirror: Boolean = false): ShelfBookQuads {
        val hw = ShelfModel.BOOK_W / 2f
        val hh = ShelfModel.BOOK_H / 2f
        val hd = ShelfModel.BOOK_D / 2f
        val r = Math.toRadians(pose.rotY.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        // Lean (about the depth axis) pivots on the bottom corner on the side the book tips to.
        val lr = Math.toRadians(pose.lean.toDouble())
        val lc = cos(lr).toFloat()
        val ls = sin(lr).toFloat()
        val pivotX = if (pose.lean >= 0f) hw else -hw
        fun place(x0: Float, ly0: Float, lz: Float, out: FloatArray, at: Int) {
            val rx = x0 - pivotX
            val ry = ly0 - hh
            val lx = rx * lc - ry * ls + pivotX
            val ly = rx * ls + ry * lc + hh
            out[at] = lx * c + lz * s + pose.x
            out[at + 1] = if (mirror) -(ly + pose.y) else ly + pose.y
            out[at + 2] = -lx * s + lz * c + pose.z
        }
        fun face(vararg local: Float): FloatArray {
            val out = FloatArray(12)
            for (i in 0 until 4) place(local[i * 3], local[i * 3 + 1], local[i * 3 + 2], out, i * 3)
            return out
        }
        fun dir(lx: Float, ly: Float, lz: Float) = floatArrayOf(lx * c + lz * s, ly, -lx * s + lz * c)
        val spine = quad(face(-hw, -hh, hd, hw, -hh, hd, hw, hh, hd, -hw, hh, hd), dir(0f, 0f, 1f))
        val cover = quad(face(-hw, -hh, -hd, -hw, -hh, hd, -hw, hh, hd, -hw, hh, -hd), dir(-1f, 0f, 0f))
        val back = quad(face(hw, -hh, hd, hw, -hh, -hd, hw, hh, -hd, hw, hh, hd), dir(1f, 0f, 0f))
        val top = quad(face(-hw + 1.2f, -hh + 1.6f, -hd + 1.2f, hw - 1.2f, -hh + 1.6f, -hd + 1.2f, hw - 1.2f, -hh + 1.6f, hd - 1.4f, -hw + 1.2f, -hh + 1.6f, hd - 1.4f), dir(0f, -1f, 0f))
        // Shadows lie on the shelf top. The key light comes from the upper left and front, so the
        // penumbra falls to the right and back, further the higher the book floats.
        val lift = pose.turn
        val contact = footprint(pose, -.2f, hw + 3f, hd + 3f, .8f, -.6f)
        val soft = footprint(pose, -.2f, hw + 13f + lift * 16f, hd + 10f + lift * 12f, 6f + lift * 12f, -5f - lift * 9f)
        // A mirror reverses the winding, so its visible faces are the clockwise ones.
        fun visible(q: ShelfQuad) = if (mirror) q.signedArea() < 0f else q.signedArea() > 0f
        return ShelfBookQuads(
            cover = cover.takeIf(::visible),
            spine = spine.takeIf(::visible),
            top = top.takeIf(::visible),
            back = back.takeIf(::visible),
            contact = contact,
            soft = soft,
            depth = (spine.depth + cover.depth + back.depth) / 3f,
        )
    }

    /** A book's footprint, turned with it, at height [y] (a shadow on the shelf or the floor). */
    fun footprint(pose: ShelfPose, y: Float, halfW: Float, halfD: Float, offX: Float, offZ: Float): ShelfQuad {
        val r = Math.toRadians(pose.rotY.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val out = FloatArray(12)
        fun foot(lx: Float, lz: Float, at: Int) {
            out[at] = lx * c + lz * s + pose.x + offX
            out[at + 1] = y
            out[at + 2] = -lx * s + lz * c + pose.z + offZ
        }
        foot(-halfW, -halfD, 0)
        foot(halfW, -halfD, 3)
        foot(halfW, halfD, 6)
        foot(-halfW, halfD, 9)
        return quad(out, floatArrayOf(0f, -1f, 0f))
    }

    /** A flat strip on the floor at [y], spanning [fromX]..[toX] and [z0]..[z1] (towards the viewer). */
    fun floorStrip(
        fromX: Float,
        toX: Float,
        y: Float,
        z0: Float,
        z1: Float,
    ): ShelfQuad = quad(floatArrayOf(fromX, y, z0, toX, y, z0, toX, y, z1, fromX, y, z1), floatArrayOf(0f, -1f, 0f))

    /** The shelf board: its top surface and its front edge, spanning [fromX]..[toX] in track space. */
    fun shelfBoard(
        fromX: Float,
        toX: Float,
        /** How far towards the viewer the board reaches (a glass floor reaches much further). */
        extraFront: Float = 0f,
    ): Pair<ShelfQuad, ShelfQuad> {
        val hd0 = ShelfModel.SHELF_DEPTH / 2f
        val hd = hd0 + extraFront
        val y = .5f
        val top = quad(floatArrayOf(fromX, y, -hd0, toX, y, -hd0, toX, y, hd, fromX, y, hd), floatArrayOf(0f, -1f, 0f))
        val front = quad(floatArrayOf(fromX, y, hd, toX, y, hd, toX, y + 9f, hd, fromX, y + 9f, hd), floatArrayOf(0f, 0f, 1f))
        return top to front
    }
}

// ---------------------------------------------------------------- 每帧绘制

internal const val TEXTURE_RADIUS = ShelfModel.DRAW_RADIUS

/** Books loaded beyond the drawn range, so a fling lands on finished books. */
internal const val PREFETCH_EXTRA = 5

/** How far the glass shelf reaches in front of the row: enough to hold the pulled-out book's shadow. */
internal const val BOARD_EXTRA_FRONT = 135f

internal class ShelfSurfaceInk(
    /** The book's back board and the blank faces shown until a texture arrives. */
    val back: Int,
    val placeholder: Int,
    /** Shadow ink: a blue-grey on the light page (grey-black reads as dirt there), near black in the dark. */
    val shadowColor: Int,
    val contactAlpha: Float,
    val softAlpha: Float,
) {
    companion object {
        val LIGHT = ShelfSurfaceInk(0xFFD8DDE1.toInt(), 0xFFE9EDF0.toInt(), 0xFF243140.toInt(), contactAlpha = .16f, softAlpha = .07f)
        val DARK = ShelfSurfaceInk(0xFF353943.toInt(), 0xFF3A3F49.toInt(), 0xFF0B0E14.toInt(), contactAlpha = .34f, softAlpha = .15f)
    }
}

private val quadPath = Path()
private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val gradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val quadMatrix = Matrix()

private fun quadToPath(q: ShelfQuad): Path {
    quadPath.reset()
    quadPath.moveTo(q.pts[0], q.pts[1])
    quadPath.lineTo(q.pts[2], q.pts[3])
    quadPath.lineTo(q.pts[4], q.pts[5])
    quadPath.lineTo(q.pts[6], q.pts[7])
    quadPath.close()
    return quadPath
}


internal fun fillQuad(c: NativeCanvas, q: ShelfQuad, color: Int) {
    fillPaint.color = color
    c.drawPath(quadToPath(q), fillPaint)
}

/**
 * Matte lighting for one face: a flat Lambert term (plus the "not selected" dim), a gentle falloff
 * away from the upper-left key light and a little occlusion where the face meets the shelf. No
 * outline: a real book has none, its faces part by their light alone.
 */
internal fun lightFace(
    c: NativeCanvas,
    q: ShelfQuad,
    dim: Float,
    ao: Float,
    /** How strongly the face is shaded: a bright high-key studio (light mode) shades less. */
    soft: Float = 1f,
) {
    val path = quadToPath(q)
    val p = q.pts
    val lightShade = (1f - q.light.coerceIn(0f, 1f)) * .22f * soft
    val a = 1f - (1f - lightShade) * (1f - dim)
    if (a > .004f) {
        fillPaint.color = android.graphics.Color.argb((a * 255).roundToInt().coerceIn(0, 255), 0, 0, 0)
        c.drawPath(path, fillPaint)
    }
    gradPaint.shader = LinearGradient(p[0], p[1], p[4], p[5], 0x00000000, ((0x1A * soft).roundToInt() shl 24), Shader.TileMode.CLAMP)
    c.drawPath(path, gradPaint)

    if (ao > .01f) {
        val bx = (p[4] + p[6]) / 2f
        val by = (p[5] + p[7]) / 2f
        // Measured on the path-traced reference: about 5% in the last few percent of the height.
        val tx = bx + ((p[0] + p[2]) / 2f - bx) * .06f
        val ty = by + ((p[1] + p[3]) / 2f - by) * .06f
        gradPaint.shader = LinearGradient(bx, by, tx, ty, android.graphics.Color.argb((ao * .06f * 255).roundToInt(), 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.drawPath(path, gradPaint)
    }
    gradPaint.shader = null
}

/**
 * The jacket laminate catching the softbox, as a clear coat does: a broad soft band that
 * sweeps across the face as the book turns, bright only near the mirror angle. [extent] is the
 * glossy part of the face from its top (the obi below it is matte paper).
 */
internal fun sheen(c: NativeCanvas, q: ShelfQuad, extent: Float, dark: Boolean) {
    // Half vector between the key light (upper left, in front) and the viewer.
    val align = (q.nx * -.19f + q.ny * -.29f + q.facing * .94f).coerceIn(0f, 1f)
    val amount = kotlin.math.exp(-(1f - align) * 7f)
    if (amount < .03f) return
    val p = q.pts
    val bx = p[0] + (p[6] - p[0]) * extent
    val by = p[1] + (p[7] - p[1]) * extent
    val cx = p[2] + (p[4] - p[2]) * extent
    val cy = p[3] + (p[5] - p[3]) * extent
    // The band slides along the diagonal with the face's sideways tilt.
    val t = (.5f + (q.nx + .19f) * 2.6f).coerceIn(-.2f, 1.2f)
    val a = ((if (dark) .14f else .2f) * amount * 255).roundToInt().coerceIn(0, 255)
    val on = (a shl 24) or 0xFFFFFF
    gradPaint.shader =
        LinearGradient(
            p[0], p[1], cx, cy,
            intArrayOf(0x00FFFFFF, 0x00FFFFFF, on, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, (t - .32f).coerceIn(0f, 1f), t.coerceIn(0f, 1f), (t + .32f).coerceIn(0f, 1f), 1f),
            Shader.TileMode.CLAMP,
        )
    quadPath.reset()
    quadPath.moveTo(p[0], p[1])
    quadPath.lineTo(p[2], p[3])
    quadPath.lineTo(cx, cy)
    quadPath.lineTo(bx, by)
    quadPath.close()
    c.drawPath(quadPath, gradPaint)
    gradPaint.shader = null
}

/** A soft rectangular shadow in [color]; [soft] spreads the falloff for the penumbra. */
internal fun shadowBitmap(color: Int, soft: Boolean): Bitmap {
    // A blurred rectangle (separable smooth falloff), mapped onto the book's footprint: the shadow
    // keeps the book's square shape with soft edges, as a real one does, rather than an ellipse.
    val s = 128
    val edge = if (soft) .46f else .3f
    val ramp = FloatArray(s) { i ->
        val t = ((minOf(i + .5f, s - i - .5f) / s) / edge).coerceIn(0f, 1f)
        val k = t * t * (3f - 2f * t)
        if (soft) k * k else k
    }
    val rgb = color and 0x00FFFFFF
    val px = IntArray(s * s) { i -> rgb or ((ramp[i % s] * ramp[i / s] * 255f).roundToInt() shl 24) }
    return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
}

/** The page block seen from above: fine cream leaves, a little darker towards the spine. */
internal fun pagesBitmap(): Bitmap {
    val w = 64
    val h = 16
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = NativeCanvas(bmp)
    val light = Paint().apply { color = 0xFFF6F1E6.toInt() }
    val dark = Paint().apply { color = 0xFFE2D9C8.toInt() }
    for (x in 0 until w) c.drawRect(x.toFloat(), 0f, x + 1f, h.toFloat(), if (x % 2 == 0) light else dark)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x14000000, 0x00000000, Shader.TileMode.CLAMP) })
    return bmp
}

internal fun mapBitmap(c: NativeCanvas, bmp: Bitmap, q: ShelfQuad, paint: Paint) {
    val w = bmp.width.toFloat()
    val h = bmp.height.toFloat()
    val ok = quadMatrix.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, q.pts, 0, 4)
    if (!ok) return
    c.drawBitmap(bmp, quadMatrix, paint)
}


private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG)

private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
private val glassStops = floatArrayOf(.18f, .3f, .38f, .46f, .6f)
private val boardBounds = android.graphics.RectF()
private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
private val glassPath = Path()

/**
 * [q] clipped to the screen (plus a margin) as a path. The glass floor projects to a polygon
 * thousands of pixels across, and the GPU renderer silently drops anti-aliased paths that large,
 * so big surfaces are clipped first (Sutherland–Hodgman against the four screen edges).
 */
private fun screenPathOf(q: ShelfQuad, w: Float, h: Float): Path {
    var poly = ArrayList<Float>(16).apply { for (v in q.pts) add(v) }
    val m = 32f
    fun clip(inside: (Float, Float) -> Boolean, cross: (Float, Float, Float, Float) -> Pair<Float, Float>) {
        val out = ArrayList<Float>(16)
        val n = poly.size / 2
        for (i in 0 until n) {
            val ax = poly[i * 2]
            val ay = poly[i * 2 + 1]
            val bx = poly[((i + 1) % n) * 2]
            val by = poly[((i + 1) % n) * 2 + 1]
            val ai = inside(ax, ay)
            val bi = inside(bx, by)
            if (ai) { out += ax; out += ay }
            if (ai != bi) cross(ax, ay, bx, by).let { out += it.first; out += it.second }
        }
        poly = out
    }
    fun atX(x: Float) = { ax: Float, ay: Float, bx: Float, by: Float -> x to ay + (by - ay) * (x - ax) / (bx - ax) }
    fun atY(y: Float) = { ax: Float, ay: Float, bx: Float, by: Float -> ax + (bx - ax) * (y - ay) / (by - ay) to y }
    clip({ x, _ -> x >= -m }, atX(-m))
    if (poly.size >= 6) clip({ x, _ -> x <= w + m }, atX(w + m))
    if (poly.size >= 6) clip({ _, y -> y >= -m }, atY(-m))
    if (poly.size >= 6) clip({ _, y -> y <= h + m }, atY(h + m))
    glassPath.reset()
    if (poly.size < 6) return glassPath
    glassPath.moveTo(poly[0], poly[1])
    for (i in 1 until poly.size / 2) glassPath.lineTo(poly[i * 2], poly[i * 2 + 1])
    glassPath.close()
    return glassPath
}

/**
 * The softbox mirrored in a glass surface: one soft band along the quad's diagonal, placed by
 * where the surface sits on screen, so it slides as the scene scrolls. The sliding is the cue that
 * reads as glass. One gradient fill.
 */
private fun windowReflection(c: NativeCanvas, q: ShelfQuad, screenW: Float, dark: Boolean, alpha: Float) {
    if (alpha <= .01f) return
    val p = q.pts
    val cx = (p[0] + p[2] + p[4] + p[6]) / 4f
    val o = (cx / screenW - .5f) * 1.3f
    val dx = p[4] - p[0]
    val dy = p[5] - p[1]
    val sx = p[0] - dx * o
    val sy = p[1] - dy * o
    val tone = if (dark) 0xC9DAFF else 0xFFFFFF
    fun pane(k: Float) = (((alpha * k).coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or tone
    glassPaint.shader =
        LinearGradient(sx, sy, sx + dx, sy + dy, intArrayOf(pane(0f), pane(.35f), pane(1f), pane(.35f), pane(0f)), glassStops, Shader.TileMode.CLAMP)
    c.drawPath(screenPathOf(q, c.width.toFloat(), c.height.toFloat()), glassPaint)
    glassPaint.shader = null
}

private fun line(c: NativeCanvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
    rimPaint.color = color
    rimPaint.strokeWidth = width
    c.drawLine(x0, y0, x1, y1, rimPaint)
}

/**
 * The shelf as a thick, clear glass plate on the reeded wall. Its top is mostly transparent (the
 * wall shows through, faintly green); the books are mirrored in it, strongest at their feet and
 * fading out within [fadeLength] — masked, so the glass stays clear where they have faded. The
 * front edge is the plate seen through its thickness: the deep green of real glass, a bright lip
 * on top and a fainter one below, where the light inside is totally reflected.
 */
internal fun drawGlassBoard(
    c: NativeCanvas,
    top: ShelfQuad,
    front: ShelfQuad,
    dark: Boolean,
    /** Screen y of the books' front feet, where their reflections start. */
    fadeFromY: Float,
    fadeLength: Float,
    reflections: (NativeCanvas) -> Unit,
) {
    val w = c.width.toFloat()
    val h = c.height.toFloat()
    val topPath = Path(screenPathOf(top, w, h))
    // A breath of the glass's own tint over the wall seen through it, denser towards the front.
    val t = top.pts
    glassFill.shader =
        LinearGradient(
            0f, (t[1] + t[3]) / 2f, 0f, (t[5] + t[7]) / 2f,
            // Clear at the back, so the plate melts into the studio behind it; a breath of light,
            // brighter in the light studio, gathering towards the front.
            if (dark) 0x009FD6CB else 0x00F2F9F7, if (dark) 0x269FD6CB else 0x5CEAF6F2,
            Shader.TileMode.CLAMP,
        )
    c.drawPath(topPath, glassFill)
    glassFill.shader = null
    // The books' mirror images, masked to fade out from their feet.
    topPath.computeBounds(boardBounds, true)
    if (boardBounds.intersect(0f, 0f, w, h)) {
        val layer = c.saveLayer(boardBounds, null)
        c.clipPath(topPath)
        reflections(c)
        fadePaint.shader = LinearGradient(0f, fadeFromY, 0f, fadeFromY + fadeLength, 0xFF000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(boardBounds, fadePaint)
        fadePaint.shader = null
        c.restoreToCount(layer)
    }
    // The lamp and window mirrored in the plate.
    windowReflection(c, top, w, dark, if (dark) .05f else .2f)
    // No line along the back edge: the plate melts into the studio there, so no seam shows beside the books.
    // The front edge, seen through the plate's thickness: bright lips where the light inside is
    // totally reflected, the deep green of the glass between them, the wall faintly through it.
    val f = front.pts
    // Across the edge, perpendicular to it: the edge runs aslant on screen.
    val ex = f[2] - f[0]
    val ey = f[3] - f[1]
    val el = kotlin.math.sqrt(ex * ex + ey * ey).coerceAtLeast(1f)
    val nx = -ey / el
    val ny = ex / el
    val mx = (f[0] + f[2]) / 2f
    val my = (f[1] + f[3]) / 2f
    val thick = ((f[6] + f[4]) / 2f - mx) * nx + ((f[7] + f[5]) / 2f - my) * ny
    // As the path-traced reference shows it: the plate's thickness is an even, translucent glass
    // green, a little deeper towards its lower edge (light runs further inside), with only a fine
    // lip of light along the top. In the dark studio it is a dark green-black with a faint lip.
    glassFill.shader =
        LinearGradient(
            mx, my, mx + nx * thick, my + ny * thick,
            intArrayOf(
                if (dark) 0xE6121B19.toInt() else 0xB3A9CBBF.toInt(),
                if (dark) 0xE60E1715.toInt() else 0xC2A6C9BC.toInt(),
                if (dark) 0xF0080F0E.toInt() else 0xD19BC2B4.toInt(),
            ),
            floatArrayOf(0f, .55f, 1f),
            Shader.TileMode.CLAMP,
        )
    c.drawPath(screenPathOf(front, w, h), glassFill)
    glassFill.shader = null
    line(c, f[0], f[1], f[2], f[3], if (dark) 0x40CFE8E0 else 0xB3FFFFFF.toInt(), 1.2f)
    line(c, f[6], f[7], f[4], f[5], if (dark) 0x1FCFE8E0 else 0x80E9F1EE.toInt(), 1f)
}


// ---------------------------------------------------------------- 书本材质（Android 13+）

/**
 * The book material on Android 13+: every visible book face is lit per pixel, like a PBR
 * material, but in one small AGSL shader on the ordinary Canvas.
 *
 * - The painted jacket/spine bitmap is the albedo.
 * - Tangent-space normal maps drawn on the CPU once ([ShelfSurfaces.laminate] for the glossy
 *   film-laminated jacket, [ShelfSurfaces.paper] for the matte obi and spine label) give the
 *   surface its relief.
 * - A large softbox key light up and to the left, a fill from the right and an ambient term, with
 *   per-pixel view and light vectors (each face knows where it is in the world), so a highlight
 *   travels across a jacket as the book turns, with Fresnel brightening at grazing angles.
 * - Light and dark mode have their own lighting rigs: a bright high-key studio, and a darker set
 *   with a stronger key and a cool rim.
 *
 * Per frame this is a handful of uniform updates per face; the per-pixel cost is a few texture
 * reads and some arithmetic, over the books' area only.
 */
/** The material where the device can run it (Android 13+), otherwise null: the baked textures are used. */
internal fun shelfBookMaterialOrNull(dark: Boolean): ShelfBookMaterial? =
    if (Build.VERSION.SDK_INT >= 33) runCatching { ShelfBookMaterial.create(dark) }.getOrNull() else null

@RequiresApi(33)
internal class ShelfBookMaterial private constructor(private val dark: Boolean) {
    private val shader = RuntimeShader(AGSL)
    private val laminate = tile(ShelfSurfaces.laminate())
    private val paper = tile(ShelfSurfaces.paper())
    private val images = WeakHashMap<Bitmap, BitmapShader>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val path = Path()
    private val src = FloatArray(8)

    init {
        shader.setInputShader("laminate", laminate)
        shader.setInputShader("paper", paper)
        // Diffuse terms are irradiance (1 = a white face fully lit). The reflection terms are the
        // radiance of what the clear coat mirrors, in the same units, worked out from the softboxes'
        // size and distance and checked against a path-traced reference of this scene: a softbox
        // that lights a face to k has a radiance of about k / 0.12 (its solid angle over pi), the
        // strip kicker about k / 0.084.
        if (dark) {
            shader.setFloatUniform("amb", .34f, .35f, .38f)
            shader.setFloatUniform("keyCol", .78f, .77f, .75f)
            shader.setFloatUniform("fillCol", .1f, .13f, .19f)
            shader.setFloatUniform("keyRad", 6.5f, 6.4f, 6.25f)
            shader.setFloatUniform("kickRad", 1.2f, 1.55f, 2.26f)
            // A dark studio: next to nothing to mirror besides the lights.
            shader.setFloatUniform("envRad", .03f, .032f, .04f)
        } else {
            shader.setFloatUniform("amb", .75f, .755f, .77f)
            shader.setFloatUniform("keyCol", .4f, .395f, .385f)
            shader.setFloatUniform("fillCol", .1f, .105f, .115f)
            shader.setFloatUniform("keyRad", 3.33f, 3.29f, 3.21f)
            shader.setFloatUniform("kickRad", 1.19f, 1.25f, 1.37f)
            // A white high-key studio all round: what gives a glossy jacket its soft veil.
            shader.setFloatUniform("envRad", .66f, .67f, .69f)
        }
        // Fill from the right and a little behind (a rim in the dark), in world space (y down).
        shader.setFloatUniform("fillDir", .78f, -.25f, -.12f)
    }

    /** Places the eye and the key light for this frame's camera. */
    fun begin(cam: ShelfCamera) {
        shader.setFloatUniform("eye", cam.originX, cam.originY, ShelfModel.PERSPECTIVE)
        shader.setFloatUniform("keyPos", cam.rigX - 430f, cam.rigY - 620f, 640f)
    }

    /**
     * Draws [bmp] on [q] with the material. [paperA] / [paperB] are uv rects (u0, v0, u1, v1)
     * that are matte paper; the rest is laminate. [unitsPerPx] converts texture px to design
     * units (for the relief tile size); [dim] darkens books away from the selection, [ao]
     * darkens the foot where the book meets the shelf. Returns false when it could not draw.
     */
    fun face(
        c: NativeCanvas,
        bmp: Bitmap,
        q: ShelfQuad,
        paperA: FloatArray,
        paperB: FloatArray,
        unitsPerPx: Float,
        dim: Float,
        ao: Float,
        alpha: Int,
    ): Boolean {
        val w = q.world ?: return false
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        src[0] = 0f; src[1] = 0f; src[2] = bw; src[3] = 0f; src[4] = bw; src[5] = bh; src[6] = 0f; src[7] = bh
        if (!matrix.setPolyToPoly(src, 0, q.pts, 0, 4)) return false
        val image = images.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR } }
        shader.setInputShader("image", image)
        shader.setFloatUniform("size", bw, bh)
        shader.setFloatUniform("c0", w[0], w[1], w[2])
        shader.setFloatUniform("du", w[3], w[4], w[5])
        shader.setFloatUniform("dv", w[6], w[7], w[8])
        shader.setFloatUniform("nrm", w[9], w[10], w[11])
        shader.setFloatUniform("paperA", paperA[0], paperA[1], paperA[2], paperA[3])
        shader.setFloatUniform("paperB", paperB[0], paperB[1], paperB[2], paperB[3])
        // Relief tiles: the laminate's long waves repeat every 150 design units, the paper every 56.
        shader.setFloatUniform("tileLam", ShelfSurfaces.SIZE / (150f / unitsPerPx))
        shader.setFloatUniform("tilePaper", ShelfSurfaces.SIZE / (56f / unitsPerPx))
        shader.setFloatUniform("dim", dim)
        shader.setFloatUniform("ao", ao)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.alpha = alpha
        path.reset()
        val p = q.pts
        path.moveTo(p[0], p[1])
        path.lineTo(p[2], p[3])
        path.lineTo(p[4], p[5])
        path.lineTo(p[6], p[7])
        path.close()
        c.drawPath(path, paint)
        paint.shader = null
        return true
    }

    companion object {
        /** Whole face is paper / no paper. */
        val ALL = floatArrayOf(0f, 0f, 1f, 1f)
        val NONE = floatArrayOf(2f, 2f, 2f, 2f)

        fun create(dark: Boolean): ShelfBookMaterial = ShelfBookMaterial(dark)

        /**
         * A normal map as an opaque bitmap (premultiplied alpha would bend the normals): red and
         * green hold the normal's x and y (its z follows from them), blue the albedo variation
         * the map carried in its alpha — the paper's fibres show as tone as well as relief.
         */
        private fun tile(map: IntArray): BitmapShader {
            val n = ShelfSurfaces.SIZE
            val px = IntArray(map.size) { (map[it] and 0x00FFFF00) or (map[it] ushr 24) or 0xFF000000.toInt() }
            val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
            return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        }

        private const val AGSL = """
uniform shader image;
uniform shader laminate;
uniform shader paper;
uniform float2 size;
uniform float3 c0;
uniform float3 du;
uniform float3 dv;
uniform float3 nrm;
uniform float3 eye;
uniform float3 keyPos;
uniform float3 keyCol;
uniform float3 fillDir;
uniform float3 fillCol;
uniform float3 amb;
uniform float3 keyRad;
uniform float3 kickRad;
uniform float3 envRad;
uniform float4 paperA;
uniform float4 paperB;
uniform float tileLam;
uniform float tilePaper;
uniform float dim;
uniform float ao;

bool inside(float2 uv, float4 r) {
    return uv.x >= r.x && uv.x <= r.z && uv.y >= r.y && uv.y <= r.w;
}

half4 main(float2 p) {
    float2 uv = p / size;
    half4 base = image.eval(p);
    bool onPaper = inside(uv, paperA) || inside(uv, paperB);
    float3 m;
    float tone = 1.0;
    if (onPaper) {
        m = float3(paper.eval(p * tilePaper).rgb);
        // Fibres: a little relief, and a breath of tone.
        tone = 1.0 + (m.b - 0.5) * 0.03;
    } else {
        m = float3(laminate.eval(p * tileLam).rgb);
    }
    float2 txy = (m.rg * 2.0 - 1.0) * (onPaper ? 0.08 : 0.06);
    float3 tn = float3(txy, sqrt(max(1.0 - dot(txy, txy), 0.0)));
    float3 T = normalize(du);
    float3 B = normalize(dv);
    float3 n = normalize(T * tn.x + B * tn.y + nrm * tn.z);
    float3 P = c0 + du * uv.x + dv * uv.y;
    float3 V = normalize(eye - P);
    float3 L = normalize(keyPos - P);
    float ndl = max(dot(n, L), 0.0);
    // Wrapped diffuse: a large softbox lights well past the terminator.
    float wrap = clamp((dot(n, L) + 0.35) / 1.35, 0.0, 1.0);
    float3 light = amb + keyCol * (0.55 * ndl + 0.45 * wrap) + fillCol * max(dot(n, normalize(fillDir)), 0.0);
    // Occlusion where the book stands on the glass: about 5% in the last few percent of its
    // height (the path-traced reference), not a band.
    light *= 1.0 - ao * 0.06 * smoothstep(0.93, 1.0, uv.y);
    // Fresnel of the coat (laminate film) or of bare paper, which scatters most of it.
    float nv = max(dot(n, V), 0.0);
    float f0 = onPaper ? 0.03 : 0.04;
    float F = f0 + (1.0 - f0) * pow(1.0 - nv, 5.0);
    if (onPaper) F = min(F, 0.12);
    // What the coat reflects is not diffused: the print under it gets the rest.
    float3 col = float3(base.rgb) * tone * light * (1.0 - F) * (1.0 - dim);
    // What the surface mirrors, at the lights' real positions: the key softbox (round, about
    // 0.2 rad of falloff with the film's roughness), the tall strip kicker on the right, and the
    // studio itself, a little brighter overhead. Paper blurs all of it into a faint sheen.
    float3 R = reflect(-V, n);
    float key = exp(-(1.0 - dot(R, L)) / (onPaper ? 0.36 : 0.045)) * (onPaper ? 0.12 : 1.0);
    float3 Fd = normalize(fillDir);
    float da = atan(R.x, R.z) - atan(Fd.x, Fd.z);
    da -= 6.2831853 * floor((da + 3.1415927) / 6.2831853);
    float el = asin(clamp(-R.y, -1.0, 1.0));
    float de = el - asin(clamp(-Fd.y, -1.0, 1.0));
    float kick = exp(-(da * da / (onPaper ? 0.25 : 0.012) + de * de / 0.2)) * (onPaper ? 0.15 : 1.0);
    float3 env = envRad * (0.85 + 0.3 * smoothstep(-0.4, 1.0, el));
    float3 spec = F * (keyRad * key + kickRad * kick + env) * (1.0 - dim);
    // Added in (roughly) linear light, with a soft shoulder so it never clips: the print stays
    // visible through the reflection.
    float3 lin = col * col;
    float3 room = max(1.0 - lin, 0.02);
    lin += room * (1.0 - exp(-spec / room));
    return half4(half3(sqrt(lin)), 1.0) * base.a;
}
"""
    }
}

// ---------------------------------------------------------------- 影棚背景

/**
 * The backdrop, in the manner of a product shot: an infinite seamless studio sweep (no floor
 * line, no props), lit by one large soft light just out of frame at the upper left. Its bloom, a
 * few very faint broad rays fanning from it, and a breath of the selected book's colour behind the
 * book are the only things on it.
 */
internal class ShelfLightStudio(private val dark: Boolean) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var w = 0f
    private var h = 0f
    private var sweep: Shader? = null
    private var bloom: Shader? = null
    private var shade: Shader? = null

    private fun build(width: Float, height: Float) {
        if (width == w && height == h && sweep != null) return
        w = width
        h = height
        // The sweep: lightest a little below the middle where the light lands on the cove, falling
        // off softly above (towards the dark ceiling) and towards the viewer.
        sweep =
            LinearGradient(
                0f, 0f, 0f, h,
                if (dark) intArrayOf(0xFF060708.toInt(), 0xFF0D0E10.toInt(), 0xFF141518.toInt(), 0xFF070809.toInt())
                else intArrayOf(0xFFE4E9EC.toInt(), 0xFFF0F3F4.toInt(), 0xFFF7F8F8.toInt(), 0xFFE8ECEE.toInt()),
                floatArrayOf(0f, .34f, .62f, 1f),
                Shader.TileMode.CLAMP,
            )
        val lx = -w * .12f
        val ly = -h * .06f
        val r = h * .95f
        bloom =
            RadialGradient(
                lx, ly, r,
                if (dark) intArrayOf(0x3DDCE8FF, 0x14B8CCF0, 0x00B8CCF0)
                else intArrayOf(0xFFFFFFFF.toInt(), 0x8CFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, .38f, 1f),
                Shader.TileMode.CLAMP,
            )
        // Cool shade in the far corner, away from the light: keeps the white from going flat.
        shade =
            RadialGradient(
                w * 1.1f, h * .9f, h * .7f,
                if (dark) intArrayOf(0x40000000, 0x00000000) else intArrayOf(0x1F5B7486, 0x005B7486),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    private var cache: Bitmap? = null
    private val cachePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.RectF()

    /**
     * The studio never changes while the shelf moves, so it is painted once, at half resolution
     * (it is all soft gradients), into one bitmap: per frame it is a single pass instead of the
     * seven full-screen gradient fills it is made of. Only [tint] — the selected book's colour
     * (alpha = strength), glowing behind it at [tintX], [tintY] — is drawn live, over it.
     */
    fun draw(c: NativeCanvas, tint: Int, tintX: Float, tintY: Float) {
        val cw = c.width.toFloat()
        val ch = c.height.toFloat()
        val bmp = cache?.takeIf { cw == w && ch == h } ?: render(cw, ch)
        dst.set(0f, 0f, w, h)
        c.drawBitmap(bmp, null, dst, cachePaint)
        val ta = android.graphics.Color.alpha(tint)
        if (ta > 2) {
            val r = h * .42f
            paint.shader = RadialGradient(tintX, tintY, r, intArrayOf(tint, (ta / 3 shl 24) or (tint and 0xFFFFFF), tint and 0xFFFFFF), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(tintX, tintY, r, paint)
            paint.shader = null
        }
    }

    private fun render(width: Float, height: Float): Bitmap {
        cache?.recycle()
        w = 0f
        sweep = null
        build(width, height)
        val bmp = Bitmap.createBitmap((width / 2f).roundToInt().coerceAtLeast(1), (height / 2f).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = NativeCanvas(bmp)
        c.scale(.5f, .5f)
        paint.shader = sweep
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = shade
        c.drawRect(0f, 0f, w, h, paint)
        rays(c)
        paint.shader = bloom
        c.drawRect(0f, 0f, w, h, paint)
        // A soft vignette that keeps the eye on the middle.
        paint.shader =
            RadialGradient(
                w / 2f, h * .45f, maxOf(w, h) * .7f,
                intArrayOf(0, 0, if (dark) 0x1F000000 else 0x06000000),
                floatArrayOf(0f, .62f, 1f),
                Shader.TileMode.CLAMP,
            )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        cache = bmp
        return bmp
    }

    /** Three broad, very soft shafts of light fanning down from the source. */
    private fun rays(c: NativeCanvas) {
        val sx = -w * .12f
        val sy = -h * .06f
        val len = h * 1.15f
        val base = if (dark) .035f else .16f
        for (i in 0 until 3) {
            val ang = Math.toRadians(52.0 + i * 11.0).toFloat()
            val half = Math.toRadians(2.6 + i * .9).toFloat()
            val a = base * (1f - i * .22f)
            val dx0 = cos(ang - half)
            val dy0 = sin(ang - half)
            val dx1 = cos(ang + half)
            val dy1 = sin(ang + half)
            path.reset()
            path.moveTo(sx, sy)
            path.lineTo(sx + dx0 * len, sy + dy0 * len)
            path.lineTo(sx + dx1 * len, sy + dy1 * len)
            path.close()
            val tone = if (dark) 0xDCE8FF else 0xFFFFFF
            val on = ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or tone
            val mid = ((a * 115).roundToInt().coerceIn(0, 255) shl 24) or tone
            paint.shader = LinearGradient(sx, sy, sx + cos(ang) * len, sy + sin(ang) * len, intArrayOf(on, mid, tone), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
        }
    }

    companion object {
        /** The studio floor, below the floating glass shelf (design units, y down). */
        const val FLOOR_Y = 28f

        /**
         * The light the shelf's front edge bends onto the floor, as one strip, across it: a soft
         * bright line, then a short spectrum where the glass splits it. Drawn once.
         */
        fun causticStrip(dark: Boolean): Bitmap {
            val n = 96
            val m = 64
            val px = IntArray(m * n)
            val col = IntArray(n)
            for (y in 0 until n) {
                val t = (y + .5f) / n
                val line = exp(-((t - .16f) / .045f).let { it * it })
                val glow = exp(-((t - .2f) / .14f).let { it * it }) * .3f
                val bowT = (t - .34f) / .44f
                val bow = if (bowT in 0f..1f) sin(bowT * Math.PI.toFloat()) else 0f
                var r = 1f
                var g = 1f
                var b = 1f
                var a = (line + glow) * (if (dark) .38f else .6f)
                if (bow > 0f) {
                    // Red .. violet down the strip, pastel: the spectrum mixed with the light.
                    val hue = bowT
                    val sr = (1.5f - kotlin.math.abs(hue * 4f - .5f)).coerceIn(0f, 1f) + (hue * 4f - 3.2f).coerceIn(0f, 1f) * .6f
                    val sg = (1.5f - kotlin.math.abs(hue * 4f - 1.9f)).coerceIn(0f, 1f)
                    val sb = (1.5f - kotlin.math.abs(hue * 4f - 3.1f)).coerceIn(0f, 1f)
                    val ba = bow * (if (dark) .16f else .22f)
                    val total = a + ba
                    if (total > 0f) {
                        r = (r * a + (sr * .7f + .3f) * ba) / total
                        g = (g * a + (sg * .7f + .3f) * ba) / total
                        b = (b * a + (sb * .7f + .3f) * ba) / total
                    }
                    a = total
                }
                col[y] =
                    ((a.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or
                        ((r * 255).roundToInt().coerceIn(0, 255) shl 16) or
                        ((g * 255).roundToInt().coerceIn(0, 255) shl 8) or
                        (b * 255).roundToInt().coerceIn(0, 255)
            }
            // Along the shelf the glint fades out both ways: a pool of light, not a stripe.
            for (x in 0 until m) {
                val u = (x + .5f) / m * 2f - 1f
                val k = exp(-u * u * 3.2f)
                for (y in 0 until n) {
                    val c = col[y]
                    val a = ((c ushr 24) * k).roundToInt()
                    px[y * m + x] = (a shl 24) or (c and 0xFFFFFF)
                }
            }
            return Bitmap.createBitmap(px, m, n, Bitmap.Config.ARGB_8888)
        }
    }
}
