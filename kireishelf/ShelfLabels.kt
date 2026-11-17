package kireishelf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// 书上印的东西都在这个文件：文字、腰封的排版、书脊上的标签和标记。
// 想换字、换颜色、换排版，只改这里。尺寸都以 em 为单位（1 em = 书高的 1/20）。

/** 书上印的固定文字。 */
internal object ShelfWords {
    /** 书脊腰封上竖排的小标记；设成 "" 就不印。 */
    const val MARK = "KIREI"
    const val UNTITLED = "未命名"
    const val HOURS = "小时"
    const val MINUTES = "分钟"
    const val LAST_PLAYED = "上次游玩"
    const val LONGEST = "书架最长"
    const val NEVER = "未游玩"
}

/** 标签的颜色：浅色模式是米白纸，深色模式是石墨灰；强调色取自封面。 */
internal class ShelfInk(dark: Boolean, swatches: ShelfSwatches) {
    /** 腰封和书脊下端的纸色。 */
    val label = if (dark) 0xFF2E2F35.toInt() else 0xFFF8F6F1.toInt()
    val ink = if (dark) 0xFFECEAE5.toInt() else 0xFF1A1A1D.toInt()
    val sub = if (dark) 0xFF9D9CA3.toInt() else 0xFF86837C.toInt()
    val accent = if (dark) swatches.lightVibrant else swatches.darkVibrant

    /** 书脊中间标题标签的底色：纸色带一点封面的色调。 */
    val spinePanel = if (dark) mix(0xFF2A2B31.toInt(), swatches.darkMuted, .3f) else mix(0xFFFAF9F6.toInt(), swatches.lightVibrant, .14f)
}

/** 一本书要印的内容，由 [ShelfBook] 和整个书架算出来。 */
internal data class ShelfBookInfo(
    val title: String,
    /** 腰封上的大数字和单位，例如 12 + 小时。 */
    val playNumber: String,
    val playUnit: String,
    val lastPlayed: String,
    /** 在书架上的位置，从 1 开始。 */
    val index: Int,
    /** 按时长排第几，从 1 开始。 */
    val rank: Int,
    val total: Int,
    /** 占书架最长时长的比例，0..1。 */
    val share: Float,
    val longestLabel: String,
)

internal fun bookInfos(books: List<ShelfBook>, nowMs: Long): List<ShelfBookInfo> {
    val longest = books.maxOfOrNull { it.playTime } ?: 0L
    // Rank by play time; equal times share the better rank.
    val rankOf = HashMap<Long, Int>()
    books.map { it.playTime }.sortedDescending().forEachIndexed { i, t -> rankOf.putIfAbsent(t, i + 1) }
    val longestLabel = duration(longest).let { "${it.first} ${it.second}" }
    return books.mapIndexed { i, b ->
        val (number, unit) = duration(b.playTime)
        ShelfBookInfo(
            title = b.title.ifBlank { ShelfWords.UNTITLED },
            playNumber = number,
            playUnit = unit,
            lastPlayed = lastPlayedLabel(b.lastPlayedAt, nowMs),
            index = i + 1,
            rank = rankOf.getValue(b.playTime),
            total = books.size,
            share = if (longest > 0L) b.playTime.toFloat() / longest else 0f,
            longestLabel = longestLabel,
        )
    }
}

/** 时长 → 数字 + 单位：12 小时，45 分钟。 */
private fun duration(ms: Long): Pair<String, String> = when {
    ms >= 3_600_000L -> (ms / 3_600_000L).toString() to ShelfWords.HOURS
    ms >= 60_000L -> (ms / 60_000L).toString() to ShelfWords.MINUTES
    else -> "0" to ShelfWords.HOURS
}

