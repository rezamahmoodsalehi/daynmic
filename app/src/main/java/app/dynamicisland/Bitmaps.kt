package app.dynamicisland

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

object Bitmaps {
    fun drawableToBitmap(d: Drawable, size: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    /** Coloured square with the first letter, used when a contact has no profile photo. */
    fun initials(name: String, size: Int = 192): Bitmap {
        val colors = intArrayOf(0xFF5E5CE6.toInt(), 0xFF30B0C7.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF375F.toInt(), 0xFF32A852.toInt())
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colors[(name.hashCode() and 0x7fffffff) % colors.size]
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = Color.WHITE
        p.textSize = size * 0.46f
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        val letter = name.trim().firstOrNull()?.toString() ?: "?"
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    /** Generated cover art for tracks without embedded artwork. */
    fun musicArt(seed: String, size: Int = 256): Bitmap {
        val pairs = arrayOf(
            intArrayOf(0xFFFF6A88.toInt(), 0xFFFF99AC.toInt()),
            intArrayOf(0xFF5B86E5.toInt(), 0xFF36D1DC.toInt()),
            intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt()),
            intArrayOf(0xFF8E2DE2.toInt(), 0xFF4A00E0.toInt())
        )
        val pair = pairs[(seed.hashCode() and 0x7fffffff) % pairs.size]
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), pair[0], pair[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = Color.argb(230, 255, 255, 255)
        p.textSize = size * 0.6f
        p.textAlign = Paint.Align.CENTER
        c.drawText("♪", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
}
