package kireishelf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// 书本贴图：把封面画成护封 + 腰封、书脊，每本书只画一次。上面印的字在 ShelfLabels.kt。
// 另外是封面取色、材质用的表面法线贴图，以及贴图的内存缓存。

internal class ShelfTextures(val cover: Bitmap, val spine: Bitmap, val swatches: ShelfSwatches)

internal object ShelfTexturePainter {
    fun paint(info: ShelfBookInfo, cover: Bitmap?, swatches: ShelfSwatches, dark: Boolean, pxPerUnit: Float): ShelfTextures {
        val k = pxPerUnit.coerceIn(1f, 3.5f)
        val ink = ShelfInk(dark, swatches)
        return ShelfTextures(paintCover(info, cover, swatches, ink, k), paintSpine(info, cover, swatches, ink, k), swatches)
    }

    /** The front: the cover art, the jacket's fold on the spine side, and the obi band. */
    private fun paintCover(info: ShelfBookInfo, cover: Bitmap?, swatches: ShelfSwatches, ink: ShelfInk, k: Float): Bitmap {
        val w = (ShelfModel.BOOK_D * k).roundToInt().toFloat()
        val h = (ShelfModel.BOOK_H * k).roundToInt().toFloat()
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val em = ShelfModel.BOOK_H / 20f * k
        drawCoverCrop(c, cover, RectF(0f, 0f, w, h), swatches.darkMuted)
        // The fold over the board edge on the spine side: a narrow, soft crease.
        c.drawRect(w - .3f * em, 0f, w, h, gradient(w - .3f * em, 0f, w, 0f, 0, 0x1F000000))
        // Printed ink never reaches pure black; a faint catch-light on the top edge.
        c.drawRect(0f, 0f, w, h, fill(0x0DF4EFE6))
        c.drawRect(0f, 0f, w, .28f * em, gradient(0f, 0f, 0f, .28f * em, 0x2EFFFFFF, 0))
        val obh = ShelfModel.obiHeight * k
        c.save()
        c.translate(0f, h - obh)
        c.drawRect(0f, 0f, w, obh, fill(ink.label))
        c.drawRect(0f, 0f, w, max(1f, .5f * k), fill(0x14000000))
        paintObi(c, info, ink, w, em)
        // A soft top sheen, as on a printed paper band, then the grain and the line where it wraps.
        c.drawRect(0f, 0f, w, obh, Paint().apply { shader = LinearGradient(0f, 0f, 0f, obh, intArrayOf(0x14FFFFFF, 0, 0x0F000000), floatArrayOf(0f, .3f, 1f), Shader.TileMode.CLAMP) })
        paperGrain(c, RectF(0f, 0f, w, obh))
        c.drawRect(0f, 0f, w, max(1f, .6f * k), fill(0x14000000))
        c.restore()
        return bmp
    }

    /** The spine: the cover art carries on round it, the title label in the middle, the obi below. */
    private fun paintSpine(info: ShelfBookInfo, cover: Bitmap?, swatches: ShelfSwatches, ink: ShelfInk, k: Float): Bitmap {
        val w = (ShelfModel.BOOK_W * k).roundToInt().toFloat()
        val h = (ShelfModel.BOOK_H * k).roundToInt().toFloat()
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val em = ShelfModel.BOOK_H / 20f * k
        val obh = ShelfModel.obiHeight * k
        // The strip of the cover next to the spine fold, scaled to the spine above the obi.
        drawCoverCrop(c, cover, RectF(0f, 0f, w, h - obh), swatches.darkMuted, alignX = 1f)
        paintSpineLabel(c, info, ink, w, h, em, obh)
        c.drawRect(0f, h - obh, w, h, fill(ink.label))
        paperGrain(c, RectF(0f, h - obh, w, h))
        c.drawRect(0f, h - obh, w, h - obh + max(1f, .4f * k), fill(0x12000000))
        paintSpineMark(c, ink, w, h - obh, obh, em)
        return bmp
    }

