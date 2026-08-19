package li.cil.vox2mc.data

enum class UvFlip(val mirrorsU: Boolean, val mirrorsV: Boolean) {
    NONE(false, false),
    U(true, false),
    V(false, true),
    UV(true, true)
}
