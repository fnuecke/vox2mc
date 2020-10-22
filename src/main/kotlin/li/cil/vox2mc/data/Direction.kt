package li.cil.vox2mc.data

import li.cil.vox2mc.vox.MODEL_RESOLUTION

enum class Direction(val normal: Int3) {
    POSX(Int3(1, 0, 0)),
    NEGX(Int3(-1, 0, 0)),
    POSY(Int3(0, 1, 0)),
    NEGY(Int3(0, -1, 0)),
    POSZ(Int3(0, 0, 1)),
    NEGZ(Int3(0, 0, -1));

    fun project(position: Int3): Int3 {
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

    fun unproject(position: Int3): Int3 {
        fun invert(i: Int) = MODEL_RESOLUTION - 1 - i
        TODO()
//        return when (this) {
//            POSX -> Int3(position.y, position.z, invert(position.x))
//            NEGX -> Int3(invert(position.y), position.z, position.x)
//            POSY -> Int3(invert(position.x), position.z, invert(position.y))
//            NEGY -> Int3(position.x, position.z, position.y)
//            POSZ -> Int3(position.x, position.y, invert(position.z))
//            NEGZ -> Int3(position.x, invert(position.y), position.z)
//        }
    }
}
