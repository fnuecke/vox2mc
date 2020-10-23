package li.cil.vox2mc.vox

import com.google.common.io.LittleEndianDataInputStream
import li.cil.vox2mc.data.Int3
import java.io.File

const val VOX_VERSION = 150
const val MODEL_RESOLUTION = 16

// https://github.com/ephtracy/voxel-model/blob/master/MagicaVoxel-file-format-vox.txt
@Suppress("UnstableApiUsage")
object VoxLoader {
    fun loadVox(f: File): Chunk {
        val chunk = LittleEndianDataInputStream(f.inputStream()).use {
            readHeader(it)
            readChunk(it)
        }

        require(chunk != null)
        require(chunk.header.id == ChunkHeader.MAIN_CHUNK_ID)

        return chunk
    }

    private fun readHeader(s: LittleEndianDataInputStream) {
        require(readChunkId(s) == ChunkHeader.VOX_HEADER_ID)
        require(s.readInt() == VOX_VERSION)
    }

    private fun readChunkHeader(s: LittleEndianDataInputStream): ChunkHeader? {
        val chunkId = readChunkId(s) ?: return null
        return ChunkHeader(chunkId, s.readInt(), s.readInt())
    }

    private fun readChunk(s: LittleEndianDataInputStream): Chunk? {
        while (true) {
            val header = readChunkHeader(s) ?: return null
            when (header.id) {
                ChunkHeader.MAIN_CHUNK_ID -> {
                    val childChunks = mutableListOf<Chunk>()
                    while (true) {
                        val chunk = readChunk(s)
                        if (chunk != null) {
                            childChunks.add(chunk)
                        } else {
                            return Chunk(header, children = childChunks)
                        }
                    }
                }
                ChunkHeader.PACK_CHUNK_ID -> {
                    return Chunk(header, DataContent(s.readInt()))
                }
                ChunkHeader.SIZE_CHUNK_ID -> {
                    val sizeX = s.readInt()
                    val sizeY = s.readInt()
                    val sizeZ = s.readInt()
                    return Chunk(header, SizeContent(Int3(sizeX, sizeY, sizeZ)))
                }
                ChunkHeader.XYZI_CHUNK_ID -> {
                    val numVoxels = s.readInt()
                    val voxels = mutableListOf<Voxel>()
                    for (i in 1..numVoxels) {
                        val x = s.readUnsignedByte()
                        val y = s.readUnsignedByte()
                        val z = s.readUnsignedByte()
                        val colorIndex = s.readUnsignedByte()
                        voxels.add(Voxel(Int3(x, y, z), colorIndex))
                    }
                    return Chunk(header, ModelContent(voxels.toTypedArray()))
                }
                ChunkHeader.RGBA_CHUNK_ID -> {
                    return Chunk(header, PaletteContent(arrayOf(0) + Array(256) { s.readInt() }))
                }
                ChunkHeader.MATT_CHUNK_ID -> {
                    val id = s.readInt()
                    val materialType = s.readInt()
                    val materialWeight = s.readFloat()
                    val properties = s.readInt()

                    val propertyList = mutableListOf<MaterialProperty>()
                    if (properties and MaterialProperty.PLASTIC != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.PLASTIC, s.readFloat()))
                    }
                    if (properties and MaterialProperty.ROUGHNESS != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.ROUGHNESS, s.readFloat()))
                    }
                    if (properties and MaterialProperty.SPECULAR != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.SPECULAR, s.readFloat()))
                    }
                    if (properties and MaterialProperty.IOR != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.IOR, s.readFloat()))
                    }
                    if (properties and MaterialProperty.ATTENUATION != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.ATTENUATION, s.readFloat()))
                    }
                    if (properties and MaterialProperty.POWER != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.POWER, s.readFloat()))
                    }
                    if (properties and MaterialProperty.GLOW != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.GLOW, s.readFloat()))
                    }
                    if (properties and MaterialProperty.IS_TOTAL_POWER != 0) {
                        propertyList.add(MaterialProperty(MaterialProperty.IS_TOTAL_POWER, 1f))
                    }

                    return Chunk(header, MaterialContent(id, materialType, materialWeight, propertyList.toTypedArray()))
                }
                else -> {
                    // Skip unknown chunk types.
                    s.skipBytes(header.contentSize + header.childChunksSize)
                    continue
                }
            }
        }
    }

    private fun readChunkId(s: LittleEndianDataInputStream): String? {
        val bytes = s.readNBytes(4)
        return if (bytes.isEmpty()) null else String(bytes)
    }
}