package kireishelf

import android.graphics.Bitmap
import android.graphics.Paint
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// KireiShelf：把一组封面排成斜着看的文库本书架。这个文件是对外接口（ShelfBook、KireiShelf），
// 以及滑动手感、拖动条和每本书贴图的加载。

/** One book on the shelf. */
data class ShelfBook(
    val id: Long,
    val title: String,
    /** The cover art, portrait. Null gives a plain coloured jacket. */
    val cover: Bitmap? = null,
    /** Play time in milliseconds: the big number on the obi. */
    val playTime: Long = 0L,
    /** When it was last played, epoch milliseconds; 0 = never. */
    val lastPlayedAt: Long = 0L,
)

/**
 * The shelf: [books] as a row of bunko books seen at an angle. Swipe to move along it; the
 * selected book slides out of the row, turns to show its jacket and settles in the middle.
 * Tapping the selected book calls [onOpen]; tapping another one brings it out.
 *
 * Rendering is one Canvas: each frame projects the visible books and maps pre-painted jacket and
 * spine bitmaps onto their faces (lit per pixel on Android 13+), so a frame is a handful of draws.
 */
@Composable
fun KireiShelf(
    books: List<ShelfBook>,
    onOpen: (ShelfBook) -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = isSystemInDarkTheme(),
    emptyText: String = "书架上还没有书",
) {
    val textColor = if (dark) Color(0xFFF1F1F3) else Color(0xFF1B1A18)
    val context = LocalContext.current
    // Follows the system's "remove animations" setting.
    val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    if (books.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val studio = remember(dark) { ShelfLightStudio(dark) }
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { studio.draw(it.nativeCanvas, 0, size.width / 2f, size.height / 2f) }
            }
            BasicText(emptyText, style = TextStyle(color = textColor.copy(alpha = .7f), fontSize = 15.sp))
        }
        return
    }
    var saved by rememberSaveable { mutableIntStateOf(0) }
    val scroll = remember { ShelfScroll(saved.coerceIn(0, books.lastIndex).toFloat()) }
    LaunchedEffect(books.size) {
        if (scroll.target > books.lastIndex) scroll.target = books.lastIndex
    }
    val selected by remember(books) { derivedStateOf { scroll.pos.roundToInt().coerceIn(0, books.lastIndex) } }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(selected) {
        saved = selected
        // One light tick per book passed while the shelf is being moved.
        if (scroll.dragging || scroll.scrubbing || abs(scroll.vel) > .6f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    val infos = remember(books) { bookInfos(books, System.currentTimeMillis()) }
    val textures = remember(dark) { mutableStateMapOf<Long, ShelfTextures>() }
    var timeSec by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        var lastTick = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000f
                last = now
                val moving = scroll.step(dt, reducedMotion) || scroll.dragging
                // At rest only the selected book's slow sway moves: on a 90/120 Hz screen it is
                // redrawn at about 60 fps instead of every refresh, which looks the same and
                // halves the idle cost. Any movement of the shelf runs at the full rate.
                if (!reducedMotion && (moving || now - lastTick >= 14_000_000L)) {
                    lastTick = now
                    timeSec = (now / 1_000_000_000.0 % 10_000.0).toFloat()
                }
            }
        }
    }
    val ink = if (dark) ShelfSurfaceInk.DARK else ShelfSurfaceInk.LIGHT
    val contactShadow = remember(dark) { shadowBitmap(ink.shadowColor, soft = false) }
    val softShadow = remember(dark) { shadowBitmap(ink.shadowColor, soft = true) }
    val pages = remember { pagesBitmap() }
    // Drawing objects reused every frame, so a frame allocates next to nothing.
    val frameBuf = remember { ShelfFrameBuffers() }
    val swatches = textures[books[selected].id]?.swatches ?: ShelfPalette.DEFAULT
    // A breath of the selected book's colour in the studio light behind it.
    val studioTint by animateColorAsState(
        if (dark) Color(swatches.vibrant).copy(alpha = .16f) else Color(swatches.lightVibrant).copy(alpha = .2f),
        tween(if (reducedMotion) 0 else 900),
        label = "shelf-studio-tint",
    )
    val studio = remember(dark) { ShelfLightStudio(dark) }
    val caustic = remember(dark) { ShelfLightStudio.causticStrip(dark) }
    // Per-pixel lit books on Android 13+; older devices draw the textures with flat shading.
    val material = remember(dark) { shelfBookMaterialOrNull(dark) }
    val insets = WindowInsets.safeDrawing.asPaddingValues()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val frame = ShelfFrame.forView(widthPx, heightPx)
        val layoutDirection = LocalLayoutDirection.current
        val unit = ShelfCamera(widthPx, heightPx, 0f, frame).unit
        // Textures are painted at the size the pulled-out book reaches (it comes ~10% closer).
        val pxPerUnit = (unit * 1.12f).coerceIn(1f, 2.8f)
        val textureKeys = remember(books, infos, dark, pxPerUnit) { books.mapIndexed { i, b -> ShelfTextureCache.key(b, infos[i], dark, pxPerUnit) } }
        // When a texture first arrived, for a short fade-in instead of a pop.
        val readyAt = remember(dark) { HashMap<Long, Long>() }
        // Loaders reach a few books beyond what is drawn, so a fling lands on finished books.
        val center = scroll.target
        val from = (center - TEXTURE_RADIUS - PREFETCH_EXTRA).coerceAtLeast(0)
        val to = (center + TEXTURE_RADIUS + PREFETCH_EXTRA).coerceAtMost(books.lastIndex)
        for (i in from..to) {
            val book = books[i]
            key(book.id) {
                ShelfTextureLoader(book, infos[i], textureKeys[i], dark, pxPerUnit, abs(i - center)) { tex, fresh ->
                    if (fresh) readyAt[book.id] = System.nanoTime()
                    textures[book.id] = tex
                }
                DisposableEffect(book.id, textures) { onDispose { textures.remove(book.id) } }
            }
        }
        val lastFrame = remember { ArrayList<Pair<Int, ShelfBookQuads>>() }
        val sens = ShelfFeel.pxPerBook(widthPx, unit)
        val currentBooks by rememberUpdatedState(books)
        val open by rememberUpdatedState(onOpen)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val hit =
                            lastFrame.asReversed().firstOrNull { (_, q) ->
                                q.cover?.contains(offset.x, offset.y) == true ||
                                    q.spine?.contains(offset.x, offset.y) == true ||
                                    q.top?.contains(offset.x, offset.y) == true
                            }?.first ?: return@detectTapGestures
                        val list = currentBooks
                        if (hit == scroll.target && abs(scroll.pos - hit) < .05f) {
                            list.getOrNull(hit)?.let(open)
                        } else {
                            scroll.target = hit.coerceIn(0, list.lastIndex)
                        }
                    }
                }.pointerInput(sens) {
                    val tracker = VelocityTracker()
                    var startPos = 0f
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            scroll.dragging = true
                            scroll.vel = 0f
                            startPos = scroll.pos
                            dragged = 0f
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x
                            val fling = ShelfFeel.flingBooks(v, sens)
                            scroll.target = (scroll.pos + fling).roundToInt().coerceIn(0, currentBooks.lastIndex)
                            // Past the ends the rubber band springs back from rest.
                            val inside = scroll.pos >= 0f && scroll.pos <= currentBooks.lastIndex
                            scroll.vel = if (inside) ShelfFeel.releaseVelocity(v, sens) else 0f
                            scroll.dragging = false
                        },
                        onDragCancel = {
                            scroll.target = scroll.pos.roundToInt().coerceIn(0, currentBooks.lastIndex)
                            scroll.dragging = false
                        },
                    ) { change, amount ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragged += amount
                        val last = currentBooks.lastIndex.toFloat()
                        var p = startPos - dragged / sens
                        if (p < 0f) p *= .3f
                        if (p > last) p = last + (p - last) * .3f
                        scroll.pos = p
                    }
                },
        ) {
            val pos = scroll.pos
            val t = timeSec
            val cam = ShelfCamera(size.width, size.height, pos, frame)
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                val sel = pos.roundToInt().coerceIn(0, books.lastIndex)
                val selPose = ShelfMotion.pose(sel, pos, t, reducedMotion, scroll.focus)
                // 1. The studio: a seamless sweep and one soft light, the selected book's colour behind it.
                val glowAt = cam.point(selPose.x, selPose.y, selPose.z)
                studio.draw(nc, studioTint.toArgb(), glowAt[0], glowAt[1])
                val c0 = pos.roundToInt()
                val drawn = frameBuf.drawn
                val poses = frameBuf.poses
                drawn.clear()
                poses.clear()
                for (i in (c0 - ShelfModel.DRAW_RADIUS).coerceAtLeast(0)..(c0 + ShelfModel.DRAW_RADIUS).coerceAtMost(books.lastIndex)) {
                    val pose = ShelfMotion.pose(i, pos, t, reducedMotion, scroll.focus)
                    val quads = cam.book(pose)
                    // Books wholly off screen are neither drawn nor shadowed nor mirrored.
                    if (!quads.onScreen(size.width, size.height)) continue
                    poses[i] = pose
                    drawn += i to quads
                }
                // Exact back-to-front order, so a book sliding out of the row is never painted
                // over the neighbour that still hides part of it.
                frameBuf.order.sort(drawn, poses, cam.eyeTrack)
                val track = pos * ShelfModel.GAP
                // 2. The floor under the floating glass shelf: the plate's own faint shadow, the
                //    books' shadows through it, and the bright line its front edge bends there.
                val floorY = ShelfLightStudio.FLOOR_Y
                val front = ShelfModel.SHELF_DEPTH / 2f + BOARD_EXTRA_FRONT
                val floorPaint = frameBuf.floorPaint
                floorPaint.alpha = if (dark) 90 else 26
                mapBitmap(nc, softShadow, cam.floorStrip(track - 1100f, track + 1100f, floorY, -ShelfModel.SHELF_DEPTH / 2f - 30f, front + 40f), floorPaint)
                for ((i, _) in drawn) {
                    val p = poses.getValue(i)
                    floorPaint.alpha = ((if (dark) 70 else 30) * (1f - p.turn * .5f)).roundToInt()
                    mapBitmap(nc, softShadow, cam.footprint(p, floorY, ShelfModel.BOOK_W / 2f + 22f, ShelfModel.BOOK_D / 2f + 18f, 10f, 6f), floorPaint)
                }
                floorPaint.alpha = 255
                // The glint sits under the selected book and travels with it.
                mapBitmap(nc, caustic, cam.floorStrip(selPose.x - 120f, selPose.x + 120f, floorY, front - 6f, front + 40f), floorPaint)
                // 3. The glass shelf, with the books mirrored in it.
                val board = cam.shelfBoard(track - 900f, track + 900f, extraFront = BOARD_EXTRA_FRONT)
                val reflectPaint = frameBuf.reflectPaint.apply { alpha = if (dark) 96 else 88 }
                val feetY = cam.point(track, 0f, ShelfModel.BOOK_D / 2f)[1]
                drawGlassBoard(nc, board.first, board.second, dark, feetY, size.height * .085f) { rc ->
                    for ((i, _) in drawn) {
                        val tex = textures[books[i].id] ?: ShelfTextureCache.peek(textureKeys.getOrNull(i)) ?: continue
                        val m = cam.book(poses.getValue(i), mirror = true)
                        m.spine?.let { mapBitmap(rc, tex.spine, it, reflectPaint) }
                        m.cover?.let { mapBitmap(rc, tex.cover, it, reflectPaint) }
                    }
                }
                // 4. Shadows: a wide penumbra away from the light, then tight contact occlusion that
                //    fades as the selected book lifts off the board.
                val shadowPaint = frameBuf.shadowPaint
                for ((i, q) in drawn) {
                    val p = poses.getValue(i)
                    // The lifted book keeps a soft shadow under it, so it reads as floating
                    // just above the glass rather than pasted on the backdrop.
                    shadowPaint.alpha = (ink.softAlpha * (.75f + .5f * p.turn) * 255).roundToInt().coerceIn(0, 255)
                    mapBitmap(nc, softShadow, q.soft, shadowPaint)
                }
                for ((i, q) in drawn) {
                    val p = poses.getValue(i)
                    val a = ink.contactAlpha * (1f - p.turn * .85f)
                    if (a < .01f) continue
                    shadowPaint.alpha = (a * 255).roundToInt().coerceIn(0, 255)
                    mapBitmap(nc, contactShadow, q.contact, shadowPaint)
                }
                // 5. Books, far to near.
                val dimK = if (dark) .24f else .03f
                val soft = if (dark) 1f else .45f
                val bitmapPaint = frameBuf.bitmapPaint
                material?.begin(cam)
                val obi = OBI_UV
                for ((i, q) in drawn) {
                    val p = poses.getValue(i)
                    val tex = textures[books[i].id] ?: ShelfTextureCache.peek(textureKeys.getOrNull(i))
                    val fade = readyAt[books[i].id]?.let { ((System.nanoTime() - it) / 160_000_000f).coerceIn(0f, 1f) } ?: 1f
                    val texAlpha = (fade * 255).roundToInt()
                    val dim = dimK * (1f - p.near)
                    val ao = 1f - p.turn
                    q.back?.let {
                        fillQuad(nc, it, ink.back)
                        lightFace(nc, it, dim, ao, soft)
                    }
                    q.top?.let {
                        if (material?.face(nc, pages, it, ShelfBookMaterial.ALL, ShelfModel.BOOK_W / pages.width, dim, 0f, 255) != true) {
                            bitmapPaint.alpha = 255
                            mapBitmap(nc, pages, it, bitmapPaint)
                            lightFace(nc, it, dim, 0f, soft)
                        }
                    }
                    bitmapPaint.alpha = texAlpha
                    // A blank volume until the texture arrives, then the texture fades in over it.
                    q.spine?.let {
                        if (tex == null || texAlpha < 255) fillQuad(nc, it, ink.placeholder)
                        // The spine is printed jacket (laminate) down to the obi: no paper label.
                        if (tex == null || material?.face(nc, tex.spine, it, obi, ShelfModel.BOOK_H / tex.spine.height, dim * 1.3f, ao, texAlpha) != true) {
                            if (tex != null) mapBitmap(nc, tex.spine, it, bitmapPaint)
                            lightFace(nc, it, dim * 1.3f, ao, soft)
                        }
                    }
                    q.cover?.let {
                        if (tex == null || texAlpha < 255) fillQuad(nc, it, ink.placeholder)
                        if (tex == null || material?.face(nc, tex.cover, it, obi, ShelfModel.BOOK_H / tex.cover.height, dim, ao, texAlpha) != true) {
                            if (tex != null) mapBitmap(nc, tex.cover, it, bitmapPaint)
                            lightFace(nc, it, dim, ao, soft)
                            sheen(nc, it, 1f - ShelfModel.OBI_FRACTION, dark)
                        }
                    }
                }
                lastFrame.clear()
                lastFrame.addAll(drawn)
            }
        }
        AnimatedContent(
            targetState = infos[selected].title,
            transitionSpec = {
                (fadeIn(tween(if (reducedMotion) 0 else 420)) + slideInVertically(tween(if (reducedMotion) 0 else 420)) { it / 5 }) togetherWith fadeOut(tween(if (reducedMotion) 0 else 160))
            },
            label = "shelf-title",
            modifier =
                Modifier
                    .fillMaxWidth(if (frame.landscape) .42f else 1f)
                    .padding(
                        start = 24.dp + insets.calculateLeftPadding(layoutDirection),
                        end = 24.dp + insets.calculateRightPadding(layoutDirection),
                        top = insets.calculateTopPadding() + 22.dp,
                    ),
        ) { title ->
            BasicText(
                title,
                style = TextStyle(color = textColor, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 31.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (books.size > 1) {
            Column(
                Modifier
                    .align(if (frame.landscape) Alignment.BottomStart else Alignment.BottomCenter)
                    .fillMaxWidth(if (frame.landscape) .42f else 1f)
                    .padding(
                        start = 24.dp + insets.calculateLeftPadding(layoutDirection),
                        end = 24.dp + if (frame.landscape) 0.dp else insets.calculateRightPadding(layoutDirection),
                        // Just under the glass shelf, not down at the screen edge.
                        bottom = insets.calculateBottomPadding() + maxHeight * (if (frame.landscape) .08f else .13f),
                    ),
                horizontalAlignment = if (frame.landscape) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                ShelfScrubber(
                    count = books.size,
                    scroll = scroll,
                    titleOf = { infos[it].title },
                    textColor = textColor,
                    dark = dark,
                    modifier = Modifier.widthIn(max = 248.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Scroll state of the shelf: a near-critically damped spring toward [target], integrated in real
 * time (so it feels the same at 60, 90 or 120 Hz). [vel] is in books per second.
 *
 * [focus] says how much the books near [pos] are pulled out of the row: it dips while the shelf
 * moves quickly, so a fast flick glides along the spines, and recovers gently as it settles.
 */
internal class ShelfScroll(start: Float) {
    var pos by mutableFloatStateOf(start)
    var target by mutableIntStateOf(start.roundToInt())
    var vel = 0f
    var dragging = false
    var scrubbing by mutableStateOf(false)
    var focus = 1f
        private set
    private var speed = 0f
    private var lastPos = start

    /** Advances by [dtSec]; returns true while still moving. */
    fun step(dtSec: Float, reduced: Boolean): Boolean {
        val dt = dtSec.coerceIn(0f, .05f)
        val moving = if (dragging) true else spring(dt, reduced)
        trackFocus(dt, reduced)
        return moving
    }

    private fun spring(dt: Float, reduced: Boolean): Boolean {
        val t = target.toFloat()
        if (reduced) {
            pos = t
            vel = 0f
            return false
        }
        // Far jumps (from the scrubber) travel faster so they never crawl past dozens of books.
        val far = abs(t - pos) > 3f
        val omega = if (far) ShelfFeel.OMEGA_FAR else ShelfFeel.OMEGA
        val zeta = ShelfFeel.DAMPING
        var p = pos
        var v = vel
        val n = (dt / (1f / 240f)).toInt().coerceAtLeast(1)
        val h = dt / n
        repeat(n) {
            v += (omega * omega * (t - p) - 2f * zeta * omega * v) * h
            p += v * h
        }
        if (abs(t - p) < .0008f && abs(v) < .02f) {
            pos = t
            vel = 0f
            return false
        }
        pos = p
        vel = v
        return true
    }

    private fun trackFocus(dt: Float, reduced: Boolean) {
        if (reduced || dt <= 0f) {
            focus = 1f
            lastPos = pos
            return
        }
        val instant = abs(pos - lastPos) / dt
        lastPos = pos
        speed += (instant - speed) * (1f - kotlin.math.exp(-dt / .06f))
        val goal = ShelfFeel.focusFor(speed)
        // Quick to tuck the books in when the shelf speeds up, unhurried when it lets one out.
        val tau = if (goal < focus) .07f else .2f
        focus += (goal - focus) * (1f - kotlin.math.exp(-dt / tau))
    }
}

/** Drag tuning: one book per [pxPerBook], flings travel further the faster they are. */
internal object ShelfFeel {
    /** Spring stiffness (rad/s) for ordinary moves and for long scrubber jumps. */
    const val OMEGA = 13f
    const val OMEGA_FAR = 19f

    /** Just under critical: lands cleanly, without the overshoot that pulled the next book out. */
    const val DAMPING = .94f

    /** Focus (how far books are pulled out) at a scroll speed in books per second. */
    fun focusFor(speedBooksPerSec: Float): Float = 1f - .72f * ShelfMotion.smooth((speedBooksPerSec - 1.6f) / 6f)

    fun pxPerBook(viewWidthPx: Float, unit: Float): Float = max(ShelfModel.GAP * unit * 1.5f, viewWidthPx * .2f)

    /** Books a release at [velocityPxPerSec] carries on for (negative = towards later books). */
    fun flingBooks(velocityPxPerSec: Float, pxPerBook: Float): Float = (-velocityPxPerSec / pxPerBook * .2f).coerceIn(-8f, 8f)

    /** Release speed in books per second, handed to the spring so the shelf carries on without a stall. */
    fun releaseVelocity(velocityPxPerSec: Float, pxPerBook: Float): Float = (-velocityPxPerSec / pxPerBook).coerceIn(-40f, 40f)

    /** Scrubber x → book index. */
    fun scrubIndex(x: Float, width: Float, count: Int): Int {
        if (count <= 1 || width <= 0f) return 0
        return ((x / width).coerceIn(0f, 1f) * (count - 1)).roundToInt()
    }
}

/**
 * A ruler of ticks, one per book: drag or tap anywhere on it to jump straight to that position,
 * with a floating label naming the book under the finger.
 */
@Composable
private fun ShelfScrubber(count: Int, scroll: ShelfScroll, titleOf: (Int) -> String, textColor: Color, dark: Boolean, modifier: Modifier) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val sidePad = with(density) { 10.dp.toPx() }
    Box(modifier.height(26.dp).onSizeChanged { widthPx = it.width.toFloat() }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun at(x: Float) {
                            fingerX = x.coerceIn(sidePad, widthPx - sidePad)
                            scroll.target = ShelfFeel.scrubIndex(x - sidePad, widthPx - 2 * sidePad, count)
                        }
                        scroll.scrubbing = true
                        at(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            at(c.position.x)
                            c.consume()
                        }
                        scroll.scrubbing = false
                    }
                },
        ) {
            val w = size.width - 2 * sidePad
            val midY = size.height / 2f
            val step = if (count > 1) w / (count - 1) else 0f
            val minGap = 4.dp.toPx()
            val every = if (step <= 0f) 1 else max(1, (minGap / step).toInt() + 1)
            val tick = textColor.copy(alpha = if (dark) .2f else .16f)
            val major = textColor.copy(alpha = if (dark) .34f else .3f)
            var i = 0
            while (i < count) {
                val x = sidePad + i * step
                val tall = i % (every * 5) == 0
                val h = if (tall) 6.dp.toPx() else 3.5.dp.toPx()
                drawRect(if (tall) major else tick, Offset(x - .5.dp.toPx(), midY - h / 2f), Size(1.dp.toPx(), h))
                i += every
            }
            // Marker at the shelf's live position.
            val mx = sidePad + scroll.pos.coerceIn(0f, (count - 1).toFloat()) * step
            val mh = (if (scroll.scrubbing) 16.dp else 11.dp).toPx()
            val mw = 2.dp.toPx()
            drawRoundRect(textColor.copy(alpha = .85f), Offset(mx - mw / 2f, midY - mh / 2f), Size(mw, mh), CornerRadius(mw / 2f))
        }
        AnimatedVisibility(
            visible = scroll.scrubbing,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(220)),
            modifier =
                Modifier
                    .wrapContentSize(align = Alignment.BottomStart, unbounded = true)
                    .offset {
                        IntOffset((fingerX - 80.dp.toPx()).roundToInt().coerceIn(0, (widthPx - 160.dp.toPx()).roundToInt().coerceAtLeast(0)), -36.dp.roundToPx())
                    },
        ) {
            val idx = scroll.target
            Box(
                Modifier
                    .widthIn(max = 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (dark) Color(0xE6202228) else Color(0xEBFFFFFF))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Column {
                    BasicText("${idx + 1} / $count", style = TextStyle(color = textColor.copy(alpha = .6f), fontSize = 10.sp, letterSpacing = .6.sp))
                    BasicText(
                        titleOf(idx),
                        style = TextStyle(color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Per-frame drawing objects, kept across frames instead of reallocated. */
private class ShelfFrameBuffers {
    val drawn = ArrayList<Pair<Int, ShelfBookQuads>>(2 * ShelfModel.DRAW_RADIUS + 1)
    val poses = HashMap<Int, ShelfPose>(2 * (2 * ShelfModel.DRAW_RADIUS + 1))
    val floorPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val reflectPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val order = ShelfDrawOrder()
}

/** The obi band's uv rect (u0, v0, u1, v1) on jacket and spine: matte paper. */
private val OBI_UV = floatArrayOf(0f, 1f - ShelfModel.OBI_FRACTION, 1f, 1f)

/** Gets one book's finished textures from [ShelfTextureCache]; `fresh` is false for a memory hit. */
@Composable
private fun ShelfTextureLoader(
    book: ShelfBook,
    info: ShelfBookInfo,
    cacheKey: String,
    dark: Boolean,
    pxPerUnit: Float,
    /** Books from the centre: the nearest start first, so the selected book never waits behind prefetch. */
    distance: Int,
    onReady: (ShelfTextures, Boolean) -> Unit,
) {
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(cacheKey) {
        ShelfTextureCache.peek(cacheKey)?.let {
            ready(it, false)
            return@LaunchedEffect
        }
        delay(distance * 45L)
        ready(ShelfTextureCache.obtain(cacheKey, book, info, dark, pxPerUnit), true)
    }
}
