package li.cil.vox2mc

import com.google.gson.Gson
import li.cil.vox2mc.algorithm.*
import li.cil.vox2mc.data.*
import li.cil.vox2mc.vox.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: vox2mc file.vox ...")
        return
    }

    var quiet = false
    var modid: String? = null
    var output = "assets"
    var noisePower: Float? = null

    val (files, _) = args.partition { arg ->
        if (arg == "-q" || arg == "--quiet") {
            quiet = true
            false
        } else if (arg.startsWith("-m=") || arg.startsWith("--modid=")) {
            modid = arg.split('=', limit = 2)[1]
            false
        } else if (arg.startsWith("-o=") || arg.startsWith("--output=")) {
            output = arg.split('=', limit = 2)[1]
            false
        } else if (arg.startsWith("-n=") || arg.startsWith("--noise=")) {
            // We actually only go up to 20% noise scale; anything more is silly, and the control
            // in the lower end is very helpful.
            noisePower = arg.split('=', limit = 2)[1].toInt().coerceIn(0..100) / 500f
            false
        } else {
            true
        }
    }

    fun log(msg: String) {
        if (!quiet) print(msg)
    }

    fun logln(msg: String) {
        if (!quiet) println(msg)
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

        val voxels = getVoxels(vox)

        logln(" done. Got %d voxels.".format(voxels.size))

        log("Extracting faces...")

        val allFaces = getFaces(voxels)

        logln(" done. Got %d faces.".format(allFaces.size))

        log("Grouping adjacent faces...")

        val groupedFaces = groupAdjacentFaces(allFaces)

        logln(" done. Got %d grouped faces.".format(groupedFaces.size))

        log("Building quads from grouped faces...")

        val blockFaces = groupedFaces.flatMap { createLeastNumberOfQuads(it) }

        logln(" done. Got %d quads.".format(blockFaces.size))

        log("Generating UVs and saving textures...")

        val baseName = file.nameWithoutExtension
        val assetsPath = output + (modid?.let { "/$it" } ?: "")
        val (occludedFaces, topFaces) = blockFaces.let { faces ->
            val facesByNormal = blockFaces.groupBy { it.normal }
            fun isOccluded(f: BlockFace) = facesByNormal[f.normal].orEmpty().any { it.occludes(f) }
            faces.partition { isOccluded(it) }
        }

        val atlases = generateAtlases(occludedFaces)

        val (textureBySide, textureByAtlas) = generateTextures(voxels, getPalette(vox), topFaces, atlases)

        val rng = Random(0xdeadbeef)
        noisePower?.let { noise ->
            (textureBySide.values + textureByAtlas.values).forEach { applyNoise(it, noise, rng) }
        }

        val texturesByName = saveTextures(baseName, assetsPath, modid, textureBySide, textureByAtlas)

        logln(" done.")

        log("Computing face culling...")

        val blockFaceAdjacency = mutableMapOf<BlockFace, MutableSet<BlockFace>>()
        blockFaces.forEachIndexed { index, face0 ->
            blockFaces.drop(index + 1).forEach { face1 ->
                if (face0.isAdjacentTo(face1)) {
                    blockFaceAdjacency.computeIfAbsent(face0) { mutableSetOf() }.add(face1)
                    blockFaceAdjacency.computeIfAbsent(face1) { mutableSetOf() }.add(face0)
                }
            }
        }

        val visibleFrom = topFaces.groupBy { it.normal }.flatMap { (normal, faces) ->
            var queue = faces.flatMap { blockFaceAdjacency[it].orEmpty() }.filterNot { topFaces.contains(it) }
            val connectedFaces = mutableSetOf<BlockFace>()
            while (queue.isNotEmpty()) {
                queue = queue.flatMap { face ->
                    if (connectedFaces.add(face)) {
                        blockFaceAdjacency[face].orEmpty().filterNot { topFaces.contains(it) }
                    } else {
                        emptyList()
                    }
                }
            }
            connectedFaces.map { it to normal }
        }.groupBy { it.first }.mapValues { entry -> entry.value.map { it.second } }

        topFaces.forEach { it.cullFace = it.normal }
        visibleFrom.filterValues { it.size == 1 }.mapValues { it.value.single() }.forEach { (face, normal) ->
            face.cullFace = normal
        }

        logln(" done.")

        log("Saving block model...")

        val visibleBlockFaces = visibleFrom.keys + topFaces
        val blockModel = BlockModel(texturesByName, visibleBlockFaces.map { it.toElement() }.toTypedArray())

        val blockModelsAssetPath = "$assetsPath/models/block/"
        Files.createDirectories(Paths.get(blockModelsAssetPath))

        val blockModelPath = Paths.get(blockModelsAssetPath, "$baseName.json")
        Files.writeString(blockModelPath, Gson().toJson(blockModel), StandardCharsets.UTF_8)

        logln(" done.")
    }
}

