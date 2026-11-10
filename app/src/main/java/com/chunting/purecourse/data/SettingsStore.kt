package com.chunting.purecourse.data

import android.content.Context

/**
 * 设置项持久化。
 *
 * tv7 之前所有设置都是纯内存 `mutableStateOf`：把「自动下一节」关掉、
 * 退出再进（或电视待机后重开），又变回「开」——用户很容易以为设置没生效。
 * 这里统一落到 SharedPreferences，冷启动时读回。
 */
object SettingsStore {
    private const val PREF = "purecourse_settings"

    /** 与 AppState 里的字段一一对应；多一个少一个都会在编译期暴露 */
    data class Snapshot(
        val contPlay: Boolean,
        val autoNext: Boolean,
        val memory: Boolean,
        val hwDec: Boolean,
        val seekStep: Int,
        val speed: Float,
        val subFont: Int,
        val subEdge: Int,
        val sortMode: Int,
        /** 主题模式：0 跟随系统 / 1 明亮 / 2 夜晚 */
        val themeMode: Int = 0,
        /** 强调色序号（见 core/Theme.kt 的 Accent） */
        val accentIndex: Int = 0,
    )

    fun load(ctx: Context): Snapshot {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            contPlay = p.getBoolean("contplay", true),
            autoNext = p.getBoolean("autonext", true),
            memory = p.getBoolean("memory", true),
            hwDec = p.getBoolean("hwdec", true),
            seekStep = p.getInt("seekstep", 10),
            speed = p.getFloat("speed", 1f),
            subFont = p.getInt("subfont", 1),
            subEdge = p.getInt("subedge", 2),
            sortMode = p.getInt("sortmode", 0),
            themeMode = p.getInt("thememode", 0),
            accentIndex = p.getInt("accent", 0),
        )
    }

    fun save(ctx: Context, s: Snapshot) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("contplay", s.contPlay)
            .putBoolean("autonext", s.autoNext)
            .putBoolean("memory", s.memory)
            .putBoolean("hwdec", s.hwDec)
            .putInt("seekstep", s.seekStep)
            .putFloat("speed", s.speed)
            .putInt("subfont", s.subFont)
            .putInt("subedge", s.subEdge)
            .putInt("sortmode", s.sortMode)
            .putInt("thememode", s.themeMode)
            .putInt("accent", s.accentIndex)
            .apply()
    }
}
