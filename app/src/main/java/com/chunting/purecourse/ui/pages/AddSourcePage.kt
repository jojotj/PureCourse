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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
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
import com.chunting.purecourse.data.RealCatalog
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.Step
import com.chunting.purecourse.state.endEdit
import com.chunting.purecourse.ui.Txt

/** 添加数据源（page-addsrc）：协议卡 → 表单 + 发现列表 → 测试连接，返回键逐级回退 */
@Composable
fun AddSourcePage(state: AppState) {
    val scroll = rememberScrollState()

    // 左右键归遥控器（MainActivity 直接改 editCaret）；键盘自带的左右箭头走
    // InputConnection.setSelection，两条路互不干扰，所以这里不需要知道键盘是否弹出。
    LaunchedEffect(state.focusTag) {
        val t = state.focusTag
        val idx = when {
            t.startsWith("proto:") -> t.removePrefix("proto:").toIntOrNull() ?: 0
            t.startsWith("fld:") -> {
                // 必须与 FormStep 里**实际渲染顺序**一致，否则焦点跑到底部时不会跟着滚动，
                // 看起来就像「光标移到了看不见的地方」。
                val fields = ArrayList<String>()
                fields += "name"
                if (state.addProto == Proto.DAV) fields += "scheme"
                fields += "host"; fields += "port"; fields += "path"
                fields += "user"; fields += "pwd"; fields += "remember"
                if (state.addProto == Proto.DAV) fields += "ignorecert"
                fields.indexOf(t.removePrefix("fld:"))
            }
            t.startsWith("fbtn:") -> if (state.addProto == Proto.DAV) 8 else 7
            else -> -1
        }
        if (idx >= 0) scroll.animateScrollTo((idx * 86).coerceAtLeast(0))
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 60.dp)
            .padding(top = 8.dp, bottom = 20.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 22.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Txt("添加数据源", 34f, C.Text, 800, letterSpacing = 0.5f)
            Txt(
                when (state.addStep) {
                    Step.Proto -> "步骤 1 / 3 · 选择协议"
                    Step.Form -> "步骤 2 / 3 · 地址表单与连接测试"
                    Step.Test -> "步骤 3 / 3 · 连接结果"
                },
                17f, C.Dim2,
            )
        }

        when (state.addStep) {
            Step.Proto -> ProtoGrid(state)
            Step.Form -> FormStep(state)
            Step.Test -> TestStep(state)
        }
    }
}

@Composable
private fun ProtoGrid(state: AppState) {
    Row(
        // 只剩 SMB / WebDAV 两张卡：宽度按两卡排（原 1480dp 是四卡尺寸，会让卡片过宽）
        Modifier.width(760.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Proto.selectable.forEachIndexed { i, p ->
            val focused = state.focusTag == "proto:$i"
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .weight(1f)
                    .height(300.dp)
                    .tvFocus(focused, 16.dp, 1f)
                    .clip(shape)
                    .background(if (focused) C.AccSoft else C.SoftCard)
                    .border(2.dp, if (focused) C.Acc else C.Line2, shape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(112.dp).clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    when (p) {
                                        Proto.SMB -> C.Smb; Proto.DAV -> C.Dav; Proto.LOCAL -> C.IconLocal
                                    },
                                    when (p) {
                                        Proto.SMB -> C.SmbDark; Proto.DAV -> C.DavDark; Proto.LOCAL -> C.IconLocal2
                                    },
                                ),
                                start = Offset.Zero, end = Offset(140f, 140f),
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) { Txt(p.short, 32f, Color.White, 900, letterSpacing = 0.5f) }
                Spacer(Modifier.height(16.dp))
                Txt(p.label, 23f, C.Text, 800)
                Spacer(Modifier.height(6.dp))
                Txt(p.desc, 15f, if (focused) C.AccText else C.Dim2, maxLines = 2)
            }
        }
    }
}

/** 协议分段小胶囊：选中态是实心橙，未选是半透明底 */
@Composable
private fun SegChip(text: String, on: Boolean, rowFocused: Boolean) {
    val bg = when {
        on && rowFocused -> C.AccSolid
        on -> C.AccSoft
        else -> C.GlassPanel
    }
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 18f, if (on && rowFocused) C.AccSolidInk else if (on) C.AccText else C.Dim, 800)
    }
}

