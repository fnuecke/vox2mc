package li.cil.vox2mc.vox

class Chunk(
    val header: ChunkHeader,
    val content: ChunkContent = EmptyContent(),
    val children: List<Chunk> = emptyList()
)
