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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import com.chunting.purecourse.state.RowType
import com.chunting.purecourse.state.SettingRow
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

/**
 * 设置页（page-settings）：5 个分组，行高 76（电视端放松行距），右侧胶囊显示当前值。
 * 多档项按确认进入「调整态」：← → 逐档切换并实时预览，确认落库，返回还原。
 */
@Composable
fun SettingsPage(state: AppState) {
    // 扁平化：组标题 + 各行，便于按焦点序号精确滚动
    data class Entry(val header: String?, val row: SettingRow?, val rowIndex: Int)

    val flat = ArrayList<Entry>()
    var ri = 0
    state.groups.forEach { (g, rows) ->
        flat += Entry(g, null, -1)
        rows.forEach { r -> flat += Entry(null, r, ri++) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.focusTag) {
        val t = state.focusTag
        if (t.startsWith("set:")) {
            val i = t.removePrefix("set:").toIntOrNull() ?: return@LaunchedEffect
            // 关键：**先保证焦点行本身可见**。
            // 原来只滚到「组标题行」(idx-1)，而「关于」是最后一个分组的唯一一行，
            // 滚到组标题后这一行正好被留在屏幕外——用户看到的就是「按到最下面什么都没有、光标也不见了」。
            // 焦点行锚定在视口正中（背后的列表在走），上下各留半屏上下文，
            // 所以最后一行「关于」也一定露得出来，不用再额外把组标题再滚一次
            val idx = flat.indexOfFirst { it.rowIndex == i }
            if (idx >= 0) listState.ensureIndexVisible(idx)
        }
    }

    LazyColumn(
        // 底部留白 40：最后一行（关于）滚到底时不会紧贴屏幕边缘
        Modifier.fillMaxSize().padding(horizontal = 96.dp).padding(top = 20.dp, bottom = 40.dp),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(flat.size) { i ->
            val e = flat[i]
            // 分组之间 10px 间距（原型 .set-grid{gap:10px}）
            val isGroupEnd = i + 1 >= flat.size || flat[i + 1].header != null
            Column(
                Modifier.widthIn(max = 1480.dp).fillMaxWidth()
                    .padding(bottom = if (isGroupEnd) 18.dp else 0.dp)
            ) {
                if (e.header != null) {
                    // 组容器上边（圆角 14 + 描边）由组头承担；行尾补下边
                    Box(
                        Modifier.fillMaxWidth().height(58.dp)
                            .clip(RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                            .background(C.Acc.copy(alpha = 0.08f))
                            .border(1.dp, C.Line, RoundedCornerShape(topStart = 13.dp, topEnd = 13.dp))
                            .padding(horizontal = 26.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Txt(e.header, 22f, C.AccText, 800, letterSpacing = 1f) }
                } else {
                    SettingRowView(state, e.row!!, e.rowIndex)
                }
            }
        }
    }
}

@Composable
private fun SettingRowView(state: AppState, r: SettingRow, index: Int) {
    val focused = state.focusTag == "set:$index"
    val adjusting = state.sadjOn && state.sadjIndex == index
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(82.dp)
            // 焦点只换底色 + 白色焦点环，不再额外画橙色粗边（原来叠三层线，边界很脏）
            .background(if (focused) C.AccSoft else Color.Transparent)
            .border(1.dp, C.Line, shape)
            .tvFocus(focused, 4.dp, 1f)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Txt(r.title, 24f, C.Text, 600)
            Spacer(Modifier.height(6.dp))
            Txt(r.desc, 17f, if (focused) C.AccText else C.Dim)
        }

        if (r.type == RowType.BOOL) {
            // 开关 62×32，圆钮 26
            val on = state.boolOf(r.key)
            Box(
                Modifier
                    .width(72.dp).height(38.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        when {
                            on -> Brush.linearGradient(listOf(C.Acc, C.AccDeep))
                            focused -> Brush.linearGradient(listOf(C.Veil2, C.Veil2))
                            else -> Brush.linearGradient(listOf(C.Veil, C.Veil))
                        }
                    ),
            ) {
                Box(
                    Modifier
                        .padding(start = if (on) 38.dp else 4.dp, top = 4.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                focused && on -> C.BtnInk
                                on -> C.Panel
                                else -> C.KnobOff
                            }
                        )
                )
            }
        } else {
            // 值胶囊
            Row(
                Modifier
                    .widthIn(min = 132.dp)
                    .height(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            adjusting -> C.AccSoft
                            focused -> C.Veil2
                            else -> C.Panel2
                        }
                    )
                    .border(
                        1.dp,
                        when {
                            adjusting -> C.Acc.copy(alpha = 0.85f)
                            focused -> C.Line3
                            else -> C.Line2
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (adjusting) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(C.Veil2),
                        contentAlignment = Alignment.Center,
                    ) { Txt("‹", 22f, C.Text, 800) }
                    Spacer(Modifier.width(12.dp))
                }
                Txt(
                    state.rowValue(r), 21f,
                    if (focused) C.Text else C.Dim,
                    if (focused) 800 else 400,
                )
                if (adjusting) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(C.Veil2),
                        contentAlignment = Alignment.Center,
                    ) { Txt("›", 22f, C.Text, 800) }
                }
            }
        }
    }
}