/** 今天 / 昨天 / 3 天前 / 2 周前 / 5月3日 / 2024年5月。 */
internal fun lastPlayedLabel(lastPlayedAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (lastPlayedAtMs <= 0L) return ShelfWords.NEVER
    val played = Instant.ofEpochMilli(lastPlayedAtMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(played, today)
    return when {
        days <= 0L -> "今天"
        days == 1L -> "昨天"
        days < 7L -> "$days 天前"
        days < 30L -> "${days / 7L} 周前"
        played.year == today.year -> "${played.monthValue}月${played.dayOfMonth}日"
        else -> "${played.year}年${played.monthValue}月"
    }
}

// ---------------------------------------------------------------- 腰封

/** 腰封上的字：大数字 + 单位、排名、时长比例条、上次游玩、书架最长。画在 [w]×[h] 的腰封区域里。 */
internal fun paintObi(c: Canvas, info: ShelfBookInfo, ink: ShelfInk, w: Float, em: Float) {
    val padX = .8f * em
    val big = text(ink.ink, 2.2f * em, serif, -.02f)
    var y = .65f * em + big.textSize * .82f
    c.drawText(info.playNumber, padX, y, big)
    c.drawText(info.playUnit, padX + big.measureText(info.playNumber) + .3f * em, y, text(ink.ink, .58f * em, serifBold))
    c.drawText("No.${info.rank} / ${info.total}", w - padX, y, text(ink.sub, .34f * em, mono, .2f, Paint.Align.RIGHT))
    // 时长比例条
    y += .55f * em
    val barH = .18f * em
    c.drawRoundRect(RectF(padX, y, w - padX, y + barH), barH / 2, barH / 2, fill(withAlpha(ink.ink, .12f)))
    val fillW = (w - 2 * padX) * info.share.coerceIn(0f, 1f)
    if (fillW > 0f) c.drawRoundRect(RectF(padX, y, padX + fillW, y + barH), barH / 2, barH / 2, fill(ink.accent))
    y += barH + .86f * em
    c.drawText("${ShelfWords.LAST_PLAYED} ${info.lastPlayed}", padX, y, text(ink.sub, .46f * em, sansLight, .06f))
    c.drawText("${ShelfWords.LONGEST} ${info.longestLabel}", w - padX, y, text(ink.sub, .46f * em, sansLight, .06f, Paint.Align.RIGHT))
}

// ---------------------------------------------------------------- 书脊

/**
 * 书脊中间的标题标签：带一点封面色调的圆角色块，里面是强调色小方块、竖排书名、短横线和序号。
 * 书脊宽 [w]、高 [h]，[obh] 是下面腰封的高度。
 */
internal fun paintSpineLabel(c: Canvas, info: ShelfBookInfo, ink: ShelfInk, w: Float, h: Float, em: Float, obh: Float) {
    val panel = RectF(.32f * em, .85f * em, w - .32f * em, h - obh - .55f * em)
    c.drawRoundRect(panel, .12f * em, .12f * em, fill(ink.spinePanel))
    val onPanel = if (luma(ink.spinePanel) > .55f) 0xFF1E1F23.toInt() else 0xFFEDEBE6.toInt()
    val cx = panel.centerX()
    // 顶部的强调色小方块
    val sq = .42f * em
    c.drawRect(cx - sq / 2, panel.top + .6f * em, cx + sq / 2, panel.top + .6f * em + sq, fill(ink.accent))
    // 底部的序号和它上面的短横线
    val number = text(onPanel, 1.05f * em, serif, -.02f, Paint.Align.CENTER)
    val numberY = panel.bottom - .55f * em
    c.drawText("%02d".format(info.index), cx, numberY, number)
    val ruleY = numberY - number.textSize * .8f - .4f * em
    c.drawRect(cx - .4f * em, ruleY, cx + .4f * em, ruleY + max(1f, .05f * em), fill(withAlpha(onPanel, .5f)))
    // 中间竖排书名，放不下就以 ︙ 结尾
    val title = text(onPanel, .8f * em, serifBold, 0f, Paint.Align.CENTER)
    val top = panel.top + .6f * em + sq + .5f * em
    val step = title.textSize * 1.14f
    drawVertical(c, fitVertical(info.title, floor((ruleY - .45f * em - top) / step).toInt()), cx, top, step, title)
}

/** 书脊腰封上竖排的 [ShelfWords.MARK]。腰封从 [obiTop] 开始，高 [obh]。 */
internal fun paintSpineMark(c: Canvas, ink: ShelfInk, w: Float, obiTop: Float, obh: Float, em: Float) {
    val chars = ShelfWords.MARK.map { it.toString() }
    if (chars.isEmpty()) return
    val p = text(ink.sub, .32f * em, mono, 0f, Paint.Align.CENTER)
    val step = p.textSize * 1.3f
    drawVertical(c, chars, w / 2f, obiTop + (obh - chars.size * step) / 2f, step, p)
}

/** [title] 能放进 [slots] 格的字；放不下就保留 slots - 1 个字再加 ︙。 */
internal fun fitVertical(title: String, slots: Int): List<String> {
    val chars = title.trim().codePoints().toArray().map { String(Character.toChars(it)) }.filter { it.isNotBlank() }
    if (slots <= 0) return emptyList()
    return if (chars.size <= slots) chars else chars.take(slots - 1) + "︙"
}

// ---------------------------------------------------------------- 小工具

private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
private val mono = Typeface.MONOSPACE
private val sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

private fun text(color: Int, size: Float, face: Typeface, spacing: Float = 0f, align: Paint.Align = Paint.Align.LEFT) =
    Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.color = color
        textSize = size
        typeface = face
        letterSpacing = spacing
        textAlign = align
    }

internal fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

private fun withAlpha(color: Int, alpha: Float) = ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

private fun luma(c: Int) = (.2126f * Color.red(c) + .7152f * Color.green(c) + .0722f * Color.blue(c)) / 255f

private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(sh: Int) = (((a shr sh) and 0xFF) * (1f - t) + ((b shr sh) and 0xFF) * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** 从 [top] 开始往下一格一个字，居中在 [cx]。 */
private fun drawVertical(c: Canvas, chars: List<String>, cx: Float, top: Float, step: Float, p: Paint) {
    var baseline = top + p.textSize * .88f
    for (ch in chars) {
        c.drawText(ch, cx, baseline, p)
        baseline += step
    }
}
