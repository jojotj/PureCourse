package com.chunting.purecourse.state

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.chunting.purecourse.data.ItemType

/**
 * 遥控器五键路由 —— 原型 `onKey / onKeyUp / back()` 的等价实现。
 *
 * 分层优先级（与原型完全一致，从高到低）：
 * 诊断层 → 文件夹小框 → 二次确认 → 播放页（失败层 / 自定义倍速 / 倍速菜单 / 全屏态）
 * → 设置调整态 → 常规方向键 → 确认键（含 800ms 长按）→ 返回键。
 */
private class LongPress {
    var downAt = 0L
    var burned = false
    var tag: String? = null
    var runnable: Runnable? = null
}

private val lp = HashMap<AppState, LongPress>()
private val handler = Handler(Looper.getMainLooper())
private const val LONG_PRESS_MS = 800L

private fun lpOf(s: AppState) = lp.getOrPut(s) { LongPress() }

// ---------------- 方向键长按连发 ----------------
//
// 为什么不用系统自带的 repeat：不同遥控器（品牌 / 按键固件）的 RepeatCount 起始延迟
// 与频率差别极大，有的 40ms 一次（快到一格都看不清），有的一次 repeat 都不发。
// 原来 MainActivity 又把所有 repeat 事件无条件吞掉，于是「按住下」等于没按。
// 成熟 TV 应用（Kodi / 各类盒子播放器）的做法是：按下先响应一格 → 停顿 REPEAT_START_MS
// → 再按固定节奏连发，节奏由应用自己掌握，这样在任何遥控器上手感一致。
private class DpadRepeat {
    var code = 0
    var runnable: Runnable? = null
}

private val repeats = HashMap<AppState, DpadRepeat>()

/** 按住多久开始连发（毫秒）。400ms ≈ 人手上「按住不放」的起点 */
private const val REPEAT_START_MS = 400L

/** 连发间隔（毫秒）。110ms ≈ 每秒 9 格，与主流 TV 播放器手感接近 */
private const val REPEAT_STEP_MS = 110L

