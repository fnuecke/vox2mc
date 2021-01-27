package li.cil.vox2mc.data

import li.cil.vox2mc.vox.Voxel

class BlockFace(
    val voxels: List<Voxel>,
    val projectedFrom: Int3, val projectedTo: Int3,
    val normal: Direction,
    var texture: String? = null,
    var cullface: Direction? = null,
    var uvs: Array<Float>? = null
) {
    fun depth() = projectedFrom.z

    fun uv0() = uvs?.sliceArray(0..1) ?: arrayOf(projectedFrom.x / 16f, projectedFrom.y / 16f)

    fun size() = abs(projectedTo.toInt2() - projectedFrom.toInt2())

    fun isAdjacentTo(f: BlockFace): Boolean {
        return false // todo
//        val fourEdges0 = fourEdges()
//        val fourEdges1 = f.fourEdges()
//        return fourEdges0.any { e0 -> fourEdges1.any { e1 -> areEdgesTouching(e0, e1) } }
    }
}
