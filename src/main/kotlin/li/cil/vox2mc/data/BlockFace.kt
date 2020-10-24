package li.cil.vox2mc.data

import kotlin.math.roundToInt

class BlockFace(
    val from: Int3, val to: Int3,
    val projectedFrom: Int3, val projectedTo: Int3,
    val normal: Direction,
    var texture: String? = null,
    var cullFace: Direction? = null,
    var uvs: Array<Float>? = null
) {
    fun depth() = projectedFrom.z

    fun uv0() = uvs?.sliceArray(0..1) ?: arrayOf(projectedFrom.x / 16f, projectedFrom.y / 16f)

    fun size() = abs((projectedTo - projectedFrom).toInt2())

    fun isAdjacentTo(f: BlockFace): Boolean {
        val fourEdges0 = fourEdges()
        val fourEdges1 = f.fourEdges()
        return fourEdges0.any { e0 -> fourEdges1.any { e1 -> areEdgesTouching(e0, e1) } }
    }

    fun occludes(f: BlockFace): Boolean {
        assert(normal == f.normal)
        if (depth() >= f.depth()) {
            return false
        }
        val a0 = projectedFrom
        val a1 = projectedTo
        val b0 = f.projectedFrom
        val b1 = f.projectedTo
        return a0.x < b1.x && a1.x > b0.x &&
                a0.y < b1.y && a1.y > b0.y
    }

    fun toElement(): BlockModel.Element = BlockModel.Element(
        from.toArray(), to.toArray(),
        mapOf(
            normal.getFaceName() to BlockModel.Face(
                texture ?: normal.getFaceName(),
                cullface = cullFace?.getFaceName(),
                uvs?.map { (it * 16).roundToInt() }?.toTypedArray()
            )
        )
    )

    private fun fourEdges(): Array<Pair<Int3, Int3>> {
        val diagonal = to - from
        val (a, b) = diagonal.decompose().filter { it != Int3.ZERO }
        return arrayOf(
            Pair(from, from + a),
            Pair(from + a, to),
            Pair(to, from + b),
            Pair(from + b, from)
        )
    }

    fun areEdgesTouching(e0: Pair<Int3, Int3>, e1: Pair<Int3, Int3>): Boolean {
        val (a0, a1) = e0
        val (b0, b1) = e1
        val dir0 = a1 - a0
        val dir1 = b1 - b0

        assert(dir0.decompose().count { it == Int3.ZERO } == 2) { "first edge not axis aligned" }
        assert(dir1.decompose().count { it == Int3.ZERO } == 2) { "second edge not axis aligned" }

        // Ensure same orientation.
        if (dot(dir0, dir1) == 0) {
            return false
        }

        // Ensure collinear.
        val a2b = b0 - a0
        if (a2b != Int3.ZERO && (a2b.decompose().count { it == Int3.ZERO } != 2 || dot(dir0, a2b) == 0)) {
            return false
        }

        // Ensure same orientation.
        val (b0o, b1o) = if (dot(dir0, dir1) < 0) Pair(b1, b0) else Pair(b0, b1)

        // Convert to one-dimensional interval points.
        val a0i = 0
        val a1i = dot(a1 - a0, Int3.ONE)
        val b0i = dot(b0o - a0, Int3.ONE)
        val b1i = dot(b1o - a0, Int3.ONE)

        return a0i < b1i && a1i > b0i
    }
}
