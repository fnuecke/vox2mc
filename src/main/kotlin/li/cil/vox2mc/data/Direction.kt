package li.cil.vox2mc.data

import li.cil.vox2mc.vox.MODEL_RESOLUTION

enum class Direction(val normal: Int3) {
    POSX(Int3(1, 0, 0)),
    NEGX(Int3(-1, 0, 0)),
    POSY(Int3(0, 1, 0)),
    NEGY(Int3(0, -1, 0)),
    POSZ(Int3(0, 0, 1)),
    NEGZ(Int3(0, 0, -1));

    fun getFaceName(): String = when (this) {
        POSX -> "east"
        NEGX -> "west"
        POSY -> "up"
        NEGY -> "down"
        POSZ -> "south"
        NEGZ -> "north"
    }

    fun fromVoxToMinecraftSpace() = when (this) {
        POSX -> POSX
        NEGX -> NEGX
        POSY -> POSZ
        NEGY -> NEGZ
        POSZ -> POSY
        NEGZ -> NEGY
    }

    // project from x = right, y = back, z = up to x = right, y = up, z = back,
    // with z = front equal this direction
    fun projectFaceIndexFromVoxSpace(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - 1 - i
        return when (this) {
            POSX -> Int3(position.y, position.z, invert(position.x))
            NEGX -> Int3(invert(position.y), position.z, position.x)
            POSY -> Int3(invert(position.x), position.z, invert(position.y))
            NEGY -> Int3(position.x, position.z, position.y)
            POSZ -> Int3(position.x, position.y, invert(position.z))
            NEGZ -> Int3(position.x, invert(position.y), position.z)
        }
    }

    // unproject from x = right, y = up to x = right, y = up, z = back, with
    // z = front equal this direction, to x = right, y = up, z = back.
    fun unprojectVertexToMinecraftSpace(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - i
        return when (this) {
            POSX -> Int3(invert(position.z), position.y, position.x)
            NEGX -> Int3(position.z, position.y, invert(position.x))
            POSY -> Int3(invert(position.x), position.y, invert(position.z))
            NEGY -> Int3(position.x, position.y, position.z)
            POSZ -> Int3(position.x, invert(position.z), position.y)
            NEGZ -> Int3(position.x, position.z, invert(position.y))
        }
    }
}
