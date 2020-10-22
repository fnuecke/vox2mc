package li.cil.vox2mc

import li.cil.vox2mc.data.*
import li.cil.vox2mc.vox.*
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: vox2mc file.vox ...")
    }

    for (arg in args) {
        val file = File(arg)
        if (!file.exists()) {
            println("File [%s] not found, skipping.".format(arg))
            continue
        }

        print("Loading file [%s]...".format(arg))

        val vox = VoxLoader.loadVox(file)

        println(" done.")

        print("Collecting voxels from loaded file...")

        val paletteChunk = vox.children.find { chunk -> chunk.header.id == ChunkHeader.RGBA_CHUNK_ID }
        val palette = if (paletteChunk != null) {
            require(paletteChunk.content is PaletteContent)
            paletteChunk.content.colors
        } else PaletteContent.DEFAULT_PALETTE

        val voxels = vox.children.flatMapIndexed { index, chunk ->
            if (chunk.header.id == ChunkHeader.SIZE_CHUNK_ID) {
                require(chunk.content is SizeContent)
                val size = chunk.content.size
                require(size.x == MODEL_RESOLUTION && size.y == MODEL_RESOLUTION && size.z == MODEL_RESOLUTION)
                val data = vox.children[index + 1]
                require(data.header.id == ChunkHeader.XYZI_CHUNK_ID)
                require(data.content is ModelContent)
                val voxels = data.content.voxels
                voxels.toList()
            } else {
                emptyList()
            }
        }.map { v -> v.position to v }.toMap()

        println(" done. Got %d voxel(s).".format(voxels.size))

        print("Extracting faces...")

        val allFaces = voxels.values.flatMap { voxel ->
            Direction.values().filterNot {
                voxels.containsKey(voxel.position + it.normal)
            }.map { VoxelFace(voxel, it) }
        }

        println(" done. Got %d face(s).".format(allFaces.size))

        print("Grouping adjacent faces...")

        val groupedFaces = allFaces.groupBy { it.direction }.values.flatMap { faces ->
            val facesByProjectedPosition = faces.map { it.projectedPosition to it }.toMap()
            val seen = mutableSetOf<VoxelFace>()
            fun collectFacesRecursively(face: VoxelFace): Sequence<VoxelFace> {
                return if (seen.add(face)) {
                    face.fourNeighbors()
                        .mapNotNull { facesByProjectedPosition[it] }
                        .flatMap { collectFacesRecursively(it) } + face
                } else {
                    emptySequence()
                }
            }
            faces.map { collectFacesRecursively(it).toList() }
        }.filterNot { it.isEmpty() }

        println(" done. Got %d grouped face(s).".format(groupedFaces.size))

        print("Building convex quads from grouped faces...")

        groupedFaces.forEach { faceGroup ->
            // NB: the depth (z component) of all face positions in one group is equal, so we can safely drop it.
            val facesByProjectedPosition = faceGroup.map { it.projectedPosition.toInt2() to it }.toMap()
            // Generate outlines from face grid. Outer edges are encoded with clockwise winding,
            // inner edges (holes) are encoded with counter-clockwise winding.
            val verticesByProjectedPosition = mutableMapOf<Int2, Vertex>()
            fun addEdge(from: Int2, to: Int2) {
                val vertexFrom = verticesByProjectedPosition.computeIfAbsent(from) { Vertex(it) }
                val vertexTo = verticesByProjectedPosition.computeIfAbsent(to) { Vertex(it) }

                assert(vertexFrom.edgeOut == null)
                assert(vertexTo.edgeIn == null)

                val edge = DirectedEdge(vertexFrom, vertexTo)
                vertexFrom.edgeOut = edge
                vertexTo.edgeIn = edge
            }
            faceGroup.forEach { face ->
                face.fourNeighbors().forEach { neighborPosition ->
                    if (!facesByProjectedPosition.containsKey(neighborPosition.toInt2())) {
                        val delta = (neighborPosition - face.projectedPosition).toInt2()
                        val vertexMin = clamp(face.min() + delta, face.min(), face.max())
                        val vertexMax = clamp(face.max() + delta, face.min(), face.max())
                        // When pushing first min vertex right or max vertex down we need to flip the edge
                        // vertices to maintain clockwise winding for outer edges and counter-clockwise
                        // winding for inner edges (holes).
                        if (delta.x > 0 || delta.y < 0) {
                            addEdge(vertexMax, vertexMin)
                        } else {
                            addEdge(vertexMin, vertexMax)
                        }
                    }
                }
            }

            val (collinearVertices, allVertices) = verticesByProjectedPosition.values.partition { it.isCollinear() }
            collinearVertices.forEach { it.remove() }
            verticesByProjectedPosition -= collinearVertices.map { it.position }

            // TODO Some index structure for faster intersection tests.
            val allEdges = allVertices.flatMap { sequenceOf(it.edgeIn, it.edgeOut) }
                .requireNoNulls().distinct().toMutableSet()

            fun intersectRayGridEdges(start: Int2, direction: Int2) =
                allEdges.mapNotNull { edge ->
                    intersectRayEdge(start, direction, edge.from.position, edge.to.position)?.let { it to edge }
                }.minByOrNull { it.first.sqDistanceTo(start) }

            // Find smallest number of rectangles filling the outline graph. Based on arXiv:0908.3916: David Eppstein,
            // Graph-Theoretic Solutions to Computational Geometry Problems, specifically chapter 3, Partition into
            // rectangles. https://arxiv.org/abs/0908.3916

            // Grab all concave vertices.
            val concaveVertices = allVertices.filter { vertex ->
                val curr = vertex.position
                val prev = vertex.prev().position
                val next = vertex.next().position

                val toCurr = curr - prev
                val toNext = next - curr

                // Since winding order is opposite we can do the same normal check for outer edges and hole edges.
                dot(toCurr.leftHandNormal(), toNext) > 0
            }
            val concaveVertexPositions = concaveVertices.map { it.position }.toSet()

            // Generate all possible good diagonals.
            val goodDiagonals = concaveVertices.flatMap { vertex ->
                val curr = vertex.position
                val prev = vertex.prev().position

                val direction0 = (curr - prev).normalizeAxisAligned()
                val direction1 = direction0.rightHandNormal()

                sequenceOf(
                    intersectRayGridEdges(curr, direction0),
                    intersectRayGridEdges(curr, direction1)
                ).filterNotNull().map { it.first }.mapNotNull {
                    if (concaveVertexPositions.contains(it)) Edge(curr, it) else null
                }
            }.distinct()

            // Find maximum set of non-intersecting good diagonals:
            // Build bipartite intersection graph.
            val horizontalDiagonals = goodDiagonals.filter { it.a.y == it.b.y }.toSet()
            val verticalDiagonals = goodDiagonals.filter { it.a.x == it.b.x }.toSet()
            val allIntersections = horizontalDiagonals.flatMap { u ->
                verticalDiagonals.flatMap { v ->
                    if (intersectPerpendicularEdges(u.a, u.b, v.a, v.b)) sequenceOf(u to v, v to u) else emptySequence()
                }
            }.groupBy { it.first }.mapValues { e -> e.value.map { it.second }.toSet() }

            val maximumMatching = hopcroftKarp(horizontalDiagonals, allIntersections)

            val matchedAdjacency = maximumMatching + maximumMatching.map { it.value to it.key }
            assert(matchedAdjacency.size == maximumMatching.size * 2)
            val unmatchedAdjacency = allIntersections
                .mapValues { (key, value) -> value.filter { matchedAdjacency[it] != key } }
                .filterValues { it.isNotEmpty() }

            val minDistances = goodDiagonals.map { it to Integer.MAX_VALUE }.toMap().toMutableMap()
            var traverseUnmatched: (Edge, Int) -> Unit = fun(_: Edge, _: Int) {}
            fun traverseMatched(e: Edge, depth: Int) {
                if (minDistances[e]!! <= depth) {
                    return
                }
                minDistances[e] = depth
                matchedAdjacency[e]?.let {
                    traverseUnmatched(it, depth + 1)
                }
            }
            traverseUnmatched = fun(e: Edge, depth: Int) {
                if (minDistances[e]!! <= depth) {
                    return
                }
                minDistances[e] = depth
                unmatchedAdjacency[e]?.forEach {
                    traverseMatched(it, depth + 1)
                }
            }
            val unmatchedDiagonals = goodDiagonals - matchedAdjacency.keys
            unmatchedDiagonals.forEach { traverseUnmatched(it, 0) }
            val selectedPartitions = minDistances.filterValues { it % 2 == 0 }.keys

            val numMatchings = maximumMatching.size
            val numDiagonals = horizontalDiagonals.size + verticalDiagonals.size
            val expectedMaximumIntersectionSetSize = numDiagonals - numMatchings
            assert(selectedPartitions.size == expectedMaximumIntersectionSetSize)

            // Assert diagonals from selected partitions do not intersect.
            selectedPartitions.forEachIndexed { index, e0 ->
                selectedPartitions.drop(index + 1)
                    .forEach { e1 -> assert(!intersectPerpendicularEdges(e0.a, e0.b, e1.a, e1.b)) }
            }

            fun removeVertex(v: Vertex) {
                v.remove()
                allEdges.remove(v.edgeIn)
                allEdges.remove(v.edgeOut)
                assert(v.edgeIn!!.from.edgeOut!! == v.edgeOut!!.to.edgeIn!!)
                allEdges.add(v.edgeIn!!.from.edgeOut!!)
            }

            fun splitVertex(v: Vertex): Pair<Vertex, Vertex> {
                val (vertexFrom, vertexTo) = v.split()
                allEdges.remove(v.edgeIn)
                allEdges.remove(v.edgeOut)
                allEdges.add(v.edgeIn!!.from.edgeOut!!)
                allEdges.add(v.edgeOut!!.to.edgeIn!!)
                return Pair(vertexFrom, vertexTo)
            }

            fun splitEdge(e: DirectedEdge, p: Int2): Pair<Vertex, Vertex> {
                val (vertexFrom, vertexTo) = e.split(p)
                allEdges.remove(e)
                allEdges.add(vertexFrom.edgeIn!!)
                allEdges.add(vertexTo.edgeOut!!)
                return Pair(vertexFrom, vertexTo)
            }

            // Insert selected diagonals as edges into the graph.
            selectedPartitions.forEach { edge ->
                val vertex0 = verticesByProjectedPosition[edge.a]!!
                val (vertexFrom0, vertexTo0) = splitVertex(vertex0)
                val vertex1 = verticesByProjectedPosition[edge.b]!!
                val (vertexFrom1, vertexTo1) = splitVertex(vertex1)

                val edge0 = DirectedEdge(vertexFrom0, vertexTo1)
                vertexFrom0.edgeOut = edge0
                vertexTo1.edgeIn = edge0
                allEdges.add(edge0)

                val edge1 = DirectedEdge(vertexFrom1, vertexTo0)
                vertexFrom1.edgeOut = edge1
                vertexTo0.edgeIn = edge1
                allEdges.add(edge1)

                sequenceOf(vertexFrom0, vertexTo0, vertexFrom1, vertexTo1)
                    .filter { it.isCollinear() }.forEach { removeVertex(it) }
            }

            // Find all bad vertices.
            val badVertices = concaveVertices.filter { v ->
                selectedPartitions.none { v.position == it.a || v.position == it.b }
            }

            // Connect each bad vertex to closest existing edge.
            badVertices.forEach { vertex ->
                val curr = vertex.position
                val prev = vertex.prev().position

                val direction0 = (curr - prev).normalizeAxisAligned()
                val direction1 = direction0.rightHandNormal()

                val (hitPosition0, hitEdge0) = intersectRayGridEdges(curr, direction0)!!
                val (hitPosition1, hitEdge1) = intersectRayGridEdges(curr, direction1)!!

                val sqDistance0 = curr.sqDistanceTo(hitPosition0)
                val sqDistance1 = curr.sqDistanceTo(hitPosition1)
                val (vertexFrom0, vertexTo0) = if (sqDistance0 < sqDistance1) {
                    splitEdge(hitEdge0, hitPosition0)
                } else {
                    splitEdge(hitEdge1, hitPosition1)
                }

                val (vertexFrom1, vertexTo1) = splitVertex(vertex)

                val edge0 = DirectedEdge(vertexFrom0, vertexTo1)
                vertexFrom0.edgeOut = edge0
                vertexTo1.edgeIn = edge0
                allEdges.add(edge0)

                val edge1 = DirectedEdge(vertexFrom1, vertexTo0)
                vertexFrom1.edgeOut = edge1
                vertexTo0.edgeIn = edge1
                allEdges.add(edge1)

                sequenceOf(vertexFrom0, vertexTo0, vertexFrom1, vertexTo1)
                    .filter { it.isCollinear() }.forEach { removeVertex(it) }
            }

            // Separate rectangles.
            val rectangleByEdge = mutableMapOf<DirectedEdge, List<Vertex>>()
            allEdges.forEach { edge ->
                val rectangle = mutableListOf<Vertex>()
                var curr = edge
                do {
                    rectangleByEdge[curr] = rectangle
                    rectangle.add(curr.to)
                    curr = curr.to.edgeOut!!
                } while (curr != edge)
            }

            val rectangles = rectangleByEdge.values.distinct()
            assert(rectangles.all { it.size == 4 })

            println("Number of rectangles is %d".format(rectangles.size))
        }

        println(" done. Got %d quads.")

        print("Saving textures and UVs...")

        // TODO

        println(" done. Got %d primary and %d UV mapped texture(s).")

        print("Grouping faces by side...")

        // TODO

        println(" done. Got %d element(s).")

        print("Saving block model...")

        // TODO

        println(" done.")
    }
}

