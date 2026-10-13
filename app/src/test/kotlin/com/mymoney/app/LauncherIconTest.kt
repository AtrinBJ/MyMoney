package com.mymoney.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.hypot
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Launcher icon checks without a device: the symbol stays inside the adaptive-icon safe zone, the themed
 * (monochrome) layer has the same silhouette as the full-color foreground, and a preview sheet shows the icon
 * under several launcher masks and themed-icon palettes (build/screenshots/launcher_icon_preview.png).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val size = 432 // 4 px per dp of the 108dp canvas

    private fun render(res: Int, tint: Int? = null): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val d = ContextCompat.getDrawable(context, res)!!.mutate()
        d.setBounds(0, 0, size, size)
        tint?.let { d.colorFilter = PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN) }
        d.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun `symbol stays inside the 66dp safe zone and is not tiny`() {
        val mono = render(R.drawable.ic_launcher_monochrome)
        var maxR = 0.0
        var minX = size; var maxX = 0; var minY = size; var maxY = 0
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(mono.getPixel(x, y)) > 16) {
                maxR = maxOf(maxR, hypot(x - size / 2.0, y - size / 2.0) / 4.0)
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        assertTrue(maxR <= 33.0, "symbol reaches ${"%.1f".format(maxR)}dp from center (safe zone radius is 33dp)")
        assertTrue((maxX - minX) / 4.0 >= 44, "symbol too small: ${(maxX - minX) / 4.0}dp wide")
        // Optically centered: bounding-box center within 1dp of the canvas center.
        assertTrue(kotlin.math.abs((minX + maxX) / 2.0 - size / 2.0) / 4.0 <= 1.0)
        assertTrue(kotlin.math.abs((minY + maxY) / 2.0 - size / 2.0) / 4.0 <= 1.0)
    }

    @Test
    fun `themed layer has exactly the foreground silhouette`() {
        val fg = render(R.drawable.ic_launcher_foreground)
        val mono = render(R.drawable.ic_launcher_monochrome)
        var differ = 0
        var on = 0
        for (y in 0 until size) for (x in 0 until size) {
            val a = Color.alpha(fg.getPixel(x, y)) > 128
            val b = Color.alpha(mono.getPixel(x, y)) > 128
            if (a || b) on++
            if (a != b) differ++
        }
        assertTrue(differ.toDouble() / on < 0.01, "silhouettes differ on $differ of $on pixels")
    }

    @Test
    fun `preview sheet - masks, sizes and themed palettes`() {
        val cell = 220
        val cols = 5
        val sheet = Bitmap.createBitmap(cell * cols, cell * 3, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(236, 239, 241))
        val bg = render(R.drawable.ic_launcher_background)
        val fg = render(R.drawable.ic_launcher_foreground)

        // Launchers show the middle 72dp of the 108dp layers, clipped by their own mask.
        fun icon(x: Int, y: Int, px: Int, mask: (RectF) -> Path, layers: List<Bitmap>) {
            val dst = RectF(x.toFloat(), y.toFloat(), (x + px).toFloat(), (y + px).toFloat())
            val inner = 18f / 108f * size
            val src = android.graphics.Rect(inner.toInt(), inner.toInt(), (size - inner).toInt(), (size - inner).toInt())
            c.save()
            c.clipPath(mask(dst))
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            layers.forEach { c.drawBitmap(it, src, dst, p) }
            c.restore()
        }
        val circle: (RectF) -> Path = { r -> Path().apply { addOval(r, Path.Direction.CW) } }
        val squircle: (RectF) -> Path = { r -> Path().apply { addRoundRect(r, r.width() * 0.32f, r.height() * 0.32f, Path.Direction.CW) } }
        val rounded: (RectF) -> Path = { r -> Path().apply { addRoundRect(r, r.width() * 0.16f, r.height() * 0.16f, Path.Direction.CW) } }
        val square: (RectF) -> Path = { r -> Path().apply { addRect(r, Path.Direction.CW) } }
        val teardrop: (RectF) -> Path = { r ->
            Path().apply {
                val rr = r.width() / 2
                addRoundRect(r, floatArrayOf(rr, rr, rr, rr, rr, rr, r.width() * 0.1f, r.width() * 0.1f), Path.Direction.CW)
            }
        }
        // Row 1: full-color icon under five launcher masks.
        listOf(circle, squircle, rounded, square, teardrop).forEachIndexed { i, m -> icon(i * cell + 30, 30, 160, m, listOf(bg, fg)) }
        // Row 2: small sizes (48, 36, 24 px) — legibility.
        listOf(96, 72, 48, 36, 24).forEachIndexed { i, px -> icon(i * cell + (cell - px) / 2, cell + (cell - px) / 2, px, circle, listOf(bg, fg)) }
        // Row 3: themed icons as a launcher draws them: tonal background + monochrome tinted with the accent.
        val palettes = listOf(
            0xFFFFDAD6.toInt() to 0xFF8C1D18.toInt(), // red
            0xFFD8E2FF.toInt() to 0xFF1B4C9A.toInt(), // blue
            0xFFEBDDFF.toInt() to 0xFF5B3A9A.toInt(), // purple
            0xFFC8F0D8.toInt() to 0xFF1E5B3A.toInt(), // green
            0xFF2B2D31.toInt() to 0xFFC4D7FF.toInt(), // dark theme
        )
        palettes.forEachIndexed { i, (back, accent) ->
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(back) }
            icon(i * cell + 30, 2 * cell + 30, 160, circle, listOf(b, render(R.drawable.ic_launcher_monochrome, accent)))
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "launcher_icon_preview.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
