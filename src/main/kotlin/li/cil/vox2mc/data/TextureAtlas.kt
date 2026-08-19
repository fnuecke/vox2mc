package li.cil.vox2mc.data

import kotlin.math.abs

class TextureAtlas private constructor(val width: Int, val height: Int, val alignment: Int) {
    companion object {
        private const val SPRITE_STEP = 16
        private const val MAX_SPRITE_SIZE = 1024
        private const val MAX_ASPECT = 4

        fun align(value: Int, alignment: Int) = (value + alignment - 1) / alignment * alignment

        fun pack(patches: List<Patch>, alignment: Int): TextureAtlas {
            // Largest first; a guillotine packer wastes far less space that way.
            val ordered = patches.sortedByDescending {
                maxOf(align(it.width, alignment), align(it.height, alignment))
            }

            for (size in candidateSizes()) {
                val atlas = TextureAtlas(size.x, size.y, alignment)
                if (ordered.all { atlas.add(it) }) {
                    return atlas
                }
            }

            throw IllegalArgumentException("Model does not fit into a sprite of at most ${MAX_SPRITE_SIZE}px.")
        }

        private fun candidateSizes(): List<Int2> {
            val sizes = mutableListOf<Int2>()
            for (width in SPRITE_STEP..MAX_SPRITE_SIZE step SPRITE_STEP) {
                for (height in SPRITE_STEP..MAX_SPRITE_SIZE step SPRITE_STEP) {
                    if (maxOf(width, height) / minOf(width, height) <= MAX_ASPECT) {
                        sizes.add(Int2(width, height))
                    }
                }
            }
            return sizes.sortedWith(compareBy({ it.x * it.y }, { abs(it.x - it.y) }))
        }
    }

    private val free = mutableListOf(Rect(0, 0, width, height))

    private fun add(patch: Patch): Boolean {
        val slotWidth = align(patch.width, alignment)
        val slotHeight = align(patch.height, alignment)

        // Best short side fit; leaves the most usable leftovers of the splits we can still make.
        val slot = free
            .filter { it.width >= slotWidth && it.height >= slotHeight }
            .minByOrNull { minOf(it.width - slotWidth, it.height - slotHeight) }
            ?: return false

        free.remove(slot)
        // +-------+-------+  -
        // | patch | right |  slotHeight
        // +-------+-------+  -
        // |     below     |
        // +---------------+
        if (slot.width > slotWidth) {
            free.add(Rect(slot.x + slotWidth, slot.y, slot.width - slotWidth, slotHeight))
        }
        if (slot.height > slotHeight) {
            free.add(Rect(slot.x, slot.y + slotHeight, slot.width, slot.height - slotHeight))
        }

        patch.atlasX = slot.x
        patch.atlasY = slot.y
        return true
    }

    private data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
}
