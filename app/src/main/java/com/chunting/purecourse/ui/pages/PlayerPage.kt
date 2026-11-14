package com.chunting.purecourse.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import com.chunting.purecourse.player.Media3Engine
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.ui.PauseBars
import com.chunting.purecourse.ui.PlayTriangle
import com.chunting.purecourse.ui.Txt

/**
 * 播放页（page-player）——核心中的核心。
 *
 * 两种态：全屏播放态（无控件、隐藏顶栏）/ 控制层态（↓ 调出，↑ 或返回收起）。
 * 关键纪律：右上角时长 OSD 在**两种态下表现必须一致**（快移一律显示 1 秒），
 * 暂停符 11×49 / left 64 / top 150（控制层展开时 top 246、opacity .72）。
 */
@Composable
fun PlayerPage(state: AppState) {
    val c = state.courses.getOrNull(state.sel)
    val ctx = LocalContext.current

    // ---------- P3：真内核接线（Media3 + SMB 数据源） ----------
    val engine = remember { Media3Engine(ctx).also { it.create() } }
    val tv = remember { mutableStateOf<android.view.TextureView?>(null) }
    // 片源异步解析：SMB 目录要网络往返，不能在建帧时同步读
    var uri by remember(state.sel, c?.curEp) { mutableStateOf<String?>(null) }
    // 视频原始比例：默认 16:9，拿到真实尺寸后覆盖（4:3 / 21:9 的片子也不会被拉伸）
    var videoRatio by remember { mutableStateOf(16f / 9f) }
    // 片尾去重不再用 Compose State：轮询协程按 uri 重启、用协程内局部 handled 变量即可。
    // 原因见下面 LaunchedEffect(uri) 的注释（协程捕获旧 State 会让去重条件恒 false）。

    DisposableEffect(Unit) {
        engine.setErrorListener { msg -> state.onPlayerError(msg) }
        // 画面比例一律由内核按「显示宽高比 DAR」给出：像素宽 × 采样宽高比 / 像素高。
        // 不能自己拿 width/height 相除 —— 老课程片常有 1440x1080 + SAR 4:3 这种非方形像素，
        // 裸像素比是 4:3、真实显示比是 16:9，用裸像素比会把画面横向压窄
        // （电脑上 VLC / PotPlayer / MPV 都按 DAR 出画，所以同一文件在电脑上是正常的）。
        engine.setScreenRatioListener { dar -> if (dar > 0f) videoRatio = dar }
        onDispose {
            state.saveProgress()
            engine.release()
        }
    }

    // 片源 = 扫描时登记好的「该课程第 N 集的真实文件路径」，不再临时列目录、更不退回演示片
    LaunchedEffect(state.sel, c?.curEp) {
        val rel = state.epPath(state.sel, c?.curEp ?: 1)   // 主线程取 Compose 状态
        // ⚠️ 必须把**内存里的**源列表一起传下去：本地存储（内置存储 / U 盘）是每次启动
        // 实时探测出来的，并不落库；只查 SourceStore 会取不到配置，结果是"U 盘里的课
        // 一播放就黑屏、片源=null"。
        val cfgs = state.sourceCfgs
        val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            resolvePlaySource(ctx, rel, cfgs)
        }
        uri = u
        android.util.Log.i("PlayerPage", "片源=" + (u ?: "null"))
    }

    // 打开片源，并按该集自己的续播位置定位
    LaunchedEffect(uri) {
        val u = uri ?: return@LaunchedEffect
        engine.open(u)
        // 续播定位用精确 seek：这里必须回到上次停下的那一秒，慢一点无妨
        if (state.curSec > 0) engine.seekToMsExact(state.curSec * 1000L)
        engine.playPause(state.playing)
    }
    LaunchedEffect(tv.value) { tv.value?.let { engine.attachTextureView(it) } }
    LaunchedEffect(state.playing) { engine.playPause(state.playing) }
    // repeatMode 仍照常设置（内核正常发 EOS 时由内核自行循环）；
    // 片尾轮询另外做一次不依赖内核事件的兜底，覆盖 FLV/部分设备的边界行为。
    LaunchedEffect(state.loop, uri) { engine.setLoop(state.loop) }
    LaunchedEffect(state.speed) { engine.setSpeed(state.speed) }
    LaunchedEffect(state.seekRequest) { state.consumeSeek()?.let { engine.seekToMs(it) } }

    // 进度/时长回写（250ms 一次；写盘只在暂停、seek、退出时做）
    //
    // ★ 这里的关键不是片尾判定，而是 LaunchedEffect 的 key 必须是 uri。
    //   2026-11-06 诊断日志实锤（tv6-diag-autonext）：
    //     hitEnd=true dedup=false endedUri=null uri=null autoNext=true contPlay=true ep=null
    //   内核正常发了 STATE_ENDED，片尾条件全部满足，唯独「endedUri != uri」恒 false。
    //   根因：轮询原用 LaunchedEffect(Unit)，首次组合就启动；那时后台 SMB 目录还没扫完，
    //   c=null → remember(state.sel, c?.curEp) 建了 State A。目录扫完 key 变化又建 State B
    //   并写入真实片源，但协程捕获的是 A，读到的 uri 永远是 null。
    //   于是冷启动直达播放页时，自动下一节 / 连续播放 / 单曲循环全部空转。
    //   TCL 真机 2026-11-05 的片尾失效同因，**不是硬解不发结束事件**（该推测已自纠）。
    //   改成 LaunchedEffect(uri)：换集即重启协程；去重改用局部 handled，不再读过期 State。
    LaunchedEffect(uri) {
        var handled = false
        var lastPosMs = -1L
        var stalled = 0
        var cooldownUntil = 0L
        var frames = 0
        while (true) {
            kotlinx.coroutines.delay(250)
            frames++
            val liveDur = engine.durationMs
            // 内核 duration 在片尾可能变 0，用 setDuration 已缓存的真实总时长做第二基准。
            val dur = if (liveDur > 0) liveDur else state.totalSec * 1000L
            val pos = engine.positionMs
            liveDur.takeIf { it > 0 }?.let { state.setDuration((it / 1000).toInt()) }
            if (state.playing) state.tickFromPlayer((pos / 1000).toInt())

            val now = android.os.SystemClock.elapsedRealtime()
            // 双保险：内核毫秒值优先；其次用界面已经确认过的秒级总时长
            val nearEndByEngine = dur > 0 && pos >= dur - 1500
            val nearEndByState = state.totalSec > 0 && state.curSec >= state.totalSec
            val nearEnd = nearEndByEngine || nearEndByState
            // 只有「位置真的不再前进」才累计。正常播完最后一秒时 pos 每帧约 +250ms，
            // 不会被误判成卡死；片尾静止时 pos 恒定，连续 4 帧（约 1 秒）即判定片尾。
            if (nearEnd && state.playing && now >= cooldownUntil) {
                if (lastPosMs >= 0 && kotlin.math.abs(pos - lastPosMs) < 250) stalled++ else stalled = 0
                lastPosMs = pos
            } else {
                stalled = 0
                lastPosMs = -1L
            }
            val hitEnd = (engine.isEnded || stalled >= 4) && now >= cooldownUntil
            // 低频诊断：接近片尾时每帧一条，其余每 10 秒一条心跳。真机排查片尾问题用。
            if (nearEnd || engine.isEnded || frames % 40 == 0) {
                android.util.Log.d(
                    "PlayerPage",
                    "tick liveDur=$liveDur dur=$dur pos=$pos total=${state.totalSec} " +
                        "cur=${state.curSec} ended=${engine.isEnded} playing=${state.playing} " +
                        "loop=${state.loop} stalled=$stalled nearEnd=$nearEnd " +
                        "hitEnd=$hitEnd handled=$handled " +
                        "autoNext=${state.autoNext} contPlay=${state.contPlay} " +
                        "ep=${state.courses.getOrNull(state.sel)?.curEp} n=${state.epCount(state.sel)} " +
                        "cd=${cooldownUntil - now}"
                )
            }
            if (hitEnd && state.playing) {
                if (state.loop) {
                    // 单曲循环：每个片尾都主动回到开头，3 秒冷却防止 seek 生效前重复触发
                    stalled = 0
                    cooldownUntil = now + 3000
                    engine.seekToMs(0)
                    engine.playPause(true)
                } else if (!handled) {
                    // 一个片源只处理一次；onPlaybackEnded() 换集后 uri 变化会重启本协程
                    handled = true
                    stalled = 0
                    cooldownUntil = now + 3000
                    state.tickFromPlayer(state.totalSec)
                    state.onPlaybackEnded()
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---------- 画面区（全屏铺满）----------
        // ⚠️ 这里**不能**加 padding(top = 104.dp)：那是页面态给顶栏留的空白，
        // 播放态是全屏，加了就是画面上方一道黑边、可用高度少 104dp，
        // 画面整体缩小并且被推到底部（下方内容被裁）。
        Box(
            Modifier
                .fillMaxSize()
                // 镜像对"画面"生效：P0 画面区是占位水印，也一并翻转，
                // 这样遥控器点「镜像视频」能立刻看到变化；P3 接内核后换 TextureView.scaleX(-1f)
                .graphicsLayer { scaleX = if (state.mirror) -1f else 1f }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF241D16), Color(0xFF050505)),
                        center = with(LocalDensity.current) { Offset(960.dp.toPx(), 460.dp.toPx()) },
                        radius = with(LocalDensity.current) { 780.dp.toPx() },
                    )
                ),
        ) {
            if (uri != null) {
                // 真实画面：TextureView（SurfaceView 无法翻转，镜像必须用它）。
                // 尺寸不再写死 aspectRatio(DAR)：改为按「视频比例」档位算出画布宽高 ——
                //   原始   = 等比内接（不变形，可能留黑边）
                //   铺满   = 等比外接（cover，超出部分由 clipToBounds 裁掉，去黑边）
                //   16:9 / 4:3 / 21:9 = 强制画幅（播放器语义：按该比例出画）
                //   拉伸   = 直接撑满整屏
                // TextureView 默认把画面拉伸填满自身尺寸，所以画布比例就是最终显示比例。
                BoxWithConstraints(
                    Modifier.fillMaxSize().clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    val cw = maxWidth.value
                    val ch = maxHeight.value
                    if (cw > 0f && ch > 0f) {
                        val screenR = cw / ch
                        val targetR = when (state.ratioMode) {
                            AppState.RATIO_16_9 -> 16f / 9f
                            AppState.RATIO_4_3 -> 4f / 3f
                            AppState.RATIO_21_9 -> 21f / 9f
                            else -> videoRatio
                        }
                        val r = if (targetR > 0f && targetR.isFinite()) targetR else 16f / 9f
                        val w: Float
                        val h: Float
                        when (state.ratioMode) {
                            AppState.RATIO_STRETCH -> { w = cw; h = ch }
                            // 外接：保证两边都不小于容器（宁可溢出被裁，也不留黑边）
                            AppState.RATIO_COVER -> if (screenR > r) { w = cw; h = cw / r } else { w = ch * r; h = ch }
                            // 内接：完整放进容器
                            else -> if (screenR > r) { w = ch * r; h = ch } else { w = cw; h = cw / r }
                        }
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { context ->
                                android.view.TextureView(context).also { tv.value = it }
                            },
                            modifier = Modifier.size(w.dp, h.dp),
                        )
                    }
                }
            } else {
                // 没有片源时保留占位水印（并说明原因，不假装在播）
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt(c?.abbr ?: "", 132f, Color(0xFF3A2C21), 900, letterSpacing = 12f)
                    Spacer(Modifier.height(12.dp))
                    Txt(
                        state.catalogError ?: "这门课没有可播放的视频 · 到数据源页重新扫描",
                        19f, Color(0xFF8A6E55), letterSpacing = 1f, maxLines = 2,
                    )
                }
            }
        }

        if (state.failVisible) {
            FailLayer(state)
        } else {
            // ---------- 顶部课程信息（仅控制层态可见）----------
            if (state.ctl) {
                // 顶部蒙版：**固定 300dp 高、从画面最顶端（0dp）开始铺**。
                // 它与视频的宽高比、分辨率、显示位置全都无关 —— 只锚在屏幕顶部。
                // 之前这里带了 padding(top = 104.dp)（页面态给顶栏留的空白），画面区改成
                // 全屏后就整体下沉 104dp，看着像"蒙版跟着视频比例跑"，实际是残留偏移。
                // 也不能再用 Column 自适应高度（标题 1 行 / 2 行会让蒙版高度跳动），
                // 所以渐变单独做成固定高度的底层，文字浮在它上面。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .align(Alignment.TopStart)
                        // 原型是 rgba(0,0,0,.82) → transparent 的两段渐变，在暗色占位画面上够用；
                        // 但真实课程视频是**白底课件**，两段渐变到 40% 处只剩 50% 黑，
                        // 标题与水印叠在一起看不清。改成三段并加深顶部、加长尾部，
                        // 保证标题区接近纯黑、同时不明显切断画面。
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0xE8000000),
                                0.42f to Color(0xB8000000),
                                1f to Color.Transparent,
                            )
                        )
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(start = 60.dp, end = 60.dp, top = 30.dp)
                ) {
                    Txt(c?.name ?: "", 36f, Color(0xFFF7F4F0), 900)
                    Spacer(Modifier.height(9.dp))
                    Txt(epLine(state, c?.curEp ?: 1), 19f, Color(0xFFCFC8C0), maxLines = 2)
                }
            }

            // ---------- 右上角时长 OSD（两态同逻辑）----------
            if (state.osdVisible()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 112.dp, end = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x9E000000))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Txt("${fmt(state.curSec)} / ${fmt(state.totalSec)}", 25f, Color.White, 700)
                }
            }

            // ---------- 暂停符 ----------
            if (!state.playing) {
                PauseBars(
                    color = Color.White.copy(alpha = if (state.ctl) 0.72f else 0.46f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // 控制层态落在顶部蒙版（0～300dp）下方，全屏播放态维持 150dp
                        .padding(start = 64.dp, top = if (state.ctl) 330.dp else 150.dp),
                )
            }

            // ---------- 底部控制层 ----------
            // 真机上课程视频多为白底 PPT，控件直接压在画面上会完全看不清。
            // 按工具链 §19.3-3：叠加层不要用半透明纯色，要用"渐变到纯黑"，
            // 让控件落在近乎纯黑的区域，同时不切断画面。
            if (state.ctl) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(360.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.35f to Color(0x99000000),
                                0.7f to Color(0xE6000000),
                                1f to Color(0xFA000000),
                            )
                        )
                )
                ControlLayer(state, Modifier.align(Alignment.BottomStart), dar = videoRatio)
            }
        }

        // ---------- 自定义倍速对话框 ----------
        if (state.customOpen) CustomDialog(state)
    }
}

