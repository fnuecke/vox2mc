package li.cil.vox2mc.data

import kotlin.math.max
import kotlin.math.min

data class Int3(val x: Int, val y: Int, val z: Int) : Comparable<Int3> {
    constructor(xy: Int2, z: Int) : this(xy.x, xy.y, z)

    companion object {
        val ZERO = Int3(0, 0, 0)
        val ONE = Int3(1, 1, 1)
    }

    operator fun plus(v: Int3) = Int3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Int3) = Int3(x - v.x, y - v.y, z - v.z)
    operator fun times(i: Int) = Int3(x * i, y * i, z * i)
    operator fun div(i: Int) = Int3(x / i, y / i, z / i)

    fun toInt2() = Int2(x, y)
    fun toArray() = arrayOf(x, y, z)
    fun decompose() = arrayOf(Int3(x, 0, 0), Int3(0, y, 0), Int3(0, 0, z))

    override fun compareTo(other: Int3): Int {
        var d = x.compareTo(other.x)
        if (d != 0) return d
        d = y.compareTo(other.y)
        if (d != 0) return d
        return z.compareTo(other.z)
    }
}

fun dot(a: Int3, b: Int3) = a.x * b.x + a.y * b.y + a.z + b.z

fun min(a: Int3, b: Int3) = Int3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
fun max(a: Int3, b: Int3) = Int3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))
