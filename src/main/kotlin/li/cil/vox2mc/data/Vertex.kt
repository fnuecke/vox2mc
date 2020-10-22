package li.cil.vox2mc.data

data class Vertex(val position: Int2, var edgeIn: DirectedEdge? = null, var edgeOut: DirectedEdge? = null) {
    fun prev() = requireNotNull(edgeIn).from
    fun next() = requireNotNull(edgeOut).to

    fun isCollinear(): Boolean {
        val prevPos = prev().position
        val nextPos = next().position
        val delta = nextPos - prevPos
        return delta.x == 0 || delta.y == 0
    }

    fun remove() {
        val edge = DirectedEdge(prev(), next())
        prev().edgeOut = edge
        next().edgeIn = edge
    }

    fun split(): Pair<Vertex, Vertex> {
        val vertexFrom = Vertex(position)
        val edgeFrom = DirectedEdge(edgeIn!!.from, vertexFrom)
        edgeFrom.from.edgeOut = edgeFrom
        vertexFrom.edgeIn = edgeFrom

        val vertexTo = Vertex(position)
        val edgeTo = DirectedEdge(vertexTo, edgeOut!!.to)
        edgeTo.to.edgeIn = edgeTo
        vertexTo.edgeOut = edgeTo

        return Pair(vertexFrom, vertexTo)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Vertex

        if (position != other.position) return false

        return true
    }

    override fun hashCode(): Int {
        return position.hashCode()
    }

    override fun toString() = "li.cil.vox2mc.Vertex(position=$position)"
}