package li.cil.vox2mc.data

import kotlin.math.abs

data class Int2(val x: Int, val y: Int) {
    companion object {
        val ZERO = Int2(0, 0)
        val ONE = Int2(1, 1)
    }

    operator fun plus(v: Int2) = Int2(x + v.x, y + v.y)
    operator fun minus(v: Int2) = Int2(x - v.x, y - v.y)
    operator fun times(i: Int) = Int2(x * i, y * i)
    operator fun div(i: Int) = Int2(x / i, y / i)
    operator fun unaryMinus() = Int2(-x, -y)

    override fun toString(): String {
        return "[$x, $y]"
    }
}

fun abs(a: Int2) = Int2(abs(a.x), abs(a.y))
