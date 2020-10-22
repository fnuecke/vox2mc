package li.cil.vox2mc.vox

data class ChunkHeader(val id: String, val contentSize: Int, val childChunksSize: Int) {
    companion object {
        const val VOX_HEADER_ID = "VOX "
        const val MAIN_CHUNK_ID = "MAIN"
        const val PACK_CHUNK_ID = "PACK"
        const val SIZE_CHUNK_ID = "SIZE"
        const val XYZI_CHUNK_ID = "XYZI"
        const val RGBA_CHUNK_ID = "RGBA"
        const val MATT_CHUNK_ID = "MATT"
    }
}