private data class Vertex(val position: Int2, var edgeIn: DirectedEdge? = null, var edgeOut: DirectedEdge? = null) {
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

private data class DirectedEdge(val from: Vertex, val to: Vertex) {
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

private data class Edge(val a: Int2, val b: Int2) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Edge

        if (a == other.a && b == other.b) return true
        if (a == other.b && b == other.a) return true

        return false
    }

    override fun hashCode() = a.hashCode() xor b.hashCode()
}

private data class VoxelFace(val voxel: Voxel, val direction: Direction) {
    // Face position projected to face plane. Z value is depth in plane to keep layers separated.
    val projectedPosition = direction.project(voxel.position)

    fun fourNeighbors() = sequenceOf(
        projectedPosition + Int3(1, 0, 0),
        projectedPosition - Int3(1, 0, 0),
        projectedPosition + Int3(0, 1, 0),
        projectedPosition - Int3(0, 1, 0)
    )

    fun min() = projectedPosition.toInt2()
    fun max() = projectedPosition.toInt2() + Int2(1, 1)
}

// Intersects two perpendicular axis aligned edges.
// Will not intersect collinear edges.
private fun intersectPerpendicularEdges(a0: Int2, a1: Int2, b0: Int2, b1: Int2): Boolean {
    if (dot(a1 - a0, b1 - b0) != 0) {
        return false
    }

    fun intersects(e0: Int2, e1: Int2, p: Int2): Boolean {
        val t = dot(project(p, e0, e1) - e0, e1 - e0)
        return t >= 0 && t <= dot(e1 - e0, e1 - e0)
    }

    return intersects(a0, a1, b0) &&
            intersects(a0, a1, b1) &&
            intersects(b0, b1, a0) &&
            intersects(b0, b1, a1)
}

