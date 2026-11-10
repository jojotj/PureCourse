package com.chunting.purecourse.data

import android.content.Context

/**
 * 续播位置的持久化（P2 的过渡实现，P2 正式化时换 Room 的 Resume 表）。
 *
 * 为什么必须有这一步：`resumeMap` 只存在内存里，**杀进程就没了**，
 * 表现为「退出再进回到 0 秒」——用户会直接认为续播是坏的。
 *
 * 键约定与 Room 版一致：`courseId:ep`（见 AppState.playKey），
 * 换成 Room 时这张表原样搬过去，上层的 loadProgress/saveProgress 不用改。
 */
object ResumeStore {

    private const val PREF = "purecourse_resume"

    fun loadAll(ctx: Context): Map<String, Int> {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.all.entries.mapNotNull { (k, v) ->
            val sec = (v as? Int) ?: return@mapNotNull null
            k to sec
        }.toMap()
    }

    fun put(ctx: Context, key: String, sec: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putInt(key, sec.coerceAtLeast(0)).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
