package li.cil.vox2mc.data

data class Int3(val x: Int, val y: Int, val z: Int) {
    operator fun plus(v: Int3) = Int3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: Int3) = Int3(x - v.x, y - v.y, z - v.z)
    operator fun div(i: Int) = Int3(x / i, y / i, z / i)

    fun toInt2() = Int2(x, y)
}
