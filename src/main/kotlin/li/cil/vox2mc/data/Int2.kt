package li.cil.vox2mc.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

data class Int2(val x: Int, val y: Int) {
    fun sqDistanceTo(v: Int2) = dot(v - this, v - this)
    fun leftHandNormal() = Int2(-y, x)
    fun rightHandNormal() = Int2(y, -x)
    fun normalizeAxisAligned() = Int2(x.sign, y.sign)

    operator fun plus(v: Int2) = Int2(x + v.x, y + v.y)
    operator fun minus(v: Int2) = Int2(x - v.x, y - v.y)
    operator fun times(i: Int) = Int2(x * i, y * i)
    operator fun div(i: Int) = Int2(x / i, y / i)
}

fun dot(a: Int2, b: Int2) = a.x * b.x + a.y * b.y
fun cross(a: Int2, b: Int2) = a.x * b.y - a.y * b.x

fun clamp(v: Int2, min: Int2, max: Int2) = Int2(
    max(min.x, min(max.x, v.x)),
    max(min.y, min(max.y, v.y))
)
