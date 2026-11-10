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
import com.chunting.purecourse.state.ftPathText
import com.chunting.purecourse.ui.MarqueeTxt
import com.chunting.purecourse.ui.StarIcon
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

/**
 * 收藏页（page-home / fav）：固定双栏，无模式切换。
 * 左右各半（1:1）：目录树会一层层展开，缩进吃掉宽度，左栏太窄时深层目录名就看不清了。
 * 左目录树（可逐层展开）+ 底部「取消收藏」长条；右内容列随左树选中项联动。
 * 取消收藏只允许勾选**收藏顶层项**，目录内部子文件夹不可勾选（文档 §3.4）。
 */
@Composable
fun FavPage(state: AppState) {
    val rows = state.ftRows()
    // 右侧内容列跟随左树当前选中节点（原型 FT.key = rows[FT.sel].key）
    val ftKey = rows.getOrNull(state.ftSel)?.key ?: "#fav"
    val treeState = rememberLazyListState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.focusTag) {
        val t = state.focusTag
        when {
            t.startsWith("ft:") -> treeState.ensureIndexVisible(t.removePrefix("ft:").toIntOrNull() ?: 0)
            t.startsWith("fpl:") -> listState.ensureIndexVisible(t.removePrefix("fpl:").toIntOrNull() ?: 0)
        }
    }

    Row(Modifier.fillMaxSize().padding(horizontal = 60.dp).padding(top = 10.dp, bottom = 20.dp)) {
        // ---------- 左：目录树 + 取消收藏条 ----------
        Column(Modifier.fillMaxHeight().weight(1f).padding(end = 26.dp)) {
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Txt("收藏目录", 28f, C.Text, 800, letterSpacing = 1f)
                Txt("${rows.size} 个节点", 17f, C.Dim)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = treeState,
                // 底部留白 24：焦点滚到最后一项时不会紧贴提示条（原来 8dp 像是被切了一半）
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 40.dp),
            ) {
                items(rows.size, key = { i -> rows[i].key }) { i ->
                    val r = rows[i]
                    val focused = state.focusTag == "ft:$i"
                    val cur = i == state.ftSel
                    val picked = state.favRmOn && r.pickable && state.favRmSet.contains(r.key)
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(if (state.favRmOn) 70.dp else 64.dp)
                            .padding(bottom = 4.dp)
                            .tvFocus(focused, 12.dp, 1f)
                            .clip(shape)
                            // 焦点不再用整块橙色铺底（原来像"选中了一行橙色"），
                            // 改成淡品牌底 + 白色焦点环，层级更清楚
                            .background(
                                when {
                                    focused -> C.AccSoft
                                    picked -> C.AccSoft
                                    cur -> C.BlueSoft
                                    else -> Color.Transparent
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    focused -> Color.Transparent
                                    picked -> C.Acc.copy(alpha = 0.42f)
                                    cur -> C.Blue.copy(alpha = 0.45f)
                                    else -> Color.Transparent
                                },
                                shape,
                            )
                            .padding(start = (12 + r.depth * 26).dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.favRmOn) {
                            val boxShape = RoundedCornerShape(8.dp)
                            Box(
                                Modifier.size(24.dp).clip(boxShape)
                                    .background(
                                        if (picked) Brush.linearGradient(listOf(C.AccSolid, C.AccSolid))
                                        else Brush.linearGradient(listOf(C.Veil2, C.Veil2))
                                    )
                                    .border(
                                        1.5.dp,
                                        if (picked) C.AccSolidInk else if (r.pickable) C.Line3 else C.Line2,
                                        boxShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) { if (picked) Txt("✓", 17f, C.AccSolidInk, 900) }
                        }
                        Txt(
                            if (r.has) (if (state.ftOpen[r.key] == true) "▾" else "▸") else "·",
                            15f, C.Dim2, modifier = Modifier.width(16.dp),
                            align = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Txt(if (r.has) "▤" else "▷", 17f, C.Dim2, modifier = Modifier.width(20.dp))
                        // 深层目录名可能比这一行还长：聚焦后再滚动播报，不聚焦就停在开头
                        MarqueeTxt(
                            text = r.name, size = 23f,
                            color = when {
                                focused -> C.Text
                                picked -> C.AccText
                                cur -> C.BlueText
                                else -> C.Text
                            },
                            weight = 700,
                            focused = focused,
                            modifier = Modifier.weight(1f),
                        )
                        // 来源标签：SMB 与 WebDAV 里可能出现同名目录，只按名字看不出是哪个源
                        if (!state.favRmOn && r.srcTag.isNotBlank()) {
                            SrcChip(r.srcTag)
                        }
                        if (!state.favRmOn) Txt(r.cnt, 17f, C.Dim2, maxLines = 1)
                    }
                }
            }

            // 底部「全选 / 取消收藏」——常驻入口，不依赖长按
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.favRmOn) {
                    RmButton(
                        focused = state.focusTag == "fpall",
                        text = if (rows.count { it.pickable } > 0 && state.favRmSet.size >= rows.count { it.pickable }) "全不选" else "全选",
                        primary = false,
                        modifier = Modifier.width(120.dp),
                    )
                }
                RmButton(
                    focused = state.focusTag == "fprm",
                    text = if (state.favRmOn) "取消收藏 · ${state.favRmSet.size}" else "☑ 取消收藏",
                    primary = state.favRmOn,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------- 右：内容列 ----------
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Txt(state.mcName(ftKey), 28f, C.Text, 900)
                Txt("${state.mcItems(ftKey).size} 项", 17f, C.Dim)
                Spacer(Modifier.weight(1f))
                Txt(state.ftPathText(), 17f, C.Dim2, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))

            val items = state.mcItems(ftKey)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp),
            ) {
                items(items.size, key = { i -> items[i].relPath ?: "${items[i].name}#$i" }) { i ->
                    val it = items[i]
                    val focused = state.focusTag == "fpl:$i"
                    val cur = i == state.ftRow
                    val isDir = it.type == ItemType.DIR
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .padding(bottom = 5.dp)
                            .tvFocus(focused, 12.dp, 1f)
                            .clip(shape)
                            .background(
                                when {
                                    focused -> C.AccSoft
                                    cur -> C.BlueSoft
                                    else -> C.Panel
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    focused -> Color.Transparent
                                    cur -> C.Blue.copy(alpha = 0.45f)
                                    else -> C.Line
                                },
                                shape,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            when {
                                isDir -> Txt("▤", 18f, C.Dim2)
                                else -> Txt("♪", 18f, C.Dim2)
                            }
                        }
                        // 文件夹名过长时：未聚焦静态截断，聚焦后滚动播报一圈、回位停 1.5s 再滚
                        MarqueeTxt(
                            text = it.name, size = 23f,
                            color = when {
                                focused -> C.Text
                                cur -> C.BlueText
                                else -> C.Text
                            },
                            weight = 700,
                            focused = focused,
                            modifier = Modifier.weight(1f),
                        )
                        if (it.srcTag != null) SrcChip(it.srcTag)
                        if (it.countText != null) Txt(it.countText!!, 17f, C.Dim2)
                        if (isDir) {
                            Box(
                                Modifier.size(54.dp, 34.dp).clip(RoundedCornerShape(10.dp))
                                    .background(C.PanelHi)
                                    .border(1.dp, C.Line3, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Txt("⋯", 19f, C.TextSoft, 900) }
                        }
                    }
                }
                if (items.isEmpty()) {
                    item { Txt("这个位置还没有内容", 23f, C.Dim2, modifier = Modifier.padding(40.dp)) }
                }
            }
            // 底部提示（原型 .fp-tip，height 36）
            Box(Modifier.fillMaxWidth().height(42.dp), contentAlignment = Alignment.CenterStart) {
                Txt(
                    "↑↓ 选目录　·　Enter 展开 / 收起　·　→ 查看右侧内容　·　← 返回上级",
                    17f, C.Dim2,
                )
            }
        }
    }
}

