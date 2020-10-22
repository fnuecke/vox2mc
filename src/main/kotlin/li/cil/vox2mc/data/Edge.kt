package li.cil.vox2mc.data

data class Edge(val a: Int2, val b: Int2) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Edge

        if (a == other.a && b == other.b) return true
        if (a == other.b && b == other.a) return true

        return false
    }

    override fun hashCode() = a.hashCode() xor b.hashCode()
}