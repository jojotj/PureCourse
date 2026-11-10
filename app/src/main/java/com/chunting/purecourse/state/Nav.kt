package com.chunting.purecourse.state

import android.os.SystemClock
import android.view.KeyEvent
import com.chunting.purecourse.data.ItemType
import com.chunting.purecourse.data.Proto
import com.chunting.purecourse.data.RealCatalog
import com.chunting.purecourse.data.SourceStore

/**
 * 区域导航模型 —— 原型 `REGDEF / REG / EXIT / nav()` 的等价实现。
 *
 * 为什么不用系统 FocusFinder：TV 上默认按几何坐标找邻居，行数一变、出现隐藏元素、
 * 或宫格末行不满，就必然出现"某个方向移不到"（工具链清单 §19.3 第一条）。
 * 因此这里完全按原型做法：**每个区域声明 kind 与列数，方向键按下标算目标，越界走显式出口**。
 */
enum class Kind { H, V, GRID, SINGLE }

data class Region(val name: String, val kind: Kind, val cols: Int, val tags: List<String>)

object Keys {
    fun dir(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        else -> null
    }

    fun isOk(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun isBack(keyCode: Int) = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
}

private fun reg(name: String, kind: Kind, cols: Int = 0, vararg tags: String) =
    name to Region(name, kind, cols, tags.toList())

private fun regList(name: String, kind: Kind, cols: Int = 0, tags: List<String>) =
    name to Region(name, kind, cols, tags)

/** 构建当前可见的全部区域（等价于原型 rebuild() 时的 REG 快照） */
fun AppState.buildRegions(): Map<String, Region> {
    // 缓存命中直接返回。一次方向键原来会构建 2~3 次整表（nav / regionOf / containsTag），
    // 收藏页每次都要递归整棵目录树，这是「遥控器不跟手」的主要来源。
    val sig = regionsSignature()
    regionsCache?.let { if (sig == regionsSig) return it }
    val m = LinkedHashMap<String, Region>()
    // 顶栏在所有页面都存在（播放页被隐藏，但主页/详情/浏览/设置/数据源/添加都有）
    m += reg("top", Kind.H, 0, "top:tab:shelf", "top:tab:fav", "top:set")

    when (page) {
        Page.Home -> if (tab == HomeTab.Shelf) {
            m += reg("hero", Kind.H, 0, "hero", "herosel")
            m += regList("shelf", Kind.GRID, 6, shelfOrder().map { "book:$it" })
        } else {
            m += regList("favtree", Kind.V, 0, ftRows().indices.map { "ft:$it" })
            // 「全选」只在批量取消收藏时才画出来。以前不管状态都注册两个标签，
            // 于是非批量模式下按左右键会把焦点移到一个**看不见的 fpall** 上，
            // 用户看到的只是"高亮框消失了"，完全不知道自己选中了什么。
            m += if (favRmOn) reg("favrm", Kind.H, 0, "fpall", "fprm")
            else reg("favrm", Kind.H, 0, "fprm")
            // 必须用 ftKeyNow()（= ftRows[ftSel].key），不能用 AppState.ftKey：
            // 进收藏页时 ftKey 还停在 "#fav"（只有 1 项），而界面右侧已经按 ftSel 显示 13 项，
            // 两边不一致会让 DOWN 被判成「没有下一项」——焦点彻底动不了，用户感受就是"卡死"。
            m += regList("fplist", Kind.V, 0, mcItems(ftKeyNow()).indices.map { "fpl:$it" })
        }

        Page.Detail -> {
            m += reg("dactions", Kind.V, 0, "dact:0", "dact:1")
            // 集数来自真实视频文件数：12 / 48 / 79 都可能，不能写死
            m += regList("dgrid", Kind.GRID, 4,
                (0 until epCount(sel).coerceAtLeast(1)).map { "ep:$it" })
        }

        Page.Browse -> {
            val n = curNode()?.items?.size ?: 0
            m += regList("btree", Kind.V, 0, browseRows().indices.map { "bt:$it" })
            m += regList("brows", Kind.V, 0, (0 until n).map { "brow:$it" })
            val ops = ArrayList<String>()
            for (i in 0 until n) {
                if (curNode()?.items?.get(i)?.type == ItemType.DIR) {
                    ops += "brop:$i:0"; ops += "brop:$i:1"
                }
            }
            m += regList("browops", Kind.GRID, 2, ops)
        }

        Page.Settings -> m += regList("setrows", Kind.V, 0, settingRows.indices.map { "set:$it" })

        Page.Sources -> {
            val tags = ArrayList<String>()
            tags += "src:local"
            netSources.indices.forEach { tags += "src:s$it" }
            tags += "src:add"
            m += regList("srcgrid", Kind.GRID, 4, tags)
            val ops = ArrayList<String>()
            netSources.indices.forEach { i -> (0..2).forEach { k -> ops += "srcop:$i:$k" } }
            m += regList("srcops", Kind.GRID, 3, ops)
        }

        Page.AddSrc -> when (addStep) {
            Step.Proto -> m += regList("proto", Kind.GRID, 2, Proto.selectable.indices.map { "proto:$it" })
            Step.Form -> {
                // 焦点链必须与 AddSourcePage 里**实际渲染的字段**逐一对齐。
                // 之前 WebDAV 分支没注册 fld:port，但页面已经画了端口输入框 ——
                // 遥控器按到那里就直接跳过，用户根本设不了端口。
                val ff = ArrayList<String>()
                ff += "fld:name"
                if (addProto == Proto.DAV) ff += "fld:scheme"
                ff += "fld:host"
                ff += "fld:port"
                ff += "fld:path"; ff += "fld:user"; ff += "fld:pwd"
                ff += "fld:remember"
                // 「忽略证书校验」只在 WebDAV 下渲染，SMB 没有这一行
                if (addProto == Proto.DAV) ff += "fld:ignorecert"
                m += regList("formfields", Kind.V, 0, ff)
                m += reg("formbtns", Kind.H, 0, "fbtn:0", "fbtn:1", "fbtn:2")
                // 「局域网自动发现」是 SMB 专用的（广播找 Windows 共享），
                // WebDAV 是填地址连的，摆一排 SMB 设备只会误点。
                if (addProto != Proto.DAV) {
                    m += regList("discover", Kind.V, 0,
                        discover.indices.map { "disc:$it" } +
                            recent.indices.map { "recent:$it" })
                }
            }
            Step.Test -> m += reg("formbtns", Kind.H, 0, "fbtn:0", "fbtn:1")
        }

        Page.Player -> {
            m += reg("pstage", Kind.SINGLE, 0, "pstage")
            m += reg("pctrl", Kind.V, 0, "ptrack")
            m += reg("pmenu", Kind.H, 0, "pmenu:0", "pmenu:1", "pmenu:2", "pmenu:3", "pmenu:4", "pmenu:5")
            if (speedMenu) m += regList("pspeed", Kind.V, 0, (0..6).map { "psi:$it" })
            // 视频比例上拉菜单：与倍速同构，档位数 = ratioPresets.size
            if (ratioMenu) m += regList("pratio", Kind.V, 0, ratioPresets.indices.map { "prt:$it" })
            // 读取失败层三个按钮（SMB 连接数耗尽时接管整个播放页）
            if (failVisible) m += reg("pfail", Kind.H, 0, "pfail:0", "pfail:1", "pfail:2")
        }
    }

    // 弹层：出现时自成一个区域，方向键只在弹层内部走
    if (diagVisible) m += reg("sdiag", Kind.H, 0, "sd:0", "sd:1", "sd:2")
    if (cfmVisible) m += reg("cfm", Kind.H, 0, "cfm:0", "cfm:1")
    if (fpopVisible) {
        // 只把"可用"的动作登记进焦点表：已在书架/已收藏的那一项焦点直接跳过
        val enabled = fpopEnabled().map { "fpop:$it" }
        if (enabled.isNotEmpty()) m += regList("fpop", Kind.V, 0, enabled)
    }
    if (aliasOpen) m += reg("aliasdlg", Kind.V, 0, "aliasfld", "alias:0", "alias:1")
    if (multiOn && (page == Page.Home || page == Page.Browse)) {
        val acts = if (multiCtx == MultiCtx.Shelf) 3 else 3
        m += regList("multi", Kind.H, 0, (0 until acts).map { "multi:$it" })
    }
    regionsCache = m
    regionsSig = sig
    return m
}

fun AppState.containsTag(tag: String): Boolean =
    buildRegions().values.any { it.tags.contains(tag) }

/** 焦点所在区域名 */
fun AppState.regionOf(tag: String): Region? {
    val regions = buildRegions()
    return regions.values.firstOrNull { it.tags.contains(tag) }
}

/** 方向键：返回 true = 已消费 */
fun AppState.nav(dir: String): Boolean {
    // 协议开关是「一行两个选项」，左右键直接切换最符合直觉
    // （界面上那行字写的就是"← → 或确认键切换"，不能只做确认键）
    if (focusTag == "fld:scheme" && addEditing == null && (dir == "left" || dir == "right")) {
        val toHttps = dir == "right"
        if (toHttps != addDavHttps) {
            addDavHttps = toHttps
            toast("协议：" + if (addDavHttps) "HTTPS 加密（默认端口 443）" else "HTTP 明文（默认端口 80）")
        }
        return true
    }
    val regions = buildRegions()
    // 复用上面这份表，不要再调 regionOf() 重新构建一次
    val r = regions.values.firstOrNull { it.tags.contains(focusTag) } ?: run {
        setFocus(defaultFocus())
        return true
    }
    val i = r.tags.indexOf(focusTag)

    // ---- 区域内移动 ----
    var target: String? = null
    when (r.kind) {
        Kind.SINGLE -> {}
        Kind.H -> target = when (dir) {
            "left" -> r.tags.getOrNull(i - 1)
            "right" -> r.tags.getOrNull(i + 1)
            else -> null
        }
        Kind.V -> target = when (dir) {
            "up" -> r.tags.getOrNull(i - 1)
            "down" -> r.tags.getOrNull(i + 1)
            else -> null
        }
        Kind.GRID -> {
            val cols = r.cols
            val row = i / cols
            val col = i % cols
            val rows = (r.tags.size + cols - 1) / cols
            target = when (dir) {
                "left" -> if (col > 0) r.tags.getOrNull(i - 1) else null
                "right" -> if (col < cols - 1) r.tags.getOrNull(i + 1) else null
                "up" -> if (row > 0) r.tags.getOrNull(i - cols) else null
                "down" -> if (row + 1 < rows) r.tags.getOrNull(i + cols) else null
                else -> null
            }
        }
    }
    if (target != null) { setFocus(target); return true }

    // ---- 显式出口（原型 EXIT.<区域>.<方向>）----
    val out = exitOf(r.name, dir, i) ?: return true
    setFocus(out)
    return true
}

private fun AppState.exitOf(region: String, dir: String, i: Int): String? = when (region) {
    "top" -> when (dir) {
        // 顶栏 ↓：按当前页面落到该页的合理落点（原型 topbarDown）
        "down" -> when (page) {
            Page.Home -> if (tab == HomeTab.Fav) "ft:0" else "hero"
            Page.Browse -> "brow:0"
            else -> defaultFocus()
        }
        else -> null
    }
    "hero" -> when (dir) {
        // 多选时顶栏被多选条盖住，↑ 必须落到多选条上，不能落到看不见的 tab
        "up" -> if (multiOn) "multi:1" else "top:tab:" + if (tab == HomeTab.Shelf) "shelf" else "fav"
        "right" -> "herosel"
        "down" -> {
            val hi = recentIdx()
            if (hi >= 0 && shelfOrder().contains(hi)) "book:$hi" else shelfOrder().firstOrNull()?.let { "book:$it" }
        }
        else -> null
    }
    "shelf" -> when (dir) {
        "up" -> if (multiOn) "multi:1" else "hero"
        // 末行不满 6 个时落到下一行最接近的一列（原型 EXIT.shelfgrid.down 的兜底）
        "down" -> {
            val order = shelfOrder()
            val cols = 6
            val row = i / cols
            val rows = (order.size + cols - 1) / cols
            if (row + 1 >= rows) null
            else {
                val start = (row + 1) * cols
                val last = minOf(start + cols, order.size) - 1
                "book:" + order[minOf(start + (i % cols), last)]
            }
        }
        else -> null
    }
    // 多选操作条（移出书架 / 全选 / 完成）：上下都要有出口，
    // 否则焦点一进去就出不来，或者从内容区干脆找不到它。
    "multi" -> when (dir) {
        // 多选条本身盖住顶栏，↑ 再往上就是不可见区域：原地不动
        "up" -> null
        "down" -> if (multiCtx == MultiCtx.Shelf) "hero" else "brow:0"
        else -> null
    }
    "favtree" -> when (dir) {
        "up" -> if (i == 0) "top:tab:fav" else null
        "down" -> if (i + 1 >= ftRows().size) "fprm" else null
        "right" -> if (favRmOn) "fprm" else "fpl:0"
        "left" -> {
            val rows = ftRows()
            val r = rows.getOrNull(i)
            when {
                favRmOn -> "fpall"
                r == null -> null
                ftOpen[r.key] == true -> { closeNode(r.key); "ft:$i" }
                r.parent != null -> { ftSel = r.parent!!; "ft:${r.parent}" }
                else -> "top:tab:fav"
            }
        }
        else -> null
    }
    "favrm" -> when (dir) {
        "up" -> "ft:${ftSel.coerceAtLeast(0)}"
        "left" -> if (favRmOn) "fpall" else null
        "right" -> if (favRmOn) "fprm" else null
        else -> null
    }
    "fplist" -> when (dir) {
        "left" -> "ft:$ftSel"
        "up" -> if (ftRow > 0) { ftRow--; "fpl:$ftRow" } else "ft:$ftSel"
        // 与界面同一份列表（ftKeyNow），否则会像上面那样判成没有下一项
        "down" -> if (ftRow + 1 < mcItems(ftKeyNow()).size) { ftRow++; "fpl:$ftRow" } else null
        else -> null
    }
    "dactions" -> when (dir) {
        "up" -> "top:tab:" + if (tab == HomeTab.Shelf) "shelf" else "fav"
        "right" -> "ep:0"
        else -> null
    }
    "dgrid" -> when (dir) {
        "up" -> "top:tab:" + if (tab == HomeTab.Shelf) "shelf" else "fav"
        "left" -> if (i % 4 == 0) "dact:0" else null
        else -> null
    }
    "btree" -> when (dir) {
        // 数据源页从「设置 → 数据源」进入，向上应回设置，不能落回书架
        "up" -> "top:set"
        "left" -> "top:set"
        "right" -> "brow:0"
        else -> null
    }
    "brows" -> when (dir) {
        "up" -> if (i == 0) "top:set" else null
        "left" -> {
            // 回左树时定位到**当前这一层**的那一行（原来按 favs.size 夹取，
            // 树行是动态的，夹出来的位置和当前目录对不上）
            val cur = browseStack.lastOrNull()
            val idx = browseRows().indexOfFirst { it.key == cur }
            "bt:${if (idx >= 0) idx else 0}"
        }
        "right" -> {
            val node = curNode()?.items?.getOrNull(i)
            if (node?.type == ItemType.DIR) "brop:$i:0" else null
        }
        else -> null
    }
    "browops" -> when (dir) {
        // 原型：browops 的 ↑ 统一回第一行，← 回本行
        "up" -> "brow:0"
        "left" -> "brow:${i / 2}"
        else -> null
    }
    "setrows" -> when (dir) {
        "up" -> if (i == 0) "top:set" else null
        else -> null
    }
    "srcgrid" -> when (dir) {
        "up" -> "top:set"
        "down" -> {
            // 本地卡没有操作按钮；网络源卡落到自己的第一个操作
            val tag = buildRegions()["srcgrid"]?.tags?.getOrNull(i)
            val si = tag?.removePrefix("src:s")?.toIntOrNull()
            if (si != null) "srcop:$si:0" else null
        }
        else -> null
    }
    "srcops" -> when (dir) {
        "up" -> "src:s${i / 3}"
        else -> null
    }
    "proto" -> when (dir) {
        "up" -> "top:set"
        "down" -> null
        else -> null
    }
    "formfields" -> when (dir) {
        "up" -> if (i == 0) "top:set" else null
        "down" -> "fbtn:0"
        // 右侧面板只有 SMB 才有（WebDAV 不显示局域网发现）
        "right" -> if (addProto == Proto.DAV) null else "disc:0"
        else -> null
    }
    "discover" -> when (dir) {
        "left" -> "fld:host"
        "right" -> null
        else -> null
    }
    "formbtns" -> when (dir) {
        // 往上要回到「最后一个表单字段」，而最后一个字段随协议变化：
        // WebDAV 是忽略证书开关，SMB 是记住密码。写死 ignorecert 会让 SMB 卡住。
        // 结果页（Step.Test）没有任何输入框，上键必须无出口，
        // 否则会跳到不存在的 tag，遥控器表现就是「上键失灵」。
        "up" -> when {
            addStep == Step.Test -> null
            addProto == Proto.DAV -> "fld:ignorecert"
            else -> "fld:remember"
        }
        else -> null
    }
    "pctrl" -> when (dir) {
        "down" -> "pmenu:0"
        else -> null
    }
    "pmenu" -> when (dir) {
        "up" -> "ptrack"
        else -> null
    }
    "pstage" -> null
    else -> null
}

// =====================================================================================
// 激活（确认键）—— 原型 activate(el) 的等价实现
// =====================================================================================
fun AppState.activate(tag: String) {
    if (!containsTag(tag)) return
    setFocus(tag)

    when {
        // ---------- 顶栏 ----------
        tag == "top:tab:shelf" -> tabAct(HomeTab.Shelf)
        tag == "top:tab:fav" -> tabAct(HomeTab.Fav)
        tag == "top:set" -> {
            go(Page.Settings, record = false)
            setFocus("top:set")
        }

        // ---------- 书架页 ----------
        tag == "hero" -> {
            val hi = recentIdx()
            if (hi >= 0) sel = hi
            go(Page.Player)
        }
        tag == "herosel" -> {
            if (multiOn) multiAct(0) else enterMulti(MultiCtx.Shelf)
        }
        tag.startsWith("book:") -> {
            val i = tag.removePrefix("book:").toInt()
            if (multiOn) togglePick(i) else { sel = i; go(Page.Detail) }
        }

        // ---------- 详情页 ----------
        tag == "dact:0" -> go(Page.Player)
        tag == "dact:1" -> back()
        tag.startsWith("ep:") -> {
            val i = tag.removePrefix("ep:").toInt()
            updateCourse(sel) { it.copy(curEp = i + 1, lastEpName = epName(sel, i)) }
            go(Page.Player)
        }

        // ---------- 收藏页（Miller 树 + 右侧内容）----------
        tag == "fpall" -> favRmAllToggle()
        tag == "fprm" -> if (favRmOn) favRmCommit() else favRmEnter()
        tag.startsWith("ft:") -> {
            val i = tag.removePrefix("ft:").toInt()
            if (favRmOn) {
                val r = ftRows().getOrNull(i) ?: return
                favRmToggle(r.key, r.pickable, r.name)
            } else {
                ftToggle(i)
            }
        }
        tag.startsWith("fpl:") -> fpEnter(tag.removePrefix("fpl:").toInt())

        // ---------- 浏览页左树 ----------
        // Enter = 展开/收起（与收藏页同一套 Miller 逻辑），右侧同时切到这一层，
        // 焦点留在树上；想进右侧列表按 →。原来这里错用了 favs 的索引去 openRealDir，
        // 树行和收藏项根本不是一一对应，所以按了像是"点不开"。
        tag.startsWith("bt:") -> {
            val i = tag.removePrefix("bt:").toIntOrNull() ?: return
            val r = browseRows().getOrNull(i) ?: return
            // 先收起/展开，再切右侧；expandSelf=false 保证"收起"不会被 openRealDir 又打开
            val willOpen = ftOpen[r.key] != true
            // 收起时必须把子孙的展开状态一起清掉（closeNode），只写 ftOpen - key 的话
            // 再展开这层会把上次展开过的子层整棵复原回来
            if (willOpen) ftOpen = ftOpen + (r.key to true) else closeNode(r.key)
            openRealDir(r.key, keepFocus = true, expandSelf = willOpen)
            setFocus("bt:$i")
        }
        tag.startsWith("brow:") -> {
            val i = tag.removePrefix("brow:").toInt()
            val node = curNode() ?: return
            val item = node.items.getOrNull(i) ?: return
            if (item.type == ItemType.DIR) {
                // 目录行确认 = 打开与收藏页**同一个**文件夹操作小框（＋加入书架 / ☆收藏）
                openFpopBrowse(i)
            } else {
                // 浏览页直接点视频：定位到该课程对应集再播，不能盲播「当前集」
                val rel = item.relPath
                val hit = if (rel == null) null else courseEpisodes.entries
                    .firstOrNull { (_, eps) -> eps.any { it.path == rel } }
                if (hit != null) {
                    val epIdx = hit.value.indexOfFirst { it.path == rel }
                    sel = hit.key
                    updateCourse(sel) { it.copy(curEp = epIdx + 1, lastEpName = epName(sel, epIdx)) }
                    go(Page.Player)
                } else {
                    toast("这个视频不在当前书架的课程里，请先把它所在的文件夹加入书架")
                }
            }
        }
        tag.startsWith("brop:") -> {
            val parts = tag.split(":")
            val i = parts[1].toInt(); val k = parts[2].toInt()
            val item = curNode()?.items?.getOrNull(i) ?: return
            when (k) {
                0 -> {
                    addToShelfByNode(item)
                    setFocus(tag)
                }
                1 -> { addFav(item); setFocus(tag) }
            }
        }

        // ---------- 设置 ----------
        tag.startsWith("set:") -> {
            val r = settingRows.getOrNull(tag.removePrefix("set:").toInt()) ?: return
            setRowAct(r)
        }

        // ---------- 数据源 ----------
        tag == "src:local" -> { openLocal() }
        tag == "src:add" -> {
            resetAddForm()
            go(Page.AddSrc); resetAddForm(); setFocus("proto:0")
            setFocus("proto:0")
        }
        tag.startsWith("src:s") -> {
            val i = tag.removePrefix("src:s").toIntOrNull() ?: return
            val s = netSources.getOrNull(i) ?: return
            // 点卡片 = 直接进这个源的根目录。多源下不能再用"唯一那个源"的根，
            // 否则点 WebDAV 卡片会跳进 SMB 的共享树。
            if (s.id.isBlank()) return
            openRealDir(sourceRootPath(s.id))
            go(Page.Browse)
            toast("浏览「${s.name}」· ${s.path}")
        }
        tag.startsWith("srcop:") -> {
            val parts = tag.split(":")
            srcOp(parts[1].toInt(), parts[2].toInt())
        }

        // ---------- 备注名弹窗 ----------
        tag == "aliasfld" -> {
            addEditing = "alias"
            setCaretToEnd("alias")
        }
        tag == "alias:0" -> closeAliasEditor(save = true)
        tag == "alias:1" -> closeAliasEditor(save = false)

        // ---------- 添加数据源 ----------
        tag.startsWith("proto:") -> {
            val i = tag.removePrefix("proto:").toInt()
            Proto.selectable.getOrNull(i)?.let { pickProto(it) }
        }
        tag.startsWith("fld:") -> toggleEdit(tag.removePrefix("fld:"))
        tag.startsWith("disc:") -> {
            val i = tag.removePrefix("disc:").toInt()
            discover.getOrNull(i)?.let { fillFrom(it); toast("已自动填入：${it.name}") }
        }
        tag.startsWith("recent:") -> {
            val i = tag.removePrefix("recent:").toInt()
            recent.getOrNull(i)?.let { fillFrom(it); toast("已填入最近使用：${it.host}") }
        }
        tag.startsWith("fbtn:") -> addAct(tag.removePrefix("fbtn:").toInt())

        // ---------- 播放页 ----------
        tag == "pstage" -> togglePlay()
        tag.startsWith("pmenu:") -> pMenuAct(tag.removePrefix("pmenu:").toInt())
        tag == "ptrack" -> closeCtl()
        tag.startsWith("psi:") -> {
            val i = tag.removePrefix("psi:").toInt()
            if (i == 0) openCustom() else pickSpeed(playMenuSpeeds.getOrElse(i - 1) { 1f })
        }
        tag.startsWith("prt:") -> pickRatio(tag.removePrefix("prt:").toInt())
        tag.startsWith("pfail:") -> pfAct(tag.removePrefix("pfail:").toInt())

        // ---------- 弹层 ----------
        tag.startsWith("sd:") -> smbDiagAct(if (tag == "sd:0") "retry" else "close")
        tag.startsWith("cfm:") -> cfmAct(if (tag == "cfm:0") "cancel" else "ok")
        tag.startsWith("fpop:") -> fpopActByTag(tag)
        tag.startsWith("multi:") -> multiAct(tag.removePrefix("multi:").toInt())
    }
}

/** 长按（≥800ms）：只是快捷方式，绝不作为唯一入口（文档 §0 硬约束） */
fun AppState.longPress(tag: String) {
    if (multiOn) return
    when {
        tag.startsWith("book:") -> enterMulti(MultiCtx.Shelf)
        tag.startsWith("src:s") -> {
            val i = tag.removePrefix("src:s").toIntOrNull() ?: return
            setFocus("srcop:$i:0")
            toast("长按确认：编辑 / 重新连接 / 删除")
        }
    }
}

// =====================================================================================
// 播放页菜单 / 失败层
// =====================================================================================
fun AppState.pMenuAct(i: Int) {
    when (i) {
        0 -> { stepEp(-1); if (!ctl) osdShow(1200) }
        1 -> { stepEp(1); if (!ctl) osdShow(1200) }
        2 -> openSpeedMenu()
        3 -> openRatioMenu()
        4 -> mirror = !mirror
        5 -> loop = !loop
    }
}

fun AppState.pfAct(i: Int) {
    when (i) {
        0 -> pfRetry()
        1 -> openSmbDiag(
            tag = "播放中断 · 连接被电脑拒绝",
            title = "读不出视频：电脑共享的连接数已满",
            host = sourceLabel(),
            retryLabel = "我已清线程，重试读取",
            retry = { pfRetry() },
        )
        2 -> { smbFailN = 0; go(Page.Detail) }
    }
}

// =====================================================================================
// 数据源动作 / 添加数据源动作
// =====================================================================================
fun AppState.srcOp(i: Int, op: Int) {
    val s = netSources.getOrNull(i) ?: return
    when (op) {
        // 编辑：真进表单，并把**这一条**配置（含密码）灌回去，不是弹一句说明
        0 -> {
            if (s.id.isBlank()) { toast("这条数据源没有 id，无法编辑"); return }
            beginEditSource(s.id)
        }
        // 重新连接：丢掉这个源的缓存 + 整轮重扫，卡片状态由扫描结果决定
        1 -> {
            if (s.err == "full") {
                openSmbDiag(
                    host = "${s.host}:${s.port}${s.path}",
                    retryLabel = "我已清线程，重新连接",
                    retry = { loadRealCatalog() },
                )
            } else {
                toast("正在重新连接「${s.name}」…")
                if (s.id.isNotBlank()) {
                    runCatching {
                        com.chunting.purecourse.data.CatalogCache.clearById(
                            com.chunting.purecourse.App.instance, s.id,
                        )
                    }
                }
                loadRealCatalog()
            }
        }
        // 删除：只删这一条；删到一条不剩才算"没有数据源"
        else -> openCfm(
            "删除数据源",
            "确定删除「${s.name}」（${s.proto.short} · ${s.host}）？" +
                "它下面的收藏与书架记录会一起清掉，其它数据源不受影响。",
        ) {
            val ctx = com.chunting.purecourse.App.instance
            val rest = com.chunting.purecourse.data.SourceStore.list(ctx).filterNot { it.id == s.id }
            com.chunting.purecourse.data.SourceStore.remove(ctx, s.id)
            runCatching { com.chunting.purecourse.data.CatalogCache.clearById(ctx, s.id) }
            snapBySrc.remove(s.id)
            if (rest.isEmpty()) {
                sourceCfgs = emptyList()
                clearCatalog("数据源已删除 · 到「添加数据源」重新配置")
            } else {
                toast("已删除「${s.name}」")
                loadRealCatalog()
            }
            setFocus("src:local")
        }
    }
}

/**
 * 表单字段的「编辑」态切换（原型 toggleEdit）。
 *
 * 现在接的是**系统输入法**（TV 上有，焦点进文本框时自动从底部弹），
 * 所以这里只做两件事：进入/退出编辑态 + 保住遥控器焦点。
 * 之前那套「确认即填演示值」是 P0 没输入法时的占位，已删除——
 * 否则用户以为自己输入了，其实填的是假数据。
 */
fun AppState.toggleEdit(k: String) {
    if (k == "scheme") {
        addDavHttps = !addDavHttps
        toast("协议：" + if (addDavHttps) "HTTPS 加密（默认端口 443）" else "HTTP 明文（默认端口 80）")
        setFocus("fld:scheme")
        return
    }
    if (k == "ignorecert") {
        addIgnoreCert = !addIgnoreCert
        toast("忽略证书校验：" + if (addIgnoreCert) "已开启 · 可连自签 HTTPS" else "已关闭")
        setFocus("fld:ignorecert")
        return
    }
    if (k == "remember") {
        addRemember = !addRemember
        toast("记住用户名和密码：" + if (addRemember) "已勾选 · 下次自动登录" else "未勾选")
        setFocus("fld:remember")
        return
    }
    val next = if (addEditing == k) null else k
    addEditing = next
    // 进入编辑时把光标放到末尾
    if (next != null) setCaretToEnd(next)
    setFocus("fld:$k")
}

/** IME 的「完成」或返回键：结束编辑，焦点留在该字段上，方向键恢复导航 */
fun AppState.endEdit() {
    if (addEditing == null) return
    val k = addEditing
    addEditing = null
    // 备注名弹窗的输入框不是表单字段，焦点要回到弹窗自己的标签上
    if (k == "alias") setFocus("aliasfld") else setFocus("fld:$k")
}

fun AppState.openLocal() {
    // 本地存储 = 一个挂载点一条数据源（内置存储 / 每个 U 盘），左树第一层直接就是
    //「本地存储」「U盘 · XXX」。所以点这张卡就是进第一个挂载点的根，和点 SMB 卡片一样。
    if (localSources.isEmpty()) {
        toast("没有发现本地存储或 U 盘")
        return
    }
    // 没有「所有文件访问权限」时，Android 11+ 的 File.listFiles() 直接返回 null，
    // 进去只会是空目录。这里直接把用户送到授权页，而不是让他面对一个空页面猜原因。
    if (!com.chunting.purecourse.data.LocalStore.hasAllFilesAccess()) {
        toast("请先允许「所有文件访问权限」，才能读取 U 盘")
        requestAllFilesAccess()
        return
    }
    val first = localSources.first()
    openRealDir(sourceRootPath(first.id))
    go(Page.Browse)
    toast(
        if (localSources.size > 1) "本地存储 ${localSources.size} 个位置（左侧可切换）"
        else "浏览「${first.name}」"
    )
}

/** 跳到系统「所有文件访问权限」页；电视固件可能裁掉这个页面，失败时给一句说明 */
fun AppState.requestAllFilesAccess() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
    val ctx = com.chunting.purecourse.App.instance
    val ok = runCatching {
        ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
    if (!ok) {
        val ok2 = runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (!ok2) toast("这台设备没有打开授权页，请到 系统设置 → 应用 → 权限 里手动开启")
    }
}

fun AppState.addAct(i: Int) {
    when (addStep) {
        Step.Form -> when (i) {
            // 「测试连接」= 真的去连（之前是 if (addHost.isNotEmpty()) "ok"，纯占位）
            0 -> startTestConn()
            1 -> saveSource(ok = true)
            2 -> { addEditing = null; addEditSourceId = null; addStep = Step.Proto; go(Page.Sources) }
        }
        Step.Test -> when (i) {
            0 -> if (addTestState == "ok") saveSource(true) else { addEditing = null; addStep = Step.Form; setFocus("fld:host") }
            1 -> if (addTestState == "ok") startTestConn() else saveSource(false)
        }
        else -> Unit
    }
}

private var pending: android.os.Handler? = null

/** 延时执行（原型 setTimeout）：页面/步骤变化后不执行由调用点自行校验 */
fun AppState.uiPost(delayMs: Long, block: () -> Unit) {
    val h = pending ?: android.os.Handler(android.os.Looper.getMainLooper()).also { pending = it }
    h.postDelayed(block, delayMs)
}

// =====================================================================================
// 设置行激活
// =====================================================================================
fun AppState.setRowAct(r: SettingRow) {
    if (sadjOn) {
        if (sadjIndex == settingRows.indexOf(r)) adjustClose(true)
        return
    }
    when (r.type) {
        RowType.BOOL -> { toggleBool(r.key); toast("${r.title}：${if (boolOf(r.key)) "已开启" else "已关闭"}") }
        RowType.ENUM, RowType.SORT -> openAdjust(r)
        RowType.GOTO -> go(Page.Sources)
        RowType.DIAG -> openSmbDiag(
            tag = "连接诊断 · SMB 共享",
            title = if (smbFull) "当前：电脑共享的连接数已满" else "SMB 连接数上限与排查",
            sub = "Windows 自带共享最多 20 条并发连接。连不上时按下面三步判断，不用靠猜。",
            retryLabel = "去数据源重连",
            retry = { go(Page.Sources) },
        )
        RowType.INFO -> toast("当前版本 v$appVersion · 目录来自 ${sourceLabel()}")
    }
}

// =====================================================================================
// 浏览页目录栈
// =====================================================================================
/**
 * 目录打开：key 现在统一是「共享内真实相对路径」。
 * 之前这里有一张 PARENT 假映射表（math/gk/ky…），配合 Demo.kt 的演示树；
 * 换成真实 SMB 后那些 key 一个都不存在，点任何目录都会静默 return（白屏）。
 */
fun AppState.openDir(key: String?) {
    val k = key ?: return
    val real = if (k == "root" || k == "local" || k == "math") rootKey else k
    openRealDir(real)
}

fun AppState.browseBack(): Boolean {
    return if (browseStack.size > 1) {
        browseStack = browseStack.dropLast(1)
        setFocus("brow:0")
        true
    } else {
        val src = origin[Page.Browse.route]
        go(browFrom, record = false)
        if (src != null) setFocus(src)
        true
    }
}

// =====================================================================================
// 收藏页交互
// =====================================================================================
/** 当前左树选中节点对应的 key（右侧内容列跟随它）；原型 ftPaint 里 `FT.key = rows[FT.sel].key` */
fun AppState.ftKeyNow(): String = ftRows().getOrNull(ftSel)?.key ?: "#fav"

/** 右栏面包屑：直接给该节点所属数据源的真实位置（UNC 或完整 URL） */
fun AppState.ftPathText(): String {
    val rows = ftRows()
    val r = rows.getOrNull(ftSel) ?: return "我的收藏"
    val key = r.key
    if (key == "#fav") return "我的收藏（跨数据源）"
    if (key.startsWith("#c")) {
        val id = key.drop(2).toIntOrNull() ?: return "我的收藏"
        return courseDirs[id]?.let { uncOfPath(it) } ?: "我的收藏"
    }
    return uncOfPath(key)
}

fun AppState.ftToggle(i: Int) {
    val r = ftRows().getOrNull(i) ?: return
    // 切目录时左右两栏必须同时换 key，并把右侧下标收回第一项：
    // 否则从 48 项的目录切到 3 项的目录后，ftRow 还停在 30，RIGHT 会算出一个不存在的 fpl:30。
    ftSel = i
    ftKey = r.key
    ftRow = 0
    if (!r.has) {
        setFocus("ft:$i")
        toast("「${r.name}」没有子文件夹")
        return
    }
    if (ftOpen[r.key] == true) {
        // 收起＝这一支彻底清零：子孙的展开状态不能留着，否则下次展开会整棵弹回来
        closeNode(r.key)
    } else {
        ftOpen = ftOpen + (r.key to true)
    }
    ftZone = "tree"
    setFocus("ft:$i")
    toast((if (ftOpen[r.key] == true) "已展开「" else "已收起「") + r.name + "」")
}

fun AppState.fpEnter(i: Int) {
    val items = mcItems(ftKeyNow())
    val item = items.getOrNull(i) ?: return
    ftRow = i
    if (item.type == ItemType.DIR) { openFpop(i); return }
    val key = ftKeyNow()
    if (key.startsWith("#c")) {
        sel = key.drop(2).toInt()
        go(Page.Detail)
        return
    }

    // ---------- 视频行：进所在目录的详情页，再在详情里选集播放 ----------
    // 这里**不弹确认窗、也不偷偷往书架里加课程**：用户实测过"点视频 → 详情页 → 选集播放"
    // 这个两段式更顺手，加课这种改书架的动作交给右侧「＋ 书架」按钮显式做。
    val rel = item.relPath
    if (rel == null) { toast("这段视频没有可识别的路径"); return }
    val dirRel = rel.substringBeforeLast('\\', "").ifBlank { rel }
    val known = courseDirs.entries.firstOrNull { it.value == dirRel }?.key
    if (known != null) {
        sel = known
        go(Page.Detail)
        return
    }
    val dirName = dirRel.substringAfterLast('\\').ifBlank { dirRel }
    toast("「$dirName」还没加入书架，先在右边按「＋ 书架」")
}

fun AppState.fpopAct(kind: String) {
    val item = fpopItemSnapshot()
    closeFpop()   // 先把焦点还原到打开它的那一行，再做动作
    if (item == null) return
    // 收藏页与浏览页共用这一套：加书架 / 收藏 / 备注名都按快照里的真实路径办，
    // 不再依赖「当前树选中项」这种会漂移的上下文。
    when (kind) {
        "shelf" -> {
            if (inShelfPath(item.relPath)) {
                toast("「${item.name}」已经在书架里了"); return
            }
            addToShelfByNode(item)
        }
        "alias" -> openAliasEditor(item)
        else -> addFav(item)
    }
}

/** 按焦点标签执行弹窗动作（焦点只落在可用项上，这里再兜一层状态检查） */
fun AppState.fpopActByTag(tag: String) {
    when (tag) {
        "fpop:0" -> if (!fpopShelfDisabled) fpopAct("shelf")
        "fpop:1" -> if (!fpopFavDisabled) fpopAct("fav")
        "fpop:2" -> fpopAct("alias")
    }
}

// =====================================================================================
// 多选动作
// =====================================================================================
fun AppState.multiAct(i: Int) {
    val picked = multiSet.filterValues { it }.keys.toList()
    val unit = if (multiCtx == MultiCtx.Shelf) "门" else "项"
    when {
        // 书架：0 移出 1 全选 2 完成 ｜ 浏览：0 加入书架 1 全选 2 完成
        i == 2 -> exitMulti("已退出多选")
        i == 1 -> {
            if (multiCtx == MultiCtx.Shelf) {
                multiSet = shelfOrder().associate { it to true }
                toast("已全选 ${multiSet.size} 门")
            } else {
                val items = curNode()?.items ?: emptyList()
                multiSet = items.indices.filter { items[it].type == ItemType.DIR }
                    .associate { it to true }
                toast("已全选 ${multiSet.size} 项")
            }
        }
        else -> when (multiCtx) {
            MultiCtx.Shelf -> {
                if (picked.isEmpty()) { toast("请先勾选要移出的课程"); return }
                openCfm(
                    "移出书架",
                    "确定将选中的 ${picked.size} 门课程移出书架？仅从书架移除，网络文件夹与视频文件不会被删除。",
                ) {
                    // 手动加入书架的目录是落过盘的，移出时要一起删记录，
                    // 否则下次冷启动 scan 又把它读回来
                    picked.forEach { k ->
                        courseDirs[k]?.let {
                            runCatching {
                                com.chunting.purecourse.data.SourceStore
                                    .removeShelfPath(com.chunting.purecourse.App.instance, it)
                                // 记一笔「已移出」：否则下次冷启动重新扫描，这门课又自动回到书架
                                com.chunting.purecourse.data.SourceStore
                                    .addHiddenPath(com.chunting.purecourse.App.instance, it)
                                com.chunting.purecourse.data.CourseMetaStore
                                    .remove(com.chunting.purecourse.App.instance, it)
                            }
                        }
                    }
                    shelvedKeys = shelvedKeys - picked.mapNotNull { courseDirs[it] }.toSet()
                    courses = courses.mapIndexed { k, c -> if (picked.contains(k)) c.copy(out = true) else c }
                    multiOn = false; multiSet = emptyMap()
                    if (courses.getOrNull(sel)?.out == true) sel = recentIdx().coerceAtLeast(0)
                    // 落到「还在书架上的第一门」：写死 book:0 时，课程 0 恰好被移出，
                    // 焦点标签就不在区域表里了，方向键会被判成失焦再重置（像卡死）
                    setFocus(shelfOrder().firstOrNull()?.let { "book:$it" } ?: defaultFocus())
                    toast("已移出 ${picked.size} 门课程")
                }
            }
            MultiCtx.Browse -> {
                if (picked.isEmpty()) { toast("请先勾选文件夹"); return }
                var added = 0; var dup = 0
                val items = curNode()?.items ?: emptyList()
                picked.forEach { idx ->
                    items.getOrNull(idx)?.let { if (addToShelfByNode(it, quiet = true)) added++ else dup++ }
                }
                exitMulti("已加入书架 $added 个文件夹" + if (dup > 0) "（$dup 个已存在）" else "")
            }
        }
    }
}
