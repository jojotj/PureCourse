package com.chunting.purecourse.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 统一文字：原型全站 font-family 为系统中文黑体，Android 侧用默认 sans 即可 */
@Composable
fun Txt(
    text: String,
    size: Float,
    color: Color = C.Text,
    weight: Int = 400,
    maxLines: Int = 1,
    letterSpacing: Float = 0f,
    lineHeight: Float = 0f,
    modifier: Modifier = Modifier,
    align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    // 遥控器每按一下都会重组整屏；TextStyle 每次新建会让 BasicText 认为样式变了。
    // 按参数记住，只有真正变化的属性才重建（收藏页一屏二三十个 Txt，收益明显）。
    val style = androidx.compose.runtime.remember(size, color, weight, letterSpacing, lineHeight, align) {
        TextStyle(
            fontSize = size.sp,
            color = color,
            fontWeight = FontWeight(weight),
            letterSpacing = letterSpacing.sp,
            lineHeight = (if (lineHeight > 0f) lineHeight else size * 1.25f).sp,
            textAlign = align ?: androidx.compose.ui.text.style.TextAlign.Start,
        )
    }
    BasicText(text = text, modifier = modifier, maxLines = maxLines, style = style)
}

/**
 * 封面渐变：`linear-gradient(145deg, c1, c2 82%)` + 右上角高光。
 * 必须按**实际尺寸**铺，否则渐变终点落在画布外，颜色被摊平（实测与原型偏差明显）。
 */
@Composable
fun CoverGradient(
    c1: Color,
    c2: Color,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(shape)
            // drawWithCache：四段渐变的 Brush 按尺寸算一次就缓存住。
            // 原来写在 drawBehind 里，列表滚动时每帧重绘都要重新 new 四个 Brush（还带 GC），
            // 一屏七八张封面同帧分配几十上百个对象，正是滑起来发涩的来源之一。
            .drawWithCache {
                val base = Brush.linearGradient(
                    colorStops = arrayOf(0f to c1, 0.82f to c2),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
                val highlight = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(size.width * 0.84f, 0f),
                    radius = size.width * 1.15f,
                )
                // 底部暗角：左下角课程缩写、右上角集数角标在任何配色上都能读清
                // （Apple TV / Netflix 的海报统一做法，比加描边更干净）
                val vignette = Brush.verticalGradient(
                    colorStops = arrayOf(0.52f to Color.Transparent, 1f to Color(0x66000000)),
                    startY = 0f,
                    endY = size.height,
                )
                // 左下柔光：避免整块封面显得平
                val soft = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(0f, size.height),
                    radius = size.width * 0.85f,
                )
                onDrawBehind {
                    drawRect(base)
                    drawRect(highlight)
                    drawRect(vignette)
                    drawRect(soft)
                }
            }
    )
}

/** 进度条（原型 .bar / .h-bar / .d-bar / .mini / .mb 的公共实现） */
@Composable
fun Bar(
    pct: Int,
    height: Dp,
    modifier: Modifier = Modifier,
    track: Color = Color.White.copy(alpha = 0.09f),
    from: Color = C.AccDeep,
    to: Color = C.Acc2,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(99.dp))
            .background(track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(pct / 100f)
                .height(height)
                .clip(RoundedCornerShape(99.dp))
                .background(Brush.horizontalGradient(listOf(from, to)))
                .drawBehind {
                    // 顶部高光，让进度条有圆柱质感（静态绘制，无额外合成开销）
                    drawRect(
                        Brush.verticalGradient(listOf(Color(0x45FFFFFF), Color.Transparent)),
                        size = Size(size.width, size.height * 0.58f),
                    )
                }
        )
    }
}

/** 播放三角（自绘，避免国产 ROM 缺字形渲染成豆腐块） */
@Composable
fun PlayTriangle(sizeDp: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp.dp, sizeDp.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.22f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.22f, size.height)
            close()
        }
        drawPath(p, color)
    }
}

