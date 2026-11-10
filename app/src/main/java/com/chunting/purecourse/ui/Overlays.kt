package com.chunting.purecourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.data.SmbSession
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.MultiCtx
import com.chunting.purecourse.state.endEdit

/**
 * 所有弹层：多选条 / Toast / 二次确认 / SMB 诊断 / 文件夹操作小框 / 备注名。
 * 层级与原型一致（multibar 70 > cfm 80 > smbdiag 85 > toast 90）。
 */
@Composable
private fun AliasBtn(text: String, focused: Boolean, primary: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(54.dp)
            .tvFocus(focused, 12.dp, 1f, drawRing = false)
            .clip(shape)
            // 必须统一成 Brush：when 分支混着 Brush 与 Color 时会被推断成 Any，
            // 两个 background() 重载都匹配不上（第一次编译就是这么炸的）
            .background(
                when {
                    focused && primary -> Brush.linearGradient(listOf(C.BtnFrom, C.BtnTo))
                    primary -> Brush.linearGradient(listOf(C.Acc2, C.Acc))
                    focused -> Brush.linearGradient(listOf(C.Veil2, C.Veil2))
                    else -> Brush.linearGradient(listOf(C.Veil, C.Veil))
                }
            )
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused && primary -> C.Acc
                    focused -> C.FocusRing
                    primary -> C.Line3
                    else -> C.Line2
                },
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text, 19f,
            when {
                primary -> C.BtnInk
                focused -> C.Text
                else -> C.TextSoft
            },
            800, letterSpacing = 1f,
        )
    }
}