/**
 * 片源 = 扫描时登记好的「课程第 N 集的真实相对路径」→ smb:// URI。
 *
 * 旧实现每次播放都现列目录、按文件名取第 ep 个，而 coursePathFor 从未被写入过，
 * 结果所有课程播的都是同一个 root 下的视频；SMB 不通时还会静默退回设备私有目录里的
 * 演示 mp4（用户会把它当成自己的课）。两条都删掉：没有真实剧集就不给片源。
 * 连接与断线重连统一由 SmbDataSource.open() 负责。
 */
private fun resolvePlaySource(
    ctx: android.content.Context,
    rel: String?,
    cfgs: List<com.chunting.purecourse.data.SourceStore.Cfg> = emptyList(),
): String? {
    if (rel.isNullOrEmpty()) return null
    // 多源：路径前缀 @s2 指定了这条片子属于哪个源，必须用那个源的配置拼 URI。
    val id = com.chunting.purecourse.data.SrcPath.idOf(rel)
    val cfg = if (id != null) {
        // 先在内存列表里找（本地源只在内存里），再退回落盘的配置
        cfgs.firstOrNull { it.id == id }
            ?: com.chunting.purecourse.data.SourceStore.get(ctx, id)
    } else {
        // 无前缀的旧路径：归第一个**网络源**，不能归本地存储
        cfgs.firstOrNull { it.proto != com.chunting.purecourse.data.Proto.LOCAL }
            ?: com.chunting.purecourse.data.SourceStore.list(ctx).firstOrNull()
    } ?: return null
    return cfg.uriOf(com.chunting.purecourse.data.SrcPath.relOf(rel))
}

