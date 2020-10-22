package li.cil.vox2mc.vox

class MaterialContent(val id: Int, val type: Int, val weight: Float, properties: Array<MaterialProperty>) :
    ChunkContent {
    companion object {
        const val MATT_TYPE_DIFFUSE = 0
        const val MATT_TYPE_METAL = 1
        const val MATT_TYPE_GLASS = 2
        const val MATT_TYPE_EMISSIVE = 3
    }
}