@Composable
private fun SrcChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(5.dp))
            .background(C.AccSoft)
            .border(1.dp, C.Acc.copy(alpha = 0.42f), RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Txt(text, 13f, C.AccText, 700, maxLines = 1) }
}

@Composable
private fun RmButton(focused: Boolean, text: String, primary: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(60.dp)
            .tvFocus(focused, 16.dp, if (focused) 1.02f else 1f, drawRing = false)
            .clip(shape)
            .background(
                when {
                    // 主操作聚焦：白色实底反白（和书架 Hero 的主按钮同一套语言）
                    focused && primary -> Brush.linearGradient(listOf(C.BtnFrom, C.BtnTo))
                    primary -> Brush.linearGradient(listOf(C.Acc2, C.Acc))
                    focused -> Brush.linearGradient(listOf(C.Veil2, C.Veil))
                    else -> Brush.linearGradient(listOf(C.Panel2, C.Panel))
                }
            )
            .border(
                // 必须有明确的焦点框：这排按钮原来的"聚焦态"只是底色微变，
                // 在深色底上几乎看不出来，用户不知道自己选中了哪个。
                if (focused) 2.dp else 1.dp,
                when {
                    focused && primary -> C.Acc
                    focused -> C.Text
                    primary -> C.Veil2
                    else -> C.Line2
                },
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text, 21f,
            when {
                primary -> C.BtnInk
                focused -> C.Text
                else -> C.TextSoft
            },
            800, letterSpacing = 1f,
        )
    }
}