/** 控制层顶部：显示真实集名与下一集，不再套 12 集固定讲名 */
private fun epLine(state: AppState, cur: Int): String {
    val curName = state.epName(state.sel, cur - 1)
    val n = state.epCount(state.sel)
    val next = if (cur < n) "　（下一节：${state.epName(state.sel, cur)}）" else "　（已是最后一节）"
    return "第 $cur 节 · $curName$next"
}

/** 时长格式：超过 1 小时要显示 h:mm:ss，原来恒为 00:mm:ss 会变成「00:75:00」 */
private fun fmt(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, ss) else "%02d:%02d".format(m, ss)
}

@Composable
private fun ControlLayer(state: AppState, modifier: Modifier = Modifier, dar: Float = 16f / 9f) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 56.dp, bottom = 52.dp)
    ) {
        // 倍速上拉菜单（锚在菜单栏上方 18）
        if (state.speedMenu) {
            SpeedMenu(state)
            Spacer(Modifier.height(18.dp))
        }
        // 视频比例上拉菜单：与倍速同位置、同尺寸、同材质，只是内容换成画幅档位
        if (state.ratioMenu) {
            RatioMenu(state)
            Spacer(Modifier.height(18.dp))
        }

        Track(state)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                // 原型时间行是 00:14:17 / 00:44:35；现在由 fmt 按真实时长给出 h:mm:ss
                Txt(fmt(state.curSec), 16f, Color.White, 700)
                Txt(" / ${fmt(state.totalSec)}", 16f, Color(0xFFCFC8C0))
            }
            Row {
                Txt("剩余 ", 16f, Color(0xFFCFC8C0))
                Txt(fmt(state.totalSec - state.curSec), 16f, Color.White, 700)
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MenuBtn(state, 0, "⏮ 上一集")
            MenuBtn(state, 1, "⏭ 下一集")
            MenuBtn(state, 2, "⚡ 倍速 ${state.spdText(state.speed)}", on = state.speed != 1f)
            RatioMenuBtn(state)
            MenuBtn(state, 4, if (state.mirror) "⇄ 镜像视频 ·已开" else "⇄ 镜像视频", on = state.mirror)
            MenuBtn(state, 5, if (state.loop) "🔁 单曲循环 ·已开" else "🔁 单曲循环", on = state.loop)
        }

        Spacer(Modifier.height(12.dp))
        Txt(
            "← → ${if (state.ctl) 60 else state.seekStep} 秒快移　·　↑ ↓ 切换控件　·　确认 收起面板　·　返回 退出播放",
            15f, Color(0xFFB9B1A9), letterSpacing = 0.3f,
        )
    }
}

