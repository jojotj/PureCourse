package com.chunting.purecourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.TOPBAR_H
import com.chunting.purecourse.core.TOPBAR_PAD
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.HomeTab
import com.chunting.purecourse.state.Page

/**
 * 常驻顶栏。
 *
 * 位置：上方留白 46 + 条高 80（1920×1080 画布），即视觉上抬到约 11.7% 屏高，
 * 不再贴着屏幕上沿；标签 28 / 品牌 32 / 时钟 22，遥控器三米外也认得出。
 * 播放页整条隐藏（`#stage[data-page="player"] .topbar{display:none}`）。
 */
@Composable
fun TopBar(state: AppState) {
    if (state.page == Page.Player) return

    // 原型右上角是写死的「20:41」演示时间；接真数据后改成系统真实时钟
    var clock by remember { mutableStateOf(currentClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            clock = currentClock()
        }
    }

    val cool = state.page != Page.Home
    // 玻璃质感：条体压得比底色更深一档，靠底部一条细线收边。
    // 明色主题下同样成立：底色是米白、条体是更亮的面板色，层级关系保持一致。
    val bg = if (cool) {
        Brush.verticalGradient(listOf(C.Bg2.copy(alpha = 0.97f), C.Bg.copy(alpha = 0.92f)))
    } else {
        Brush.verticalGradient(listOf(C.Bg.copy(alpha = 0.97f), C.Bg.copy(alpha = 0.90f)))
    }

    Column(Modifier.fillMaxWidth().background(bg)) {
        Spacer(Modifier.height(TOPBAR_PAD.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(TOPBAR_H.dp)
                .padding(start = 96.dp, end = 96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // ---- 品牌 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(C.Acc2, C.Acc, C.AccDeep),
                                start = androidx.compose.ui.geometry.Offset.Zero,
                                end = androidx.compose.ui.geometry.Offset(72f, 72f),
                            )
                        )
                        .border(1.dp, C.Line2, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt("▤", 23f, C.BtnInk, 900)
                }
                Column {
                    Txt("纯看课", 32f, C.Text, 800, letterSpacing = 0.8f)
                    Spacer(Modifier.height(3.dp))
                    Txt(state.sourceLabel(), 14f, C.Dim2, 400, letterSpacing = 1.6f, maxLines = 1)
                }
            }

            Spacer(Modifier.width(28.dp))

            // ---- 三个入口：书架 / 收藏 / 设置（焦点到达即切页，120ms 防抖在 Nav 里）----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton(state, "top:tab:shelf", "书架", state.tab == HomeTab.Shelf && state.page == Page.Home)
                TabButton(state, "top:tab:fav", "收藏", state.tab == HomeTab.Fav && state.page == Page.Home)
                TabButton(
                    state, "top:set", "设置",
                    state.page == Page.Settings || state.page == Page.Sources || state.page == Page.AddSrc,
                )
            }

            Spacer(Modifier.weight(1f))
            Txt(clock, 22f, C.Dim2, 500, letterSpacing = 1.2f)
        }
        HLine(C.Line2)
    }
}

private fun currentClock(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date())

@Composable
private fun TabButton(state: AppState, tag: String, label: String, active: Boolean) {
    val focused = state.focusTag == tag
    val shape = RoundedCornerShape(99.dp)
    // 焦点 = 胶囊「反白」：暗色主题是近白底深字，明色主题是墨底白字（C.FocusSolid/FocusInk）。
    // 当前页但未聚焦 = 一层极淡蒙层；其余纯文字。比橙色描边框干净。
    val bg = when {
        focused -> C.FocusSolid
        active -> C.Veil
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = when {
        focused -> C.FocusInk
        active -> C.Text
        else -> C.Dim
    }
    Box(
        Modifier
            // 实底胶囊本身已经是强对比焦点，再套焦点环会糊成一片，所以关掉环
            .tvFocus(focused, 99.dp, 1f, drawRing = false)
            .height(64.dp)
            .clip(shape)
            .background(bg)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, 27f, fg, if (focused || active) 800 else 600, letterSpacing = 0.6f)
    }
}
