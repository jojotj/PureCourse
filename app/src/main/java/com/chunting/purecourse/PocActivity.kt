package com.chunting.purecourse

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chunting.purecourse.core.C
import com.chunting.purecourse.player.PocBench
import com.chunting.purecourse.ui.Txt

/**
 * 内核 PoC 台架界面（不进原型 UI 拓扑，只由 adb 唤起）：
 * `adb shell am start -n com.chunting.purecourse/.PocActivity`
 * 同时把每行结果打到 logcat（TAG=POC），便于脚本采集。
 */
class PocActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PocScreen() }
    }
}

@Composable
private fun PocScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rows = remember { mutableStateListOf<PocBench.Row>() }
    var done by remember { androidx.compose.runtime.mutableStateOf(false) }

    LaunchedEffect(Unit) {
        PocBench.runAll(ctx) { r ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { rows.add(r) }
        }
        done = true
        Log.i(PocBench.TAG, "ALL_DONE")
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())
            .padding(40.dp)
    ) {
        Txt("播放内核 PoC 台架", 34f, C.Text, 900)
        Txt("同一素材 · 同一 Surface · 串行两引擎 · 各测 init / 首帧 / 时长 / seek", 18f, C.Dim)
        rows.forEach { r ->
            Txt(
                "${r.engine}   ${r.file}   ok=${r.r.ok}\n" +
                    "init=${r.r.initMs}ms  首帧=${r.r.firstFrameMs}ms  时长=${r.r.durationMs}ms  " +
                    "seek=${r.seekMs}ms  codec=${r.r.codec}  ${r.r.err}",
                20f,
                if (r.r.ok) C.Ok else C.Danger,
                maxLines = 4,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (done) Txt("测试完成 · 结果已写入 logcat (TAG=POC)", 20f, C.Acc2, modifier = Modifier.padding(top = 24.dp))
    }
}
