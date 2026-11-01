package me.rerere.rikkihub.utils

fun <E> Collection<E>.checkDifferent(
    other: Collection<E>,
    eq: (E, E) -> Boolean,
): Pair<List<E>, List<E>> {
    val added = other.filter { e ->
        this.none { eq(it, e) }
    }
    val removed = this.filter { e ->
        other.none { eq(it, e) }
    }
    return added to removed
}

/**
 * 拖动排序在最新的列表上重放：把 [movedKey] 对应的项挪到 [targetKey] 当前所在的位置。
 *
 * 界面手里的列表可能比最新值旧（连续拖动、后台同步），按下标整份替换会搬错或丢掉期间的改动。
 * 任一项已不在列表里时原样返回。
 */
fun <E, K> List<E>.moveByKey(key: (E) -> K, movedKey: K, targetKey: K): List<E> {
    val from = indexOfFirst { key(it) == movedKey }
    val to = indexOfFirst { key(it) == targetKey }
    if (from < 0 || to < 0 || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** 在最新的列表上只替换 id 相同的那一项，其余项保持最新值。 */
fun <E, K> List<E>.replaceByKey(key: (E) -> K, item: E): List<E> {
    val target = key(item)
    return map { if (key(it) == target) item else it }
}
