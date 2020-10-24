package li.cil.vox2mc.algorithm

import li.cil.vox2mc.data.Int3

fun abgr2rgb(color: Int): Int {
    val r = color and 0xFF
    val g = (color shr 8) and 0xFF
    val b = (color shr 16) and 0xFF
    return (r shl 16) or (g shl 8) or b
}

fun Int.toRGBInt3(): Int3 {
    val r = (this shr 16) and 0xFF
    val g = (this shr 8) and 0xFF
    val b = this and 0xFF
    return Int3(r, g, b)
}

fun Int3.toRGBInt() = (x shl 16) or (y shl 8) or z
