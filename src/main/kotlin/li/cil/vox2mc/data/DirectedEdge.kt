package li.cil.vox2mc.data

data class DirectedEdge(val from: Vertex, val to: Vertex) {
    fun split(v: Int2): Pair<Vertex, Vertex> {
        val vertexFrom = Vertex(v)
        val edgeFrom = DirectedEdge(from, vertexFrom)
        from.edgeOut = edgeFrom
        vertexFrom.edgeIn = edgeFrom

        val vertexTo = Vertex(v)
        val edgeTo = DirectedEdge(vertexTo, to)
        to.edgeIn = edgeTo
        vertexTo.edgeOut = edgeTo

        return Pair(vertexFrom, vertexTo)
    }
}