@Composable
private fun FormStep(state: AppState) {
    Row(Modifier.fillMaxWidth().widthIn(max = 1600.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        // ---------- 表单 ----------
        Column(Modifier.weight(1f)) {
            val isDav = state.addProto == Proto.DAV
            // 名称可选：别的播放器都允许给数据源起名，电视上看到「PC」比看到一串 IP 好认
            Field(state, "fld:name", "名称", state.addName,
                if (isDav) "可选，如 家里NAS（留空则显示地址）" else "可选，如 PC（留空则显示 IP）") { state.addName = it }
            if (isDav) {
                // 协议必须能真的选：内网 NAS / OpenList 大多是明文 http，
                // 公网或群晖才用 https。之前只在地址栏里留了个"可写前缀"的暗示，
                // 遥控器上等于没法选。
                val schFocused = state.focusTag == "fld:scheme"
                val schShape = RoundedCornerShape(10.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .tvFocus(schFocused, 10.dp, 1.01f)
                        .clip(schShape)
                        .background(if (schFocused) C.AccSoft else C.GlassPanel)
                        .border(2.dp, if (schFocused) C.Acc else C.Line2, schShape)
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("协议", 18f, if (schFocused) C.Text else C.Dim, modifier = Modifier.width(150.dp))
                    SegChip("HTTP", !state.addDavHttps, schFocused)
                    Spacer(Modifier.width(12.dp))
                    SegChip("HTTPS", state.addDavHttps, schFocused)
                    Spacer(Modifier.weight(1f))
                    Txt(
                        if (schFocused) "← → 或确认键切换" else "",
                        15f, if (schFocused) C.AccText else C.Dim2,
                    )
                }

                Field(state, "fld:host", "服务器地址", state.addHost, "IP 或域名，如 192.168.1.10") {
                    state.addHost = it
                    // 直接粘贴整条 URL 时，顺手把协议开关拨对
                    if (it.startsWith("https://", true)) state.addDavHttps = true
                    else if (it.startsWith("http://", true)) state.addDavHttps = false
                }
                Field(
                    state, "fld:port", "端口", state.addPort,
                    if (state.addDavHttps) "443" else "80",
                    numeric = true,
                ) {
                    state.addPort = it.filter { c -> c.isDigit() }
                }
                Field(state, "fld:path", "路径", state.addPath,
                    "如 /dav/115/我的课程/分类后（留空 = 根）") { state.addPath = it }
            } else {
                Field(state, "fld:host", "主机 / IP", state.addHost, RealCatalog.DEFAULT_HOST.ifBlank { "例如 192.168.1.10" }) { state.addHost = it }
                Field(state, "fld:port", "端口", state.addPort, "445", numeric = true) { state.addPort = it }
                // 路径 = 共享名（或 共享名/子目录）。
                // **留空 = 整机模式**：这台机器的所有共享都会进左树，不用在这里挑一个。
                Field(state, "fld:path", "共享 / 路径", state.addPath, "留空 = 整机模式（全部共享）") { state.addPath = it }
            }
            Field(state, "fld:user", "账号", state.addUser, if (isDav) "WebDAV 账号（可留空）" else "guest") { state.addUser = it }
            Field(state, "fld:pwd", "密码", state.addPwd, "密码", isPwd = true) { state.addPwd = it }

            // 记住用户名和密码
            val remFocused = state.focusTag == "fld:remember"
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .tvFocus(remFocused, 10.dp, 1.01f)
                    .clip(shape)
                    .background(if (remFocused) C.AccSoft else C.GlassPanel)
                    .border(2.dp, if (remFocused) C.Acc else C.Line2, shape)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("记住用户名和密码", 18f, if (remFocused) C.Text else C.Dim, modifier = Modifier.width(150.dp))
                Txt("下次自动登录（默认勾选，确认切换）", 15f, if (remFocused) C.AccText else C.Dim2)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.width(62.dp).height(32.dp).clip(RoundedCornerShape(99.dp))
                        .background(if (state.addRemember) C.SwitchOn else C.SwitchOff)
                ) {
                    Box(
                        Modifier.padding(start = if (state.addRemember) 33.dp else 3.dp, top = 3.dp)
                            .size(26.dp).clip(CircleShape)
                            .background(if (state.addRemember) C.Panel else C.Dim3)
                    )
                }
            }

            // 忽略证书校验（仅 WebDAV 有意义）
            if (isDav) {
                val igFocused = state.focusTag == "fld:ignorecert"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(70.dp)
                        .tvFocus(igFocused, 10.dp, 1.01f)
                        .clip(shape)
                        .background(if (igFocused) C.AccSoft else C.GlassPanel)
                        .border(2.dp, if (igFocused) C.Acc else C.Line2, shape)
                        .padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt("忽略证书校验", 18f, if (igFocused) C.Text else C.Dim, modifier = Modifier.width(150.dp))
                    Txt("自签 HTTPS 直通（内网 NAS / Alist 常用）", 15f, if (igFocused) C.AccText else C.Dim2)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.width(62.dp).height(32.dp).clip(RoundedCornerShape(99.dp))
                            .background(if (state.addIgnoreCert) C.SwitchOn else C.SwitchOff)
                    ) {
                        Box(
                            Modifier.padding(start = if (state.addIgnoreCert) 33.dp else 3.dp, top = 3.dp)
                                .size(26.dp).clip(CircleShape)
                                .background(if (state.addIgnoreCert) C.Panel else C.Dim3)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FBtn(state.focusTag == "fbtn:0", "测试连接", primary = true)
                FBtn(state.focusTag == "fbtn:1", "保存", primary = false)
                FBtn(state.focusTag == "fbtn:2", "返回", primary = false)
            }

            // 查询共享 / 测试连接的结果就地显示：失败只闪一个 toast 的话，
            // 用户（和我排查时）根本看不到原因。
            Spacer(Modifier.height(16.dp))
            when {
                state.addSelectBusy -> Txt(
                    if (state.addProto == Proto.DAV) "正在连接 ${state.addHost} …"
                    else "正在查询 ${state.addHost} 的共享列表…",
                    15f, C.AccText,
                )
                state.addShares.isNotEmpty() ->
                    Txt("已读到 ${state.addShares.size} 个共享 · 路径留空 = 整机模式，全部进左树", 15f, C.Seg)
                state.addTestState == "bad" && state.addTestErr.isNotEmpty() ->
                    Txt(state.addTestErr, 15f, C.DangerText, maxLines = 3)
            }
        }

        // ---------- 右侧：SMB 显示局域网发现；WebDAV 显示填写说明 ----------
        Column(
            Modifier
                .width(430.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(C.GlassPanel)
                .border(1.dp, C.Line, RoundedCornerShape(14.dp))
                .padding(18.dp)
        ) {
            if (state.addProto == Proto.DAV) {
                // WebDAV 是「照着地址填」的，不存在广播发现；
                // 这里把最容易填错的几项直接写出来，比留一大片 SMB 设备列表有用。
                Txt("WebDAV 填写说明", 17f, C.AccText, 800)
                Spacer(Modifier.height(12.dp))
                DavTip("地址", "只填 IP 或域名，如 192.168.1.10；带了 http:// 也能识别")
                DavTip("端口", "按服务器实际端口填；NAS 常见 5005 / 5006，OpenList 常见 5244")
                DavTip("路径", "填到你要当根的那一层，如 /dav/115/我的课程/分类后")
                DavTip("协议", "内网多数是 HTTP；公网 / 群晖是 HTTPS")
                DavTip("证书", "HTTPS 且是自签证书时，打开下方「忽略证书校验」")
                Spacer(Modifier.height(14.dp))
                Txt(
                    "保存后会在后台递归扫描这一层以下的目录，含视频的文件夹会自动变成课程。",
                    14f, C.Dim2, maxLines = 3, lineHeight = 21f,
                )
            } else {
                Txt("局域网自动发现", 17f, C.AccText, 800)
                Spacer(Modifier.height(6.dp))
                Txt("扫描到 ${state.discover.size} 台设备 · 确认直接填入", 14f, C.Dim2)
                Spacer(Modifier.height(14.dp))
                state.discover.forEachIndexed { i, e ->
                    EndpointRow(state, "disc:$i", e.proto, e.name, "${e.host}:${e.port} · ${e.path}")
                }
                Spacer(Modifier.height(14.dp))
                Txt("从最近使用选择", 17f, C.AccText, 800)
                Spacer(Modifier.height(10.dp))
                state.recent.forEachIndexed { i, e ->
                    EndpointRow(state, "recent:$i", e.proto, e.host, "${e.port} · ${e.path} · ${e.user}")
                }
                Spacer(Modifier.height(14.dp))
                Txt("密码可直接用电视系统输入法的扫码输入，无需自建扫码面板", 14f, C.Dim2, maxLines = 2, lineHeight = 21f)
            }
        }
    }
}

/** 右侧说明面板的一行：左边小标签，右边灰色说明 */
@Composable
private fun DavTip(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Txt(label, 14f, C.Acc, 800, modifier = Modifier.width(48.dp))
        Txt(text, 14f, C.Dim2, maxLines = 3, lineHeight = 20f)
    }
}

