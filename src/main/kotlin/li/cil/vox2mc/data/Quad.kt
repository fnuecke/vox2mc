package li.cil.vox2mc.data

import li.cil.vox2mc.vox.MODEL_RESOLUTION

class Quad(
    val normal: Direction,
    val u0: Int, val v0: Int,
    val width: Int, val height: Int,
    val depth: Int,
    val opaque: Set<Int2>
) {
    lateinit var patch: Patch

    var flip = UvFlip.NONE

    var cullable = depth == 0

    val cullface get() = if (cullable) normal else null

    val isOpaque get() = opaque.size == width * height

    fun contains(x: Int, y: Int) = Int2(x, y) in opaque

    fun voxelAt(x: Int, y: Int): Int3 {
        val origin = normal.unprojectVertexToVoxSpace(Int3(u0, v0, depth))
        return normal.faceToVoxelCoordinate(origin + normal.rightInVoxSpace() * x + normal.upInVoxSpace() * y)
    }

    fun toElement(texture: String, atlasWidth: Int, atlasHeight: Int): BlockModel.Element {
        var lo = voxelAt(0, 0)
        var hi = lo
        for (x in intArrayOf(0, width - 1)) {
            for (y in intArrayOf(0, height - 1)) {
                val voxel = voxelAt(x, y)
                lo = min(lo, voxel)
                hi = max(hi, voxel)
            }
        }

        // Voxel space is x = right, y = back, z = up; Minecraft model space is x = right, y = up,
        // z = front. Convert the voxel bounds to vertex bounds, then flatten along the normal.
        val mcLo = Int3(lo.x, lo.z, MODEL_RESOLUTION - lo.y)
        val mcHi = Int3(hi.x + 1, hi.z + 1, MODEL_RESOLUTION - (hi.y + 1))
        var from = min(mcLo, mcHi)
        var to = max(mcLo, mcHi)
        when (normal) {
            Direction.LEFT -> from = from.copy(x = to.x)
            Direction.RIGHT -> to = to.copy(x = from.x)
            Direction.UP -> from = from.copy(y = to.y)
            Direction.DOWN -> to = to.copy(y = from.y)
            Direction.FRONT -> to = to.copy(z = from.z)
            Direction.BACK -> from = from.copy(z = to.z)
        }

        val uMin = patch.atlasX * 16f / atlasWidth
        val vMin = patch.atlasY * 16f / atlasHeight
        val uMax = (patch.atlasX + patch.width) * 16f / atlasWidth
        val vMax = (patch.atlasY + patch.height) * 16f / atlasHeight
        // Bounds running backwards are how Minecraft is told to mirror the texture.
        val uv = arrayOf(
            if (flip.mirrorsU) uMax else uMin,
            if (flip.mirrorsV) vMax else vMin,
            if (flip.mirrorsU) uMin else uMax,
            if (flip.mirrorsV) vMin else vMax
        )

        return BlockModel.Element(
            from.toArray(), to.toArray(),
            mapOf(normal.getFaceName() to BlockModel.Face(texture, cullface?.getFaceName(), uv))
        )
    }
}
