package com.chunting.purecourse.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 内核 A：Media3(ExoPlayer) 1.5.1。
 * TV 平台细节（tunneling / 帧率切换 / 音频焦点 / MediaSession）全由它处理，
 * SMB 只需实现一个可 seek 的 `DataSource`（P1 接 smbj）。
 */
class Media3Engine(private val ctx: Context) : PlaybackEngine {

    override val name = "Media3 1.5.1"

    companion object {
        private const val TAG = "PureCourse"
    }

    private var player: ExoPlayer? = null
    /** 最近一次算出的显示宽高比（DAR），0 表示还没拿到 */
    private var lastScreenRatio = 0f
    private var t0 = 0L
    private var readyMs = -1L
    private var firstMs = -1L
    private var ready = CompletableDeferred<Long>()
    private var first = CompletableDeferred<Long>()
    private var seekDone: CompletableDeferred<Long>? = null
    private var seekStart = 0L

    override fun create(): Long {
        t0 = SystemClock.elapsedRealtime()
        ready = CompletableDeferred()
        first = CompletableDeferred()
        // 关键：数据源按当前数据源协议路由 —— WebDAV 走 OkHttp（Basic + 自签证书），
        // SMB 走自写 SmbDataSource，file:// 仍由 DefaultDataSource 自己处理。
        // 2.0.9 快移手感：默认两处都在拖慢 seek ——
        // 1) SeekParameters 默认 EXACT：先跳到目标点**之前**的关键帧，再把关键帧到目标点之间
        //    的帧全部解码丢弃。课程片 GOP 常 5~10 秒，一次就白解上百帧（TCL 真机 1~2 秒）；
        // 2) DefaultLoadControl 默认 bufferForPlayback=2500ms：seek 后必须重新填满 2.5 秒
        //    媒体才离开 BUFFERING，跳远时叠加成 3~4 秒。
        // 改成「对齐最近关键帧 + 只等 0.5 秒数据」，这也是 VLC / PotPlayer 的快切策略。
        // 续播定位仍走精确 seek（见 seekToMsExact），不吃这个折损。
        val p = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, SourceRoutingFactory(ctx)))
            )
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    // min / max / 起播门槛 / 重缓冲后再起播门槛（毫秒）
                    .setBufferDurationsMs(12_000, 24_000, 500, 1_200)
                    .build()
            )
            .build()
        player = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !ready.isCompleted) {
                    readyMs = SystemClock.elapsedRealtime() - t0
                    ready.complete(readyMs)
                }
            }

            override fun onRenderedFirstFrame() {
                if (!first.isCompleted) {
                    firstMs = SystemClock.elapsedRealtime() - t0
                    first.complete(firstMs)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!ready.isCompleted) ready.complete(-1)
                if (!first.isCompleted) first.complete(-1)
                seekDone?.let { if (!it.isCompleted) it.complete(-1) }
            }
        })
        return SystemClock.elapsedRealtime() - t0
    }

    override fun attach(surface: Surface?) {
        player?.setVideoSurface(surface)
    }

    override fun open(uri: String) {
        // ⚠️ `file:` 必须走 fromUri：本地存储 / U 盘的片源已经是带 scheme 的完整 URI
        // （`file:///storage/emulated/0/课程/第1集.mp4`）。以前这里只认 smb/http，
        // 其余一律 `Uri.fromFile(File(uri))` —— 于是那个 URI 被当成**裸路径**又包了一层，
        // 变成 `file://file:/storage/...`，播放器报
        // `FileNotFoundException: /file:/storage/...`，画面全黑。
        val hasScheme = uri.startsWith("smb://") || uri.startsWith("http") || uri.startsWith("file:")
        // 换片即作废旧比例：新片尺寸没解析出来之前不要沿用上一集的比例
        lastScreenRatio = 0f
        val item = if (hasScheme) {
            MediaItem.fromUri(uri)
        } else {
            MediaItem.fromUri(Uri.fromFile(File(uri)))
        }
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    override suspend fun awaitPrepared(timeoutMs: Long): PrepResult {
        val p = player ?: return PrepResult(false, -1, -1, -1, "-", "player=null")
        val initMs = withTimeoutOrNull(timeoutMs) { ready.await() } ?: -1
        val firstFrame = withTimeoutOrNull(10_000) { first.await() } ?: -1
        val dur = runCatching { p.duration }.getOrDefault(-1)
        var codec = "-"
        runCatching {
            outer@ for (group in p.currentTracks.groups) {
                for (i in 0 until group.length) {
                    val f = group.getTrackFormat(i)
                    if (f.sampleMimeType?.startsWith("video/") == true && group.isSupported) {
                        codec = f.codecs ?: f.sampleMimeType.orEmpty()
                        break@outer
                    }
                }
            }
        }
        return PrepResult(
            ok = initMs > 0,
            initMs = initMs,
            firstFrameMs = firstFrame,
            durationMs = dur,
            codec = codec,
            err = if (initMs > 0) "" else "prepare 超时或失败",
        )
    }

    override suspend fun seek(ms: Long, timeoutMs: Long): Long {
        val p = player ?: return -1
        val target = ms.coerceAtLeast(0)
        val done = CompletableDeferred<Long>()
        seekDone = done
        seekStart = SystemClock.elapsedRealtime()
        // 基准测试也按用户真实快移的语义走（对齐关键帧），否则测出来的耗时不代表手感
        runCatching { p.setSeekParameters(SeekParameters.CLOSEST_SYNC) }
        p.seekTo(target)
        // 判据：位置真的走到目标之后 150ms（Media3 无 seek 完成回调，用位置+READY 状态判定）
        val res = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (p.playbackState == Player.STATE_READY && p.currentPosition >= target + 100) {
                    return@withTimeoutOrNull SystemClock.elapsedRealtime() - seekStart
                }
                delay(16)
            }
            @Suppress("UNREACHABLE_CODE") -1L
        }
        seekDone?.let { if (!it.isCompleted) it.complete(-1) }
        return res ?: -1
    }

    override fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    override fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    override fun release() {
        runCatching { player?.release() }
        player = null
    }

    // ---------------- 播放页需要的实时状态（P3 接线） ----------------

    /** 镜像：TextureView 才能翻转（SurfaceView 不行，交接文档 §6 已给结论） */
    fun attachTextureView(view: android.view.TextureView) {
        player?.setVideoTextureView(view)
    }

    val positionMs: Long get() = player?.currentPosition ?: 0L
    val durationMs: Long get() = player?.duration?.takeIf { it > 0 } ?: 0L
    val isPlayingNow: Boolean get() = player?.isPlaying == true
    /** 片尾判定：由播放页 250ms 轮询驱动自动下一节 / 连续播放 */
    val isEnded: Boolean get() = player?.playbackState == Player.STATE_ENDED

    fun playPause(play: Boolean) { player?.playWhenReady = play }

    /**
     * 快移 / 进度条跳转：对齐最近关键帧，落点最多偏一个 GOP，换来「按下去就动」。
     * 这是 VLC、PotPlayer 的默认 Seek 语义，画面上用户感知不到偏差。
     */
    fun seekToMs(ms: Long) = applySeek(ms, SeekParameters.CLOSEST_SYNC)

    /**
     * 续播定位：必须回到上次停留的那一秒，走精确 seek。
     * 只在打开片源时调用一次，慢一点无妨，但要准。
     */
    fun seekToMsExact(ms: Long) = applySeek(ms, SeekParameters.EXACT)

    private fun applySeek(ms: Long, params: SeekParameters) {
        player?.apply {
            runCatching { setSeekParameters(params) }
            seekTo(ms.coerceAtLeast(0))
        }
    }
    /** 单曲循环交给内核，避免自己轮询把同一集反复 seek 到 0 */
    fun setLoop(loop: Boolean) {
        player?.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /**
     * 视频显示宽高比（DAR）：界面按它给画布留位置，绝不拉伸画面。
     *
     * ⚠️ 这里必须带采样宽高比（SAR/PAR），不能直接 width/height：
     * - Media3 的 `VideoSize.width/height` 是**未修正的像素尺寸**。老课程片常见
     *   "1440x1080 存储 + SAR 4:3"，它实际是 16:9 的画面，只有把像素宽乘上
     *   `pixelWidthHeightRatio`（=4/3）才是真实显示比 1.778。
     * - VLC / PotPlayer / MPV 默认都按 DAR = 像素宽 × SAR / 像素高 出画，所以同一文件
     *   在电脑上是满屏的；App 只拿 width/height 算就会把 16:9 压成 4:3（左右黑边、
     *   人物发窄）。这不是"这几个视频坏"，是没读容器里的采样宽高比。
     * - `pixelWidthHeightRatio <= 0` 表示容器没标记（方形像素），按 1f 兜底。
     *
     * 另：回调值做缓存，注册时先补发一次最近的值 —— 视频可能在 UI 挂 listener 之前
     * 就已经解析出尺寸（快速切集 / 秒开），那样错过回调就会一直用默认 16:9。
     */
    fun setScreenRatioListener(onRatio: (Float) -> Unit) {
        val cached = lastScreenRatio
        if (cached > 0f) onRatio(cached)
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val par = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1f
                // 还有一类"格式不全"的片子带旋转元数据（手机竖拍 / 部分转码工具输出 90°），
                // Media3 把旋转报在 unappliedRotationDegrees 上，此时真实显示比是「高比宽」。
                val swap = videoSize.unappliedRotationDegrees % 180 != 0
                val w = if (swap) videoSize.height else videoSize.width
                val h = if (swap) videoSize.width else videoSize.height
                val dar = w * par / h
                if (dar <= 0f || !dar.isFinite()) return
                lastScreenRatio = dar
                Log.i(
                    TAG,
                    "videoSize ${videoSize.width}x${videoSize.height} par=$par rot=${videoSize.unappliedRotationDegrees} dar=$dar" +
                        (if (par != 1f) " (非方形像素，已修正)" else ""),
                )
                onRatio(dar)
            }
        })
    }

    /** 播放错误回调：交给失败层做分类（网络 / 会话满 / 路径） */
    fun setErrorListener(onError: (String) -> Unit) {
        player?.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError("${error.errorCodeName}: ${error.message ?: ""}")
            }
        })
    }
}