@Composable
fun OverlayLayer(state: AppState) {
    // ---------- 多选操作条（整条盖住顶栏）----------
    if (state.multiOn && (state.page == com.chunting.purecourse.state.Page.Home ||
                state.page == com.chunting.purecourse.state.Page.Browse)
    ) {
        val picked = state.multiSet.count { it.value }
        val unit = if (state.multiCtx == MultiCtx.Shelf) "门" else "项"
        val labels = if (state.multiCtx == MultiCtx.Shelf) {
            listOf("移出书架" to "danger", "全选" to "", "完成" to "pri")
        } else {
            listOf("加入书架" to "pri", "全选" to "", "完成" to "")
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(104.dp)
                // 多选条盖住顶栏，做成"玻璃替代顶栏"：与顶栏同一套底色渐变，
                // 明暗主题自动跟随，避免暖棕突兀
                .background(
                    Brush.verticalGradient(
                        listOf(C.Bg2.copy(alpha = 0.97f), C.Bg.copy(alpha = 0.92f))
                    )
                )
                .padding(horizontal = 60.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("已选 ", 20f, C.Text, 700)
                Txt("$picked", 24f, C.AccText, 700)
                Txt(" $unit", 20f, C.Text, 700)
            }
            Spacer(Modifier.weight(1f))
            labels.forEachIndexed { i, (label, tone) ->
                val focused = state.focusTag == "multi:$i"
                val shape = RoundedCornerShape(99.dp)
                Box(
                    Modifier
                        .height(48.dp)
                        .tvFocus(focused, 99.dp, if (focused) 1.03f else 1f, drawRing = false)
                        .clip(shape)
                        .background(if (focused) C.FocusSolid else C.Veil2)
                        .border(
                            // 聚焦必须有一圈明确的框：这些按钮在多选条上
                            // 只靠底色变化根本看不出来选中了哪个
                            if (focused) 2.dp else 1.dp,
                            when {
                                focused && tone == "danger" -> C.Danger
                                focused -> C.Acc
                                tone == "danger" -> C.Danger.copy(alpha = 0.45f)
                                tone == "pri" -> C.Acc.copy(alpha = 0.40f)
                                else -> C.Line2
                            },
                            shape,
                        )
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(
                        label, 18f,
                        when {
                            focused -> C.BtnInk
                            tone == "danger" -> C.DangerText
                            tone == "pri" -> C.AccText
                            else -> C.Text
                        },
                        800,
                    )
                }
            }
        }
    }

    // ---------- 文件夹操作小框 ----------
    if (state.fpopVisible) {
        Box(
            Modifier.fillMaxSize().background(C.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(C.SheetFrom, C.SheetTo)))
                    .border(1.dp, C.Line2, RoundedCornerShape(22.dp))
                    .padding(start = 26.dp, end = 26.dp, top = 26.dp, bottom = 22.dp)
            ) {
                // 标题/副标题来自打开弹窗那一刻的快照，不随树状态变化（修：串位）
                Txt(state.fpopName.ifEmpty { "文件夹" }, 22f, C.Text, 900)
                Spacer(Modifier.height(6.dp))
                Txt(state.fpopSub.ifEmpty { "文件夹" }, 14f, C.Dim2)
                Spacer(Modifier.height(18.dp))

                val disabled = listOf(state.fpopShelfDisabled, state.fpopFavDisabled, false)
                listOf(
                    "＋ 加入书架" to "fpop:0",
                    "☆ 收藏当前" to "fpop:1",
                    "✎ 备注名（区分同名目录）" to "fpop:2",
                ).forEachIndexed { i, (label, tag) ->
                    val dis = disabled[i]
                    val focused = !dis && state.focusTag == tag
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(bottom = 10.dp)
                            .tvFocus(focused, 12.dp, 1f)
                            .clip(shape)
                            .background(
                                when {
                                    // 置灰：放弃橙色，压到极淡（比正常项还淡一档）
                                    dis -> C.Line
                                    focused -> C.AccSoft
                                    else -> C.Veil
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    dis -> C.Line
                                    focused -> Color.Transparent
                                    else -> C.Line2
                                },
                                shape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Txt(
                            if (dis) {
                                if (i == 0) "✓ 已在书架（不可选）" else "✓ 已收藏（不可选）"
                            } else label,
                            18f,
                            if (dis) C.Dim2 else C.Text,
                            700, letterSpacing = 1f,
                        )
                    }
                }
            }
        }
    }

    // ---------- 备注名编辑 ----------
    if (state.aliasOpen) {
        Box(
            Modifier.fillMaxSize().background(C.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(760.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(C.SheetFrom, C.SheetTo)))
                    .border(1.dp, C.Line2, RoundedCornerShape(22.dp))
                    .padding(horizontal = 30.dp, vertical = 26.dp),
            ) {
                Txt("备注名", 24f, C.Text, 900)
                Spacer(Modifier.height(6.dp))
                Txt(
                    "原名：${state.aliasOrig} · 留空 = 恢复原名。同名目录（SMB 与 WebDAV 都有「钢琴课」）靠它区分。",
                    14f, C.Dim2,
                )
                Spacer(Modifier.height(18.dp))

                val editing = state.addEditing == "alias"
                val focusedField = state.focusTag == "aliasfld" || editing
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (editing) C.Hi else C.Line)
                        .border(2.dp, if (focusedField) C.Acc else C.Line2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Txt("备注名", 18f, if (focusedField) C.Text else C.Dim, modifier = Modifier.width(90.dp))
                    if (editing) {
                        // 与表单页同一套：光标由 AppState.editCaret 统管，
                        // 遥控器左右键在 MainActivity 里直接改它（电视输入法没有正向删除）
                        val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                        androidx.compose.runtime.LaunchedEffect(editing) {
                            kotlinx.coroutines.delay(60)
                            runCatching { fr.requestFocus() }
                            kb?.show()
                        }
                        var tv by remember {
                            androidx.compose.runtime.mutableStateOf(
                                androidx.compose.ui.text.input.TextFieldValue(
                                    state.aliasText,
                                    androidx.compose.ui.text.TextRange(state.editCaret),
                                )
                            )
                        }
                        androidx.compose.runtime.LaunchedEffect(state.editCaret) {
                            val pos = state.editCaret.coerceIn(0, tv.text.length)
                            if (tv.selection.start != pos) tv = tv.copy(selection = androidx.compose.ui.text.TextRange(pos))
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = tv,
                            onValueChange = { nv ->
                                tv = nv
                                state.onAliasTextChange(nv.text)
                                state.syncCaret(nv.selection.start)
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 20.sp,
                                color = C.Text,
                                fontWeight = androidx.compose.ui.text.font.FontWeight(600),
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(C.Acc),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { state.endEdit() },
                            ),
                            modifier = Modifier.weight(1f)
                                .focusRequester(fr),
                        )
                    } else {
                        Txt(
                            state.aliasText.ifEmpty { "（未设置，显示原名）" }, 20f,
                            if (state.aliasText.isEmpty()) C.Dim2 else C.Text, 600,
                            modifier = Modifier.weight(1f),
                        )
                        Txt("确认输入", 14f, C.AccText)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Txt(
                    if (editing) "◀▶ 移光标 · 返回结束输入 · 输入完按输入法的「完成」"
                    else "再次按确认可继续修改",
                    14f, C.Dim2,
                )

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AliasBtn("保存", state.focusTag == "alias:0", primary = true, Modifier.weight(1f))
                    AliasBtn("取消", state.focusTag == "alias:1", primary = false, Modifier.weight(1f))
                }
            }
        }
    }

    // ---------- 二次确认 ----------
    if (state.cfmVisible) {
        Box(
            Modifier.fillMaxSize().background(C.Scrim),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(780.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(C.SheetFrom, C.SheetTo)))
                    .border(1.dp, C.Line2, RoundedCornerShape(24.dp))
                    .padding(start = 36.dp, end = 36.dp, top = 36.dp, bottom = 28.dp)
            ) {
                Txt(state.cfmTitle, 26f, C.Text, 800)
                Spacer(Modifier.height(12.dp))
                Txt(state.cfmDesc, 18f, C.Dim, maxLines = 3, lineHeight = 29f)
                Spacer(Modifier.height(30.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                    listOf(state.cfmCancelText to "cfm:0", state.cfmOkText to "cfm:1").forEach { (label, tag) ->
                        val focused = state.focusTag == tag
                        val danger = tag == "cfm:1"
                        val shape = RoundedCornerShape(99.dp)
                        Box(
                            Modifier
                                // 「加入并播放」这类 5 字按钮在 170dp 里会挤，按字数放宽
                                .width(if (label.length >= 5) 232.dp else 170.dp)
                                .height(62.dp)
                                .tvFocus(focused, 99.dp, 1f, drawRing = false)
                                .clip(shape)
                                .background(
                                    when {
                                        focused && danger -> C.Danger
                                        focused -> C.FocusSolid
                                        danger -> C.Danger.copy(alpha = 0.10f)
                                        else -> C.Veil
                                    }
                                )
                                .border(
                                    1.dp,
                                    when {
                                        focused -> Color.Transparent
                                        danger -> C.Danger.copy(alpha = 0.35f)
                                        else -> C.Line2
                                    },
                                    shape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Txt(
                                label, 19f,
                                when {
                                    focused && danger -> C.DangerInk
                                    focused -> C.BtnInk
                                    danger -> C.DangerText
                                    else -> C.Text
                                },
                                800,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- SMB 连接数诊断 ----------
    if (state.diagVisible) SmbDiag(state)

    // ---------- Toast ----------
    state.toastMsg?.let { msg ->
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                Modifier
                    .padding(bottom = 86.dp)
                    .widthIn(max = 1100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.Panel2)
                    .border(1.dp, C.Line3, RoundedCornerShape(16.dp))
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            ) { Txt(msg, 19f, C.Text, 400, maxLines = 2) }
        }
    }
}

@Composable
private fun SmbDiag(state: AppState) {
    Box(
        Modifier.fillMaxSize().background(C.Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(1000.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(C.Panel)
                .border(1.dp, C.Warn.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                .padding(start = 38.dp, end = 38.dp, top = 32.dp, bottom = 26.dp)
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(C.Warn.copy(alpha = 0.14f))
                    .border(1.dp, C.Warn.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Txt(state.diagTag, 15f, C.WarnSoft, 800) }

            Spacer(Modifier.height(16.dp))
            Txt(state.diagTitle, 29f, C.Text, 800)
            Spacer(Modifier.height(8.dp))
            Txt(
                state.diagSub.replace("<b>", "").replace("</b>", ""),
                18f, C.Dim, maxLines = 2, lineHeight = 29f,
            )

            Spacer(Modifier.height(18.dp))
            val why = listOf(
                "Windows 10 / 11 做共享服务端，最多只允许 20 条并发 SMB 会话，家庭版、专业版都一样，改不了",
                "播一次、切一集、异常退出、反复点重试，各占一条；断掉的会话不会立刻释放，默认要空闲约 15 分钟才回收",
                "占满之后电脑会反复弹账号密码框、输对了也进不去，播放器这边就是一直转圈、黑屏、读不出数据",
                "本软件已收紧占用：浏览目录与播放共用一条会话，播完立即断开，失败后不自动狂重试",
            )
            why.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    row.forEach { t ->
                        Row(Modifier.weight(1f)) {
                            Txt("·", 22f, C.WarnSoft, 900)
                            Spacer(Modifier.width(8.dp))
                            Txt(t, 16f, C.Dim, maxLines = 3, lineHeight = 25f)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(2.dp))
            listOf(
                "到电脑上，右键 清除计算机连接数.bat → 以管理员身份运行（脚本就一句 net session /delete /y），把被占用的会话全部清掉",
                "回到电视，按「我已清线程，重新连接」。这次能连上，就说明确实是连接数问题",
                "清完还连不上、而且电脑根本没弹登录框，才按网络排查：ping 电脑 IP、确认同一网段、共享是否还开着",
            ).forEachIndexed { i, text ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.Line)
                        .border(1.dp, C.Line2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(C.Warn),
                        contentAlignment = Alignment.Center,
                    ) { Txt("${i + 1}", 16f, C.WarnSolidInk, 900) }
                    Txt(text, 17f, C.Text, maxLines = 3, lineHeight = 26f)
                }
            }

            Spacer(Modifier.height(6.dp))
            Txt(
                "排查口诀：先看电脑有没有弹登录框——弹了、输对了也进不去，就是连接数满；不弹、直接说不通，才查网络或共享设置。",
                15f, C.Dim2, maxLines = 3, lineHeight = 24f,
            )

            // ---------- 本机会话实时值 + 释放策略 ----------
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.Line)
                    .border(1.dp, C.Line2, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("本机 SMB 会话", 16f, C.Dim)
                        Txt(
                            if (SmbSession.localSessions > 0) "${SmbSession.localSessions} 条" else "0 条",
                            20f,
                            if (SmbSession.localSessions > 1) C.DangerText else C.AccText,
                            900,
                        )
                        Txt(
                            when {
                                SmbSession.localSessions > 1 -> "（超过上限，请点右侧按钮释放）"
                                SmbSession.localSessions == 0 -> "（尚未接入 SMB，P1 起显示真实值）"
                                else -> "（浏览与播放共用一条，上限自控 ≤2）"
                            },
                            14f, C.Dim2,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Txt(
                        "释放策略：退出播放页后空闲 ${SmbSession.idleReleaseSec} 秒断开 · 最近释放 ${SmbSession.lastReleaseText}",
                        14f, C.Dim2,
                    )
                    Spacer(Modifier.height(4.dp))
                    Txt(
                        "说明：这里只能清掉「本播放器」占的会话，电脑端其他设备与僵尸会话清不掉，仍需在电脑上跑那个 bat。",
                        13f, C.Dim3, maxLines = 2,
                    )
                }
                Spacer(Modifier.width(16.dp))
                val dfocus = state.focusTag == "sd:0"
                val dshape = RoundedCornerShape(99.dp)
                Box(
                    Modifier
                        .height(56.dp)
                        .widthIn(min = 200.dp)
                        .tvFocus(dfocus, 99.dp, 1f, drawRing = false)
                        .clip(dshape)
                        .background(if (dfocus) C.FocusSolid else C.Veil)
                        .border(
                            1.dp,
                            if (dfocus) Color.Transparent else if (SmbSession.localSessions > 0) C.Acc.copy(alpha = 0.45f) else C.Line2,
                            dshape,
                        )
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(
                        if (SmbSession.localSessions > 0) "断开本机全部会话" else "断开本机全部会话",
                        18f,
                        if (dfocus) C.FocusInk else if (SmbSession.localSessions > 0) C.AccText else C.Dim,
                        700,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End)) {
                listOf(
                    state.diagRetryLabel to "sd:1",
                    "先关掉" to "sd:2",
                ).forEach { (label, tag) ->
                    val focused = state.focusTag == tag
                    val shape = RoundedCornerShape(99.dp)
                    Box(
                        Modifier
                            .height(60.dp)
                            .widthIn(min = 180.dp)
                            .tvFocus(focused, 99.dp, 1f, drawRing = false)
                            .clip(shape)
                            .background(if (focused) C.FocusSolid else C.Veil)
                            .border(1.dp, if (focused) Color.Transparent else C.Line2, shape)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) { Txt(label, 19f, if (focused) C.BtnInk else C.Text, 800) }
                }
            }
        }
    }
}