@Composable
private fun Field(
    state: AppState,
    tag: String,
    label: String,
    value: String,
    hint: String,
    isPwd: Boolean = false,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    val focused = state.focusTag == tag
    val key = tag.removePrefix("fld:")
    val editing = state.addEditing == key
    val shape = RoundedCornerShape(10.dp)

    // 编辑态才让 BasicTextField 参与组合：非编辑态完全不渲染输入框，
    // 遥控器的方向键就不会被文本框吃走（TV 上这是最常见的「导航突然失灵」原因）。
    val fr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        // 等一帧让输入框完成布局，否则 requestFocus 会抛 "not initialized"
        kotlinx.coroutines.delay(60)
        runCatching { fr.requestFocus() }
        kb?.show()
    }

    // 键盘收起**不再**自动退出编辑态：用户「按返回收键盘」之后要能接着用遥控器左右键
    // 移光标、用遥控器退格删字，所以编辑态必须保留。真正退出编辑靠再按一次返回
    // （MainActivity 里分流：键盘开着先收键盘，键盘已收就退出编辑）。

    Row(
        Modifier
            .fillMaxWidth()
            .height(74.dp)
            .tvFocus(focused, 10.dp, 1f)
            .clip(shape)
            .background(
                when {
                    editing -> C.AccSoft
                    focused -> C.AccSoft
                    else -> C.GlassPanel
                }
            )
            .border(2.dp, if (editing || focused) C.Acc else C.Line2, shape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Txt(label, 18f, if (focused || editing) C.Text else C.Dim, modifier = Modifier.width(150.dp))

        if (editing) {
            // 字号/字重必须与展示态**完全一致**（都用 .sp，走同一份重映射的 LocalDensity），
            // 否则点进编辑那一瞬间文字大小会跳。
            //
            // 用 TextFieldValue 而不是 String：电视输入法（搜狗 TV 版）不处理硬件方向键，
            // 光标只能停在末尾——用户没法把光标移到前面去删改后面的字，整行只能重输。
            // 这里自己接管左右键，直接改 selection。
            // 光标由 AppState.editCaret 统管（左右键在 MainActivity 里直接改它），
            // 输入框只负责把 editCaret 渲染成 selection。
            var tv by remember { mutableStateOf(TextFieldValue(value, TextRange(state.editCaret))) }
            LaunchedEffect(value) {
                // 外部改写（点右侧快捷位置自动填表）时同步文本，光标放到末尾
                if (tv.text != value) {
                    tv = TextFieldValue(value, TextRange(value.length))
                    state.syncCaret(value.length)
                }
            }
            LaunchedEffect(state.editCaret) {
                // 左右键改动 → 同步成真正的 selection（夹在文本长度内）
                val pos = state.editCaret.coerceIn(0, tv.text.length)
                if (tv.selection.start != pos || tv.selection.end != pos) {
                    tv = tv.copy(selection = TextRange(pos))
                }
            }
            // 反向回报：输入法键盘上的箭头是走 InputConnection.setSelection 移光标的，
            // 不会触发 onValueChange。不回传的话 editCaret 停在旧位置，
            // 下一次按遥控器左右键就会从错误的位置开始跳。
            LaunchedEffect(tv.selection.start, tv.selection.end) {
                if (tv.selection.collapsed) state.syncCaret(tv.selection.start)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (tv.text.isEmpty()) {
                    // 输入框是透明的，占位提示要自己画在下面
                    Txt(hint, 20f, C.Dim2, 400)
                }
                BasicTextField(
                    value = tv,
                    onValueChange = { nv ->
                        tv = nv
                        // 打字 / 退格后把真实光标位置回报给状态层
                        state.syncCaret(nv.selection.start)
                        if (nv.text != value) onValueChange(nv.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 20.sp,
                        color = C.Text,
                        fontWeight = FontWeight(600),
                    ),
                    cursorBrush = SolidColor(C.Acc),
                    visualTransformation = if (isPwd) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { state.endEdit() }),
                    // 左右键由 MainActivity 直接接管（见 dispatchKeyEvent），
                    // 这里不再用 onPreviewKeyEvent，避免和输入法的按键竞争。
                    modifier = Modifier.fillMaxWidth().focusRequester(fr),
                )
            }
            // 把按键归属写清楚：遥控器左右移光标、退格删左边；返回键先收键盘，再按一次退出编辑。
            Txt("◀▶ 移光标 · 返回退出", 14f, C.AccText)
        } else {
            // 密码在**展示态**也必须遮蔽：PasswordVisualTransformation 只在输入框里生效，
            // 退出编辑后走的是普通文本，不处理就会把密码明文显示在电视上。
            val shown = if (isPwd && value.isNotEmpty()) "•".repeat(value.length.coerceAtMost(16)) else value
            Txt(
                shown.ifEmpty { hint }, 20f,
                if (value.isEmpty()) C.Dim2 else C.Text,
                if (value.isEmpty()) 400 else 600,
                modifier = Modifier.weight(1f),
            )
            Txt(if (isPwd) "确认输入" else "确认编辑", 14f, if (focused) C.AccText else C.Dim2)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EndpointRow(state: AppState, tag: String, proto: Proto, name: String, sub: String) {
    val focused = state.focusTag == tag
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 9.dp)
            .tvFocus(focused, 9.dp, 1f)
            .clip(shape)
            .background(if (focused) C.AccSoft else C.SoftCard)
            .border(2.dp, if (focused) C.Acc else Color.Transparent, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                .background(
                    when (proto) {
                        Proto.SMB -> C.Smb; Proto.DAV -> C.Dav; Proto.LOCAL -> C.IconLocal
                    }
                ),
            contentAlignment = Alignment.Center,
        ) { Txt(proto.short, 11f, Color.White, 900) }
        Column(Modifier.weight(1f)) {
            Txt(name, 17f, C.Text, 600)
            Spacer(Modifier.height(3.dp))
            Txt(sub, 13f, if (focused) C.AccText else C.Dim2)
        }
    }
}

@Composable
private fun FBtn(focused: Boolean, text: String, primary: Boolean) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier
            .height(64.dp)
            .tvFocus(focused, 11.dp, 1f, drawRing = false)
            .clip(shape)
            .background(
                when {
                    focused -> C.AccSoft
                    primary -> C.AccSoft
                    else -> C.SoftCard
                }
            )
            .border(2.dp, if (focused) C.Acc else if (primary) C.Acc.copy(alpha = 0.55f) else C.Line2, shape)
            .padding(horizontal = 34.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, 19f, if (primary || focused) C.AccText else C.Text, 700) }
}

