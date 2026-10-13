package com.maestroseb.marcarpro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Keeps the board's ink between sessions, so closing Marcar Pro (on purpose or not) never
 * loses a lesson. A compact binary file: the whole board is a few hundred KB at most, plus any
 * pictures cut out of the screen (each as a PNG, after its corners).
 */
internal object InkStore {
    private const val MAGIC = 0x4D4B5031 // "MKP1"
    private const val MAX_ITEMS = 1_000_000
    private const val MAX_PICTURE = 64 * 1024 * 1024
    private const val STROKE_HEADER = 23 // tool, colour, size, speed unit, group, 2 flags, point count

    /** Serialises on the calling thread (one pass into one buffer); the caller may write the bytes elsewhere. */
    fun encode(strokes: List<Stroke>): ByteArray {
        var size = 8
        for (s in strokes) {
            size += STROKE_HEADER + 12 * s.points.size
            if (s.tool == InkTool.IMAGE) size += 4 + pictureData(s).size
        }
        // Big-endian, the same layout DataOutputStream wrote (old files still load).
        val out = ByteBuffer.allocate(size)
        out.putInt(MAGIC)
        out.putInt(strokes.size)
        for (s in strokes) {
            out.put(s.tool.ordinal.toByte())
            out.putInt(s.color)
            out.putFloat(s.size)
            out.putFloat(s.speedUnit)
            out.putInt(s.group)
            out.put(if (s.uniform) 1 else 0)
            out.put(if (s.hasRealPressure) 1 else 0)
            out.putInt(s.points.size)
            for (p in s.points) {
                out.putFloat(p.x)
                out.putFloat(p.y)
                out.putFloat(p.pressure)
            }
            if (s.tool == InkTool.IMAGE) {
                val png = pictureData(s)
                out.putInt(png.size)
                out.put(png)
            }
        }
        return out.array()
    }

    /** A picture's PNG: made when it was cut out, or now if that hasn't finished yet. */
    private fun pictureData(s: Stroke): ByteArray = s.imageData ?: png(s.image!!).also { s.imageData = it }

    fun png(image: Bitmap): ByteArray =
        java.io.ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    /** Returns the saved strokes, or an empty list when there is nothing (valid) to load. */
    fun decode(data: ByteArray): List<Stroke> = try {
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            if (input.readInt() != MAGIC) return emptyList()
            val count = input.readInt()
            if (count !in 0..MAX_ITEMS) return emptyList()
            List(count) {
                val tool = InkTool.entries[input.readUnsignedByte()]
                val color = input.readInt()
                val size = input.readFloat()
                val speed = input.readFloat()
                val group = input.readInt()
                val uniform = input.readBoolean()
                val realPressure = input.readBoolean()
                val n = input.readInt()
                if (n !in 0..MAX_ITEMS) throw IOException("corrupt")
                val pts = List(n) { InkPoint(input.readFloat(), input.readFloat(), input.readFloat()) }
                val s = if (tool == InkTool.IMAGE) {
                    val len = input.readInt()
                    if (len !in 1..MAX_PICTURE) throw IOException("corrupt")
                    val png = ByteArray(len).also { input.readFully(it) }
                    val image = BitmapFactory.decodeByteArray(png, 0, len) ?: throw IOException("corrupt")
                    Stroke.savedPicture(image, png)
                } else {
                    Stroke(tool, color, size, speed)
                }
                s.apply {
                    this.group = group
                    this.uniform = uniform
                    hasRealPressure = realPressure
                    points.addAll(pts)
                    finish()
                }
            }
        }
    } catch (_: IOException) {
        emptyList()
    } catch (_: RuntimeException) {
        // A damaged file (bad tool index, absurd sizes…) loads as an empty board, never a crash.
        emptyList()
    }

    /**
     * Writes atomically and synced to storage: a crash or a power cut mid-write leaves the
     * previous board intact (the panel is often switched off at the wall).
     */
    fun write(file: File, data: ByteArray) {
        val atomic = AtomicFile(file)
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(data)
            atomic.finishWrite(out)
        } catch (_: IOException) {
            if (out != null) atomic.failWrite(out)
        }
    }

    /** Removes a board's file (and AtomicFile's backup), e.g. once its ink is all gone. */
    fun delete(file: File) = AtomicFile(file).delete()

    fun read(file: File): List<Stroke> {
        val atomic = AtomicFile(file)
        val data = try {
            atomic.readFully()
        } catch (_: IOException) {
            return emptyList()
        }
        return decode(data)
    }
}