    /** [cover] scaled to fill [dst], cropped around ([alignX], [focusY]); a flat [fallback] without one. */
    private fun drawCoverCrop(c: Canvas, cover: Bitmap?, dst: RectF, fallback: Int, focusY: Float = .3f, alignX: Float = .5f) {
        if (cover == null || cover.width <= 0 || cover.height <= 0) {
            c.drawRect(dst, fill(fallback))
            return
        }
        val scale = max(dst.width() / cover.width, dst.height() / cover.height)
        val sw = dst.width() / scale
        val sh = dst.height() / scale
        val sx = ((cover.width - sw) * alignX).coerceAtLeast(0f)
        val sy = ((cover.height - sh) * focusY).coerceAtLeast(0f)
        val src = Rect(sx.roundToInt(), sy.roundToInt(), (sx + sw).roundToInt().coerceAtMost(cover.width), (sy + sh).roundToInt().coerceAtMost(cover.height))
        c.drawBitmap(cover, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int) =
        Paint().apply { shader = LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP) }

    /** Fine paper grain on the paper parts only (obi), never on the printed art. */
    private fun paperGrain(c: Canvas, r: RectF) {
        c.drawRect(r, Paint().apply { alpha = 24; shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
    }

    private val grain: Bitmap by lazy {
        val n = 96
        val rnd = java.util.Random(7)
        val px = IntArray(n * n) {
            val v = rnd.nextInt(256)
            when {
                v > 214 -> ((v - 214) * 9 / 41 shl 24) or 0xFFFFFF
                v < 42 -> ((42 - v) * 10 / 42) shl 24
                else -> 0
            }
        }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }
}

// ---------------------------------------------------------------- 表面贴图

/**
 * Tileable surface maps for the book material, generated once on the CPU: an ARGB pixel array
 * whose rgb holds a tangent-space normal (0.5 + 0.5·n) and alpha a faint albedo variation.
 */
internal object ShelfSurfaces {
    const val SIZE = 256

    /**
     * The jacket laminate: mirror-smooth up close, but the film lies in long, shallow waves over the
     * board. Those waves gently bend the edge of the reflection, which makes a glossy jacket read as real.
     */
    fun laminate(size: Int = SIZE): IntArray {
        val swell = Lattice(size, 3, 31)
        val ripple = Lattice(size, 7, 32)
        val h = FloatArray(size * size) { swell.at((it % size).toFloat(), (it / size).toFloat()) * .7f + ripple.at((it % size).toFloat(), (it / size).toFloat()) * .3f }
        return encode(h, size, strength = 9f, albedo = 0f)
    }

    /** Uncoated paper (the obi): cloudy fibre mottling plus short scattered fibres. */
    fun paper(size: Int = SIZE): IntArray {
        val cloud = Lattice(size, 8, 21)
        val mid = Lattice(size, 32, 22)
        val fine = Lattice(size, 128, 23)
        val h = FloatArray(size * size) {
            val x = (it % size).toFloat()
            val y = (it / size).toFloat()
            cloud.at(x, y) * .35f + mid.at(x, y) * .35f + fine.at(x, y) * .3f
        }
        // Short fibres: thin raised strands at random angles, wrapped around the tile.
        val rnd = java.util.Random(24)
        repeat(size * 4) {
            val cx = rnd.nextFloat() * size
            val cy = rnd.nextFloat() * size
            val ang = rnd.nextFloat() * PI.toFloat()
            val len = 5f + rnd.nextFloat() * 18f
            val amp = .25f + rnd.nextFloat() * .45f
            val steps = (len * 2f).toInt()
            for (s in 0..steps) {
                val t = s / steps.toFloat() - .5f
                val px = cx + cos(ang) * len * t
                val py = cy + sin(ang) * len * t
                val fade = 1f - (2f * t) * (2f * t)
                for (oy in -1..1) for (ox in -1..1) {
                    val i = Math.floorMod((py + oy).toInt(), size) * size + Math.floorMod((px + ox).toInt(), size)
                    h[i] = maxOf(h[i], h[i] * .6f + amp * fade * exp(-(ox * ox + oy * oy) * 1.4f))
                }
            }
        }
        return encode(h, size, strength = 2.4f, albedo = .9f)
    }

    /** Height field → packed normal + albedo-variation map. */
    private fun encode(h: FloatArray, size: Int, strength: Float, albedo: Float): IntArray {
        val lo = h.min()
        val span = (h.max() - lo).coerceAtLeast(1e-4f)
        fun at(x: Int, y: Int) = (h[Math.floorMod(y, size) * size + Math.floorMod(x, size)] - lo) / span
        return IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val dx = (at(x + 1, y) - at(x - 1, y)) * strength
            val dy = (at(x, y + 1) - at(x, y - 1)) * strength
            val l = sqrt(dx * dx + dy * dy + 1f)
            val a = (.5f + (at(x, y) - .5f) * albedo).coerceIn(0f, 1f)
            fun ch(v: Float) = ((v * .5f + .5f) * 255f).toInt() and 0xFF
            ((a * 255f).toInt() shl 24) or (ch(-dx / l) shl 16) or (ch(-dy / l) shl 8) or ch(1f / l)
        }
    }

    /** Tileable smooth value noise on a [cells]-per-tile lattice; coordinates are in tile pixels. */
    private class Lattice(private val size: Int, private val cells: Int, seed: Long) {
        private val v = java.util.Random(seed).let { r -> FloatArray(cells * cells) { r.nextFloat() } }

        fun at(x: Float, y: Float): Float {
            val gx = x / size * cells
            val gy = y / size * cells
            val tx = fade(gx - floor(gx))
            val ty = fade(gy - floor(gy))
            val ix = Math.floorMod(floor(gx).toInt(), cells)
            val iy = Math.floorMod(floor(gy).toInt(), cells)
            val ix1 = (ix + 1) % cells
            val iy1 = (iy + 1) % cells
            val a = v[iy * cells + ix] + (v[iy * cells + ix1] - v[iy * cells + ix]) * tx
            val b = v[iy1 * cells + ix] + (v[iy1 * cells + ix1] - v[iy1 * cells + ix]) * tx
            return a + (b - a) * ty
        }

        private fun fade(t: Float) = t * t * (3f - 2f * t)
    }
}

// ---------------------------------------------------------------- 封面取色

/** Swatches picked from a cover, in the spirit of AndroidX Palette. Colors are ARGB ints. */
internal data class ShelfSwatches(val vibrant: Int, val darkVibrant: Int, val lightVibrant: Int, val darkMuted: Int)

internal object ShelfPalette {
    val DEFAULT = ShelfSwatches(0xFF6F7FA6.toInt(), 0xFF3D4A6B.toInt(), 0xFFB9C4DE.toInt(), 0xFF3A3D45.toInt())

