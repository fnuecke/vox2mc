package li.cil.vox2mc.data

class TextureAtlas(val size: Int2, val name: String) {
    constructor(size: Int, name: String) : this(Int2(size, size), name)

    private var data: Data? = null

    fun faces(): Sequence<BlockFace> = data?.let {
        return (it.child0?.faces() ?: emptySequence()) +
                (it.child1?.faces() ?: emptySequence()) +
                it.face
    } ?: emptySequence()

    fun add(face: BlockFace): Boolean {
        val faceSize = face.size()
        if (faceSize.x > size.x || faceSize.y > size.y) {
            return false
        }

        data?.let { data ->
            // See split diagram below, as such we try to pack wider-than-high
            // faces to child1 first because it is wider than child0.
            if (faceSize.x >= faceSize.y) {
                data.child1?.let { if (it.add(face)) return true }
                data.child0?.let { if (it.add(face)) return true }
            } else {
                data.child0?.let { if (it.add(face)) return true }
                data.child1?.let { if (it.add(face)) return true }
            }
            return false
        }

        // We split as such:
        // +------+--------+  -
        // | face | child0 |  |
        // +------+--------+  size.y
        // |     child1    |  |
        // +---------------+  -
        // |--- size.x ----|
        val sizeChild0 = Int2(size.x - faceSize.x, faceSize.y)
        val child0 = if (sizeChild0.x != 0) TextureAtlas(sizeChild0, name) else null
        val sizeChild1 = Int2(size.x, size.y - faceSize.y)
        val child1 = if (sizeChild1.y != 0) TextureAtlas(sizeChild1, name) else null
        data = Data(face, child0, child1)
        return true
    }

    fun applyUVs() {
        applyUVs(0, 0, size.x.toFloat(), size.y.toFloat())
    }

    private fun applyUVs(u0: Int, v0: Int, sizeU: Float, sizeV: Float) {
        data?.let { data ->
            val faceSize = data.face.size()
            data.face.uvs = arrayOf(
                u0 / sizeU, v0 / sizeV,
                (u0 + faceSize.x) / sizeU, (v0 + faceSize.y) / sizeV
            )
            data.child0?.applyUVs(u0 + faceSize.x, v0, sizeU, sizeV)
            data.child1?.applyUVs(u0, v0 + faceSize.y, sizeU, sizeV)
        }
    }

    private data class Data(val face: BlockFace, val child0: TextureAtlas?, val child1: TextureAtlas?)
}