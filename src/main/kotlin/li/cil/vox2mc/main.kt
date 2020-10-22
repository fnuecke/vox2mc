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

                require(vertexFrom.edgeOut == null)
                require(vertexTo.edgeIn == null)

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

            // Remove collinear vertices.
            val allVertices = verticesByProjectedPosition.values.filter { vertex ->
                val prevPos = vertex.prev().position
                val nextPos = vertex.next().position
                val delta = nextPos - prevPos
                if (delta.x == 0 || delta.y == 0) {
                    val edge = DirectedEdge(vertex.prev(), vertex.next())
                    vertex.prev().edgeOut = edge
                    vertex.next().edgeIn = edge
                    false
                } else {
                    true
                }
            }

            // TODO Some index structure for faster intersection tests.
            val gridEdges = allVertices.flatMap { sequenceOf(it.edgeIn, it.edgeOut) }.requireNoNulls().distinct()
            fun intersectRayGridEdges(start: Int2, direction: Int2) =
                gridEdges.mapNotNull { intersectRayEdge(start, direction, it.from.position, it.to.position) }
                    .minByOrNull { it.sqDistanceTo(start) }

            // Separate curves.
            val curves = mutableListOf<MutableSet<Vertex>>()
            allVertices.forEach { vertex ->
                if (curves.none { it.contains(vertex) }) {
                    val set = mutableSetOf(vertex)
                    var next = vertex.next()
                    while (set.add(next)) {
                        next = next.next()
                    }
                    curves.add(set)
                }
            }

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
                ).filterNotNull()
                    .mapNotNull { if (concaveVertexPositions.contains(it)) Edge(curr, it) else null }
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
            val groupedIntersections = mutableListOf<Map<Edge, Set<Edge>>>()
            allIntersections.forEach { entry ->
                if (groupedIntersections.none { it.containsKey(entry.key) }) {
                    val group = mutableMapOf(entry.key to entry.value)
                    fun addRecursively(s: Set<Edge>) {
                        s.forEach {
                            if (!group.containsKey(it)) {
                                val intersections = allIntersections.getValue(it)
                                group[it] = intersections
                                addRecursively(intersections)
                            }
                        }
                    }
                    addRecursively(entry.value)
                    groupedIntersections.add(group)
                }
            }

            val selectedPartitions = groupedIntersections.flatMap { intersections ->
                val diagonals = intersections.keys
                val (horizontals, verticals) = diagonals.partition { it.a.y == it.b.y }

                val maximumMatching = hopcroftKarp(horizontals.toSet(), verticals.toSet(), intersections)
                val matchedDiagonals = (maximumMatching.keys + maximumMatching.values).toSet()
                val unmatchedDiagonals = diagonals.filter { !matchedDiagonals.contains(it) }
                val anyUnmatchedDiagonal = unmatchedDiagonals.first() // Could be any, we just pick the first one.

                val partition = diagonals.map { dijkstra(it, anyUnmatchedDiagonal, intersections) to it }
                    .groupBy { it.first }.mapValues { v -> v.value.map { it.second } }

                // Select partitions with even path-lengths.
                partition.keys.filter { (it % 2) == 0 }.flatMap { partition.getValue(it) }
            }

            // Assert diagonals from selected partitions do not intersect.
            selectedPartitions.forEachIndexed { index, e0 ->
                selectedPartitions.drop(index + 1)
                    .forEach { e1 -> assert(!intersectPerpendicularEdges(e0.a, e0.b, e1.a, e1.b)) }
            }

            // Find all bad vertices.
            val badVertices = concaveVertices.filter { v ->
                selectedPartitions.none { v.position != it.a && v.position != it.b }
            }


            // Connect each bad vertex to closest existing edge.
            val extraEdges = selectedPartitions.toMutableList()
            fun intersectRayAllEdges(start: Int2, direction: Int2) =
                (gridEdges.mapNotNull { intersectRayEdge(start, direction, it.from.position, it.to.position) } +
                        extraEdges.mapNotNull { intersectRayEdge(start, direction, it.a, it.b) })
                    .minByOrNull { it.sqDistanceTo(start) }

            badVertices.forEach { vertex ->
                val curr = vertex.position
                val prev = vertex.prev().position

                val direction0 = (curr - prev).normalizeAxisAligned()
                val direction1 = direction0.rightHandNormal()

                val intersect0 = requireNotNull(intersectRayAllEdges(curr, direction0))
                val intersect1 = requireNotNull(intersectRayAllEdges(curr, direction1))

                val sqDistance0 = curr.sqDistanceTo(intersect0)
                val sqDistance1 = curr.sqDistanceTo(intersect1)
                if (sqDistance0 < sqDistance1) {
                    extraEdges.add(Edge(curr, intersect0))
                } else {
                    extraEdges.add(Edge(curr, intersect1))
                }
            }

            // While edges are left
            // - find edge that is only part of one rectangle.
            // - generate quad from this rectangle.
            // - remove edge and all other edges of the rect that were only part of that rect
            // TODO

