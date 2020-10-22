package li.cil.vox2mc.data

data class BlockFace(
    val from: Int3, val to: Int3,
    val projectedFrom: Int3, val projectedTo: Int3,
    val normal: Direction,
    var cullFace: Direction? = null
) {
    fun toElement(): BlockModel.Element = BlockModel.Element(
        from.toArray(), to.toArray(),
        mapOf(normal.getFaceName() to BlockModel.Face())
    )
}
