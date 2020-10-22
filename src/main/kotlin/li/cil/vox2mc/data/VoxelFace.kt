package li.cil.vox2mc.data

import li.cil.vox2mc.vox.Voxel

data class VoxelFace(val voxel: Voxel, val direction: Direction) {
    // Face position projected to face plane. Z value is depth in plane to keep layers separated.
    val projectedPosition = direction.projectFaceIndexFromVoxSpace(voxel.position)

    fun fourNeighbors() = sequenceOf(
        projectedPosition + Int3(1, 0, 0),
        projectedPosition - Int3(1, 0, 0),
        projectedPosition + Int3(0, 1, 0),
        projectedPosition - Int3(0, 1, 0)
    )

    fun min() = projectedPosition.toInt2()
    fun max() = projectedPosition.toInt2() + Int2(1, 1)
}