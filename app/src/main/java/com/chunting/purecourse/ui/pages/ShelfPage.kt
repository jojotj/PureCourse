package com.chunting.purecourse.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.data.Course
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.ui.Bar
import com.chunting.purecourse.ui.CoverGradient
import com.chunting.purecourse.ui.PlayTriangle
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

/**
 * 书架页（page-home / shelf）。
 * 继续学习横幅 hero（200 高）+ 右侧「选择/移除」入口 + 我的书架（每行 6 本 + 木板）。
 * 数值全部来自原型：hs-scroll padding 22/60/26，shelf-inner gap 42，plank 9，行距 30。
 */
@Composable
fun ShelfPage(state: AppState) {
    val order = state.shelfOrder()
    val rows = order.chunked(6)
    val listState = rememberLazyListState()
    // 统计全部来自扫描到的真实目录，不再写「8 门课程 · 每门 12 集」
    val active = state.courses.filter { !it.out }
    val epTotal = active.sumOf { it.epCount }
    val finished = active.count { it.epCount > 0 && it.doneEp >= it.epCount }

    // 焦点进入某一行时把该行滚进视野（原型 ensureVisible）
    LaunchedEffect(state.focusTag) {
        val tag = state.focusTag
        if (tag.startsWith("book:")) {
            val idx = tag.removePrefix("book:").toIntOrNull() ?: return@LaunchedEffect
            val pos = order.indexOf(idx)
            if (pos >= 0) listState.ensureIndexVisible((pos / 6) + 1)
        } else if (tag == "hero" || tag == "herosel") {
            listState.ensureIndexVisible(0)
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(start = 96.dp, end = 96.dp)
            .padding(top = 30.dp, bottom = 34.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            HeroRow(state)
            // sec-h：height 52 + margin-top 8（原型 .sec-h 固定高度）
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).height(56.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Txt("我的书架", 26f, C.Text, 900, letterSpacing = 0.5f)
                Txt("${active.size} 门课程 · 共 $epTotal 个视频 · $finished 门已看完", 16f, C.Dim,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            // 焦点卡片会按 1.028 放大，上沿会越出自身行、压到「我的书架」这行字上。
            // 留出这段空隙，放大后也不碰到标题。
            Spacer(Modifier.height(34.dp))
        }
        // 空态：WebDAV 的课程常常在第 3~4 层，扫描要几分钟。
        // 之前这里什么都不画，用户看到的是纯黑一片 + 一句「0 门课程」，
        // 无法判断是在扫描还是坏了。
        if (rows.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 46.dp)) {
                    Txt(
                        if (state.catalogLoading) "正在扫描目录…" else "书架还是空的",
                        22f, C.Text, 800,
                    )
                    Spacer(Modifier.height(10.dp))
                    Txt(
                        state.catalogProgress
                            ?: if (state.catalogLoading) "扫到的课程会自动出现在这里"
                            else "检查数据源是否可访问，或换一个起始目录",
                        16f, C.Dim2, maxLines = 3,
                    )
                    if (state.catalogLoading) {
                        Spacer(Modifier.height(8.dp))
                        Txt("扫描期间可以先到「收藏」页逐层进目录浏览", 16f, C.Dim2)
                    }
                }
            }
        }
        items(rows.size, key = { r -> rows[r].firstOrNull() ?: -r - 1 }) { r ->
            ShelfRow(state, rows[r])
        }
    }
}

