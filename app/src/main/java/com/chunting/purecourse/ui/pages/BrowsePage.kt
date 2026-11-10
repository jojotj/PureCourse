package com.chunting.purecourse.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.chunting.purecourse.data.ItemType
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.MultiCtx
import com.chunting.purecourse.ui.MarqueeTxt
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

/**
 * 浏览页（page-browse）：左目录树 + 右内容列。
 *
 * **版式必须与收藏页同一套**：两栏比例（左右等宽 1:1）、外边距、标题字号、行高、圆角、焦点态、
 * 底部提示条全部对齐 FavPage，只在左上角写清「这一页是数据源」。
 * 之前这里自创了一套（行高 76、左色条、5px 焦点条、字号 20、行内按钮 46dp），
 * 来回切两页像两个软件 —— 用户第一眼就看出来了。
 */
@Composable
fun BrowsePage(state: AppState) {
    val node = state.curNode()
    if (node == null) {
        // 扫描是后台网络请求，没回来之前不能整页空白（用户会以为软件坏了）
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Txt(state.catalogError ?: "正在读取共享目录…", 24f, C.Dim)
        }
        return
    }
    val rows = state.browseRows()
    val treeState = rememberLazyListState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.focusTag) {
        val t = state.focusTag
        when {
            t.startsWith("bt:") -> treeState.ensureIndexVisible(t.removePrefix("bt:").toIntOrNull() ?: 0)
            t.startsWith("brow:") || t.startsWith("brop:") -> {
                val i = t.split(":")[1].toIntOrNull() ?: 0
                listState.ensureIndexVisible(i)
            }
        }
    }

    // 左上角标识：当前浏览位置**属于哪个源**（多源并存，不能写死 SMB）
    val curKey = state.browseStack.lastOrNull()
    val srcLabel = state.srcTagOf(curKey).ifBlank { "全部数据源" }

    Row(Modifier.fillMaxSize().padding(horizontal = 60.dp).padding(top = 10.dp, bottom = 20.dp)) {
        // ---------- 左：目录树 ----------
        Column(Modifier.fillMaxHeight().weight(1f).padding(end = 26.dp)) {
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Txt("数据源", 28f, C.Text, 800, letterSpacing = 1f)
                    Txt(srcLabel, 14f, C.Dim2, letterSpacing = 1f, maxLines = 1)
                }
                Txt("${rows.size} 个节点", 17f, C.Dim)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = treeState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 10.dp, bottom = 40.dp,
                ),
            ) {
                items(rows.size) { i ->
                    val r = rows[i]
                    val focused = state.focusTag == "bt:$i"
                    val cur = r.key == state.browseStack.lastOrNull()
                    val expanded = state.ftOpen[r.key] == true
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .padding(bottom = 4.dp)
                            // 与收藏页同一套焦点：不缩放、白环、淡品牌底
                            .tvFocus(focused, 12.dp, 1f)
                            .clip(shape)
                            .background(
                                when {
                                    focused -> C.AccSoft
                                    cur -> C.BlueSoft
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    focused -> Color.Transparent
                                    cur -> C.Blue.copy(alpha = 0.45f)
                                    else -> Color.Transparent
                                },
                                shape,
                            )
                            .padding(start = (12 + r.depth * 26).dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 与收藏页同一套箭头：能展开 ▸、已展开 ▾、没有子目录 ·（不再画第三套符号）
                        Txt(
                            if (expanded) "▾" else if (r.has) "▸" else "·",
                            15f, C.Dim2, modifier = Modifier.width(16.dp),
                            align = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        // 所有目录统一一个图标：之前按 depth 分成 ▦/▤,同一层的共享图标都不一样
                        Txt("▤", 17f, C.Dim2, modifier = Modifier.width(20.dp))
                        // 深层目录名过长：聚焦后才滚动播报，未聚焦静态停在开头
                        MarqueeTxt(
                            text = r.name, size = 23f,
                            color = when {
                                focused -> C.Text
                                cur -> C.BlueText
                                else -> C.Text
                            },
                            weight = 700,
                            focused = focused,
                            modifier = Modifier.weight(1f),
                        )
                        if (r.srcTag.isNotBlank()) {
                            Box(
                                Modifier.clip(RoundedCornerShape(5.dp))
                                    .background(C.AccSoft)
                                    .border(1.dp, C.Acc.copy(alpha = 0.42f), RoundedCornerShape(5.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) { Txt(r.srcTag, 13f, C.AccText, 700, maxLines = 1) }
                        }
                        Txt(r.cnt, 17f, C.Dim2, maxLines = 1)
                    }
                }
            }
        }

        // ---------- 右：标题 + 行 ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Txt(node.name, 28f, C.Text, 900)
                val dirs = node.items.count { it.type == ItemType.DIR }
                val vids = node.items.count { it.type == ItemType.VID }
                Txt("$dirs 个文件夹 · $vids 个视频", 17f, C.Dim)
                Spacer(Modifier.weight(1f))
                val curRel = state.browseStack.lastOrNull()
                if (curRel != null) Txt(state.uncOfPath(curRel), 17f, C.Dim2, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

            if (node.items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Txt("这个文件夹是空的", 23f, C.Dim2)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp,
                    ),
                ) {
                    items(node.items.size) { i ->
                        BrowseRow(state, i, node.items[i])
                    }
                }
            }

            // 底部提示：与收藏页同一行字、同一个位置
            Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.CenterStart) {
                Txt("↑↓ 选目录　·　Enter 展开 / 收起　·　→ 查看右侧内容　·　← 返回上级", 17f, C.Dim2)
            }
        }
    }
}

