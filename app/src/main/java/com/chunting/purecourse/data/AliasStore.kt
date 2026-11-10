package com.chunting.purecourse.data

import android.content.Context

/**
 * 目录备注名（解决「两个源里有同名文件夹」）。
 *
 * 键是**带源前缀的完整路径**（见 [SrcPath]），所以 SMB 的「钢琴课」和
 * WebDAV 的「钢琴课」可以各自起不同的备注名，互不影响。
 *
 * 存储格式：每行 `path<TAB>备注名`，与 SourceStore 的其它路径表一致。
 * 备注名清空 = 删除这条记录（回落到真实目录名）。
 */
object AliasStore {

    private const val PREF = "purecourse_alias"
    private const val K = "aliases"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(ctx: Context): Map<String, String> =
        (sp(ctx).getString(K, "") ?: "")
            .split('\n').mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val p = line.substringBefore('\t').trim()
                val n = line.substringAfter('\t', "").trim()
                if (p.isEmpty() || n.isEmpty()) null else p to n
            }.toMap()

    fun get(ctx: Context, path: String): String? = all(ctx)[path]

    fun put(ctx: Context, path: String, name: String) {
        if (path.isBlank()) return
        val m = all(ctx).toMutableMap()
        if (name.isBlank()) m.remove(path) else m[path] = name.trim()
        sp(ctx).edit()
            .putString(K, m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            .apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(K).apply()
    }
}