@Composable
private fun HeroRow(state: AppState) {
    val heroIdx = state.recentIdx().coerceAtLeast(0)
    val c = state.courses.getOrNull(heroIdx) ?: return
    Row(
        Modifier.fillMaxWidth().height(184.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        HeroCard(state, c, heroIdx)
        HeroSelect(state)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeroCard(state: AppState, c: Course, heroIdx: Int) {
    val focused = state.focusTag == "hero"
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .weight(1f)
            .height(184.dp)
            .tvFocus(focused, 20.dp, 1.01f)
            .clip(shape)
            // 四段渐变：左侧品牌暖光 → 中部淡染 → 右侧收黑，比三段更自然
            .background(
                Brush.horizontalGradient(
                    listOf(C.Acc.copy(alpha = 0.24f), C.Acc.copy(alpha = 0.08f), C.Veil.copy(alpha = 0.03f), Color.Transparent),
                )
            )
            .border(1.dp, if (focused) Color.Transparent else C.Line2, shape)
            .padding(horizontal = 30.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            // 封面 252×142，左下课程缩写、右上 12 节、中央播放圆钮
            Box(Modifier.width(240.dp).height(135.dp)) {
                CoverGradient(c.c1, c.c2, RoundedCornerShape(12.dp), Modifier.fillMaxSize())
                Txt(
                    c.abbr, 38f, Color.White, 900, letterSpacing = 2f,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 6.dp),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0x80000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) { Txt("${c.epCount} 节", 12f, Color.White) }
                Box(
                    Modifier.align(Alignment.Center).size(44.dp)
                        .clip(CircleShape).background(Color(0x6B000000))
                        .border(2.dp, Color(0xBFFFFFFF), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.padding(start = 3.dp)) { PlayTriangle(15f, Color.White) }
                }
            }

            // 文案区
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.clip(RoundedCornerShape(99.dp)).background(C.Acc.copy(alpha = 0.18f))
                        .border(1.dp, C.Acc.copy(alpha = 0.26f), RoundedCornerShape(99.dp))
                        .padding(horizontal = 13.dp, vertical = 5.dp)
                ) { Txt("继续学习", 13f, C.AccText, 800, letterSpacing = 2f) }
                Spacer(Modifier.height(10.dp))
                Txt(c.name, 32f, C.Text, 900, letterSpacing = 0.5f)
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (c.srcTag.isNotBlank()) MetaText("", c.srcTag, "", "", "")
                    MetaText("第 ", "${c.doneEp}", " 节 / 共 ", "${c.epCount}", " 节")
                    MetaText("上次 ", c.lastEpName, "", "", "")
                    MetaText("体积 ", state.totalSizeText(heroIdx), "", "", "")
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(520.dp)) {
                    Bar(c.pct, 7.dp, track = C.Veil2)
                }
            }

            // 「▶ 继续播放 第 N 节」——CSS 覆盖为半透明白底描边按钮，橙色只留给焦点框
            // 主操作按钮改成白色实底 + 深字：这是 Apple TV / Disney+ 首页「继续观看」的标准做法，
            // 比半透明白底更明确是"按这里就走"，橙色仍然只保留给焦点与进度。
            Box(
                Modifier
                    .height(82.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(C.BtnFrom, C.BtnTo)))
                    .padding(horizontal = 34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlayTriangle(18f, C.BtnInk)
                    Txt("继续播放 第 ${c.curEp} 节", 24f, C.BtnInk, 900)
                }
            }
        }
    }
}

@Composable
private fun MetaText(prefix: String, value: String, mid: String, value2: String, suffix: String) {
    Row {
        Txt(prefix, 17f, C.Dim)
        Txt(value, 17f, C.AccText)
        if (mid.isNotEmpty()) Txt(mid, 17f, C.Dim)
        if (value2.isNotEmpty()) Txt(value2, 17f, C.AccText)
        if (suffix.isNotEmpty()) Txt(suffix, 17f, C.Dim)
    }
}

/** 右侧常驻「选择 / 移除」入口：多选必须有显式入口，长按只是快捷（文档 §3.1 硬要求） */
@Composable
private fun HeroSelect(state: AppState) {
    val focused = state.focusTag == "herosel"
    val shape = RoundedCornerShape(20.dp)
    val bg = Brush.verticalGradient(listOf(C.Panel2, C.Panel))
    Box(
        Modifier
            .width(132.dp)
            .height(184.dp)
            .tvFocus(focused, 20.dp, 1f)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (focused) Color.Transparent else C.Line2, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("☰", 25f, if (focused) C.Acc else C.Dim, 400)
            Spacer(Modifier.width(8.dp))
            Column {
                Txt("选择", 20f, if (state.multiOn) C.Dim else C.Text, 800, letterSpacing = 2f)
                Txt("移除", 20f, if (state.multiOn) C.Text else C.Dim2, 800, letterSpacing = 2f)
            }
        }
    }
}

