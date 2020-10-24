package li.cil.vox2mc

import com.google.gson.Gson
import li.cil.vox2mc.algorithm.abgr2rgb
import li.cil.vox2mc.algorithm.hopcroftKarp
import li.cil.vox2mc.algorithm.intersectPerpendicularEdges
import li.cil.vox2mc.algorithm.intersectRayEdge
import li.cil.vox2mc.data.*
import li.cil.vox2mc.vox.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.roundToInt

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: vox2mc file.vox ...")
        return
    }

    val flags = mutableMapOf<String, Boolean>()
    val options = mutableMapOf<String, String>()
    val (files, _) = args.partition { arg ->
        if (arg == "-q" || arg == "--quiet") {
            flags["quiet"] = true
            false
        } else if (arg.startsWith("-m=") || arg.startsWith("--modid=")) {
            options["modid"] = arg.split('=', limit = 2)[1]
            false
        } else if (arg.startsWith("-o=") || arg.startsWith("--output")) {
            options["output"] = arg.split('=', limit = 2)[1]
            false
        } else {
            true
        }
    }

    fun getFlag(name: String) = flags.getOrDefault(name, false)
    fun getOption(name: String) = options[name]

    fun log(msg: String) {
        if (!getFlag("quiet")) print(msg)
    }

    fun logln(msg: String) {
        if (!getFlag("quiet")) println(msg)
    }

    for (filename in files) {
        val file = File(filename)
        if (!file.exists()) {
            logln("File [%s] not found, skipping.".format(filename))
            continue
        }

        log("Loading file [%s]...".format(filename))

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
                voxels.containsKey(voxel.position + it.normalInVoxSpace())
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
            // Additional references:
            // - https://en.wikipedia.org/wiki/Bipartite_graph
            // - https://en.wikipedia.org/wiki/Intersection_graph
            // - https://en.wikipedia.org/wiki/Matching_(graph_theory)
            // - https://en.wikipedia.org/wiki/Hopcroft–Karp_algorithm

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
                    if (intersectPerpendicularEdges(u.a, u.b, v.a, v.b))
                        sequenceOf(u to v, v to u)
                    else
                        emptySequence()
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
            assert(selectedPartitions.mapIndexed { index, e0 ->
                selectedPartitions.drop(index + 1).any { e1 ->
                    intersectPerpendicularEdges(e0.a, e0.b, e1.a, e1.b)
                }
            }.count { it } == 0)

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

                val projectedCorners = rectangle.map { Int3(it.position, z) }
                val corners = projectedCorners.map { faceNormal.unprojectVertexToMinecraftSpace(it) }

                val minProjectedCorner = projectedCorners.fold(projectedCorners[0]) { acc, pos -> min(acc, pos) }
                val maxProjectedCorner = projectedCorners.fold(projectedCorners[0]) { acc, pos -> max(acc, pos) }
                val minCorner = corners.fold(corners[0]) { acc, pos -> min(acc, pos) }
                val maxCorner = corners.fold(corners[0]) { acc, pos -> max(acc, pos) }

                BlockFace(
                    minCorner, maxCorner,
                    minProjectedCorner, maxProjectedCorner,
                    faceNormal
                )
            }
        }

        logln(" done. Got %d quads.".format(blockFaces.size))

        log("Saving textures and UVs...")

        // Collect block face adjacency info.
        val blockFaceAdjacency = mutableMapOf<BlockFace, MutableSet<BlockFace>>()
        blockFaces.forEachIndexed { index, face0 ->
            blockFaces.drop(index + 1).forEach { face1 ->
                if (face0.isAdjacentTo(face1)) {
                    blockFaceAdjacency.computeIfAbsent(face0) { mutableSetOf() }.add(face1)
                    blockFaceAdjacency.computeIfAbsent(face1) { mutableSetOf() }.add(face0)
                }
            }
        }

        // Partition block faces by whether they're occluded or not.
        val facesByNormal = blockFaces.groupBy { it.normal }
        fun isOccluded(f: BlockFace) = facesByNormal[f.normal].orEmpty().any { it.occludes(f) }
        val (occludedFaces, topFaces) = blockFaces.partition { isOccluded(it) }

        val atlases = mutableListOf<TextureAtlas>()
