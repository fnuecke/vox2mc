package li.cil.vox2mc.data

import li.cil.vox2mc.vox.MODEL_RESOLUTION

enum class Direction {
    LEFT,
    RIGHT,
    UP,
    DOWN,
    FRONT,
    BACK;

    fun getFaceName(): String = when (this) {
        LEFT -> "east"
        RIGHT -> "west"
        UP -> "up"
        DOWN -> "down"
        FRONT -> "north"
        BACK -> "south"
    }

    fun normalInVoxSpace() = when (this) {
        LEFT -> Int3(1, 0, 0)
        RIGHT -> Int3(-1, 0, 0)
        UP -> Int3(0, 0, 1)
        DOWN -> Int3(0, 0, -1)
        FRONT -> Int3(0, 1, 0)
        BACK -> Int3(0, -1, 0)
    }

    fun rightInVoxSpace() = when (this) {
        LEFT -> Int3(0, 1, 0)
        RIGHT -> Int3(0, -1, 0)
        UP -> Int3(1, 0, 0)
        DOWN -> Int3(1, 0, 0)
        FRONT -> Int3(-1, 0, 0)
        BACK -> Int3(1, 0, 0)
    }

    fun upInVoxSpace() = when (this) {
        LEFT -> Int3(0, 0, 1)
        RIGHT -> Int3(0, 0, 1)
        UP -> Int3(0, 1, 0)
        DOWN -> Int3(0, -1, 0)
        FRONT -> Int3(0, 0, 1)
        BACK -> Int3(0, 0, 1)
    }

    fun faceToVoxelCoordinate(v: Int3) = when (this) {
        LEFT -> v - Int3(1, 0, 0)
        RIGHT -> v - Int3(0, 1, 0)
        UP -> v - Int3(0, 0, 1)
        DOWN -> v - Int3(0, 1, 0)
        FRONT -> v - Int3(1, 1, 0)
        BACK -> v
    }

    // project from x = right, y = back, z = up to x = right, y = up, z = depth.
    fun projectFaceIndexFromVoxSpace(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - 1 - i
        return when (this) {
            LEFT -> Int3(position.y, position.z, invert(position.x))
            RIGHT -> Int3(invert(position.y), position.z, position.x)
            UP -> Int3(position.x, position.y, invert(position.z))
            DOWN -> Int3(position.x, invert(position.y), position.z)
            FRONT -> Int3(invert(position.x), position.z, invert(position.y))
            BACK -> Int3(position.x, position.z, position.y)
        }
    }

    fun unprojectVertexToVoxSpace(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - i
        return when (this) {
            LEFT -> Int3(invert(position.z), position.x, position.y)
            RIGHT -> Int3(position.z, invert(position.x), position.y)
            UP -> Int3(position.x, position.y, invert(position.z))
            DOWN -> Int3(position.x, invert(position.y), position.z)
            FRONT -> Int3(invert(position.x), invert(position.z), position.y)
            BACK -> Int3(position.x, position.z, position.y)
        }
    }

    // unproject from x = right, y = up, z = depth to x = left, y = up, z = front.
    fun unprojectVertexToMinecraftSpace(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - i
        return when (this) {
            LEFT -> Int3(invert(position.z), position.y, invert(position.x))
            RIGHT -> Int3(position.z, position.y, position.x)
            UP -> Int3(position.x, invert(position.z), invert(position.y))
            DOWN -> Int3(position.x, position.z, position.y)
            FRONT -> Int3(invert(position.x), position.y, position.z)
            BACK -> Int3(position.x, position.y, invert(position.z))
        }
    }
}