private fun project(p: Int2, e0: Int2, e1: Int2): Int2 {
    val v0 = e1 - e0
    val v1 = p - e0
    return e0 + v0 * dot(v0, v1) / dot(v0, v0)
}

// Intersect axis aligned ray with axis aligned edge.
// Will not intersect with edge a ray starts on.
// Will not intersect edges that go counter-clockwise from ray's view.
// Will intersect with collinear edges.
private fun intersectRayEdge(start: Int2, direction: Int2, e0: Int2, e1: Int2): Int2? {
    val v0 = start - e0
    val v1 = e1 - e0
    val v2 = direction.leftHandNormal()

    val det = dot(v1, v2)
    if (det == 0) {
        return if (cross(v1, v0) == 0) { // collinear
            val t0 = dot(e0 - start, direction)
            val t1 = dot(e1 - start, direction)
            assert(t0.sign == t1.sign) { "start point inside line" }
            val t = min(t0, t1)
            if (t <= 0) null else start + direction * t
        } else { // parallel
            null
        }
    } else { // perpendicular
        assert(v1.x == 0 || v1.y == 0)
        if (dot(v2, v1) >= 0) return null // Only return clockwise edges.
        val elen = abs(v1.x + v1.y)
        val t0 = cross(v1, v0) / det
        val t1 = dot(v0, v2) * elen / det
        return if (t0 <= 0 || t1 < 0 || t1 > elen) null else start + direction * t0
    }
}

