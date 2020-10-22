package li.cil.vox2mc.data

import kotlin.math.max
import kotlin.math.min

data class Int3(val x: Int, val y: Int, val z: Int) {
    constructor(xy: Int2, z: Int) : this(xy.x, xy.y, z)

    operator fun plus(v: Int3) = Int3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Int3) = Int3(x - v.x, y - v.y, z - v.z)
    operator fun div(i: Int) = Int3(x / i, y / i, z / i)

    fun toInt2() = Int2(x, y)
}

fun min(a: Int3, b: Int3) = Int3(min(a.x, b.x), min(a.y, b.y), min(a.z, b.z))
fun max(a: Int3, b: Int3) = Int3(max(a.x, b.x), max(a.y, b.y), max(a.z, b.z))
