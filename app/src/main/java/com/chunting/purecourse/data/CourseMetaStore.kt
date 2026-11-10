package com.chunting.purecourse.data

import android.content.Context

/**
 * 课程播放状态的持久化（最近播放时间 / 上次看到第几节）。
 *
 * 为什么需要：`Course.lastAt` 以前只在内存里，每次冷启动扫描都写 0，
 * 于是书架上播过的课重启后又显示「尚未播放」，「继续学习」也只能退回到扫描顺序第一门。
 *
 * 键用**目录路径**（`courseDirs[id]`）而不是课程 id：
 * id 是扫描结果的下标，增删一个目录就会整体移位，用它做键会让记录串课。
 */
object CourseMetaStore {

    private const val PREF = "purecourse_course_meta"

    data class Meta(
        val lastAt: Long,
        val curEp: Int,
        val lastEpName: String,
        /** 已看完的集号；旧版本记录没有这一段，读出来是空集（= 一节都没看完） */
        val done: Set<Int> = emptySet(),
    )

    fun loadAll(ctx: Context): Map<String, Meta> {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.all.entries.mapNotNull { (k, v) ->
            val s = v as? String ?: return@mapNotNull null
            val parts = s.split('\u0001')
            if (parts.size < 3) return@mapNotNull null
            val at = parts[0].toLongOrNull() ?: return@mapNotNull null
            val ep = parts[1].toIntOrNull() ?: 1
            val done = parts.getOrNull(3)?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()
            k to Meta(at, ep.coerceAtLeast(1), parts[2], done)
        }.toMap()
    }

    fun put(ctx: Context, dir: String, meta: Meta) {
        if (dir.isBlank()) return
        val done = meta.done.sorted().joinToString(",")
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(dir, "${meta.lastAt}\u0001${meta.curEp}\u0001${meta.lastEpName}\u0001$done")
            .apply()
    }

    /** 目录被移出书架时顺手清掉，避免越积越多 */
    fun remove(ctx: Context, dir: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(dir).apply()
    }
}
