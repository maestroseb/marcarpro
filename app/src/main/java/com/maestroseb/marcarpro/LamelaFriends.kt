package com.maestroseb.marcarpro

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The four friends that ride the Lamela rows (a caterpillar, a whale, a rocket and a plesiosaur),
 * drawn from the shapes the user approved in the mockup (2026-11-27), in "row units": one unit is
 * a square, x from 0 to 3.4, y up from the row's lower strong line (the body fills -1..0, only
 * antennae, spout, fins, tail or crests stick out). Our own drawings, none from the notebooks.
 * Shapes are built once; drawing is a handful of paths per row on screen.
 */
internal object LamelaFriends {
    private sealed class Op {
        class Fill(val path: Path, val color: Int, val alpha: Int = 255) : Op()
        class Stroke(val path: Path, val color: Int, val width: Float) : Op()
        class Circle(val cx: Float, val cy: Float, val r: Float, val color: Int, val alpha: Int = 255) : Op()
        class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val color: Int, val turn: Float = 0f) : Op()
        class Box(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float, val color: Int) : Op()
    }

    private const val W = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF2C3138.toInt()
    private const val CHEEK = 0xFFFF8FA3.toInt()

    /** A path from SVG's absolute M L Q C Z (and relative l), as the mockup wrote them. */
    private fun svg(d: String): Path {
        val p = Path()
        val t = Regex("[MLQCZlz]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var x = 0f
        var y = 0f
        fun n() = t[i++].toFloat()
        while (i < t.size) {
            val tok = t[i]
            if (tok.length == 1 && tok[0].isLetter()) {
                cmd = tok[0]
                i++
                if (cmd == 'Z' || cmd == 'z') { p.close(); continue }
            }
            when (cmd) {
                'M' -> { x = n(); y = n(); p.moveTo(x, y); cmd = 'L' }
                'L' -> { x = n(); y = n(); p.lineTo(x, y) }
                'l' -> { x += n(); y += n(); p.lineTo(x, y) }
                'Q' -> { val a = n(); val b = n(); x = n(); y = n(); p.quadTo(a, b, x, y) }
                'C' -> { val a = n(); val b = n(); val c = n(); val e = n(); x = n(); y = n(); p.cubicTo(a, b, c, e, x, y) }
                else -> i++
            }
        }
        return p
    }

    private fun MutableList<Op>.face(cx: Float, cy: Float, k: Float) {
        add(Op.Circle(cx - .12f * k, cy - .06f * k, .055f * k, INK))
        add(Op.Circle(cx + .12f * k, cy - .06f * k, .055f * k, INK))
        add(Op.Circle(cx - .1f * k, cy - .08f * k, .018f * k, W))
        add(Op.Circle(cx + .14f * k, cy - .08f * k, .018f * k, W))
        add(Op.Stroke(Path().apply { moveTo(cx - .12f * k, cy + .07f * k); quadTo(cx, cy + .2f * k, cx + .12f * k, cy + .07f * k) }, INK, .045f * k))
    }

    private fun MutableList<Op>.cheeks(cx: Float, cy: Float, k: Float) {
        add(Op.Circle(cx - .22f * k, cy + .08f * k, .06f * k, CHEEK, 217))
        add(Op.Circle(cx + .22f * k, cy + .08f * k, .06f * k, CHEEK, 217))
    }

    private val caterpillar: List<Op> = mutableListOf<Op>().apply {
        add(Op.Stroke(svg("M2.72 -.9 Q2.58 -1.45 2.32 -1.6"), 0xFF3C8A32.toInt(), .07f))
        add(Op.Stroke(svg("M3.1 -.9 Q3.2 -1.45 3.3 -1.6"), 0xFF3C8A32.toInt(), .07f))
        add(Op.Circle(2.32f, -1.6f, .11f, 0xFFFFB547.toInt())); add(Op.Circle(3.3f, -1.6f, .1f, 0xFFFFB547.toInt()))
        add(Op.Circle(2.29f, -1.63f, .035f, W, 179)); add(Op.Circle(3.27f, -1.63f, .03f, W, 179))
        for ((cx, c) in listOf(.5f to 0xFF7ED957, 1.3f to 0xFF5BC24A, 2.1f to 0xFF7ED957, 2.9f to 0xFF5BC24A)) add(Op.Circle(cx, -.5f, .5f, c.toInt()))
        add(Op.Stroke(svg("M.25 -.82 Q.5 -.95 .72 -.88 M1.05 -.82 Q1.3 -.95 1.52 -.88 M1.85 -.82 Q2.1 -.95 2.32 -.88"), 0xFFB6F09A.toInt(), .07f))
        face(2.98f, -.55f, 1.5f); cheeks(2.98f, -.55f, 1.3f)
    }

    private val whale: List<Op> = mutableListOf<Op>().apply {
        for ((x, y, r) in listOf(Triple(2.3f, -1.42f, .08f), Triple(2.12f, -1.66f, .07f), Triple(2.48f, -1.64f, .07f), Triple(2.3f, -1.9f, .06f))) add(Op.Circle(x, y, r, 0xFF7CC8FF.toInt()))
        add(Op.Stroke(svg("M2.3 -1.02 L2.3 -1.32"), 0xFF7CC8FF.toInt(), .08f))
        val tail = 0xFF2F86D6.toInt()
        add(Op.Fill(svg("M.5 -.64 Q.3 -.6 .2 -.5 Q.3 -.4 .5 -.36 Z"), tail))
        add(Op.Oval(.1f, -.78f, .13f, .3f, tail, -38f)); add(Op.Oval(.1f, -.22f, .13f, .3f, tail, 38f))
        add(Op.Circle(.24f, -.5f, .12f, tail))
        add(Op.Fill(svg("M.35 -.5 Q.35 -1 1.4 -1 L2.5 -1 Q3.25 -1 3.25 -.45 Q3.25 0 2.55 0 L1.05 0 Q.35 0 .35 -.5 Z"), 0xFF3A9BE8.toInt()))
        add(Op.Fill(svg("M.95 0 Q1.55 -.36 2.9 -.24 Q3.15 -.2 3.22 -.32 Q3.15 0 2.55 0 Z"), 0xFFCFEAFF.toInt()))
        for (xx in floatArrayOf(1.7f, 1.95f, 2.2f)) add(Op.Stroke(Path().apply { moveTo(xx, -.25f); lineTo(xx + .04f, -.06f) }, 0xFFA9D6F7.toInt(), .035f))
        add(Op.Stroke(svg("M.7 -.82 Q1.3 -.96 2.2 -.92"), 0xFF7CC0F5.toInt(), .07f))
        add(Op.Fill(svg("M1.3 -.44 Q1.58 -.06 1.72 -.42 Q1.5 -.4 1.3 -.44 Z"), tail))
        face(2.65f, -.6f, 1.35f); cheeks(2.65f, -.6f, 1.2f)
    }

    private val rocket: List<Op> = mutableListOf<Op>().apply {
        add(Op.Fill(svg("M.5 -.82 Q.22 -.76 -.02 -.5 Q.22 -.24 .5 -.18 Q.34 -.5 .5 -.82 Z"), 0xFFFF8A3D.toInt()))
        add(Op.Fill(svg("M.5 -.7 Q.3 -.65 .14 -.5 Q.3 -.35 .5 -.3 Q.4 -.5 .5 -.7 Z"), 0xFFFFC23D.toInt()))
        add(Op.Fill(svg("M.5 -.6 Q.38 -.56 .3 -.5 Q.38 -.44 .5 -.4 Z"), 0xFFFFF1A8.toInt()))
        add(Op.Box(.46f, -.74f, .22f, .48f, .06f, 0xFF5B6B84.toInt())); add(Op.Box(.46f, -.74f, .22f, .1f, .05f, 0xFF8393AD.toInt()))
        val pink = 0xFFFF6B9A.toInt()
        add(Op.Fill(svg("M.78 -.95 Q.62 -1.2 .5 -1.46 Q.85 -1.48 1.15 -1.22 Q1.3 -1.08 1.42 -.95 Z"), pink))
        add(Op.Fill(svg("M.78 -.05 Q.62 .2 .5 .46 Q.85 .48 1.15 .22 Q1.3 .08 1.42 -.05 Z"), pink))
        add(Op.Fill(svg("M.66 -1 L2.5 -1 Q3.37 -.92 3.37 -.5 Q3.37 -.08 2.5 0 L.66 0 Q.6 -.5 .66 -1 Z"), 0xFF7B61FF.toInt()))
        add(Op.Fill(svg("M.66 -.22 L2.5 -.22 Q3.0 -.25 3.2 -.32 Q3.0 -.06 2.5 0 L.66 0 Z"), 0xFF6A50EE.toInt()))
        add(Op.Fill(svg("M2.62 -.99 Q3.37 -.9 3.37 -.5 Q3.37 -.1 2.62 -.01 Q2.82 -.5 2.62 -.99 Z"), pink))
        add(Op.Stroke(svg("M.75 -.86 L2.5 -.86"), 0xFFA99AFF.toInt(), .07f))
        add(Op.Box(1.62f, -1f, .16f, 1f, 0f, pink))
        add(Op.Circle(1.15f, -.5f, .33f, 0xFFD6CCFF.toInt())); add(Op.Circle(1.15f, -.5f, .25f, 0xFF5CC8FF.toInt()))
        add(Op.Stroke(svg("M.98 -.6 Q1.06 -.71 1.2 -.71"), W, .06f))
        add(Op.Circle(1.27f, -.38f, .035f, W, 204))
        for (ex in floatArrayOf(2.15f, 2.47f)) {
            add(Op.Oval(ex, -.6f, .1f, .13f, W)); add(Op.Oval(ex + .02f, -.58f, .06f, .08f, INK)); add(Op.Circle(ex + .04f, -.62f, .022f, W))
        }
        add(Op.Stroke(svg("M2.18 -.32 Q2.31 -.2 2.44 -.32"), INK, .05f))
        add(Op.Circle(2.0f, -.36f, .06f, CHEEK, 230)); add(Op.Circle(2.62f, -.36f, .06f, CHEEK, 230))
        val star = 0xFFFFB020.toInt()
        add(Op.Circle(.95f, -1.75f, .045f, star)); add(Op.Circle(3.12f, -1.32f, .055f, star))
        add(Op.Fill(svg("M2.0 -1.75 l.05 .12 l.12 .02 l-.1 .07 l.03 .12 l-.1 -.07 l-.1 .07 l.03 -.12 l-.1 -.07 l.12 -.02 Z"), star))
    }

    private val plesiosaur: List<Op> = mutableListOf<Op>().apply {
        add(Op.Fill(svg("M.66 -.76 C.28 -.7 .05 -.42 .07 -.02 C.09 .28 .24 .46 .42 .54 C.33 .38 .29 .2 .32 .02 C.36 -.18 .5 -.27 .66 -.27 Z"), 0xFF2FB37F.toInt()))
        add(Op.Fill(svg("M.42 .48 Q.22 .58 .26 .78 Q.42 .72 .5 .6 Q.6 .76 .76 .7 Q.68 .52 .48 .46 Z"), 0xFFFFB547.toInt()))
        for (sx in floatArrayOf(1.0f, 1.42f, 1.84f)) {
            add(Op.Fill(Path().apply { moveTo(sx - .16f, -.9f); quadTo(sx - .06f, -1.3f, sx, -1.4f); quadTo(sx + .06f, -1.3f, sx + .16f, -.9f); close() }, 0xFFFFB547.toInt()))
        }
        val green = 0xFF34C38F.toInt()
        add(Op.Fill(svg("M2.2 -.6 L2.26 -.88 Q2.36 -1.55 2.82 -1.76 Q3.0 -1.82 3.18 -1.78 Q3.42 -1.72 3.4 -1.48 Q3.36 -1.24 3.06 -1.22 Q2.8 -1.2 2.74 -.82 L2.74 -.5 Z"), green))
        add(Op.Box(.5f, -1f, 2.25f, 1f, .48f, green))
        add(Op.Fill(svg("M.8 -.16 Q1.65 -.42 2.55 -.2 Q2.5 0 2.25 0 L1.05 0 Q.85 0 .8 -.16 Z"), 0xFF7FE0B5.toInt()))
        add(Op.Stroke(svg("M.85 -.84 Q1.5 -.96 2.3 -.86"), 0xFF5FDBA6.toInt(), .06f))
        add(Op.Fill(svg("M2.16 -.4 C1.98 -.44 1.76 -.3 1.6 -.1 C1.47 .07 1.4 .24 1.46 .3 C1.53 .35 1.7 .22 1.86 .06 C2.02 -.1 2.16 -.22 2.21 -.3 C2.24 -.36 2.21 -.4 2.16 -.4 Z"), 0xFF1F8F63.toInt()))
        add(Op.Stroke(svg("M2.1 -.36 C1.92 -.34 1.72 -.18 1.56 .06"), 0xFF5FD6A3.toInt(), .045f))
        add(Op.Circle(3.08f, -1.54f, .08f, INK)); add(Op.Circle(3.1f, -1.57f, .028f, W))
        add(Op.Stroke(svg("M2.98 -1.36 Q3.13 -1.27 3.3 -1.37"), INK, .05f))
        add(Op.Circle(2.88f, -1.4f, .06f, CHEEK, 217))
        add(Op.Circle(3.33f, -1.62f, .025f, 0xFF1F8F63.toInt()))
    }

    /** In turn, row after row; the whale and the rocket a little to the right, as in the mockup. */
    private val friends = listOf(caterpillar to 0f, whale to .15f, rocket to .03f, plesiosaur to 0f)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val box = RectF()

    /** Friend number [row] (any whole number: they take turns) at ([x], [base]), [unit] pixels a square. */
    fun draw(canvas: Canvas, row: Int, x: Float, base: Float, unit: Float) {
        val (ops, dx) = friends[((row % 4) + 4) % 4]
        canvas.save()
        canvas.translate(x, base)
        canvas.scale(unit, unit)
        canvas.translate(dx, 0f)
        for (op in ops) when (op) {
            is Op.Fill -> { fill.color = op.color; fill.alpha = op.alpha; canvas.drawPath(op.path, fill) }
            is Op.Stroke -> { stroke.color = op.color; stroke.strokeWidth = op.width; canvas.drawPath(op.path, stroke) }
            is Op.Circle -> { fill.color = op.color; fill.alpha = op.alpha; canvas.drawCircle(op.cx, op.cy, op.r, fill) }
            is Op.Oval -> {
                fill.color = op.color
                box.set(op.cx - op.rx, op.cy - op.ry, op.cx + op.rx, op.cy + op.ry)
                if (op.turn != 0f) {
                    canvas.save()
                    canvas.rotate(op.turn, op.cx, op.cy)
                    canvas.drawOval(box, fill)
                    canvas.restore()
                } else {
                    canvas.drawOval(box, fill)
                }
            }
            is Op.Box -> { fill.color = op.color; box.set(op.x, op.y, op.x + op.w, op.y + op.h); canvas.drawRoundRect(box, op.r, op.r, fill) }
        }
        canvas.restore()
    }
}