//        val wideFaces = occludedFaces.filter { it.size().x > it.size().y }.sortedBy { -it.size().x }
//        val highFaces = occludedFaces.filter { it.size().x < it.size().y }.sortedBy { -it.size().y }
//        val otherFaces = occludedFaces - wideFaces - highFaces
//        wideFaces.forEach { atlas.add(it) }
//        highFaces.forEach { atlas.add(it) }
//        otherFaces.forEach { atlas.add(it) }
        occludedFaces.forEach { face ->
            if (!atlases.any { it.add(face) }) {
                val atlas = TextureAtlas(MODEL_RESOLUTION)
                require(atlas.add(face))
                atlases.add(atlas)
            }
        }
        atlases.forEach { it.applyUVs() }

        val atlasTextureNames = atlases.mapIndexed { index, atlas ->
            atlas to "atlas$index"
        }.toMap()

        topFaces.forEach { face -> face.texture = face.normal.getFaceName() }
        atlases.forEach { atlas -> atlas.faces().forEach { it.texture = atlasTextureNames.getValue(atlas) } }

        val textureBySide = topFaces.map { it.normal }.distinct().map {
            it to BufferedImage(MODEL_RESOLUTION, MODEL_RESOLUTION, BufferedImage.TYPE_INT_RGB)
        }.toMap()
        val textureByAtlas = atlases.map {
            it to BufferedImage(MODEL_RESOLUTION, MODEL_RESOLUTION, BufferedImage.TYPE_INT_RGB)
        }.toMap()

        fun copyFaceColors(face: BlockFace, texture: BufferedImage, flipY: Boolean) {
            val (u0, v0) = face.uv0()
            val x0 = (u0 * texture.width).roundToInt()
            val y0 = (v0 * texture.width).roundToInt()
            val size = face.size()
            val origin = face.normal.unprojectVertexToVoxSpace(face.projectedFrom)
            val right = face.normal.rightInVoxSpace()
            val up = face.normal.upInVoxSpace()
            for (x in 0 until size.x) {
                for (y in 0 until size.y) {
                    val facePosition = origin + right * x + up * y
                    val voxelCoordinate = face.normal.faceToVoxelCoordinate(facePosition)
                    val voxel = voxels.getValue(voxelCoordinate)
                    val color = palette[voxel.colorIndex]
                    val pixelX = x0 + x
                    val pixelY = if (flipY) texture.height - 1 - (y0 + y) else (y0 + y)
                    texture.setRGB(pixelX, pixelY, abgr2rgb(color))
                }
            }
        }

        topFaces.forEach { copyFaceColors(it, textureBySide.getValue(it.normal), true) }
        atlases.forEach { atlas -> atlas.faces().forEach { copyFaceColors(it, textureByAtlas.getValue(atlas), false) } }

        val texturesByName = mutableMapOf<String, String>()
        val baseName = file.nameWithoutExtension
        val modid = getOption("modid")

        val basePath = getOption("output") ?: "assets"
        val assetsPath = basePath + (modid?.let { "/$it" } ?: "")
        val textureAssetsPath = "$assetsPath/textures/blocks/$baseName"
        Files.createDirectories(Paths.get(textureAssetsPath))

        val prefix = (modid?.plus(":") ?: "") + "blocks/$baseName"
        textureBySide.forEach { (direction, image) ->
            val internalName = direction.getFaceName()
            val name = "${baseName}_$internalName"
            ImageIO.write(image, "png", File("$textureAssetsPath/$name.png"))
            texturesByName[internalName] = "$prefix/$name"
        }

        atlases.forEach { atlas ->
            val image = textureByAtlas.getValue(atlas)
            val internalName = atlasTextureNames.getValue(atlas)
            val name = "${baseName}_${internalName}"
            ImageIO.write(image, "png", File("$textureAssetsPath/$name.png"))
            texturesByName[internalName] = "$prefix/$name"
        }

        logln(" done.")

        log("Computing face culling...")

        // for each face, find block faces directly attached to face, tag for side
        // for all faces internal to these faces (holes) continue walking edges to connected faces and propagate tag,
        // unless adjacent face is block hull face from first phase
        // for all faces with exactly one tag, set cullface
        // TODO

        logln(" done.")

        log("Saving block model...")

        val blockModelsAssetPath = "$assetsPath/models/block/"
        Files.createDirectories(Paths.get(blockModelsAssetPath))

        val blockModel = BlockModel(texturesByName, blockFaces.map { it.toElement() }.toTypedArray())
        val blockModelPath = Paths.get(blockModelsAssetPath, "$baseName.json")
        Files.writeString(blockModelPath, Gson().toJson(blockModel), StandardCharsets.UTF_8)

        logln(" done.")
    }
}
