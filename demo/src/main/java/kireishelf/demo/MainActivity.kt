package kireishelf.demo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kireishelf.KireiShelf
import kireishelf.ShelfBook

/**
 * 演示：把 assets/covers/ 里的图片排上书架，文件名（去掉开头的序号和下划线）就是书名，
 * 例如 `1_夏日的回忆.jpg`。文件夹是空的就用代码生成的几本示例书。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val books = coverBooks().ifEmpty { sampleBooks() }
        setContent {
            KireiShelf(
                books = books,
                onOpen = { Toast.makeText(this, "打开《${it.title}》", Toast.LENGTH_SHORT).show() },
            )
        }
    }

    private fun coverBooks(): List<ShelfBook> {
        val files = assets.list("covers").orEmpty().filter { it.endsWith(".jpg") || it.endsWith(".png") || it.endsWith(".webp") }.sorted()
        return files.mapIndexed { i, name ->
            val cover = assets.open("covers/$name").use { BitmapFactory.decodeStream(it) }
            book(i, name.substringBeforeLast('.').substringAfter('_'), cover)
        }
    }

    private fun sampleBooks(): List<ShelfBook> =
        listOf(
            "夏日的回忆" to intArrayOf(0xFF7EC8E3.toInt(), 0xFF3A6EA5.toInt()),
            "雨の街" to intArrayOf(0xFF9AA5B1.toInt(), 0xFF3D4852.toInt()),
            "樱色彼方" to intArrayOf(0xFFF6C1CC.toInt(), 0xFFB5658A.toInt()),
            "月影回廊" to intArrayOf(0xFF5B5F97.toInt(), 0xFF1E2040.toInt()),
            "晴空轨迹" to intArrayOf(0xFFFFD58A.toInt(), 0xFFE07A5F.toInt()),
        ).mapIndexed { i, (title, colors) -> book(i, title, gradientCover(colors)) }

    /** Demo numbers: a different play time and last-played day for each book. */
    private fun book(
        i: Int,
        title: String,
        cover: Bitmap?,
    ): ShelfBook {
        val hour = 3_600_000L
        val day = 24 * hour
        return ShelfBook(
            id = i.toLong(),
            title = title,
            cover = cover,
            playTime = longArrayOf(14, 9, 11, 17, 6, 21, 3)[i % 7] * hour + (i * 7 % 60) * 60_000L,
            lastPlayedAt = System.currentTimeMillis() - longArrayOf(0, 2, 5, 9, 14, 30, 60)[i % 7] * day - 2 * hour,
        )
    }

    private fun gradientCover(colors: IntArray): Bitmap {
        val bmp = Bitmap.createBitmap(500, 710, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(
            0f, 0f, 500f, 710f,
            Paint().apply { shader = LinearGradient(0f, 0f, 500f, 710f, colors[0], colors[1], Shader.TileMode.CLAMP) },
        )
        return bmp
    }
}
