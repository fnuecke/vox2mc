package li.cil.vox2mc.algorithm

fun abgr2rgb(color: Int): Int {
    val r = color and 0xFF
    val g = (color shr 8) and 0xFF
    val b = (color shr 16) and 0xFF
    return (r shl 16) or (g shl 8) or b
}
