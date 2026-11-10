package com.chunting.purecourse.data

/**
 * 统一路径的「源前缀」编解码（M2 多源并存）。
 *
 * ## 为什么用前缀，而不是给每条数据加一个 sourceId 字段
 *
 * 书架、收藏、浏览栈、目录节点、剧集全部用 `Map<key, ...>` 以路径为键。
 * 只要路径本身带源标识，这些容器天然就能装下不同来源的同名目录，
 * 不必给 Course / FsItem / Episode / favs / shelf_paths 各加一套 (sourceId, rel) 二元组，
 * 也不会出现"某个 map 忘了按源过滤"这类漏改。
 *
 * ## 格式
 *
 * ```
 * @s1\课程共享\02 乐理课程     ← 源 s1（整机模式，第一段是共享名）
 * @s2                        ← 源 s2 的根（WebDAV 起始目录）
 * 课程共享\02 乐理课程          ← 旧版无前缀路径（读盘时按"第一个源"归一化）
 * ```
 *
 * 分隔符选 `@` 而不是 `#`：`#fav` / `#c5` 已经是收藏页的伪节点 key，
 * 两者混用会让 `startsWith("#")` 这类判断把真实路径当成伪节点。
 */
object SrcPath {

    private const val MARK = '@'

    fun of(id: String, rel: String): String {
        val r = rel.trim('\\', '/').replace('/', '\\')
        return if (r.isEmpty()) "$MARK$id" else "$MARK$id\\$r"
    }

    /** 带前缀路径 → 源 id；无前缀（旧数据）或格式不对 → null */
    fun idOf(path: String?): String? {
        if (path.isNullOrEmpty() || path[0] != MARK) return null
        val id = path.substring(1).substringBefore('\\')
        return id.ifEmpty { null }
    }

    /** 去掉源前缀后的「该源内部路径」；无前缀原样返回 */
    fun relOf(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        if (path[0] != MARK) return path
        return path.substringAfter('\\', "")
    }

    /** 是否已带源前缀 */
    fun hasPrefix(path: String?): Boolean = idOf(path) != null
}
