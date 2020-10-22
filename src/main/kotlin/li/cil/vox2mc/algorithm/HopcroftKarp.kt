package li.cil.vox2mc.algorithm

// https://en.wikipedia.org/wiki/Hopcroft%E2%80%93Karp_algorithm
fun <T> hopcroftKarp(us: Set<T>, adjacent: Map<T, Set<T>>): Map<T, T> {
    val pairU = mutableMapOf<T, T>()
    val pairV = mutableMapOf<T, T>()
    val dist = mutableMapOf<T?, Float>()

    // Find shortest paths from unpaired us to unpaired vs.
    fun bfs(): Boolean {
        val (paired, unpaired) = us.partition { pairU.containsKey(it) }
        paired.forEach { dist[it] = Float.POSITIVE_INFINITY }
        unpaired.forEach { dist[it] = 0f }

        val queue: MutableList<T?> = unpaired.toMutableList()
        dist[null] = Float.POSITIVE_INFINITY // best distance to unmapped v
        while (queue.isNotEmpty()) {
            val u = queue.removeAt(0)
            // dist of u to unmapped v < best dist and u != null check
            if (dist.getValue(u) < dist.getValue(null)) {
                assert(u != null)
                adjacent[u]?.forEach { v ->
                    if (dist.getValue(pairV[v]).isInfinite()) { // paired to paired u or unpaired
                        dist[pairV[v]] = dist.getValue(u) + 1
                        queue.add(pairV[v])
                    }
                }
            }
        }
        return !dist.getValue(null).isInfinite() // best distance != infinity -> found new best path
    }

    fun dfs(u: T?): Boolean {
        if (u != null) {
            adjacent[u]?.forEach { v ->
                if (dist.getValue(pairV[v]) == dist.getValue(u) + 1) {
                    if (dfs(pairV[v])) {
                        pairV[v] = u
                        pairU[u] = v
                        return true
                    }
                }
            }
            dist[u] = Float.POSITIVE_INFINITY
            return false
        }
        return true
    }

    var matching = 0
    while (bfs()) {
        us.forEach { u ->
            if (pairU[u] == null) {
                if (dfs(u)) {
                    matching++
                }
            }
        }
    }

    assert(pairU.size == matching)
    return pairU
}