@Composable
private fun TestStep(state: AppState) {
    val idle = state.addTestState == "idle"
    val ok = state.addTestState == "ok"
    val border = when {
        idle -> C.Line2
        ok -> C.Ok.copy(alpha = 0.55f)
        else -> C.Danger.copy(alpha = 0.55f)
    }
    val bg = when {
        idle -> C.GlassPanel
        ok -> C.OkSoft
        else -> C.DangerSoft
    }
    Column(
        Modifier
            .widthIn(max = 900.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (idle) {
            Box(
                Modifier.size(70.dp).clip(CircleShape)
                    .border(4.dp, C.Line3, CircleShape)
            ) {
                Box(
                    Modifier.size(70.dp).clip(CircleShape)
                        .border(4.dp, C.Acc, CircleShape)
                )
            }
            Spacer(Modifier.height(16.dp))
            Txt("正在连接…", 26f, C.Text, 800)
            Spacer(Modifier.height(8.dp))
            if (state.addProto == Proto.DAV) {
                val dPort = state.addPort.ifEmpty { if (state.addDavHttps) "443" else "80" }
                Txt(
                    "WebDAV · ${state.addHost.ifEmpty { "（未填地址）" }}:$dPort · ${state.addPath.ifEmpty { "/" }} · 正在读取目录",
                    17f, C.Dim,
                )
            } else {
                Txt(
                    "SMB · ${state.addHost.ifEmpty { "（未填写）" }}:${state.addPort.ifEmpty { "445" }} · ${state.addPath.ifEmpty { "（未填共享名）" }} · 正在读取目录",
                    17f, C.Dim,
                )
            }
        } else {
            Txt(if (ok) "✓" else "！", 46f, if (ok) C.Seg else C.Danger)
            Spacer(Modifier.height(10.dp))
            Txt(if (ok) "连接成功" else "连接失败", 26f, C.Text, 800)
            Spacer(Modifier.height(8.dp))
            // 真实结果：成功给出真实条目数，失败给出分类原因（不写死"6 个目录 / 错误码 -35"）
            Txt(
                when {
                    ok -> state.addTestMsg.ifEmpty { "已连上" }
                    state.addTestErr.isNotEmpty() -> state.addTestErr
                    else -> "无法访问 ${state.addHost}:${state.addPort} · 请检查地址、端口与网络"
                },
                17f, if (ok) C.Dim else C.DangerText, maxLines = 3,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (ok) {
                    FBtn(state.focusTag == "fbtn:0", "保存并返回", primary = true)
                    FBtn(state.focusTag == "fbtn:1", "重新测试", primary = false)
                } else {
                    FBtn(state.focusTag == "fbtn:0", "返回修改", primary = true)
                    FBtn(state.focusTag == "fbtn:1", "仍然保存", primary = false)
                }
            }
        }
    }
}