//            val groupedCurves = curves.groupBy { it.first().isHole() }
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

    fun signedArea(): Int {
        var curr = this
        var sum = 0
        do {
            val next = curr.next()
            sum += (next.position.x - curr.position.x) * (next.position.y + curr.position.y)
            curr = next
        } while (curr != this)
        return sum
    }

    fun isHole() = signedArea() < 0

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

private data class DirectedEdge(val from: Vertex, val to: Vertex)
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
    val projectedPosition = project(voxel.position, direction)

    fun fourNeighbors() = sequenceOf(
        projectedPosition + Int3(1, 0, 0),
        projectedPosition - Int3(1, 0, 0),
        projectedPosition + Int3(0, 1, 0),
        projectedPosition - Int3(0, 1, 0)
    )

    fun min() = projectedPosition.toInt2()
    fun max() = projectedPosition.toInt2() + Int2(1, 1)

    private fun project(position: Int3, direction: Direction): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - 1 - i
        return when (direction) {
            Direction.POSX -> Int3(position.y, position.z, invert(position.x))
            Direction.NEGX -> Int3(invert(position.y), position.z, position.x)
            Direction.POSY -> Int3(invert(position.x), position.z, invert(position.y))
            Direction.NEGY -> Int3(position.x, position.z, position.y)
            Direction.POSZ -> Int3(position.x, position.y, invert(position.z))
            Direction.NEGZ -> Int3(position.x, invert(position.y), position.z)
        }
    }
}

// Intersects two perpendicular axis aligned edges.
// Will not intersect if either edge is only touched at an end point.
// Will not intersect collinear edges.
private fun intersectPerpendicularEdges(a0: Int2, a1: Int2, b0: Int2, b1: Int2): Boolean {
    if (dot(a1 - a0, b1 - b0) != 0) {
        return false
    }

    return fullyContains(a0, a1, b0) &&
            fullyContains(a0, a1, b1) &&
            fullyContains(b0, b1, a0) &&
            fullyContains(b0, b1, a1)
}

private fun fullyContains(e0: Int2, e1: Int2, p: Int2): Boolean {
    val t = dot(project(p, e0, e1) - e0, e1 - e0)
    return t > 0 && t < dot(e1 - e0, e1 - e0)
}

private fun project(p: Int2, e0: Int2, e1: Int2): Int2 {
    val v0 = e1 - e0
    val v1 = p - e0
    return e0 + v0 * dot(v0, v1) / dot(v0, v0)
}

// Intersect axis aligned ray with axis aligned edge.
// Will not intersect with edge a ray starts on.
// Will intersect with collinear edges.
private fun intersectRayEdge(start: Int2, direction: Int2, e0: Int2, e1: Int2): Int2? {
    val v0 = start - e0
    val v1 = e1 - e0
    val v2 = direction.leftHandNormal()

    val det = dot(v1, v2)
    if (det == 0) {
        if (cross(v1, v0) == 0) { // collinear
            val t0 = dot(e0 - start, direction)
            val t1 = dot(e1 - start, direction)
            assert(t0.sign == t1.sign) { "start point inside line" }
            val t = min(t0, t1)
            return if (t <= 0) null else start + direction * t
        } else { // parallel
            return null
        }
    } else { // perpendicular
        assert(v1.x == 0 || v1.y == 0)
        val elen = abs(v1.x + v1.y)
        val t0 = cross(v1, v0) / det
        val t1 = dot(v0, v2) * elen / det
        return if (t0 <= 0 || t1 < 0 || t1 > elen) null else start + direction * t0
    }
}

fun <T> dijkstra(source: T, target: T, adjacent: Map<T, Set<T>>): Int {
    val q = adjacent.keys.toMutableSet()
    val dist = q.map { it to Int.MAX_VALUE }.toMap().toMutableMap()
    val prev = mutableMapOf<T, T>()
    dist[source] = 0

    while (q.isNotEmpty()) {
        val u = requireNotNull(q.mapNotNull { it to dist.getValue(it) }.minByOrNull { it.second }?.first)

        if (u == target) {
            return dist.getValue(u)
        }

        q.remove(u)
        adjacent[u]?.forEach { v ->
            if (!dist.containsKey(v) || dist.getValue(v) >= dist.getValue(u)) {
                dist[v] = dist.getValue(u) + 1
                prev[v] = u
            }
        }
    }

    return Int.MAX_VALUE
}

// https://en.wikipedia.org/wiki/Hopcroft%E2%80%93Karp_algorithm
private fun <T> hopcroftKarp(us: Set<T>, vs: Set<T>, adjacent: Map<T, Set<T>>): Map<T, T> {
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