// https://en.wikipedia.org/wiki/Hopcroft%E2%80%93Karp_algorithm
private fun <T> hopcroftKarp(us: Set<T>, adjacent: Map<T, Set<T>>): Map<T, T> {
    val pairU = mutableMapOf<T, T>()
    val pairV = mutableMapOf<T, T>()
    val dist = mutableMapOf<T?, Float>()

    // Find shortest paths from unpaired us to unpaired vs.
    fun bfs(): Boolean {
        val (paired, unpaired) = us.partition { pairU.containsKey(it) }
        paired.forEach { dist[it] = Float.POSITIVE_INFINITY }
        unpaired.forEach { dist[it] = 0f }

        val queue: MutableList<T?> = unpaired.toMutableList()
        dist[null] = Float.POSITIVE_INFINITY // best distance to unmapped v
        while (queue.isNotEmpty()) {
            val u = queue.removeAt(0)
            // dist of u to unmapped v < best dist and u != null check
            if (dist.getValue(u) < dist.getValue(null)) {
                assert(u != null)
                adjacent[u]?.forEach { v ->
                    if (dist.getValue(pairV[v]).isInfinite()) { // paired to paired u or unpaired
                        dist[pairV[v]] = dist.getValue(u) + 1
                        queue.add(pairV[v])
                    }
                }
            }
        }
        return !dist.getValue(null).isInfinite() // best distance != infinity -> found new best path
    }

    fun dfs(u: T?): Boolean {
        if (u != null) {
            adjacent[u]?.forEach { v ->
                if (dist.getValue(pairV[v]) == dist.getValue(u) + 1) {
                    if (dfs(pairV[v])) {
                        pairV[v] = u
                        pairU[u] = v
                        return true
                    }
                }
            }
            dist[u] = Float.POSITIVE_INFINITY
            return false
        }
        return true
    }

    var matching = 0
    while (bfs()) {
        us.forEach { u ->
            if (pairU[u] == null) {
                if (dfs(u)) {
                    matching++
                }
            }
        }
    }

    assert(pairU.size == matching)
    return pairU
}
