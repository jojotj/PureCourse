package com.chunting.purecourse.ui.pages

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.core.tvFocus
import com.chunting.purecourse.data.Proto
import com.chunting.purecourse.data.Source
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.ui.Txt
import com.chunting.purecourse.ui.ensureIndexVisible

private fun protoColors(p: Proto): Pair<Color, Color> = when (p) {
    Proto.SMB -> C.Smb to C.SmbDark
    Proto.DAV -> C.Dav to C.DavDark
    Proto.LOCAL -> C.IconLocal to C.IconLocal2
}

/** 数据源页（page-sources）：首卡「本地数据」+ 网络源卡（名称/协议/Host/状态点）+ 3 个操作 + 新增卡 */
@Composable
fun SourcesPage(state: AppState) {
    val nets = state.netSources
    val locals = state.localSources
    val total = 1 + nets.size + 1
    Column(Modifier.fillMaxSize().padding(start = 60.dp, end = 140.dp).padding(top = 8.dp, bottom = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Txt("数据源", 34f, C.Text, 800, letterSpacing = 0.5f)
            Txt(
                "本地 ${locals.size} 个位置 + 已添加 ${nets.size} 个网络位置 · 可同时在线",
                17f, C.Dim2,
            )
        }
        // 数据源多了以后同样按「焦点居中、卡片滚动」走，和剧集列表保持一致
        val gridState = rememberLazyGridState()
        val focusIdx = when {
            state.focusTag == "src:local" -> 0
            state.focusTag == "src:add" -> total - 1
            state.focusTag.startsWith("src:s") -> (state.focusTag.removePrefix("src:s").toIntOrNull() ?: -1) + 1
            else -> -1
        }
        LaunchedEffect(state.focusTag) {
            if (focusIdx in 0 until total) gridState.ensureIndexVisible(focusIdx)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize(),
            state = gridState,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(total) { idx ->
                when {
                    idx == 0 -> LocalCell(state, locals)
                    idx == total - 1 -> AddCell(state)
                    else -> SourceCell(state, nets, idx - 1)
                }
            }
        }
    }
}

@Composable
private fun LocalCell(state: AppState, locals: List<Source>) {
    val focused = state.focusTag == "src:local"
    val shape = RoundedCornerShape(18.dp)
    // 内置存储一定在，U 盘按数量显示：插了两个就写「U盘 ×2」
    val usbN = locals.count { it.name.startsWith("U盘") }
    val granted = com.chunting.purecourse.data.LocalStore.hasAllFilesAccess()
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .tvFocus(focused, 18.dp, 1f)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.Panel)
            .border(2.dp, if (focused) C.Acc else C.Line2, shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconSquare(C.IconLocal to C.IconLocal2, "▣", "本地", focused)
            Column(Modifier.weight(1f)) {
                Txt("本地存储", 21f, if (focused) C.Text else C.Text, 700)
                Spacer(Modifier.height(5.dp))
                Txt(
                    if (usbN > 0) "内置存储 + U盘 ×$usbN" else "内置存储 · 未检测到 U 盘",
                    15f, if (focused) C.AccText else C.Dim2,
                )
                Spacer(Modifier.height(7.dp))
                Txt(
                    when {
                        !granted -> "未授权 · 点此开启「所有文件访问权限」"
                        locals.any { it.err != null } ->
                            "读取异常 · ${locals.first { it.err != null }.err}"
                        else -> "${locals.size} 个位置 · 可浏览 / 加书架 / 收藏"
                    },
                    15f,
                    if (focused) C.Text else if (granted && locals.none { it.err != null }) C.Dim2 else C.Danger,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SourceCell(state: AppState, nets: List<Source>, i: Int) {
    val s: Source = nets.getOrNull(i) ?: return
    val focused = state.focusTag == "src:s$i"
    val shape = RoundedCornerShape(18.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .tvFocus(focused, 18.dp, 1f)
                .clip(shape)
                .background(if (focused) C.AccSoft else C.Panel)
                .border(2.dp, if (focused) C.Acc else C.Line2, shape)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconSquare(protoColors(s.proto), "▤", s.proto.short, focused)
                Column(Modifier.weight(1f)) {
                    Txt(s.name, 21f, if (focused) C.Text else C.Text, 700)
                    Spacer(Modifier.height(5.dp))
                    // WebDAV 的 path 本身已是完整 URL（含 host:port），再拼一遍会又长又重复，
                    // 且长路径会把卡片撑破；这里只显示一次并限制行数。
                    Txt(
                        if (s.proto == Proto.DAV) s.path else "${s.host}:${s.port} · ${s.path}",
                        15f, if (focused) C.AccText else C.Dim2, maxLines = 1,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Dot(s.ok, focused)
                        Txt(
                            when {
                                // 多源并行扫描时，每张卡各自报自己的进度，
                                // 不能都写一句"正在扫描"（用户无法判断是哪个源在动）
                                s.progress != null -> "正在扫描… ${s.progress}"
                                state.catalogLoading && s.dirs == 0 && s.err == null -> "正在读取目录…（可先返回书架）"
                                s.ok -> "已连接 · ${s.dirs} 项顶层内容"
                                s.err == "full" -> "连接被拒 · 电脑共享连接数已满"
                                s.err == "auth" -> "连接失败 · 账号或密码不对"
                                s.err == "network" -> "连接失败 · 连不上主机，检查网络"
                                s.err == "path" -> "连接失败 · 共享不存在或无权限"
                                else -> "连接失败 · 检查网络与账号"
                            },
                            15f, if (focused) C.Text else C.Dim,
                        )
                    }
                }
            }
        }
        // 三个操作：编辑 / 重连 / 删除
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("编辑", "重连", "删除").forEachIndexed { k, label ->
                OpBtn(
                    focused = state.focusTag == "srcop:$i:$k",
                    text = label,
                    danger = k == 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AddCell(state: AppState) {
    val focused = state.focusTag == "src:add"
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(132.dp)
            .tvFocus(focused, 18.dp, 1f)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.SoftCard)
            .border(
                2.dp,
                if (focused) Color.Transparent else C.Line2,
                shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt("＋", 38f, if (focused) C.AccText else C.Acc, 400)
            Txt("添加数据源", 19f, if (focused) C.AccText else C.Dim)
        }
    }
}

@Composable
private fun IconSquare(colors: Pair<Color, Color>, glyph: String, badge: String, focused: Boolean) {
    val (a, b) = colors
    Box(Modifier.size(92.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(a, b), start = Offset.Zero, end = Offset(120f, 120f))),
            contentAlignment = Alignment.Center,
        ) { Txt(glyph, 34f, Color.White) }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(0.dp).clip(RoundedCornerShape(6.dp))
                .background(C.FocusSolid).padding(horizontal = 8.dp, vertical = 3.dp)
        ) { Txt(badge, 12f, C.FocusInk, 900, letterSpacing = 0.5f) }
    }
}

@Composable
private fun Dot(ok: Boolean, focused: Boolean) {
    val color = when {
        focused && ok -> C.Ok
        focused -> C.Danger
        ok -> C.Seg
        else -> C.Danger
    }
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

@Composable
private fun OpBtn(focused: Boolean, text: String, danger: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .height(44.dp)
            .tvFocus(focused, 12.dp, 1f, drawRing = false)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.SoftCard)
            .border(2.dp, if (focused) C.Acc else C.Line2, shape),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 15f, if (focused) C.Text else if (danger) C.Danger else C.Dim)
    }
}
