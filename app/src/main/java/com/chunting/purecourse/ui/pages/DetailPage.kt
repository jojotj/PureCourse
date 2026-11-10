package com.chunting.purecourse.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.ui.Bar
import com.chunting.purecourse.ui.CoverGradient
import com.chunting.purecourse.ui.PlayTriangle
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

/** 详情页（page-detail）：左 560 封面与主按钮，右 4 列剧集宫格 */
@Composable
fun DetailPage(state: AppState) {
    val c = state.courses.getOrNull(state.sel) ?: return
    val n = state.epCount(state.sel).coerceAtLeast(0)
    Row(Modifier.fillMaxSize()) {
        // ---------- 左栏 ----------
        Column(
            Modifier
                .width(560.dp)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0x12FF8E52), Color(0x03000000), Color.Transparent)))
                .padding(horizontal = 44.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.width(330.dp).aspectRatio(16f / 9f)) {
                CoverGradient(c.c1, c.c2, RoundedCornerShape(14.dp), Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.CenterStart).width(8.dp).fillMaxHeight()
                        .background(Brush.verticalGradient(listOf(Color(0x4DFFFFFF), Color(0x99000000))))
                )
                Txt(
                    c.abbr, 48f, Color.White, 900, letterSpacing = 2f,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 8.dp),
                )
                Box(
                    Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0x80000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) { Txt("$n 节", 14f, Color.White) }
            }

            Spacer(Modifier.height(26.dp))
            Txt(c.name, 40f, C.Text, 900, maxLines = 2, lineHeight = 50f)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Row { Txt("共 ", 18f, C.Dim); Txt("$n", 18f, C.AccText); Txt(" 节", 18f, C.Dim) }
                Row { Txt("已看 ", 18f, C.Dim); Txt("${c.doneEp}", 18f, C.AccText); Txt(" 节", 18f, C.Dim) }
                Row { Txt("总计 ", 18f, C.Dim); Txt(state.totalSizeText(state.sel), 18f, C.AccText) }
            }
            Spacer(Modifier.height(20.dp))
            Bar(c.pct, 8.dp, track = Color(0x1AFFFFFF))
            Spacer(Modifier.height(11.dp))
            Txt("已看 ${c.pct}% · 上次看到 ${c.lastEpName}", 17f, C.Dim2, maxLines = 1)

            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                focused = state.focusTag == "dact:0",
                text = "继续播放 第 ${c.curEp} 节",
            )
            Spacer(Modifier.height(10.dp))
            LineButton(focused = state.focusTag == "dact:1", text = "← 返回书架")
        }

        // ---------- 右栏 ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Txt("剧集", 25f, C.Text, 900)
                Txt("共 $n 集", 20f, C.Dim)
                Txt("· 已看打勾，当前集高亮", 20f, C.Dim2)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

            val gridState = rememberLazyGridState()
            LaunchedEffect(state.focusTag) {
                val t = state.focusTag
                if (t.startsWith("ep:")) {
                    val i = t.removePrefix("ep:").toIntOrNull() ?: return@LaunchedEffect
                    gridState.ensureIndexVisible(i)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(n) { idx ->
                    val j = idx + 1
                    // 打勾只认「真的看完的集」，不再用「j < 当前集」推：
                    // 用户只是跳到第 50 节看一眼，前面 49 节不该变成 ✓ 已看
                    val done = c.doneSet.contains(j)
                    val cur = j == c.curEp
                    val st = when {
                        cur -> "current"
                        done -> "done"
                        else -> ""
                    }
                    EpCard(
                        no = "EP %02d".format(j),
                        name = state.epName(state.sel, idx),
                        status = when {
                            cur && done -> "✓ 已看 · 当前集"
                            cur -> "● 正在播放"
                            done -> "✓ 已看"
                            else -> "未看"
                        },
                        st = st,
                        focused = state.focusTag == "ep:$idx",
                    )
                }
            }
        }
    }
}

@Composable
private fun EpCard(no: String, name: String, status: String, st: String, focused: Boolean) {
    val leftBar = when (st) {
        "done" -> C.Ok
        "current" -> C.Acc
        else -> C.Line3
    }
    val bg = when {
        focused -> C.AccSoft
        st == "done" -> C.OkSoft
        st == "current" -> C.AccSoft
        else -> C.Panel
    }
    val statusColor = when (st) {
        "done" -> C.Ok
        "current" -> C.AccText
        else -> C.Dim2
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(148.dp)
            .tvFocus(focused, 12.dp, 1.035f)
            .clip(shape)
            .background(bg)
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    st == "current" -> C.Acc.copy(alpha = 0.42f)
                    else -> C.Line
                },
                shape,
            )
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(leftBar))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 15.dp).fillMaxWidth()) {
            Txt(no, 15f, C.Dim2, letterSpacing = 1.6f)
            Spacer(Modifier.height(6.dp))
            Txt(name, 21f, C.Text, 700, maxLines = 2, lineHeight = 29f, modifier = Modifier.height(60.dp))
            Spacer(Modifier.height(5.dp))
            Txt(status, 16f, statusColor)
        }
    }
}

@Composable
private fun PrimaryButton(focused: Boolean, text: String) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(80.dp)
            .tvFocus(focused, 18.dp, 1.03f)
            .clip(shape)
            // 与书架 Hero 的「继续播放」保持同一套主操作语言（白色实底 + 深字），
            // 避免同一个动作在两个页面是两种颜色的按钮
            .background(Brush.linearGradient(listOf(C.BtnFrom, C.BtnTo)))
            .border(1.dp, Color.Transparent, shape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PlayTriangle(22f, C.BtnInk)
        Spacer(Modifier.width(12.dp))
        Txt(text, 28f, C.BtnInk, 900)
    }
}

@Composable
private fun LineButton(focused: Boolean, text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .tvFocus(focused, 14.dp, 1.03f)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.Panel)
            .border(1.dp, if (focused) Color.Transparent else C.Line2, shape),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 21f, C.Text, 700) }
}
