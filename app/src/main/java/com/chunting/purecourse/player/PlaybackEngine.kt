package com.chunting.purecourse.player

import android.view.Surface

/**
 * 播放内核抽象（文档 §6 要求：无论最终选谁都要这层，换内核只加实现类，UI 不返工）。
 *
 * 约定：`uri` 既可以是本地绝对路径，也可以是 `smb://user:pass@host/share/path`
 * ——SMB 桥接由实现类内部的 `DataSource`（Media3）或 `stream_cb`（libmpv）负责。
 */
data class PrepResult(
    val ok: Boolean,
    /** 从创建内核实例到 STATE_READY / FILE_LOADED 的耗时 */
    val initMs: Long,
    /** 从创建实例到首帧呈现（RENDER_FIRST_FRAME / 首个 PLAYBACK_RESTART）的耗时 */
    val firstFrameMs: Long,
    val durationMs: Long,
    val codec: String,
    val err: String = "",
)

interface PlaybackEngine {
    val name: String

    /** 创建内核实例（初始化，不含加载媒体） */
    fun create(): Long

    fun attach(surface: Surface?)

    /** 异步打开媒体 */
    fun open(uri: String)

    /** 等待就绪 + 首帧 + 时长，超时返回 ok=false 的结果 */
    suspend fun awaitPrepared(timeoutMs: Long = 15_000): PrepResult

    /** 随机访问；返回耗时 ms，超时/失败返回 -1 */
    suspend fun seek(ms: Long, timeoutMs: Long = 8_000): Long

    fun setSpeed(speed: Float)
    fun setPaused(paused: Boolean)
    fun release()
}