    /** Lightness and saturation range and target of one swatch. */
    private class Target(val lMin: Float, val lTarget: Float, val lMax: Float, val sMin: Float, val sTarget: Float, val sMax: Float)

    private val VIBRANT = Target(.3f, .5f, .7f, .35f, 1f, 1f)
    private val DARK_VIBRANT = Target(0f, .26f, .45f, .35f, 1f, 1f)
    private val LIGHT_VIBRANT = Target(.55f, .74f, 1f, .35f, 1f, 1f)
    private val MUTED = Target(.3f, .5f, .7f, 0f, .3f, .4f)
    private val DARK_MUTED = Target(0f, .26f, .45f, 0f, .3f, .4f)
    private val LIGHT_MUTED = Target(.55f, .74f, 1f, 0f, .3f, .4f)

    private class Bucket(val hsl: FloatArray, val count: Int)

    /** [pixels] are ARGB ints of a downsampled cover (a few thousand pixels is plenty). */
    fun extract(pixels: IntArray): ShelfSwatches {
        // Average the colours in 4-bit-per-channel buckets.
        val sums = HashMap<Int, LongArray>()
        for (p in pixels) {
            if ((p ushr 24) < 0x80) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val acc = sums.getOrPut(((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)) { LongArray(4) }
            acc[0] += r.toLong(); acc[1] += g.toLong(); acc[2] += b.toLong(); acc[3]++
        }
        val buckets = sums.values.map { Bucket(rgbToHsl((it[0] / it[3]).toInt(), (it[1] / it[3]).toInt(), (it[2] / it[3]).toInt()), it[3].toInt()) }
            .filter { it.hsl[2] in .06f..0.95f }
        val maxCount = buckets.maxOfOrNull { it.count } ?: 1
        val used = HashSet<Bucket>()
        fun pick(t: Target): FloatArray? {
            val best = buckets.filter { it !in used && it.hsl[1] in t.sMin..t.sMax && it.hsl[2] in t.lMin..t.lMax }
                .maxByOrNull { (1f - abs(it.hsl[1] - t.sTarget)) * 3f + (1f - abs(it.hsl[2] - t.lTarget)) * 6.5f + it.count.toFloat() / maxCount * 1.2f }
            return best?.let { used += it; it.hsl.copyOf() }
        }
        val v = pick(VIBRANT)
        val dv = pick(DARK_VIBRANT)
        val lv = pick(LIGHT_VIBRANT)
        val m = pick(MUTED)
        val dm = pick(DARK_MUTED)
        val base = v ?: m ?: dv ?: dm ?: lv ?: pick(LIGHT_MUTED) ?: return DEFAULT
        fun fallback(t: Target) = floatArrayOf(base[0], if (t.sTarget >= 1f) max(base[1], .45f) else min(base[1], .3f), t.lTarget)
        // Keep the accents legible on labels and the dark swatch soft.
        val darkVibrant = (dv ?: fallback(DARK_VIBRANT)).let { floatArrayOf(it[0], min(it[1], .75f), it[2].coerceIn(.28f, .4f)) }
        val lightVibrant = (lv ?: fallback(LIGHT_VIBRANT)).let { floatArrayOf(it[0], min(it[1], .8f), it[2].coerceIn(.68f, .8f)) }
        val darkMuted = (dm ?: fallback(DARK_MUTED)).let { floatArrayOf(it[0], min(it[1], .42f), it[2].coerceIn(.2f, .32f)) }
        return ShelfSwatches(hslToArgb(v ?: fallback(VIBRANT)), hslToArgb(darkVibrant), hslToArgb(lightVibrant), hslToArgb(darkMuted))
    }

    private fun rgbToHsl(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > .5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
            gf -> (bf - rf) / d + 2f
            else -> (rf - gf) / d + 4f
        }
        return floatArrayOf(h / 6f, s, l)
    }