@Composable
private fun MenuBtn(
    state: AppState,
    i: Int,
    label: String,
    on: Boolean = false,
) {
    val focused = state.focusTag == "pmenu:$i"
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .height(62.dp)
            // 与控制中心其他主操作统一：焦点 = 白色实底反白，激活 = 橙色淡底
            .tvFocus(focused, 16.dp, 1.02f, drawRing = false)
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFF5F2EE)
                    on -> Color(0x33FF8E52)
                    else -> Color(0x14FFFFFF)
                }
            )
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    on -> Color(0x66FF8E52)
                    else -> Color(0x24FFFFFF)
                },
                shape,
            )
            .padding(horizontal = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fg = when {
            focused -> Color(0xFF1B1512)
            on -> Color(0xFFFFD2B0)
            else -> Color(0xFFF1EBE5)
        }
        Txt(label, 21f, fg, if (focused) 900 else 700)
    }
}

/**
 * 「视频比例」按钮：左侧图形为屏幕画框 + 按当前档位真实宽高比内接的画面块。
 * 原始比例直接取播放内核回报的 DAR，片源是 4:3 / 21:9 时图标会跟着变形。
 */
@Composable
private fun RatioMenuBtn(state: AppState) {
    val focused = state.focusTag == "pmenu:3"
    val active = state.ratioMode != AppState.RATIO_ORIGINAL
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .height(62.dp)
            .tvFocus(focused, 16.dp, 1.02f, drawRing = false)
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFF5F2EE)
                    active -> Color(0x33FF8E52)
                    else -> Color(0x14FFFFFF)
                }
            )
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    active -> Color(0x66FF8E52)
                    else -> Color(0x24FFFFFF)
                },
                shape,
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val fg = when {
            focused -> Color(0xFF1B1512)
            active -> Color(0xFFFFD2B0)
            else -> Color(0xFFF1EBE5)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RatioGlyph(state.ratioMode, fg)
            Spacer(Modifier.width(10.dp))
            Txt(if (active) state.ratioText() else "视频比例", 21f, fg, if (focused) 900 else 700)
        }
    }
}

