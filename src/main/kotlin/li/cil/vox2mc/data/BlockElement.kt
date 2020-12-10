package li.cil.vox2mc.data

class BlockElement(
    val from: Int3, val to: Int3,
    val faces: List<BlockFace>
) {
    fun toElement(): BlockModel.Element {
        return BlockModel.Element(
            from.toArray(),
            to.toArray(),
            faces.map {
                val texture = it.texture ?: it.normal.getFaceName()
                val cullface = it.cullface?.getFaceName()
                val uvs = it.uvs?.map { uv -> (uv * 16) }?.toTypedArray()
                it.normal.getFaceName() to BlockModel.Face(texture, cullface, uvs)
            }.toMap()
        )
    }
}