@Composable
private fun ShelfRow(state: AppState, row: List<Int>) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(42.dp),
        ) {
            for (i in 0 until 6) {
                val ci = row.getOrNull(i)
                if (ci == null) {
                    // 末行不足 6 本用 ghost 占位：不参与导航，只保持列宽
                    Spacer(Modifier.weight(1f))
                } else {
                    CourseCard(state, ci, Modifier.weight(1f))
                }
            }
        }
        // 层板：原来是一条暖棕色木纹，显脏。改成暗色实体层板 + 顶部 1px 高光线，
        // 保留"书架分层"的结构语义，但更像成品 UI 的分隔而非贴图。
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 26.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Brush.verticalGradient(listOf(C.PanelHi, C.Bg2)))
                .drawBehind {
                    drawLine(
                        color = C.Line3,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
        )
    }
}

@Composable
private fun CourseCard(state: AppState, index: Int, modifier: Modifier) {
    val c = state.courses[index]
    val focused = state.focusTag == "book:$index"
    val selected = index == state.sel
    val picked = state.multiSet[index] == true
    val shape = RoundedCornerShape(16.dp)

    // 卡片改成实体面板（原来几乎全透明，在深色底上看不出轮廓）。
    // 未聚焦也有 1dp 白色 7% 描边，整排卡片才有"实体卡片"的边界感。
    val bg = when {
        focused -> C.AccSoft
        selected -> C.BlueSoft
        else -> C.Panel
    }
    val borderColor = when {
        focused -> Color.Transparent
        selected -> C.Blue.copy(alpha = 0.45f)
        else -> C.Line
    }

    Column(
        modifier
            .tvFocus(focused, 16.dp, 1.02f)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .padding(10.dp)
    ) {
        // ---- 封面（16:9，与课程视频同形）----
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            CoverGradient(c.c1, c.c2, RoundedCornerShape(11.dp), Modifier.fillMaxSize())
            // 书脊：6px 竖向渐层，压在封面最左侧
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color(0x4DFFFFFF), Color(0x99000000))))
            )
            Txt(
                c.abbr, 30f, Color.White, 900, letterSpacing = 1.5f,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 8.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0x7A000000))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) { Txt("${c.epCount} 节", 11f, Color.White, letterSpacing = 0.5f) }
            Box(
                Modifier.align(Alignment.TopStart).padding(20.dp, 8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(C.OkBg)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) { Txt("✓ ${c.doneEp}/${c.epCount}", 11f, Color(0xFF06200F), 800) }

            // 来源标签：SMB 与 WebDAV 里可能有同名课程（都是「钢琴课」），
            // 没有这个角标用户根本分不清点的是哪一个源。放在封面右下，不抢标题排版。
            if (c.srcTag.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) { Txt(c.srcTag, 11f, Color(0xFFFFDCC4), 700, maxLines = 1) }
            }

            // 多选勾选框
            if (state.multiOn) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(12.dp).size(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (picked) C.AccSolid else Color(0x80000000))
                        .border(2.dp, if (picked) C.AccSolidInk else Color(0xA6FFFFFF), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (picked) Txt("✓", 17f, C.AccSolidInk, 900) }
            }
        }

        // ---- 文案 ----
        Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 3.dp)) {
            // 真实课程目录名普遍很长（「宋昭阳-中央音乐学院音基考级（中级）(3918)」），
            // 单行会截成前半截、几门课看起来一模一样。固定两行高度保证卡片对齐。
            Txt(c.name, 22f, C.Text, 800,
                maxLines = 2, lineHeight = 27f,
                modifier = Modifier.fillMaxWidth().height(54.dp))
            Spacer(Modifier.height(6.dp))
            // 次要信息也要能读：Dim2 在深色卡片上几乎看不见，提到 Dim 并加大 1 号
            Txt("已看 ${c.doneEp} 节 / 共 ${c.epCount} 节", 15f, C.Dim)
            Spacer(Modifier.height(4.dp))
            Txt("最近播放 · ${c.lastTxt}", 14f, C.AccText)
            Spacer(Modifier.height(8.dp))
            Bar(c.pct, 5.dp, track = C.Veil)
        }
    }
}
