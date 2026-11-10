package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved boards keep their cutouts (pictures) next to the ink. */
class InkStoreTest {

    @Test
    fun picturesSurviveSaving() {
        val image = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(3, 4, Color.RED)
        }
        val ink = Stroke(InkTool.PEN, Color.BLUE, 5f, 14f).apply {
            add(0f, 0f, 1f)
            add(30f, 10f, 1f)
            finish()
        }
        val picture = Stroke.picture(image, null, 100f, 50f, 180f, 90f).apply { group = 7 }

        val back = InkStore.decode(InkStore.encode(listOf(ink, picture)))

        assertEquals(2, back.size)
        assertEquals(InkTool.PEN, back[0].tool)
        val p = back[1]
        assertEquals(InkTool.IMAGE, p.tool)
        assertEquals(7, p.group)
        assertEquals(40, p.image!!.width)
        assertEquals(Color.RED, p.image!!.getPixel(3, 4))
        assertEquals(100f, p.bounds.left, 0.01f)
        assertEquals(90f, p.bounds.bottom, 0.01f)
        // The picture is hit inside its corners, not outside.
        assertTrue(p.hits(140f, 70f, 1f))
        assertTrue(!p.hits(190f, 70f, 1f))
    }

    @Test
    fun textsSurviveSaving() {
        val words = "Tema 3\nañadir ñ y €"
        val text = Stroke.text(words, Color.RED, 44f, 100f, 50f, 300f, 120f, PointF(1f, 0f), PointF(0f, 1f))
        val ink = Stroke(InkTool.PEN, Color.BLUE, 5f, 14f).apply {
            add(0f, 0f, 1f)
            add(30f, 10f, 1f)
            finish()
        }

        val back = InkStore.decode(InkStore.encode(listOf(text, ink)))

        assertEquals(2, back.size)
        val t = back[0]
        assertEquals(InkTool.TEXT, t.tool)
        assertEquals(words, t.text)
        assertEquals(Color.RED, t.color)
        assertEquals(100f, t.bounds.left, 0.01f)
        assertEquals(170f, t.bounds.bottom, 0.01f)
        assertTrue(t.hits(200f, 100f, 1f))
        assertTrue(!t.hits(450f, 100f, 1f))
        assertEquals(InkTool.PEN, back[1].tool)
    }

    @Test
    fun aMixedBoardIsSavedInPiecesWithoutCopyingPictures() {
        fun ink(x: Float) = Stroke(InkTool.PEN, Color.BLUE, 5f, 14f).apply {
            add(x, 0f, 1f)
            add(x + 30f, 10f, 1f)
            finish()
        }
        fun picture(c: Int) = Stroke.picture(
            Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }, null, 0f, 0f, 30f, 20f,
        )
        val text = Stroke.text("ñ", Color.RED, 44f, 0f, 0f, 50f, 50f, PointF(1f, 0f), PointF(0f, 1f))
        val board = listOf(ink(0f), picture(Color.RED), text, picture(Color.GREEN), picture(Color.BLUE), ink(50f))

        val parts = InkStore.encodeParts(board)
        // Each picture's PNG goes as it is, not copied into a bigger array.
        for (k in listOf(1, 3, 4)) assertTrue(parts.any { it === board[k].imageData })
        val file = java.io.File.createTempFile("board", ".ink")
        InkStore.write(file, parts)
        val back = InkStore.read(file)
        file.delete()

        assertEquals(listOf(InkTool.PEN, InkTool.IMAGE, InkTool.TEXT, InkTool.IMAGE, InkTool.IMAGE, InkTool.PEN), back.map { it.tool })
        assertEquals(Color.RED, back[1].image!!.getPixel(5, 5))
        assertEquals(Color.GREEN, back[3].image!!.getPixel(5, 5))
        assertEquals(Color.BLUE, back[4].image!!.getPixel(5, 5))
        assertEquals("ñ", back[2].text)
        assertEquals(50f, back[5].points[0].x, 0.01f)
        // The same bytes as in one piece.
        assertTrue(InkStore.encode(board).contentEquals(parts.fold(ByteArray(0)) { a, b -> a + b }))
    }
}
