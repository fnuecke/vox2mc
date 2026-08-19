package li.cil.vox2mc.data

import com.google.gson.annotations.SerializedName

class BlockModel(
    @SerializedName("render_type") val renderType: String?,
    val textures: Map<String, String>,
    val elements: List<Element>
) {
    val parent = "block/block"

    class Element(val from: Array<Int>, val to: Array<Int>, val faces: Map<String, Face>)
    class Face(val texture: String, val cullface: String?, val uv: Array<Float>)
}
