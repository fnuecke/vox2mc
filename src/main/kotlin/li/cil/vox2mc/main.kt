package li.cil.vox2mc

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val DEFAULT_ALIGNMENT = 2
private const val NOISE_SCALE = 500f
private const val DEFAULT_NOISE = 3
private const val OPAQUE = 0xFF shl 24

fun main(args: Array<String>) = Vox2Mc().main(args)

class Vox2Mc : CliktCommand(name = "vox2mc") {
    override fun help(context: Context) =
        "Turns MagicaVoxel models into Minecraft block models, one quad soup and one sprite each."

    private val quiet by option("-q", "--quiet").help("Suppress progress output.").flag()
    private val modid by option("-m", "--modid", metavar = "ID").help("Mod id to qualify the texture reference with.")
    private val output by option("-o", "--output", metavar = "PATH").help("Root directory to write assets to.")
        .default("assets")
    private val gradient by option("-g", "--gradient").help("Bake a soft directional gradient into the texture.")
        .flag("--no-gradient", default = true, defaultForHelp = "enabled")
    private val noise by option(
        "-n",
        "--noise",
        metavar = "0-100"
    ).help("Bake monochromatic noise into the texture. 0 turns it off.").int().restrictTo(0..100)
        .default(DEFAULT_NOISE)
    private val alignment by option(
        "--align",
        metavar = "N"
    ).help("Texture atlas alignment. Keeps mip levels up to log2(N) free of foreign texels.").int()
        .default(DEFAULT_ALIGNMENT).check("must be a power of two") { it > 0 && it and (it - 1) == 0 }
    private val dedup by option("--dedup").help("Share one atlas slot between quads that render the same thing.")
        .flag("--no-dedup", default = true, defaultForHelp = "enabled")
    private val renderType by option(
        "--render-type",
        metavar = "ID"
    ).help("Render type to declare in the model. Defaults to none.")

    private val files by argument(name = "FILE").help("The .vox models to convert.")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true).multiple(required = true)

    private fun log(message: String) {
        if (!quiet) print(message)
    }

    private fun logln(message: String) {
        if (!quiet) println(message)
    }

    override fun run() {
        val duplicates = files.groupBy { it.nameWithoutExtension }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw CliktError("Multiple input files map to the same model: ${duplicates.joinToString()}.")
        }

        files.sortedBy { it.name }.forEach { file -> convert(file) }
    }

    private fun convert(file: File) {
        log("Loading file [%s]...".format(file))

        val vox = VoxLoader.loadVox(file)
        val voxels = getVoxels(vox)
        val palette = getPalette(vox)
        if (voxels.isEmpty()) {
            throw CliktError("Model [$file] contains no voxels.")
        }

        logln(" done. Got %d voxels.".format(voxels.size))

        log("Meshing surface...")

        val quads = buildQuads(voxels)

        logln(" done. Got %d quads.".format(quads.size))

        log("Rendering quad textures...")

        // Patches have to be finished - gradient included - before they can be compared, so this
        // happens up front rather than as passes over the atlas.
        quads.forEach { it.patch = renderPatch(it, voxels, palette, gradient) }
        val patches = if (dedup) deduplicate(quads) else quads.map { it.patch }

        logln(" done. %d quads share %d distinct textures.".format(quads.size, patches.size))

        log("Packing texture atlas...")

        val atlas = TextureAtlas.pack(patches, alignment)
        val texture = paintAtlas(atlas, patches, voxels, palette)

        // Noise has to come after deduplication: applied per quad it would make every patch unique
        // and there would be nothing left to share.
        if (noise > 0) {
            applyNoise(texture, noise / NOISE_SCALE, Random(0xdeadbeef))
        }
        // Padding repeats the finished texels, so it has to come after everything that recolors them.
        patches.forEach { paintPadding(texture, it, atlas.alignment) }

        logln(" done. Sprite is %dx%d.".format(atlas.width, atlas.height))

        log("Saving assets...")

        val baseName = file.nameWithoutExtension
        val assetsPath = output + (modid?.let { "/$it" } ?: "")

        val texturePath = Paths.get("$assetsPath/textures/block")
        Files.createDirectories(texturePath)
        ImageIO.write(texture, "png", texturePath.resolve("$baseName.png").toFile())

        val textureName = (modid?.plus(":") ?: "") + "block/$baseName"
        val model = BlockModel(
            renderType,
            mapOf("atlas" to textureName, "particle" to "#atlas"),
            quads.map { it.toElement("atlas", atlas.width, atlas.height) })

        val modelPath = Paths.get("$assetsPath/models/block")
        Files.createDirectories(modelPath)
        Files.writeString(modelPath.resolve("$baseName.json"), Gson().toJson(model), StandardCharsets.UTF_8)

        logln(" done.")
    }
}