/** 暂停符：两根 11×49、圆角 4 的竖条（文档 §3.8 的重点数值） */
@Composable
fun PauseBars(color: Color, modifier: Modifier = Modifier, w: Float = 11f, h: Float = 49f) {
    androidx.compose.foundation.layout.Row(
        modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(13.dp),
    ) {
        repeat(2) {
            Box(
                Modifier
                    .width(w.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

/** 五角星（收藏标记） */
@Composable
fun StarIcon(sizeDp: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(sizeDp.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val rOut = size.width / 2
        val rIn = rOut * 0.42f
        val path = Path()
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) rOut else rIn
            val a = (-90.0 + i * 36.0) * Math.PI / 180.0
            val x = (cx + r * Math.cos(a)).toFloat()
            val y = (cy + r * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/** 通用「卡片 + 焦点」容器：统一边框、底色、圆角、缩放与焦点环 */
@Composable
fun FocusCard(
    focused: Boolean,
    radius: Dp,
    bg: Color,
    border: Color,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 2.dp,
    focusBg: Color = C.AccSoft,
    scale: Float = 1.032f,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .tvFocus(focused, radius, scale)
            // 聚焦时不再画边框：白色焦点环已经就是描边，双层会变成「双白线」
            .clip(shape)
            .background(if (focused) focusBg else bg)
            .border(borderWidth, if (focused) Color.Transparent else border, shape),
        content = content,
    )
}

/** 分隔线 */
@Composable
fun HLine(color: Color = C.Line, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** 空占位（高度等于 spacing） */
@Composable
fun VSpace(h: Float) {
    Box(Modifier.height(h.dp))
}

/**
 * 遥控器「焦点锚定」滚动 —— 全站所有竖向列表统一走这里。
 *
 * 参考 tvOS / Apple TV 上多选项列表的通用范式（也是用户要的效果）：
 *  - 列表能滚时，**焦点框停在视口正中不动，是背后的内容在上下走**；
 *  - 只有滚到首 / 尾、内容已经顶死的时候，焦点框才自己往下 / 往上挪；
 *  - 上下各留半屏上下文，永远不会出现「按一下翻一屏、光标又弹回最上面」。
 *
 * 实现口径：把焦点项的中心与视口中心的差值交给 [scrollBy]，
 * 边界由 Compose 自己 clamp（首项上方 / 末项下方滚不过去），所以「到顶 / 到底光标才动」
 * 是自然结果，不需要另外写边界分支。
 *
 * 帧同步的「临界阻尼跟随」滚动（tvOS / UIScrollView 的手感）。
 *
 * 不用 animateScrollBy + tween 的原因：那个做法每按一次键都从 **0 速度**重新起一段缓动，
 * 连按（间隔小于动画时长）时速度被反复打断、归零再重新加速，画面就是一顿一顿的、累眼。
 * 这里改成每帧自己算速度：目标速度与剩余距离成正比，速度本身再走一次低通滤波，
 * 于是连按时速度连续不归零，只有剩余距离变短才自然减速收住 ——
 * 这正是 Apple 系 App 长列表连按方向键时「内容匀速滑、边滑边收」的观感来源。
 *
 * @param distance 每帧重算的剩余距离（目标项中心 − 视口中心，>0 表示还要继续往下滚）
 * @param scrollByPx 执行一次滚动，返回实际消耗的像素（到边界时返回 0，直接收工）
 */
private suspend fun smoothFollowCenter(
    distance: () -> Float,
    scrollByPx: suspend (Float) -> Float,
) {
    var vel = 0f
    var prevNanos = withFrameNanos { it }
    var guard = 0
    while (guard++ < 400) {
        val now = withFrameNanos { it }
        val dt = ((now - prevNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
        prevNanos = now
        val d = distance()
        // 收敛系数 12/s：一格的距离几百毫秒内到位；上限 9000px/s 防止跨屏时糊成一片
        val targetVel = (d * 12f).coerceIn(-9000f, 9000f)
        // 速度低通（时间常数 ~55ms）：连按打断的瞬间速度不断崖
        val k = 1f - kotlin.math.exp(-dt / 0.055f)
        vel += (targetVel - vel) * k
        val move = vel * dt
        if (kotlin.math.abs(move) >= 0.35f) {
            if (scrollByPx(move) == 0f) return      // 已顶死 / 到底
        }
        if (kotlin.math.abs(d) < 0.6f && kotlin.math.abs(vel) < 8f) return
    }
}

/** 列表版（书架、收藏右侧列表、设置、浏览右侧列表）：把焦点项锚到视口正中 */
suspend fun androidx.compose.foundation.lazy.LazyListState.ensureIndexVisible(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty() || info.viewportEndOffset <= info.viewportStartOffset) {
        scrollToItem(index); return
    }
    // 目标不在视口（跨屏跳转 / 返回还原焦点）：先粗定位到「目标落在视口中段」的那一屏，
    // 收尾仍交给跟随动画，避免原来「顶对齐 + 瞬移校正」那一下硬跳。
    if (info.visibleItemsInfo.none { it.index == index }) {
        val span = info.visibleItemsInfo.size
        // 注意：LazyListState 本身没有 totalItemsCount（那是 LazyGridState 的属性），
        // 列表侧的项数要从 layoutInfo 快照取。
        val anchor = (index - span / 2).coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
        scrollToItem(anchor)
    }
    smoothFollowCenter(
        distance = {
            val li = layoutInfo
            val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
            li.visibleItemsInfo.firstOrNull { it.index == index }
                ?.let { it.offset + it.size / 2f - center } ?: 0f
        },
        scrollByPx = { scrollBy(it) },
    )
}

/** 同上，网格版（详情页剧集、数据源卡片、书架）：按「行」锚定在视口正中 */
suspend fun androidx.compose.foundation.lazy.grid.LazyGridState.ensureIndexVisible(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    if (info.visibleItemsInfo.isEmpty() || info.viewportEndOffset <= info.viewportStartOffset) {
        scrollToItem(index); return
    }
    if (info.visibleItemsInfo.none { it.index == index }) {
        val span = info.visibleItemsInfo.size
        // 同上：项数统一从 layoutInfo 快照取，避免依赖不同 Compose 版本上的 state 属性。
        val anchor = (index - span / 2).coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
        scrollToItem(anchor)
    }
    smoothFollowCenter(
        distance = {
            val li = layoutInfo
            val center = (li.viewportStartOffset + li.viewportEndOffset) / 2f
            li.visibleItemsInfo.firstOrNull { it.index == index }
                ?.let { it.offset.y + it.size.height / 2f - center } ?: 0f
        },
        scrollByPx = { scrollBy(it) },
    )
}

/**
 * 长名字滚动播报（收藏页 / 数据源页的目录树与右侧列表共用）。
 *
 * 照着 tvOS 上 Apple TV「影片名放不下」的做法，不是那种一直晃的跑马灯：
 *  - **只有在焦点落上来时才滚**，没聚焦就静止停在开头（一屏十几行标题同时跑会晃得没法看）；
 *  - 滚之前先在开头停一下（默认 1.5s），让人先把前半段读进去；
 *  - 从头滚到名字末尾（末尾再留一小段空白），然后**瞬回起始位置**；
 *  - 回到起始位置后再停 1.5s，才滚下一圈，如此往复直到焦点移开；
 *  - 焦点一移开立刻复位到开头，不留半截偏移。
 *
 * 名字没超宽时完全不滚（和普通 [Txt] 一样，不额外产生动画与测量开销）。
 */
@Composable
fun MarqueeTxt(
    text: String,
    size: Float,
    color: Color = C.Text,
    weight: Int = 400,
    focused: Boolean,
    modifier: Modifier = Modifier,
    startHoldMs: Long = 1500L,
    endHoldMs: Long = 700L,
    speedDpPerSec: Float = 62f,
    letterSpacing: Float = 0f,
) {
    val style = remember(size, color, weight, letterSpacing) {
        TextStyle(
            fontSize = size.sp,
            color = color,
            fontWeight = FontWeight(weight),
            letterSpacing = letterSpacing.sp,
            lineHeight = (size * 1.25f).sp,
        )
    }
    val density = LocalDensity.current
    // 整名真实宽度：文本按「不受宽度约束」排版，布局完回填；受约束时长名会被裁，量不出真实长度
    var fullWidthPx by remember { mutableIntStateOf(0) }
    val offsetX = remember { Animatable(0f) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val availPx = constraints.maxWidth
        val overflow = availPx > 0 && fullWidthPx > availPx
        LaunchedEffect(focused, overflow, fullWidthPx, availPx) {
            if (!focused || !overflow) {
                offsetX.snapTo(0f)
                return@LaunchedEffect
            }
            val tailGapPx = with(density) { 28.dp.toPx() }
            val dist = (fullWidthPx - availPx).toFloat() + tailGapPx
            val pxPerSec = with(density) { speedDpPerSec.dp.toPx() }
            val durMs = ((dist / pxPerSec) * 1000f).toInt().coerceIn(900, 9000)
            while (true) {
                delay(startHoldMs)      // 起始位置停留
                offsetX.animateTo(-dist, tween(durMs, easing = LinearEasing))
                delay(endHoldMs)      // 末尾稍停，最后几个字才看得清
                offsetX.snapTo(0f)      // 瞬回起始位置
            }
        }
        Box(Modifier.offset { IntOffset(offsetX.value.roundToInt(), 0) }) {
            BasicText(
                text = text,
                style = style,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.wrapContentWidth(unbounded = true),
                onTextLayout = { fullWidthPx = it.size.width },
            )
        }
    }
}
