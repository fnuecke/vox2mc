package li.cil.vox2mc.data

class Patch(val width: Int, val height: Int, val pixels: IntArray) {
    var atlasX = 0
    var atlasY = 0

    operator fun get(x: Int, y: Int) = pixels[y * width + x]

    fun mirrored(flip: UvFlip): Patch {
        if (flip == UvFlip.NONE) {
            return this
        }
        val result = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                result[y * width + x] = this[
                    if (flip.mirrorsU) width - 1 - x else x,
                    if (flip.mirrorsV) height - 1 - y else y
                ]
            }
        }
        return Patch(width, height, result)
    }

    override fun equals(other: Any?) = other is Patch &&
            width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode() = (31 * width + height) * 31 + pixels.contentHashCode()
}