// Get all voxels in a Vox model. We do not support scene graph nor different parts right now.
// All gets thrown into one big blob of voxels.
private fun getVoxels(vox: Chunk) = vox.children.flatMapIndexed { index, chunk ->
    if (chunk.header.id == ChunkHeader.SIZE_CHUNK_ID) {
        require(chunk.content is SizeContent)
        val size = chunk.content.size
        require(size.x <= MODEL_RESOLUTION && size.y <= MODEL_RESOLUTION && size.z <= MODEL_RESOLUTION) {
            "Model must fit within ${MODEL_RESOLUTION}^3 voxels, got $size."
        }
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

private fun buildQuads(voxels: Map<Int3, Voxel>): List<Quad> {
    val airContacts = if (isFullBounds(voxels)) airBoundaryContacts(voxels) else null
    return Direction.entries.flatMap { normal ->
        buildQuadsForDirection(voxels, normal, airContacts)
    }
}

private fun isFullBounds(voxels: Map<Int3, Voxel>) =
    voxels.keys.let { keys ->
        intArrayOf(0, MODEL_RESOLUTION - 1).all { bound ->
            keys.any { it.x == bound } && keys.any { it.y == bound } && keys.any { it.z == bound }
        }
    }

private fun airBoundaryContacts(voxels: Map<Int3, Voxel>): Map<Int3, Set<Direction>> {
    val contacts = HashMap<Int3, Set<Direction>>()
    val inBox = { p: Int3 ->
        p.x in 0 until MODEL_RESOLUTION && p.y in 0 until MODEL_RESOLUTION && p.z in 0 until MODEL_RESOLUTION
    }
    for (x in 0 until MODEL_RESOLUTION) {
        for (y in 0 until MODEL_RESOLUTION) {
            for (z in 0 until MODEL_RESOLUTION) {
                val seed = Int3(x, y, z)
                if (seed in voxels || seed in contacts) continue

                val queue = ArrayDeque(listOf(seed))
                val touched = mutableSetOf<Direction>()
                contacts[seed] = touched
                while (queue.isNotEmpty()) {
                    val cell = queue.removeFirst()
                    Direction.entries.forEach { direction ->
                        val neighbor = cell + direction.normalInVoxSpace()
                        if (!inBox(neighbor)) {
                            touched.add(direction)
                        } else if (neighbor !in voxels && neighbor !in contacts) {
                            contacts[neighbor] = touched
                            queue.add(neighbor)
                        }
                    }
                }
            }
        }
    }
    return contacts
}

private fun buildQuadsForDirection(
    voxels: Map<Int3, Voxel>, normal: Direction, airContacts: Map<Int3, Set<Direction>>?
): List<Quad> {
    val offset = normal.normalInVoxSpace()

    // Projecting is a bijection, so each depth key holds exactly the voxels of one layer.
    val solidByDepth = mutableMapOf<Int, MutableSet<Int2>>()
    val visibleByDepth = mutableMapOf<Int, MutableSet<Int2>>()
    voxels.keys.forEach { position ->
        val projected = normal.projectFaceIndexFromVoxSpace(position)
        solidByDepth.getOrPut(projected.z, ::mutableSetOf).add(projected.toInt2())
        if (position + offset !in voxels) {
            visibleByDepth.getOrPut(projected.z, ::mutableSetOf).add(projected.toInt2())
        }
    }

    return visibleByDepth.entries.sortedBy { it.key }.flatMap { (depth, visible) ->
        val solid = solidByDepth.getValue(depth)
        val quads = greedyQuads(normal, depth, visible, solid)

        // An inset face may be flagged for culling if every sightline to it must pass through the
        // covering neighbor: the air its visible cells front reaches no other side of the box.
        quads.forEach { quad ->
            quad.cullable = quad.depth == 0 || airContacts != null && quad.cells().all { cell ->
                Int2(quad.u0 + cell.x, quad.v0 + cell.y) !in visible ||
                        airContacts.getValue(quad.voxelAt(cell.x, cell.y) + offset).all { it == normal }
            }
        }

        quads
    }
}

private fun greedyQuads(normal: Direction, depth: Int, visible: Set<Int2>, solid: Set<Int2>): List<Quad> {
    val remaining = visible.toMutableSet()
    val usable = solid.toMutableSet()
    val quads = mutableListOf<Quad>()

    for (v in 0 until MODEL_RESOLUTION) {
        for (u in 0 until MODEL_RESOLUTION) {
            if (Int2(u, v) !in remaining) continue

            var width = 1
            while (Int2(u + width, v) in usable) width++
            var height = 1
            while ((0 until width).all { Int2(u + it, v + height) in usable }) height++

            // Trailing rows and columns holding nothing visible only add hidden area, so drop them.
            while (height > 1 && (0 until width).none { Int2(u + it, v + height - 1) in remaining }) height--
            while (width > 1 && (0 until height).none { Int2(u + width - 1, v + it) in remaining }) width--

            for (y in 0 until height) {
                for (x in 0 until width) {
                    remaining.remove(Int2(u + x, v + y))
                    usable.remove(Int2(u + x, v + y))
                }
            }

            quads.add(Quad(normal, u, v, width, height, depth))
        }
    }

    return quads
}

private fun deduplicate(quads: List<Quad>): List<Patch> {
    // Keyed by every mirroring of each stored patch, so a later quad finds a match whichever way
    // round it happens to be, and learns the flip that reads the stored one back.
    val byOrientation = HashMap<Patch, Pair<Patch, UvFlip>>()
    val unique = mutableListOf<Patch>()

    quads.forEach { quad ->
        val match = byOrientation[quad.patch]
        if (match != null) {
            quad.patch = match.first
            quad.flip = match.second
        } else {
            val stored = quad.patch
            unique.add(stored)
            // NONE first, so a symmetric patch is stored the way round it was drawn.
            UvFlip.entries.forEach { flip -> byOrientation.putIfAbsent(stored.mirrored(flip), stored to flip) }
        }
    }

    return unique
}

private fun renderPatch(quad: Quad, voxels: Map<Int3, Voxel>, palette: Array<Int>, gradient: Boolean): Patch {
    val pixels = IntArray(quad.width * quad.height)
    for (y in 0 until quad.height) {
        for (x in 0 until quad.width) {
            val color = OPAQUE or abgr2rgb(palette[voxels.getValue(quad.voxelAt(x, y)).colorIndex])
            pixels[patchIndex(quad, x, y)] = if (gradient) shade(color, quad, x, y) else color
        }
    }

    return Patch(quad.width, quad.height, pixels)
}

// Patches are stored in image order, where row 0 is the quad's topmost row.
private fun patchIndex(quad: Quad, x: Int, y: Int) = (quad.height - 1 - y) * quad.width + x

private fun paintAtlas(
    atlas: TextureAtlas, patches: List<Patch>, voxels: Map<Int3, Voxel>, palette: Array<Int>
): BufferedImage {
    val texture = BufferedImage(atlas.width, atlas.height, BufferedImage.TYPE_INT_ARGB)

    // Unused atlas area is filled with the model's mean color rather than left black, so the mip
    // levels past what the alignment protects fade into something plausible.
    val background = OPAQUE or meanColor(voxels, palette)
    for (y in 0 until atlas.height) {
        for (x in 0 until atlas.width) {
            texture.setRGB(x, y, background)
        }
    }

    patches.forEach { patch ->
        for (y in 0 until patch.height) {
            for (x in 0 until patch.width) {
                texture.setRGB(patch.atlasX + x, patch.atlasY + y, patch[x, y])
            }
        }
    }

    return texture
}

private fun paintPadding(texture: BufferedImage, patch: Patch, alignment: Int) {
    val slotWidth = TextureAtlas.align(patch.width, alignment)
    val slotHeight = TextureAtlas.align(patch.height, alignment)
    for (y in 0 until slotHeight) {
        for (x in 0 until slotWidth) {
            if (x < patch.width && y < patch.height) continue
            val color = texture.getRGB(
                patch.atlasX + min(x, patch.width - 1), patch.atlasY + min(y, patch.height - 1)
            )
            texture.setRGB(patch.atlasX + x, patch.atlasY + y, color)
        }
    }
}

private fun meanColor(voxels: Map<Int3, Voxel>, palette: Array<Int>): Int {
    var r = 0L
    var g = 0L
    var b = 0L
    voxels.values.forEach { voxel ->
        val (cr, cg, cb) = abgr2rgb(palette[voxel.colorIndex]).toRGBInt3()
        r += cr
        g += cg
        b += cb
    }
    val count = voxels.size.coerceAtLeast(1)
    return Int3((r / count).toInt(), (g / count).toInt(), (b / count).toInt()).toRGBInt()
}

// Applies uniform monochromatic noise in linear color space.
fun applyNoise(image: BufferedImage, noiseStrength: Float, rng: Random) {
    for (x in 0 until image.width) {
        for (y in 0 until image.height) {
            val argb = image.getRGB(x, y)
            val (r, g, b) = argb.toRGBInt3()
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
            image.setRGB(x, y, (argb and OPAQUE) or Int3(rGamma, gGamma, bGamma).toRGBInt())
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
    Direction.UP, Direction.DOWN -> ::radialGradient
    else -> ::linearGradient
}

private fun shade(color: Int, quad: Quad, x: Int, y: Int): Int {
    val multiplier = gradientBySide(quad.normal)(
        (quad.u0 + x) / MODEL_RESOLUTION.toFloat(), (quad.v0 + y) / MODEL_RESOLUTION.toFloat()
    )
    val (r, g, b) = color.toRGBInt3()
    val shaded = Int3(
        (r * multiplier).roundToInt().coerceIn(0..255),
        (g * multiplier).roundToInt().coerceIn(0..255),
        (b * multiplier).roundToInt().coerceIn(0..255)
    )
    return (color and OPAQUE) or shaded.toRGBInt()
}
