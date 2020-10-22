package li.cil.vox2mc.data

enum class Direction(val normal: Int3) {
    POSX(Int3(1, 0, 0)),
    NEGX(Int3(-1, 0, 0)),
    POSY(Int3(0, 1, 0)),
    NEGY(Int3(0, -1, 0)),
    POSZ(Int3(0, 0, 1)),
    NEGZ(Int3(0, 0, -1))
}
