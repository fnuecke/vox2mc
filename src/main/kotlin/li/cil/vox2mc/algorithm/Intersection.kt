package li.cil.vox2mc.algorithm

import li.cil.vox2mc.data.Int2
import li.cil.vox2mc.data.cross
import li.cil.vox2mc.data.dot
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

// Intersects two perpendicular axis aligned edges.
// Will not intersect collinear edges.
fun intersectPerpendicularEdges(a0: Int2, a1: Int2, b0: Int2, b1: Int2): Boolean {
    if (dot(a1 - a0, b1 - b0) != 0) {
        return false
    }

    fun project(p: Int2, e0: Int2, e1: Int2): Int2 {
        val v0 = e1 - e0
        val v1 = p - e0
        return e0 + v0 * dot(v0, v1) / dot(v0, v0)
    }

    fun intersects(e0: Int2, e1: Int2, p: Int2): Boolean {
        val t = dot(project(p, e0, e1) - e0, e1 - e0)
        return t >= 0 && t <= dot(e1 - e0, e1 - e0)
    }

    return intersects(a0, a1, b0) &&
            intersects(a0, a1, b1) &&
            intersects(b0, b1, a0) &&
            intersects(b0, b1, a1)
}

// Intersect axis aligned ray with axis aligned edge.
// Will not intersect with edge a ray starts on.
// Will not intersect edges that go counter-clockwise from ray's view.
// Will intersect with collinear edges.
fun intersectRayEdge(start: Int2, direction: Int2, e0: Int2, e1: Int2): Int2? {
    val v0 = start - e0
    val v1 = e1 - e0
    val v2 = direction.leftHandNormal()

    val det = dot(v1, v2)
    if (det == 0) {
        return if (cross(v1, v0) == 0) { // collinear
            val t0 = dot(e0 - start, direction)
            val t1 = dot(e1 - start, direction)
            assert(t0.sign == t1.sign) { "start point inside line" }
            val t = min(t0, t1)
            if (t <= 0) null else start + direction * t
        } else { // parallel
            null
        }
    } else { // perpendicular
        assert(v1.x == 0 || v1.y == 0)
        if (dot(v2, v1) >= 0) return null // Only return clockwise edges.
        val elen = abs(v1.x + v1.y)
        val t0 = cross(v1, v0) / det
        val t1 = dot(v0, v2) * elen / det
        return if (t0 <= 0 || t1 < 0 || t1 > elen) null else start + direction * t0
    }
}
