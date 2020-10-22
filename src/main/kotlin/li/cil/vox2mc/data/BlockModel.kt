package li.cil.vox2mc.data

class BlockModel(val textures: Map<String, String>, val elements: Array<Element>) {
    class Element(val from: Array<Int>, val to: Array<Int>, val faces: Map<String, Face>)
    class Face(val texture: String? = "all", val cullface: String? = null, val uv: Array<Int>? = null)
}