/**
 * 「视频比例」按钮的图形标记：外层是屏幕画框。
 * 未选档位时框内画「左右双向箭头」，表示画幅可拉伸调整；已选档位时框内直接画出该档位的真实画幅块。
 * 两种形态都由粗线条构成、保留足够间隙，缩到 30x18dp 也不会糊成一条扁条。
 */
@Composable
private fun RatioGlyph(mode: Int, tint: Color) {
    Canvas(Modifier.size(30.dp, 18.dp)) {
        val stroke = size.height * 0.11f
        drawRoundRect(
            color = tint.copy(alpha = 0.95f),
            cornerRadius = CornerRadius(size.height * 0.22f),
            style = Stroke(width = stroke),
        )
        val cy = size.height / 2f
        if (mode == AppState.RATIO_ORIGINAL) {
            val l = size.width * 0.26f
            val r = size.width * 0.74f
            drawLine(tint, Offset(l, cy), Offset(r, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            val hw = size.height * 0.17f
            val hd = size.height * 0.20f
            drawPath(
                Path().apply {
                    moveTo(l - hd * 0.3f, cy)
                    lineTo(l + hd, cy - hw)
                    lineTo(l + hd, cy + hw)
                    close()
                },
                tint,
            )
            drawPath(
                Path().apply {
                    moveTo(r + hd * 0.3f, cy)
                    lineTo(r - hd, cy - hw)
                    lineTo(r - hd, cy + hw)
                    close()
                },
                tint,
            )
        } else {
            val padX = size.height * 0.30f
            val padY = size.height * 0.28f
            val boxW = size.width - padX * 2
            val boxH = size.height - padY * 2
            val target = when (mode) {
                AppState.RATIO_COVER, AppState.RATIO_STRETCH -> boxW / boxH
                AppState.RATIO_16_9 -> 16f / 9f
                AppState.RATIO_4_3 -> 4f / 3f
                AppState.RATIO_21_9 -> 21f / 9f
                else -> 16f / 9f
            }
            var w = boxW
            var h = w / target
            if (h > boxH) {
                h = boxH
                w = h * target
            }
            drawRoundRect(
                color = tint.copy(alpha = 0.88f),
                topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(h * 0.22f),
            )
        }
    }
}

/** 进度条：h14、圆角 8；获焦时滑块放大 1.5 倍并转白 */
@Composable
private fun Track(state: AppState) {
    val focused = state.focusTag == "ptrack"
    // 时长还没从播放器回填时 totalSec=0，直接相除会得到 NaN，滑块会被画到画布外
    val pct = if (state.totalSec > 0) (state.curSec.toFloat() / state.totalSec).coerceIn(0f, 1f) else 0f

    // 焦点只落在小球上（老板明确要求：不要整条高亮）。
    // 容器 28dp = 轨道 14dp + 上下各 7dp，给放大后的滑块留余量，避免被裁。
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = size.height
            val cy = h / 2f
            val r = 7.dp.toPx()
            // 轨道
            drawRoundRect(
                color = Color(0x1CFFFFFF),
                size = size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
            // 已播放
            if (pct > 0f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFFFFA978), Color(0xFFFF8E52), Color(0xFFFFC49A)),
                        endX = (size.width * pct).coerceAtLeast(1f),
                    ),
                    size = androidx.compose.ui.geometry.Size((size.width * pct).coerceAtLeast(1f), h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                )
            }
            // 滑块：基础 9dp 半径，获焦 11dp（1.22 倍，比原型的 1.5 倍克制）
            val knobR = if (focused) 11.dp.toPx() else 9.dp.toPx()
            val cx = (size.width * pct).coerceIn(knobR, size.width - knobR)
            if (focused) {
                // 柔光：画在球底下，只做氛围，不会在球上留线条
                drawCircle(
                    color = Color(0x4DFF8E52),
                    radius = knobR + 3.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
            // 球体：先白圆（完全盖住轨道），再在圆**内侧**描橙边，保证没有线穿过球
            drawCircle(color = Color.White, radius = knobR, center = Offset(cx, cy))
            if (focused) {
                val stroke = 2.5.dp.toPx()
                drawCircle(
                    color = Color(0xFFFF8E52),
                    radius = knobR - stroke / 2f,
                    center = Offset(cx, cy),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            }
        }
    }
}

@Composable
private fun SpeedMenu(state: AppState) {
    val entries = listOf(
        "自定义…" to state.spdText(state.customVal),
        "0.5x" to "", "0.75x" to "", "1.0x" to "", "1.5x" to "", "2.0x" to "", "3.0x" to "",
    )
    Column(
        Modifier
            .width(430.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(C.Panel)
            .border(1.dp, C.Line2, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Txt("播放速度", 16f, C.Dim, letterSpacing = 2f, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        entries.forEachIndexed { i, (label, extra) ->
            val focused = state.focusTag == "psi:$i"
            val isCur = when (i) {
                0 -> state.customSet && state.speed == state.customVal
                else -> !state.customSet && state.playMenuSpeeds[i - 1] == state.speed
            }
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .height(56.dp)
                    .tvFocus(focused, 14.dp, 1f, drawRing = false)
                    .clip(shape)
                    .background(if (focused) C.AccSoft else C.Veil)
                    .border(1.dp, if (focused) Color.Transparent else C.Line, shape)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(label, 20f, if (focused) C.Text else if (isCur) C.Acc2 else C.TextSoft, 700)
                Spacer(Modifier.weight(1f))
                if (extra.isNotEmpty()) Txt(extra, 18f, C.Acc2, 700)
                if (isCur) {
                    Box(
                        Modifier.padding(start = 12.dp).clip(RoundedCornerShape(6.dp))
                            .background(C.Hi).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Txt("当前", 14f, C.Acc2) }
                }
            }
        }
    }
}

/**
 * 视频比例上拉菜单：与 SpeedMenu 共用同一套材质与尺寸令牌
 * （宽 430 / 圆角 20 / C.Panel 底 / C.Line2 描边 / 条目 56dp / 焦点 C.AccSoft / 当前项 C.Acc2 + 徽标），
 * 保证两个弹层在明暗主题与强调色下表现完全一致。
 */
@Composable
private fun RatioMenu(state: AppState) {
    // 右侧小字只作语义提示，当前项用「当前」徽标表达，不重复显示
    val notes = listOf("按片源", "去黑边", "强制", "强制", "强制", "不保比例")
    Column(
        Modifier
            .width(430.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(C.Panel)
            .border(1.dp, C.Line2, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Txt("视频比例", 16f, C.Dim, letterSpacing = 2f, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
        state.ratioPresets.forEachIndexed { i, label ->
            val focused = state.focusTag == "prt:$i"
            val isCur = i == state.ratioMode
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .height(56.dp)
                    .tvFocus(focused, 14.dp, 1f, drawRing = false)
                    .clip(shape)
                    .background(if (focused) C.AccSoft else C.Veil)
                    .border(1.dp, if (focused) Color.Transparent else C.Line, shape)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(label, 20f, if (focused) C.Text else if (isCur) C.Acc2 else C.TextSoft, 700)
                Spacer(Modifier.weight(1f))
                val note = notes.getOrElse(i) { "" }
                if (note.isNotEmpty() && !isCur) Txt(note, 15f, C.Dim2)
                if (isCur) {
                    Box(
                        Modifier.padding(start = 12.dp).clip(RoundedCornerShape(6.dp))
                            .background(C.Hi).padding(horizontal = 8.dp, vertical = 2.dp)
                    ) { Txt("当前", 14f, C.Acc2) }
                }
            }
        }
    }
}

@Composable
private fun CustomDialog(state: AppState) {
    Box(
        Modifier.fillMaxSize().background(C.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(760.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(C.SheetFrom, C.SheetTo)))
                .border(1.dp, C.Line2, RoundedCornerShape(24.dp))
                .padding(start = 52.dp, end = 52.dp, top = 44.dp, bottom = 38.dp)
        ) {
            Txt("自定义播放速度", 22f, C.Dim, 900, letterSpacing = 0.6f)
            Spacer(Modifier.height(14.dp))
            // 原型 `.pc-v{display:flex;align-items:baseline;gap:6px}` + `b:66px / span:30px`
            // 之前用了底对齐（Bottom），X 会沉到数字脚下，看着又小又偏；这里改成基线对齐，
            // 并把 X 从 30 提到 34（66 的 0.52 倍），遥控器 3 米外也能看清是"倍"。
            Row {
                Txt(
                    state.spdText(state.customVal).removeSuffix("x"),
                    66f, C.Acc, 900, lineHeight = 66f,
                    modifier = Modifier.alignByBaseline(),
                )
                Txt(
                    "x", 34f, C.Acc, 900, lineHeight = 34f,
                    modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
                )
            }
            Spacer(Modifier.height(30.dp))

            val pct = (state.customVal - 0.2f) / (3f - 0.2f)
            // 用 BoxWithConstraints 拿真实可用宽度算滑块位置：
            // 之前写死 756dp，但内容宽只有 760-52*2=656dp，球会跑到轨道右边外面
            BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
                val trackW = maxWidth
                val knob = 26.dp
                Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    drawRoundRect(
                        color = C.Line3, size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
                    )
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(C.AccDeep, C.Acc)),
                        size = androidx.compose.ui.geometry.Size(size.width * pct, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f, size.height / 2f),
                    )
                }
                Box(
                    Modifier
                        .offset(x = (trackW - knob) * pct)
                        .size(knob)
                        .clip(CircleShape)
                        .background(C.Panel)
                        .border(3.dp, C.Acc, CircleShape)
                )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Txt("0.2x", 16f, C.Dim2)
                Txt("3.0x", 16f, C.Dim2)
            }
            Spacer(Modifier.height(26.dp))
            Txt("← → 每档 0.05 　·　确认 应用 　·　返回 取消", 15f, C.Dim2)
        }
    }
}

