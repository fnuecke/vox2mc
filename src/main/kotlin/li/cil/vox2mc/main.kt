package li.cil.vox2mc

import com.google.gson.Gson
import li.cil.vox2mc.algorithm.abgr2rgb
import li.cil.vox2mc.algorithm.toRGBInt
import li.cil.vox2mc.algorithm.toRGBInt3
import li.cil.vox2mc.data.*
import li.cil.vox2mc.vox.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.math.*
import kotlin.random.Random
import kotlin.reflect.KFunction2

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Usage: vox2mc file.vox ...")
        return
    }

    var quiet = false
    var modid: String? = null
    var output = "assets"
    var gradient = false
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
        } else if (arg.startsWith("-g") || arg.startsWith("--gradient")) {
            gradient = true
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

        log("Grouping voxels...")

        val groupedVoxels = groupVoxels2(voxels)

        logln(" done. Got %d groups.".format(groupedVoxels.values.distinct().size))

        log("Generating block elements...")

        val blockElements = groupFacesByVoxelGroup(groupedVoxels)
        val blockFaces = blockElements.flatMap { it.faces }

        logln(
            " done. Got %d block elements with %d faces.".format(
                blockElements.size,
                blockElements.flatMap { it.faces }.size
            )
        )

        log("Generating UVs and saving textures...")

        val baseName = file.nameWithoutExtension
        val assetsPath = output + (modid?.let { "/$it" } ?: "")

        val atlases = generateAtlases(blockFaces)
        val textureByAtlas = generateTextures(voxels, getPalette(vox), atlases)

        val rng = Random(0xdeadbeef)
        noisePower?.let { noise ->
            textureByAtlas.values.forEach { applyNoise(it, noise, rng) }
        }

        if (gradient) {
            textureByAtlas.forEach { (atlas, image) ->
                atlas.faces().forEach { face ->
                    val getGradient = gradientBySide(face.normal)
                    require(image.width == image.height)
                    val (x0, y0) = face.uv0().map { (it * image.width).roundToInt() }
                    val (width, height) = face.size()
                    applyGradient(image, x0 until x0 + width, y0 until y0 + height, getGradient)
                }
            }
        }

        val texturesByName = saveTextures(baseName, assetsPath, modid, emptyMap(), textureByAtlas) +
                ("particle" to "#atlas0")

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

        val topFaces = blockFaces.filterNot {
            it.voxels.all { voxel ->
                voxels.containsKey(voxel.position + it.normal.normalInVoxSpace())
            }
        }.let { faces ->
            fun isOccluded(f: BlockFace): Boolean {
                val normal = f.normal.normalInVoxSpace()
                return f.voxels.any { start ->
                    !voxels.containsKey(start.position + normal) && (2..MODEL_RESOLUTION).any { i ->
                        voxels.containsKey(start.position + normal * i)
                    }
                }
            }
            faces.filterNot { isOccluded(it) }
        }
        val borderFaces = topFaces.filter {
            it.depth() == 0 || it.depth() == MODEL_RESOLUTION
        }
        val visibleFrom = borderFaces.groupBy { it.normal }.flatMap { (normal, faces) ->
            var queue = faces.flatMap { blockFaceAdjacency[it].orEmpty() }.filterNot { borderFaces.contains(it) }
            val connectedFaces = mutableSetOf<BlockFace>()
            while (queue.isNotEmpty()) {
                queue = queue.flatMap { face ->
                    if (connectedFaces.add(face)) {
                        blockFaceAdjacency[face].orEmpty().filterNot { borderFaces.contains(it) }
                    } else {
                        emptyList()
                    }
                }
            }
            connectedFaces.map { it to normal }
        }.groupBy { it.first }.mapValues { entry -> entry.value.map { it.second } }

        borderFaces.forEach { it.cullface = it.normal }
        visibleFrom.filterValues { it.size == 1 }.mapValues { it.value.single() }.forEach { (face, normal) ->
            face.cullface = normal
        }

        logln(" done.")

        log("Saving block model...")

        val blockModel = BlockModel(texturesByName, blockElements.map { it.toElement() }.toTypedArray())

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

private fun groupVoxels2(voxels: Map<Int3, Voxel>): Map<Voxel, List<Voxel>> {
    val groups = voxels.map { (_, voxel) -> voxel to mutableListOf(voxel) }.toMap().toMutableMap()

    val axes = sequenceOf(Int3(1, 0, 0), Int3(0, 1, 0), Int3(0, 0, 1))
    axes.forEach { axis ->
        val projection = Int3.ONE - axis // multiply with pos to get 2d pos in plane axis is normal of

        val queue = groups.values.distinct()
            .sortedBy { group -> -group.minOf { dot(it.position, axis) } }
            .toMutableList()
        while (queue.isNotEmpty()) {
            val group = queue.removeLast()

            // Grab top layer in direction we're trying to expand this group, then keep
            // going as long as we can.
            var topLayer = group.maxOf { dot(it.position, axis) }
            while (true) {
                val layer = group.map {
                    voxels.getValue(projection * it.position + axis * topLayer)
                }.toSet()
                topLayer++

                val neighborLayer = layer.mapNotNull { voxels[it.position + axis] }
                if (neighborLayer.size != layer.size) {
                    break
                }

                val neighborGroups = neighborLayer.map { groups.getValue(it) }.distinct()
                if (neighborGroups.size > 1) {
                    break
                }

                val neighborGroup = neighborGroups.single()
                if (neighborGroup.size != neighborLayer.size) {
                    break
                }

                neighborGroup.forEach { voxel -> groups[voxel] = group }
                group.addAll(neighborGroup)
                queue.remove(neighborGroup)
            }
        }
    }

    return groups
}

fun groupFacesByVoxelGroup(groupedVoxels: Map<Voxel, List<Voxel>>): List<BlockElement> {
    val allVoxelPositions = groupedVoxels.keys.map { it.position }.toSet()
    return groupedVoxels.values.distinct().mapNotNull { group ->
        val voxelPositions = group.map { it.position }.toSet()
        val from = voxelPositions.minOrNull()!!
        val to = voxelPositions.maxOrNull()!! + Int3.ONE // voxel coord to vertex coord

        val faceVoxels = Direction.values().map { direction ->
            // Grab voxels in group that define face of this group with direction as normal.
            direction to group.filterNot {
                voxelPositions.contains(it.position + direction.normalInVoxSpace())
            }
        }.toMap()

        val faceVisibility = faceVoxels.map { (direction, voxels) ->
            direction to voxels.any { !allVoxelPositions.contains(it.position + direction.normalInVoxSpace()) }
        }.toMap()

        if (!faceVisibility.values.any()) {
            return@mapNotNull null
        }

        val faces = faceVoxels.filterKeys { normal ->
            faceVisibility.getValue(normal) || !faceVisibility.getValue(normal.opposite())
        }.map { (normal, voxels) ->
            val projectedPositions = voxels.map {
                normal.projectFaceIndexFromVoxSpace(it.position)
            }

            val minPos = projectedPositions.reduce { acc, v -> min(acc, v) }
            val maxPos = projectedPositions.reduce { acc, v -> max(acc, v) } + Int3(1, 1, 0)

            BlockFace(voxels, minPos, maxPos, normal)
        }

        val mcFrom = Int3(from.x, from.z, 16 - from.y)
        val mcTo = Int3(to.x, to.z, 16 - to.y)

        BlockElement(min(mcFrom, mcTo), max(mcFrom, mcTo), faces)
    }
}

private fun generateAtlases(faces: List<BlockFace>): List<TextureAtlas> {
    val atlases = mutableListOf<TextureAtlas>()
    faces.sortedBy { -max(it.size().x, it.size().y) }.forEach { face ->
        if (!atlases.any { it.add(face) }) {
            val atlas = TextureAtlas(MODEL_RESOLUTION * 2, "atlas" + atlases.size)
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
    atlases: List<TextureAtlas>
): Map<TextureAtlas, BufferedImage> {
    val textureByAtlas = atlases.map {
        it to BufferedImage(it.size.x, it.size.y, BufferedImage.TYPE_INT_RGB)
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
                val pixelY = if (flipY) texture.height - 1 - (y0 + y) else (y0 + size.y - y - 1)
                texture.setRGB(pixelX, pixelY, abgr2rgb(color))
            }
        }
    }

    atlases.forEach { atlas -> atlas.faces().forEach { copyFaceColors(it, textureByAtlas.getValue(atlas), false) } }

    return textureByAtlas
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

private fun linearGradient(u: Float, v: Float) = 0.75f + 0.25f * min(1f, v * 1.25f)

private fun radialGradient(u: Float, v: Float): Float {
    val du = (u - 0.5f) * 1.25f
    val dv = (v - 0.5f) * 1.25f
    return 0.75f + 0.25f * sqrt(du * du + dv * dv)
}

private fun gradientBySide(side: Direction) = when (side) {
    Direction.LEFT -> ::linearGradient
    Direction.RIGHT -> ::linearGradient
    Direction.UP -> ::radialGradient
    Direction.DOWN -> ::radialGradient
    Direction.FRONT -> ::linearGradient
    Direction.BACK -> ::linearGradient
}

private fun applyGradient(
    image: BufferedImage,
    xRange: IntRange,
    yRange: IntRange,
    getGradient: KFunction2<Float, Float, Float>
) {
    for (x in xRange) {
        for (y in yRange) {
            val u = x / (image.width - 1).toFloat()
            val v = 1 - y / (image.height - 1).toFloat()
            val multiplier = getGradient(u, v)
            val (r, g, b) = image.getRGB(x, y).toRGBInt3()
            image.setRGB(
                x, y, Int3(
                    (r * multiplier).roundToInt().coerceIn(0..255),
                    (g * multiplier).roundToInt().coerceIn(0..255),
                    (b * multiplier).roundToInt().coerceIn(0..255)
                ).toRGBInt()
            )
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

    val textureAssetsPath = "$assetsPath/textures/block/$baseName"
    Files.createDirectories(Paths.get(textureAssetsPath))

    val prefix = (modid?.plus(":") ?: "") + "block/$baseName"
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