/** 开始连发某方向键（按下时调用一次，抬起时 [stopDpadRepeat]） */
fun AppState.startDpadRepeat(keyCode: Int) {
    stopDpadRepeat()
    if (Keys.dir(keyCode) == null) return
    val st = DpadRepeat().also { it.code = keyCode }
    val s = this
    val r = object : Runnable {
        override fun run() {
            val cur = repeats[s] ?: return
            if (cur.code != keyCode) return
            if (s.addEditing != null &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                // 表单编辑态：左右键在 MainActivity 里被接管成「移光标」，
                // 连发时同样要移光标，而不是走区域导航。
                s.moveCaret(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            } else {
                // 其余所有情况（列表滚动 / 弹窗选项 / 播放页 seek 与面板移动）
                // 都复用按下时的同一套分层路由，保证长按与单按语义完全一致。
                s.handleKeyDown(keyCode)
            }
            handler.postDelayed(this, REPEAT_STEP_MS)
        }
    }
    st.runnable = r
    repeats[this] = st
    handler.postDelayed(r, REPEAT_START_MS)
}

/** 停止连发（方向键抬起 / 动作被取消时调用） */
fun AppState.stopDpadRepeat() {
    repeats.remove(this)?.runnable?.let { handler.removeCallbacks(it) }
}

fun AppState.handleKeyDown(keyCode: Int): Boolean {
    val dir = Keys.dir(keyCode)
    val ok = Keys.isOk(keyCode)
    val back = Keys.isBack(keyCode)

    // ---------- 0. 表单编辑态：按键交给系统输入法 / 文本框 ----------
    // 这里必须**放行**，否则遥控器的方向键会被我们的区域导航吃掉，
    // 用户看到的是「输入框里光标不动、焦点却在乱跳」。
    // 返回键由我们收：先结束编辑（键盘随之收起），再让后续返回走正常分级。
    if (addEditing != null) {
        // 上下键 = 结束本格输入并直接跳到上/下一格。
        // 原来这里把上下键也放行给输入框，用户在电视上改完一格必须先按返回
        // 退出编辑（而且返回被拆成「先收键盘、再退编辑」两段），才能移焦点，
        // 改一个字段要按好几下。左右键仍保留给光标（键盘的左右箭头走 IME，
        // 不收键盘时用遥控器左右移光标 —— 这是之前特意的行为，不动）。
        if (dir == "up" || dir == "down") {
            endEdit()
            // 不 return：继续往下走正常区域导航，一步就换到相邻字段
        } else {
            // 返回键：一次就退出编辑（收键盘由调用方负责）
            if (back) { endEdit(); return true }
            return false
        }
    }

    // ---------- 1. SMB 连接诊断层：←→ 切按钮、确认执行、返回关闭 ----------
    if (diagVisible) {
        val list = listOf("sd:0", "sd:1", "sd:2")
        if (dir != null) {
            val i = list.indexOf(focusTag)
            val step = if (dir == "left" || dir == "up") -1 else 1
            setFocus(list[(if (i < 0) 0 else (i + step)).coerceIn(0, list.size - 1)])
            return true
        }
        if (ok) { smbDiagAct(list.getOrElse(list.indexOf(focusTag).coerceAtLeast(0)) { "sd:2" }); return true }
        if (back) { smbDiagAct("close"); return true }
        return true
    }

    // ---------- 2. 收藏页文件夹操作小框 ----------
    if (fpopVisible) {
        // 只走可用项：已在书架/已收藏的那一行是灰的，焦点根本到不了
        val list = fpopEnabled().map { "fpop:$it" }
        if (dir != null) {
            if (list.isEmpty()) return true
            val i = list.indexOf(focusTag).let { if (it < 0) 0 else it }
            val step = if (dir == "up" || dir == "left") -1 else 1
            setFocus(list[(i + step).coerceIn(0, list.size - 1)])
            return true
        }
        if (ok) {
            fpopActByTag(focusTag)
            return true
        }
        if (back) { closeFpop(); return true }
        return true
    }

    // ---------- 2b. 备注名弹窗 ----------
    if (aliasOpen) {
        // 输入态在上面的 addEditing 分支已经处理：方向键交给输入法/光标，返回退出编辑
        if (dir != null) return nav(dir)
        if (ok) {
            when (focusTag) {
                "alias:0" -> closeAliasEditor(save = true)
                "alias:1" -> closeAliasEditor(save = false)
                // 再按一次输入框 = 重新进入输入态（比"必须退回再点进来"顺手）
                else -> activate("aliasfld")
            }
            return true
        }
        if (back) { closeAliasEditor(save = false); return true }
        return true
    }

    // ---------- 3. 二次确认框 ----------
    if (cfmVisible) {
        if (dir != null) {
            val list = listOf("cfm:0", "cfm:1")
            val i = list.indexOf(focusTag)
            val step = if (dir == "left" || dir == "up") -1 else 1
            setFocus(list[(if (i < 0) 0 else (i + step)).coerceIn(0, 1)])
            return true
        }
        if (ok) { cfmAct(if (focusTag == "cfm:0") "cancel" else "ok"); return true }
        if (back) { cfmAct("cancel"); return true }
        return true
    }

    // ---------- 4. 播放页 ----------
    if (page == Page.Player) {
        // 4a. 读取失败层
        if (failVisible) {
            if (dir == "left" || dir == "right") {
                val list = listOf("pfail:0", "pfail:1", "pfail:2")
                val i = list.indexOf(focusTag)
                val step = if (dir == "left") -1 else 1
                setFocus(list[(if (i < 0) 0 else (i + step)).coerceIn(0, 2)])
                return true
            }
            if (dir != null) return true
            if (ok) { pfAct(listOf("pfail:0", "pfail:1", "pfail:2").indexOf(focusTag).coerceAtLeast(0)); return true }
            if (back) { pfAct(2); return true }
            return true
        }
        // 4b. 自定义倍速对话框
        if (customOpen) {
            when {
                dir == "left" -> customStep(-1)
                dir == "right" -> customStep(1)
                dir != null -> Unit
                ok -> applyCustom()
                back -> cancelCustom()
            }
            return true
        }
        // 4c. 倍速上拉菜单
        if (speedMenu) {
            if (dir == "up" || dir == "down") {
                val i = focusTag.removePrefix("psi:").toIntOrNull() ?: 0
                val n = (i + if (dir == "up") -1 else 1).coerceIn(0, 6)
                setFocus("psi:$n")
                return true
            }
            if (ok) {
                val i = focusTag.removePrefix("psi:").toIntOrNull() ?: 0
                if (i == 0) openCustom() else pickSpeed(playMenuSpeeds.getOrElse(i - 1) { 1f })
                return true
            }
            if (back || dir == "left") { closeSpeedMenu(); return true }
            return true
        }
        // 4d. 视频比例上拉菜单（与倍速同构：上下选档、确认生效、返回/左键收起）
        if (ratioMenu) {
            if (dir == "up" || dir == "down") {
                val i = focusTag.removePrefix("prt:").toIntOrNull() ?: ratioMode
                val step = if (dir == "up") -1 else 1
                setFocus("prt:${(i + step).coerceIn(0, ratioPresets.size - 1)}")
                return true
            }
            if (ok) {
                pickRatio(focusTag.removePrefix("prt:").toIntOrNull() ?: ratioMode)
                return true
            }
            if (back || dir == "left") { closeRatioMenu(); return true }
            return true
        }
        // 4e. 全屏态 / 控制层态
        if (dir == "down" && !ctl) { openCtl(); return true }
        if (dir == "up" && ctl && !focusTag.startsWith("pmenu")) return true
        if ((dir == "left" || dir == "right") && !focusTag.startsWith("pmenu")) {
            val step = if (ctl) 60 else seekStep
            seek(if (dir == "left") -step else step)
            osdShow(1000)
            return true
        }
        if (dir != null) return nav(dir)
        if (ok) {
            if (!ctl) { togglePlay(); return true }
            if (focusTag.startsWith("pmenu:")) { pMenuAct(focusTag.removePrefix("pmenu:").toInt()); return true }
            if (focusTag == "ptrack") { closeCtl(); return true }
            return true
        }
        if (back) { back(); return true }
        return true
    }

    // ---------- 5. 设置项调整态：左右切档、上下失效 ----------
    if (sadjOn) {
        when (dir) {
            "left" -> adjustShift(-1)
            "right" -> adjustShift(1)
            "up", "down" -> Unit
        }
        if (ok) { adjustClose(true); return true }
        if (back) { adjustClose(false); return true }
        return true
    }

    // ---------- 6. 常规方向键 ----------
    if (dir != null) return nav(dir)

    // ---------- 7. 确认键：按下开始计时，抬起未满 800ms 才执行 ----------
    if (ok) {
        val st = lpOf(this)
        if (st.downAt == 0L) {
            st.downAt = SystemClock.uptimeMillis()
            st.burned = false
            st.tag = focusTag
            val s = this
            val tag = focusTag
            val r = Runnable {
                val cur = lpOf(s)
                if (cur.downAt != 0L && cur.tag == tag) {
                    cur.burned = true
                    longPress(tag)
                }
            }
            st.runnable = r
            handler.postDelayed(r, LONG_PRESS_MS)
        }
        return true
    }

    // ---------- 8. 返回 ----------
    // 必须把 back() 的返回值透传出去：false = 没有更上层了，交给 MainActivity finish() 退出应用。
    // 之前这里写成 { back(); return true }，把返回值吞掉，导致所有返回键都被报成「已消费」，
    // 应用永远退不出去（电视真机连按 3 次 BACK 前台仍是本 App，已复现）。
    if (back) return back()
    return false
}

fun AppState.handleKeyUp(keyCode: Int): Boolean {
    // 编辑态下 OK 键属于输入法（用它自己的「完成/换行」），不要触发我们的 activate
    if (addEditing != null) return false
    if (!Keys.isOk(keyCode)) return false
    val st = lpOf(this)
    st.runnable?.let { handler.removeCallbacks(it) }
    val tag = st.tag
    val burned = st.burned
    st.downAt = 0L; st.burned = false; st.tag = null; st.runnable = null
    if (!burned && tag != null) activate(tag)
    return true
}

/**
 * 返回分级（原型 back()）：返回 true = 已消费，false = 交给系统（退出应用）。
 */
fun AppState.back(): Boolean {
    if (diagVisible) { smbDiagAct("close"); return true }
    if (aliasOpen) { closeAliasEditor(save = false); return true }
    if (fpopVisible) { closeFpop(); return true }
    if (cfmVisible) { toast("已取消"); closeCfm(); return true }
    if (sadjOn) { adjustClose(false); return true }
    if (addEditing != null) {
        addEditing = null
        setFocus("fld:host")
        return true
    }
    if (multiOn) { exitMulti("已退出多选"); return true }
    if (favRmOn) { favRmExit("已退出取消收藏"); return true }

    when (page) {
        Page.Player -> {
            if (failVisible) { pfAct(2); return true }
            // 弹层必须先于控制层收起：否则 ctl 一关，弹窗留在半空且焦点无处可去
            if (ratioMenu) { closeRatioMenu(); return true }
            if (ctl) { closeCtl(); return true }
            if (speedMenu) { closeSpeedMenu(); return true }
            // 与片尾回退保持一致：从书架进的播放页，返回也回书架。
            // 原来一律 go(Page.Detail)，从 Hero 直接开播再按返回会莫名落到详情页。
            val backTo = if (playerFrom == Page.Detail) Page.Detail else Page.Home
            val o = origin[Page.Player.route]
            go(backTo, record = false, noRecall = true)
            setFocus(
                if (o != null && containsTag(o)) o
                else if (backTo == Page.Detail) "dact:0" else "hero"
            )
            return true
        }
        Page.Detail -> {
            val o = origin[Page.Detail.route]
            go(Page.Home)
            if (o != null) setFocus(o) else setFocus(if (tab == HomeTab.Fav) "ft:0" else "hero")
            return true
        }
        Page.Browse -> return browseBack()
        Page.AddSrc -> {
            when (addStep) {
                Step.Test -> {
                    addStep = Step.Form
                    setFocus("fbtn:0")
                }
                Step.Form -> {
                    addEditing = null
                    addStep = Step.Proto
                    setFocus("proto:0")
                }
                Step.Proto -> go(Page.Sources, record = false)
            }
            return true
        }
        Page.Sources -> {
            go(Page.Settings, record = false)
            setFocus("top:set")
            return true
        }
        Page.Settings -> {
            // 原型 back() 末尾有 setFocus(curTabEl())，移植时漏了这一级；用户也明确要求：
            // 光标还在页面内容里时，第一次返回先回到顶栏「自己所在的」那个入口。
            if (!focusTag.startsWith("top:")) { setFocus("top:set"); return true }
            // 已经在顶栏 → 原型行为：回进入设置前的 Tab 页（书架 / 收藏）
            val tb = tab
            go(Page.Home, record = false)
            applyTab(tb)
            setFocus(tabLanding(tb))
            return true
        }
        Page.Home -> {
            val topTag = if (tab == HomeTab.Fav) "top:tab:fav" else "top:tab:shelf"
            // 已在顶栏：没有更上层，返回 false 交给 MainActivity finish() 退出应用。
            if (focusTag.startsWith("top:")) return false
            if (tab == HomeTab.Fav) {
                // 原型 back()：右列 → 回左树；左树已展开 → 先收起；否则回上层
                if (focusTag.startsWith("fpl:")) { setFocus("ft:$ftSel"); return true }
                val rows = ftRows()
                val r = rows.getOrNull(ftSel)
                if (r != null && ftOpen[r.key] == true) {
                    // 收起要连子孙一起清零，否则下次展开父目录会把旧的子层整棵复原
                    closeNode(r.key)
                    setFocus("ft:$ftSel")
                    return true
                }
                if (r?.parent != null) { ftSel = r.parent!!; setFocus("ft:${r.parent}"); return true }
                if (ftSel != 0) { ftSel = 0; setFocus("ft:0"); return true }
            }
            // 原型 back() 最后一行 setFocus(curTabEl())：回顶栏当前 Tab。
            // 再按一次返回才退出应用（避免误按直接退出，TV 上的常规做法）。
            setFocus(topTag)
            return true
        }
    }
    return false
}

/** 顶栏：任意页面按确认都能直接切走（原型 tabAct） */
fun AppState.topTabEnter(tag: String) {
    if (tag == "top:set") { go(Page.Settings, record = false); return }
    tabAct(if (tag.endsWith("fav")) HomeTab.Fav else HomeTab.Shelf)
}

