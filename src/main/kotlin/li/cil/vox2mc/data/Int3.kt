package li.cil.vox2mc.data

data class Int3(val x: Int, val y: Int, val z: Int) : Comparable<Int3> {
    constructor(xy: Int2, z: Int) : this(xy.x, xy.y, z)

    operator fun plus(v: Int3) = Int3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Int3) = Int3(x - v.x, y - v.y, z - v.z)
    operator fun div(i: Int) = Int3(x / i, y / i, z / i)

    fun toInt2() = Int2(x, y)

    override fun compareTo(other: Int3): Int {
        var d = x.compareTo(other.x)
        if (d != 0) return d
        d = y.compareTo(other.y)
        if (d != 0) return d
        return z.compareTo(other.z)
    }
}