@Composable
private fun BrowseRow(state: AppState, i: Int, item: com.chunting.purecourse.data.FsItem) {
    val isDir = item.type == ItemType.DIR
    val focused = state.focusTag == "brow:$i"
    val cur = item.relPath != null && item.relPath == state.browseStack.lastOrNull()
    val picked = state.multiSet[i] == true
    val inShelf = isDir && state.inShelfPath(item.relPath)
    val inFav = isDir && state.inFavKey(item.relPath)
    val shape = RoundedCornerShape(12.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(bottom = 5.dp)
            // 与收藏页右侧列表同一套：面板底 + 圆角 + 细描边 + 不缩放的白色焦点环
            .tvFocus(focused, 12.dp, 1f)
            .clip(shape)
            .background(
                when {
                    focused -> C.AccSoft
                    picked -> C.AccSoft
                    cur -> C.BlueSoft
                    else -> C.Panel
                }
            )
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    picked -> C.Acc.copy(alpha = 0.42f)
                    cur -> C.Blue.copy(alpha = 0.45f)
                    else -> C.Line
                },
                shape,
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.multiOn && state.multiCtx == MultiCtx.Browse && isDir) {
            val boxShape = RoundedCornerShape(8.dp)
            Box(
                Modifier.size(24.dp).clip(boxShape)
                    .background(
                        if (picked) Brush.linearGradient(listOf(C.AccSolid, C.AccSolid))
                        else Brush.linearGradient(listOf(C.Veil2, C.Veil2))
                    )
                    .border(
                        1.5.dp,
                        if (picked) C.AccSolidInk else C.Line3,
                        boxShape,
                    ),
                contentAlignment = Alignment.Center,
            ) { if (picked) Txt("✓", 17f, C.AccSolidInk, 900) }
        }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Txt(if (isDir) "▤" else "♪", 18f, C.Dim2)
        }
        // 与收藏页同一套：名字放不下时，聚焦后滚一圈、回位停 1.5s 再滚
        MarqueeTxt(
            text = item.name, size = 23f,
            color = when {
                focused -> C.Text
                cur -> C.BlueText
                else -> C.Text
            },
            weight = 700,
            focused = focused,
            modifier = Modifier.weight(1f),
        )
        item.countText?.let { Txt(it, 17f, C.Dim2, maxLines = 1) }
        if (isDir) {
            // 行内两个操作，视觉沿用收藏页那个「⋯」小方块的同一套材质（PanelHi + Line3）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RowChip(
                    focused = state.focusTag == "brop:$i:0",
                    text = if (inShelf) "★ 已在书架" else "＋ 书架",
                    accent = inShelf,
                )
                RowChip(
                    focused = state.focusTag == "brop:$i:1",
                    text = if (inFav) "★ 已收藏" else "☆ 收藏",
                    accent = inFav,
                )
            }
        } else {
            Txt("播放 ›", 17f, if (focused) C.AccText else C.Dim2, modifier = Modifier.width(76.dp),
                align = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun RowChip(focused: Boolean, text: String, accent: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(38.dp)
            .tvFocus(focused, 10.dp, 1.02f)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.PanelHi)
            .border(
                1.dp,
                when {
                    focused -> Color.Transparent
                    accent -> C.Acc.copy(alpha = 0.5f)
                    else -> C.Line3
                },
                shape,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 15f, if (focused) C.Text else if (accent) C.AccText else C.Dim, 700, maxLines = 1)
    }
}
