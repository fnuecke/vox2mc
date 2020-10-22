package li.cil.vox2mc.vox

data class MaterialProperty(val property: Int, val value: Float) {
    companion object {
        const val PLASTIC = 1 shl 0
        const val ROUGHNESS = 1 shl 1
        const val SPECULAR = 1 shl 2
        const val IOR = 1 shl 3
        const val ATTENUATION = 1 shl 4
        const val POWER = 1 shl 5
        const val GLOW = 1 shl 6
        const val IS_TOTAL_POWER = 1 shl 7
    }
}