// Get all voxels in a Vox model. We do not support scene graph nor different parts right now.
// All gets thrown into one big blob of voxels.
private fun getVoxels(vox: Chunk) = vox.children.flatMapIndexed { index, chunk ->
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

// Get color palette for a vox model.
private fun getPalette(vox: Chunk): Array<Int> {
    val paletteChunk = vox.children.find { chunk -> chunk.header.id == ChunkHeader.RGBA_CHUNK_ID }
    return if (paletteChunk != null) {
        require(paletteChunk.content is PaletteContent)
        paletteChunk.content.colors
    } else PaletteContent.DEFAULT_PALETTE
}

// Get all open voxel faces (i.e. neighbor voxel in the face's direction is not set).
private fun getFaces(voxels: Map<Int3, Voxel>) = voxels.values.flatMap { voxel ->
    Direction.values().filterNot {
        voxels.containsKey(voxel.position + it.normalInVoxSpace())
    }.map { VoxelFace(voxel, it) }
}

// Groups faces by normal and depth into patches of adjacent faces.
private fun groupAdjacentFaces(allFaces: List<VoxelFace>) = allFaces.groupBy { it.direction }.values.flatMap { faces ->
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

private fun createLeastNumberOfQuads(faces: List<VoxelFace>): List<BlockFace> {
    // Generate outlines from face grid. Outer edges are encoded with clockwise winding,
    // inner edges (holes) are encoded with counter-clockwise winding.
    val (vertices, edges) = createGraph(faces)

    // Could be sped up with a BVH or so, but for our input sizes doesn't really matter.
    fun intersectRayGridEdges(start: Int2, direction: Int2) =
        edges.mapNotNull { edge ->
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
    val concaveVertices = getConcaveVertices(vertices)

    // Find maximum set of non-intersecting good diagonals:
    val selectedPartitions = getMaximumNonIntersectingDiagonalsSet(concaveVertices) { start, direction ->
        intersectRayGridEdges(start, direction)?.first
    }

    // Need to correctly update allEdges set when inserting edges for future intersection tests.
    fun removeVertex(v: Vertex) {
        v.remove()
        edges.remove(v.edgeIn)
        edges.remove(v.edgeOut)
        assert(v.edgeIn!!.from.edgeOut!! == v.edgeOut!!.to.edgeIn!!)
        edges.add(v.edgeIn!!.from.edgeOut!!)
    }

    fun splitVertex(v: Vertex): Pair<Vertex, Vertex> {
        val (vertexFrom, vertexTo) = v.split()
        edges.remove(v.edgeIn)
        edges.remove(v.edgeOut)
        edges.add(v.edgeIn!!.from.edgeOut!!)
        edges.add(v.edgeOut!!.to.edgeIn!!)
        return Pair(vertexFrom, vertexTo)
    }

    fun splitEdge(e: DirectedEdge, p: Int2): Pair<Vertex, Vertex> {
        val (vertexFrom, vertexTo) = e.split(p)
        edges.remove(e)
        edges.add(vertexFrom.edgeIn!!)
        edges.add(vertexTo.edgeOut!!)
        return Pair(vertexFrom, vertexTo)
    }

    // Insert selected diagonals as edges into the graph.
    val concaveVerticesByPosition = concaveVertices.map { it.position to it }.toMap()
    selectedPartitions.forEach { edge ->
        val vertex0 = concaveVerticesByPosition.getValue(edge.a)
        val (vertexFrom0, vertexTo0) = splitVertex(vertex0)
        val vertex1 = concaveVerticesByPosition.getValue(edge.b)
        val (vertexFrom1, vertexTo1) = splitVertex(vertex1)

        val edge0 = DirectedEdge(vertexFrom0, vertexTo1)
        vertexFrom0.edgeOut = edge0
        vertexTo1.edgeIn = edge0
        edges.add(edge0)

        val edge1 = DirectedEdge(vertexFrom1, vertexTo0)
        vertexFrom1.edgeOut = edge1
        vertexTo0.edgeIn = edge1
        edges.add(edge1)

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
        edges.add(edge0)

        val edge1 = DirectedEdge(vertexFrom1, vertexTo0)
        vertexFrom1.edgeOut = edge1
        vertexTo0.edgeIn = edge1
        edges.add(edge1)

        sequenceOf(vertexFrom0, vertexTo0, vertexFrom1, vertexTo1)
            .filter { it.isCollinear() }.forEach { removeVertex(it) }
    }

    // Separate rectangles, it's all we can have left at this point.
    val rectangles = extractCurves(edges)

    // Move back to 3D space.
    val faceNormal = faces[0].direction
    val z = faces[0].projectedPosition.z
    return rectanglesToBlockFaces(rectangles, faceNormal, z)
}

private fun createGraph(faces: List<VoxelFace>): Pair<List<Vertex>, MutableSet<DirectedEdge>> {
    // NB: the depth (z component) of all face positions in one group is equal, so we can safely drop it.
    val facesByProjectedPosition = faces.map { it.projectedPosition.toInt2() to it }.toMap()

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

    faces.forEach { face ->
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

    val vertices = removeCollinearVertices(verticesByProjectedPosition)
    val edges = vertices.flatMap { sequenceOf(it.edgeIn, it.edgeOut) }
        .requireNoNulls().distinct().toMutableSet()

    return Pair(vertices, edges)
}

private fun removeCollinearVertices(verticesByProjectedPosition: MutableMap<Int2, Vertex>): List<Vertex> {
    val (collinearVertices, cornerVertices) = verticesByProjectedPosition.values.partition { it.isCollinear() }
    collinearVertices.forEach { it.remove() }
    verticesByProjectedPosition -= collinearVertices.map { it.position }
    return cornerVertices
}

private fun getConcaveVertices(vertices: List<Vertex>) = vertices.filter { vertex ->
    val curr = vertex.position
    val prev = vertex.prev().position
    val next = vertex.next().position

    val toCurr = curr - prev
    val toNext = next - curr

    // Since winding order is opposite we can do the same normal check for outer edges and hole edges.
    dot(toCurr.leftHandNormal(), toNext) > 0
}

fun getMaximumNonIntersectingDiagonalsSet(vertices: List<Vertex>, intersect: (Int2, Int2) -> Int2?): Set<Edge> {
    // Generate all possible good diagonals.
    val goodDiagonals = getGoodDiagonals(vertices, intersect)

    // Build bipartite intersection graph.
    val horizontalDiagonals = goodDiagonals.filter { it.a.y == it.b.y }.toSet()
    val verticalDiagonals = goodDiagonals.filter { it.a.x == it.b.x }.toSet()
    val intersections = horizontalDiagonals.flatMap { u ->
        verticalDiagonals.flatMap { v ->
            if (intersectPerpendicularEdges(u.a, u.b, v.a, v.b))
                sequenceOf(u to v, v to u)
            else
                emptySequence()
        }
    }.groupBy { it.first }.mapValues { e -> e.value.map { it.second }.toSet() }

    // Find maximum matching of intersections.
    val maximumMatching = hopcroftKarp(horizontalDiagonals, intersections)

    // Find shortest alternating paths.
    val matchedAdjacency = (maximumMatching + maximumMatching.map { it.value to it.key })
    val unmatchedAdjacency = intersections
        .mapValues { (key, value) -> value.filter { matchedAdjacency[it] != key } }
        .filterValues { it.isNotEmpty() }

    val minDistances = goodDiagonals.map { it to Integer.MAX_VALUE }.toMap().toMutableMap()
    fun traverse(e: Edge, depth: Int, currAdjacency: Map<Edge, List<Edge>>, nextAdjacency: Map<Edge, List<Edge>>) {
        if (minDistances[e]!! > depth) {
            minDistances[e] = depth
            currAdjacency[e]?.forEach { traverse(it, depth + 1, nextAdjacency, currAdjacency) }
        }
    }

    val unmatchedDiagonals = goodDiagonals - matchedAdjacency.keys
    unmatchedDiagonals.forEach { unmatchedDiagonal ->
        traverse(unmatchedDiagonal, 0, unmatchedAdjacency, matchedAdjacency.mapValues { listOf(it.value) })
    }

    // Pick partitions at even levels (we skip the partitioning and just check for even distances directly).
    return minDistances.filterValues { it % 2 == 0 }.keys
}

private fun getGoodDiagonals(vertices: List<Vertex>, intersect: (Int2, Int2) -> Int2?): List<Edge> {
    val vertexPositions = vertices.map { it.position }.toSet()
    return vertices.flatMap { vertex ->
        val curr = vertex.position
        val prev = vertex.prev().position

        val direction0 = (curr - prev).normalizeAxisAligned()
        val direction1 = direction0.rightHandNormal()

        sequenceOf(
            intersect(curr, direction0),
            intersect(curr, direction1)
        ).filterNotNull().mapNotNull {
            if (vertexPositions.contains(it)) Edge(curr, it) else null
        }
    }.distinct()
}

private fun extractCurves(edges: Set<DirectedEdge>): List<List<Vertex>> {
    val curveByEdge = mutableMapOf<DirectedEdge, List<Vertex>>()
    edges.forEach { edge ->
        val rectangle = mutableListOf<Vertex>()
        var curr = edge
        do {
            curveByEdge[curr] = rectangle
            rectangle.add(curr.to)
            curr = curr.to.edgeOut!!
        } while (curr != edge)
    }
    return curveByEdge.values.distinct()
}

private fun rectanglesToBlockFaces(rectangles: List<List<Vertex>>, faceNormal: Direction, z: Int) =
    rectangles.map { rectangle ->
        assert(rectangle.size == 4)

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

private fun generateAtlases(faces: List<BlockFace>): List<TextureAtlas> {
    val atlases = mutableListOf<TextureAtlas>()
    faces.sortedBy { -max(it.size().x, it.size().y) }.forEach { face ->
        if (!atlases.any { it.add(face) }) {
            val atlas = TextureAtlas(MODEL_RESOLUTION, "atlas" + atlases.size)
            require(atlas.add(face))
            atlases.add(atlas)
        }
    }
    atlases.forEach { it.applyUVs() }

    atlases.forEach { atlas -> atlas.faces().forEach { it.texture = atlas.name } }

    return atlases
}

private fun generateTextures(
    voxels: Map<Int3, Voxel>,
    palette: Array<Int>,
    topFaces: List<BlockFace>,
    atlases: List<TextureAtlas>
): Pair<Map<Direction, BufferedImage>, Map<TextureAtlas, BufferedImage>> {
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

    return Pair(textureBySide, textureByAtlas)
}

// Applies uniform monochromatic noise in linear color space.
fun applyNoise(image: BufferedImage, noiseStrength: Float, rng: Random) {
    for (x in 0 until image.width) {
        for (y in 0 until image.height) {
            val (r, g, b) = image.getRGB(x, y).toRGBInt3()
            val gamma = 2.2f // good enough for our purposes
            val rLinear = (r / 255f).pow(1f / gamma)
            val gLinear = (g / 255f).pow(1f / gamma)
            val bLinear = (b / 255f).pow(1f / gamma)

            val noise = (rng.nextFloat() - 0.5f) * 2f * noiseStrength
            val rNoise = (rLinear + noise).coerceIn(0f..1f)
            val gNoise = (gLinear + noise).coerceIn(0f..1f)
            val bNoise = (bLinear + noise).coerceIn(0f..1f)

            val rGamma = (rNoise.pow(gamma) * 255).roundToInt()
            val gGamma = (gNoise.pow(gamma) * 255).roundToInt()
            val bGamma = (bNoise.pow(gamma) * 255).roundToInt()
            image.setRGB(x, y, Int3(rGamma, gGamma, bGamma).toRGBInt())
        }
    }
}

private fun saveTextures(
    baseName: String,
    assetsPath: String,
    modid: String?,
    textureBySide: Map<Direction, BufferedImage>,
    textureByAtlas: Map<TextureAtlas, BufferedImage>
): Map<String, String> {
    val texturesByName = mutableMapOf<String, String>()

    val textureAssetsPath = "$assetsPath/textures/blocks/$baseName"
    Files.createDirectories(Paths.get(textureAssetsPath))

    val prefix = (modid?.plus(":") ?: "") + "blocks/$baseName"
    textureBySide.forEach { (direction, image) ->
        val internalName = direction.getFaceName()
        val name = "${baseName}_$internalName"
        ImageIO.write(image, "png", File("$textureAssetsPath/$name.png"))
        texturesByName[internalName] = "$prefix/$name"
    }

    textureByAtlas.forEach { (atlas, image) ->
        val internalName = atlas.name
        val name = "${baseName}_${internalName}"
        ImageIO.write(image, "png", File("$textureAssetsPath/$name.png"))
        texturesByName[internalName] = "$prefix/$name"
    }

    return texturesByName
}
