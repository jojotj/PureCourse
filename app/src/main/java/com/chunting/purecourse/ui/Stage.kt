package com.chunting.purecourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.STAGE_H
import com.chunting.purecourse.core.STAGE_W
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.Page
import com.chunting.purecourse.ui.pages.AddSourcePage
import com.chunting.purecourse.ui.pages.BrowsePage
import com.chunting.purecourse.ui.pages.DetailPage
import com.chunting.purecourse.ui.pages.FavPage
import com.chunting.purecourse.ui.pages.PlayerPage
import com.chunting.purecourse.ui.pages.SettingsPage
import com.chunting.purecourse.ui.pages.ShelfPage
import com.chunting.purecourse.ui.pages.SourcesPage

/**
 * 舞台画布：原型 `#stage` 固定 1920×1080，JS 用 `transform: scale(min(vw/1920, vh/1080))`。
 *
 * 这里**不用 graphicsLayer 放大**（那等于先按 1080p 画一张位图再拉伸，4K 电视上字会发虚），
 * 而是把 `LocalDensity` 重映射成 `fit = min(屏宽/1920, 屏高/1080)`：
 *  - 1080p：fit = 1.0，1 设计单位 = 1 物理像素
 *  - 4K：fit = 2.0，1 设计单位 = 2 物理像素（**按 4K 原生分辨率重新排版与描字**）
 *  - 2K/其他：fit 按比例，始终保持 16:9 等比、四周留黑边、版式完全一致
 *
 * 因此全工程的尺寸只有一个基准：**1920×1080 设计空间里的 dp**。
 * 代码里禁止出现"屏幕像素"常量（画布里也要写 `x.dp.toPx()`），否则换分辨率就会走形。
 */
@Composable
fun StageCanvas(content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // 每个设计单位对应多少物理像素（= 原型的缩放系数 S）
        val fit = minOf(
            constraints.maxWidth.toFloat() / STAGE_W,
            constraints.maxHeight.toFloat() / STAGE_H,
        )
        CompositionLocalProvider(LocalDensity provides Density(fit, 1f)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(STAGE_W.dp, STAGE_H.dp)
                    .background(C.Bg),
                content = content,
            )
        }
    }
}

/** 根界面：顶栏常驻（播放页隐藏）+ 页面体 + 弹层 */
@Composable
fun AppRoot(state: AppState) {
    // 主题注入点：主题模式（跟随/明亮/夜晚）× 强调色 → core 里的整套令牌。
    // remember 保证只在真正变化时才写，组合期不会空转；令牌变化会让读取它的组件自动重组。
    val sysDark = isSystemInDarkTheme()
    val dark = when (state.themeMode) {
        com.chunting.purecourse.core.ThemeMode.LIGHT -> false
        com.chunting.purecourse.core.ThemeMode.DARK -> true
        else -> sysDark
    }
    remember(state.accentIndex, dark) { C.applyTheme(state.accentIndex, dark) }

    StageCanvas {
        if (state.page == Page.Player) {
            PlayerPage(state)
        } else {
            Column(Modifier.fillMaxSize()) {
                TopBar(state)
                Box(Modifier.fillMaxSize()) {
                    when (state.page) {
                        Page.Home -> if (state.tab == com.chunting.purecourse.state.HomeTab.Shelf) {
                            val d = LocalDensity.current
                            // 首页氛围光：三段径向渐变（品牌暖光 → 极淡橙 → 透明），
                            // 比原来单段 0x24 橙更柔，避免顶部一块"橙色污渍"
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.radialGradient(
                                        colors = listOf(C.Ambient, C.Hi.copy(alpha = 0.06f), Color.Transparent),
                                        center = with(d) {
                                            androidx.compose.ui.geometry.Offset(960.dp.toPx(), (-160).dp.toPx())
                                        },
                                        radius = with(d) { 1200.dp.toPx() },
                                    )
                                )
                            ) { ShelfPage(state) }
                        } else FavPage(state)
                        Page.Detail -> DetailPage(state)
                        Page.Browse -> BrowsePage(state)
                        Page.Settings -> SettingsPage(state)
                        Page.Sources -> SourcesPage(state)
                        Page.AddSrc -> AddSourcePage(state)
                        Page.Player -> Unit
                    }
                }
            }
        }
        OverlayLayer(state)
        // 真实目录扫描是后台网络请求：加载中 / 失败都要让用户看见，不能只留一个空书架
        if (state.page != Page.Player && (state.catalogLoading || state.catalogError != null)) {
            CatalogBanner(state)
        }
    }
}

@Composable
private fun BoxScope.CatalogBanner(state: AppState) {
    val err = state.catalogError
    val prog = state.catalogProgress
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 26.dp)
            .clip(shape)
            .background(if (err != null) C.Danger.copy(alpha = 0.16f) else C.Panel2)
            .border(1.dp, if (err != null) C.Warn else C.Line2, shape)
            .padding(horizontal = 30.dp, vertical = 14.dp),
    ) {
        Txt(
            err ?: prog ?: "正在读取 ${state.sourceLabel()} 的真实目录…",
            19f,
            if (err != null) C.Warn else C.Dim,
        )
    }
}