    private fun hslToArgb(hsl: FloatArray): Int {
        val (h, s, l) = hsl
        fun hue(p: Float, q: Float, tIn: Float): Float {
            val t = if (tIn < 0f) tIn + 1f else if (tIn > 1f) tIn - 1f else tIn
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < .5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val q = if (l < .5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun ch(x: Float) = ((if (s == 0f) l else x) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(hue(p, q, h + 1f / 3f)) shl 16) or (ch(hue(p, q, h)) shl 8) or ch(hue(p, q, h - 1f / 3f))
    }
}

// ---------------------------------------------------------------- 贴图缓存

/**
 * Finished textures in memory, keyed by everything that goes into them. Building one (a palette
 * pass and a paint) runs off the UI thread, so each book is built once and scrolling back shows
 * finished books.
 */
internal object ShelfTextureCache {
    /** About 1/8 of the heap, at most 64 MiB: ~40 books at the usual texture size. */
    private val memory =
        object : LruCache<String, ShelfTextures>((Runtime.getRuntime().maxMemory() / 1024 / 8).coerceAtMost(64L * 1024).toInt()) {
            override fun sizeOf(key: String, value: ShelfTextures) = (value.cover.byteCount + value.spine.byteCount) / 1024
        }

    /** Two books are built at a time, so the nearest ones are never stuck behind the rest. */
    private val building = Semaphore(2)

    fun key(book: ShelfBook, info: ShelfBookInfo, dark: Boolean, pxPerUnit: Float): String =
        "${book.cover?.let { System.identityHashCode(it) }}|$info|$dark|${(pxPerUnit.coerceIn(1f, 3.5f) * 100).roundToInt()}"

    /** The finished textures if they are in memory; never blocks. */
    fun peek(key: String?): ShelfTextures? = key?.let { memory.get(it) }

    suspend fun obtain(key: String, book: ShelfBook, info: ShelfBookInfo, dark: Boolean, pxPerUnit: Float): ShelfTextures {
        memory.get(key)?.let { return it }
        val built = building.withPermit {
            withContext(Dispatchers.Default) {
                val cover = book.cover?.let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                val swatches = cover?.let {
                    val small = Bitmap.createScaledBitmap(it, 48, 68, true)
                    val px = IntArray(small.width * small.height)
                    small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
                    if (small !== it) small.recycle()
                    ShelfPalette.extract(px)
                } ?: ShelfPalette.DEFAULT
                ShelfTexturePainter.paint(info, cover, swatches, dark, pxPerUnit)
            }
        }
        memory.put(key, built)
        return built
    }
}