/**
 * 读取失败层（#p-fail）：SMB 连接数耗尽时全屏接管，绝不静默黑屏。
 * 三个按钮：重试读取 / 查看解决步骤 / 返回课程。
 */
@Composable
private fun FailLayer(state: AppState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(C.Bg, C.Bg2))
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(900.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(C.Line).border(3.dp, C.Warn, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Txt("!", 44f, C.Warn, 900) }
            Spacer(Modifier.height(18.dp))
            Txt("视频读不出来：电脑那边连接满了", 31f, C.Text, 800)
            Spacer(Modifier.height(12.dp))
            Txt(
                "已连续 ${state.smbFailN} 次读不到数据。不是网络断了，是 Windows 共享的并发连接（默认 20 条）被占满，新连接一律被拒，所以画面一直黑、进度条不走。",
                19f, C.Dim, maxLines = 3, lineHeight = 31f,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf("↻ 重试读取", "查看解决步骤", "返回课程").forEachIndexed { i, label ->
                    val focused = state.focusTag == "pfail:$i"
                    val shape = RoundedCornerShape(99.dp)
                    Box(
                        Modifier
                            .height(62.dp)
                            .width(200.dp)
                            .tvFocus(focused, 99.dp, 1f, drawRing = false)
                            .clip(shape)
                            .background(if (focused) C.FocusSolid else C.Veil)
                            .border(1.dp, if (focused) Color.Transparent else C.Line2, shape),
                        contentAlignment = Alignment.Center,
                    ) { Txt(label, 19f, if (focused) C.BtnInk else C.Text, 800) }
                }
            }
        }
    }
}
