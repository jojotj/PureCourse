package com.chunting.purecourse

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.chunting.purecourse.state.AppState
import com.chunting.purecourse.state.Keys
import com.chunting.purecourse.state.endEdit
import com.chunting.purecourse.state.handleKeyDown
import com.chunting.purecourse.state.handleKeyUp
import com.chunting.purecourse.state.startDpadRepeat
import com.chunting.purecourse.state.stopDpadRepeat
import com.chunting.purecourse.ui.AppRoot

/**
 * 单 Activity + 自绘界面。
 *
 * 遥控器五键**在 dispatchKeyEvent 层统一接管**（不依赖 Compose 焦点系统）：
 * 方向键走原型那套 REG/EXIT 区域导航，确认键带 800ms 长按判定，返回键按层级回退。
 * 这样做的原因见工具链清单 §19.3：TV 上默认 FocusFinder 是坐标式的，
 * 行数一变或出现隐藏元素就必然有方向移不到。
 */
class MainActivity : ComponentActivity() {

    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        // Android 10 及更早没有「所有文件访问权限」这个开关，遍历 U 盘靠的是
        // READ_EXTERNAL_STORAGE 运行时权限。不在这里申请的话，这类设备上
        // 本地存储会一直显示空目录 —— 用户完全看不出是权限问题。
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
            }
        }

        setContent {
            val s = remember {
                AppState().also { st ->
                    // 把上次保存的真实 SMB 配置还原成数据源卡（否则重启后只剩演示卡片）
                    st.restoreRealSource()
                    // 插拔 U 盘后自动刷新本地源（否则要重启应用才看得到新盘）
                    st.watchStorage()
                    // 台架直达：adb shell am start -n com.chunting.purecourse/.MainActivity --es page player
                    when (intent?.getStringExtra("page")) {
                        "player" -> st.go(com.chunting.purecourse.state.Page.Player)
                        "settings" -> st.go(com.chunting.purecourse.state.Page.Settings)
                        "sources" -> st.go(com.chunting.purecourse.state.Page.Sources)
                        "fav" -> { st.tabAct(com.chunting.purecourse.state.HomeTab.Fav) }
                        "detail" -> st.go(com.chunting.purecourse.state.Page.Detail)
                        "browse" -> st.go(com.chunting.purecourse.state.Page.Browse)
                        "addsrc" -> { st.go(com.chunting.purecourse.state.Page.AddSrc); st.resetAddForm(); st.setFocus("proto:0") }
                        "playerctl" -> { st.go(com.chunting.purecourse.state.Page.Player); st.openCtl() }
                        "diag" -> st.openSmbDiag()
                        "smbfull" -> { st.smbFull = true; st.go(com.chunting.purecourse.state.Page.Player) }
                    }
                }
            }
            state = s
            AppRoot(s)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // ★ 表单编辑态：左右键由我们直接改光标。
        //
        // 电视输入法（搜狗 TV 版）只吃「退格删左边」，没有正向删除。光标一旦只能停在末尾，
        // 整行就只能重输；所以必须让遥控器左右键能移光标。
        //
        // 为什么**不**按「输入法是否弹出」分流：实测这个信号在本工程拿不到 ——
        // Activity 是 adjustPan + 沉浸全屏，框架根本不给这类窗口派发 IME inset
        // （Android 15 上 IME 明明弹出、dumpsys input_method 里 mInputShown=true，
        //  而 decorView 的 Type.ime() inset 恒为 0，可见区域高度差也恒为 0）。
        // 依赖一个观测不到的状态一定会出错，改成不依赖它的确定性规则：
        //   · 键盘弹出时，键盘自带的左右箭头走 InputConnection.setSelection 移光标，
        //     不经过 dispatchKeyEvent，我们接管左右键不会和它打架；
        //   · 键盘收起后，遥控器左右键走到这里，直接改 editCaret。
        //
        // 返回键分两段：第一次只收键盘（编辑态保留，接着就能用遥控器移光标 / 删字），
        // 第二次才真正退出编辑。这样不管键盘是谁收掉的，用户按两次总能回到导航。
        if (state.addEditing != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                // 一次返回就退出编辑态（同时收键盘）。
                // 原来是两段式（第一下只收键盘、第二下才退编辑），在电视上表现为
                // 「按一次没反应、要连按两三次才能换到下一格」，实测非常折磨。
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideImeNow()
                    state.endEdit()
                }
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                when (event.action) {
                    // 光标同样支持长按连续移动（连发节奏由 KeyRouter 统一控制）
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            state.moveCaret(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                            state.startDpadRepeat(code)
                        }
                    }
                    KeyEvent.ACTION_UP -> state.stopDpadRepeat()
                }
                return true
            }
        }

        val relevant = Keys.dir(code) != null || Keys.isOk(code) || Keys.isBack(code)
        if (!relevant) return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 系统自带的 repeat 一律吞掉：连发节奏由 KeyRouter.startDpadRepeat 自己控制。
                // 各家遥控器的 RepeatCount 起始延迟 / 频率差异太大（有的 40ms 一次快到看不清），
                // 直接用系统 repeat 会导致「换个遥控器手感全变」。原来这里是无条件吞掉，
                // 于是长按方向键完全不动 —— 现在改成按下即启动应用自己的连发定时器。
                if (event.repeatCount > 0) return true
                val handled = state.handleKeyDown(code)
                if (!handled) {
                    if (Keys.isBack(code)) {
                        finish()
                        return true
                    }
                    // ★ 必须放行给系统：编辑态下 handleKeyDown 返回 false 就是这个意思。
                    // 原来这里无条件 return true，把左右键吞掉了 —— 输入框里光标移不动、
                    // 删不掉后面的字，用户只能退出编辑，整行内容改不了。
                    return super.dispatchKeyEvent(event)
                }
                // 方向键：按下先走一格，按住不放则由定时器按固定节奏继续
                if (Keys.dir(code) != null) state.startDpadRepeat(code)
                return true
            }
            KeyEvent.ACTION_UP -> {
                state.stopDpadRepeat()
                val handled = state.handleKeyUp(code)
                // 同理：编辑态（addEditing != null）handleKeyUp 返回 false，交给输入框
                if (!handled) return super.dispatchKeyEvent(event)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 回到前台时重新探测本地挂载点。
     *
     * 主要是为「去系统设置里打开所有文件访问权限」这条路径服务：用户授权完按返回回来，
     * 界面必须立刻能读到 U 盘，而不是还停在"未授权"的提示上。
     */
    override fun onResume() {
        super.onResume()
        if (::state.isInitialized) runCatching { state.refreshLocalSources() }
    }

    override fun onDestroy() {
        if (::state.isInitialized) runCatching { state.unwatchStorage() }
        super.onDestroy()
    }

    /**
     * 主动收起软键盘（不退出编辑态）。
     *
     * 不在编辑态里把 BACK 让给系统去关键盘：一旦输入法不认这个返回键，事件会继续往
     * Activity 默认链路走 → onBackPressed → finish()，用户只是想把键盘收起来，App 却退了。
     * 自己调 IMM 是可控的。
     */
    private fun hideImeNow() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val tokens = listOfNotNull(window.decorView.windowToken, currentFocus?.windowToken)
        for (t in tokens) runCatching { imm.hideSoftInputFromWindow(t, 0) }
    }
}
