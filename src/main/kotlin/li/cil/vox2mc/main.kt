package li.cil.vox2mc

import li.cil.vox2mc.algorithm.hopcroftKarp
import li.cil.vox2mc.algorithm.intersectPerpendicularEdges
import li.cil.vox2mc.algorithm.intersectRayEdge
import li.cil.vox2mc.data.*
import li.cil.vox2mc.vox.*
import java.io.File

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: vox2mc file.vox ...")
        return
    }

    val quiet = args.any { it == "-q" || it == "--quiet" }

    fun log(msg: String) {
        if (!quiet) print(msg)
    }

    fun logln(msg: String) {
        if (!quiet) println(msg)
    }

    for (arg in args) {
        if (arg == "-q" || arg == "--quiet") {
            continue
        }

        val file = File(arg)
        if (!file.exists()) {
            logln("File [%s] not found, skipping.".format(arg))
            continue
        }

        log("Loading file [%s]...".format(arg))

        val vox = VoxLoader.loadVox(file)

        logln(" done.")

        log("Collecting voxels from loaded file...")

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

        logln(" done. Got %d voxel(s).".format(voxels.size))

        log("Extracting faces...")

        val allFaces = voxels.values.flatMap { voxel ->
            Direction.values().filterNot {
                voxels.containsKey(voxel.position + it.normal)
            }.map { VoxelFace(voxel, it) }
        }

        logln(" done. Got %d face(s).".format(allFaces.size))

        log("Grouping adjacent faces...")

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

        logln(" done. Got %d grouped face(s).".format(groupedFaces.size))

        log("Building convex quads from grouped faces...")

        val blockFaces = groupedFaces.flatMap { faceGroup ->
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

            // Find shortest alternating paths.
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

            // Verify.
            val numMatchings = maximumMatching.size
            val numDiagonals = horizontalDiagonals.size + verticalDiagonals.size
            val expectedMaximumIntersectionSetSize = numDiagonals - numMatchings
            assert(selectedPartitions.size == expectedMaximumIntersectionSetSize)

            // Assert diagonals from selected partitions do not intersect.
            selectedPartitions.forEachIndexed { index, e0 ->
                selectedPartitions.drop(index + 1)
                    .forEach { e1 -> assert(!intersectPerpendicularEdges(e0.a, e0.b, e1.a, e1.b)) }
            }

            // Need to correctly update allEdges set when inserting edges for future intersection tests.
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

            // Separate rectangles, it's all we can have left at this point.
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

            // Move back to 3D space.
            rectangles.map { rectangle ->
                val faceNormal = faceGroup[0].direction
                val z = faceGroup[0].projectedPosition.z

                val corners = rectangle.map { Int3(it.position, z) }
                    .map { Pair(it, faceNormal.unprojectVertexToMinecraftSpace(it)) }
                val (minProjectedCorner, minCorner) = corners.minByOrNull { it.second }!!
                val (maxProjectedCorner, maxCorner) = corners.maxByOrNull { it.second }!!

                BlockFace(
                    minCorner, maxCorner,
                    minProjectedCorner, maxProjectedCorner,
                    faceNormal.fromVoxToMinecraftSpace()
                )
            }
        }

        logln(" done. Got %d quads.".format(blockFaces.size))

        log("Saving textures and UVs...")

        // for each block face, check if block face is occluded.
        // all non-occluded blockfaces get stored at their position (no custom uvs) into the main texture for the
        // the side they are facing
        // all occluded blockfaces will be placed into a custom atlas texture, packed in order of occurrence
        // TODO

        logln(" done.")

        log("Computing face culling...")

        // for each face, find block faces directly attached to face, tag for side
        // for all faces internal to these faces (holes) continue walking edges to connected faces and propagate tag,
        // unless adjacent face is block hull face from first phase
        // for all faces with exactly one tag, set cullface
        // TODO

        logln(" done..")

        log("Saving block model...")

        // generate mc json, one element per block face
        // TODO

        logln(" done.")
    }
}
