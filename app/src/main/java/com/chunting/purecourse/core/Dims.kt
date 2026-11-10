package com.chunting.purecourse.core

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计令牌。
 *
 * 版式数值（间距、尺寸、字号）仍逐条对齐原型 merged-shelf-list.html；
 * 本轮按用户要求只重做**视觉**：配色层级、描边、圆角、焦点态、光晕。
 *
 * 参考方向（Apple TV / Netflix / Disney+ 这类获奖 TV 界面的共同点）：
 *  1. 底色够黑且偏中性（不是棕黑），靠「面板明度差」分层，而不是靠大量描边；
 *  2. 描边一律用低透明度白色，暖色只留给品牌强调与焦点；
 *  3. 焦点 = 白色硬环 + 品牌色内光晕，比纯色粗描边更「高级」也更清晰；
 *  4. 圆角统一放大一档（10→14 / 14→18），卡片更像实体卡片而不是色块。
 */
object C {
    /**
     * 当前调色板。由 AppRoot 每次组合时按「主题模式 + 强调色」写入。
     *
     * 所有字段都只是它的 getter：这样全工程 465 处 `C.xxx` 一行不用改，
     * 就能整体跟随明暗与强调色切换（读取发生在组合/绘制期，会正常触发重组与重绘）。
     */
    internal var pal by androidx.compose.runtime.mutableStateOf(palette(0, true))
        private set

    /** 幂等：值没变就不写，避免组合期空转重组 */
    fun applyTheme(accentIndex: Int, dark: Boolean) {
        val p = palette(accentIndex, dark)
        if (pal != p) pal = p
    }

    // ---- 品牌 / 强调 ----
    val Acc get() = pal.acc
    val Acc2 get() = pal.acc2
    val AccDeep get() = pal.accDeep
    val AccSoft get() = pal.accSoft
    val Hi get() = pal.hi

    // ---- 文字四级 ----
    val Text get() = pal.text
    val TextSoft get() = pal.textSoft
    val Dim get() = pal.dim
    val Dim2 get() = pal.dim2
    val Dim3 get() = pal.dim3

    // ---- 背景 / 面板 ----
    val Bg get() = pal.bg
    val Bg2 get() = pal.bg2
    val Panel get() = pal.panel
    val Panel2 get() = pal.panel2
    val PanelHi get() = pal.panelHi

    // ---- 描边 ----
    val Line get() = pal.line
    val Line2 get() = pal.line2
    val Line3 get() = pal.line3
    val WarmLine get() = pal.warmLine

    // ---- 焦点态 ----
    val FocusRing get() = pal.focusRing
    val FocusGlow get() = pal.focusGlow
    /** 焦点反白实底（暗=近白 / 明=近墨） */
    val FocusSolid get() = pal.focusSolid
    val FocusInk get() = pal.focusInk

    // ---- 主按钮（「继续播放」这类实底按钮）----
    val BtnFrom get() = pal.btnFrom
    val BtnTo get() = pal.btnTo
    val BtnInk get() = pal.btnInk

    // ---- 选中态专用（明暗 × 6 强调色都验过对比度）----
    /** 强调色系文字：选中态的标题/强调文案用它，别用 Acc/Acc2 直接当文字 */
    val AccText get() = pal.accText
    /** 强调色实底：开关轨、勾选框、选中胶囊 */
    val AccSolid get() = pal.accSolid
    /** 强调色实底上的字 */
    val AccSolidInk get() = pal.accSolidInk
    /** 未选中卡片底 */
    val SoftCard get() = pal.softCard
    /** 输入框 / 列表区玻璃底 */
    val GlassPanel get() = pal.glassPanel

    // ---- 语义色 ----
    val Black get() = pal.black
    val Ok get() = pal.ok
    val OkBg get() = pal.okBg
    val OkText get() = pal.okText
    val OkSoft get() = pal.okSoft
    val Blue get() = pal.blue
    val BlueText get() = pal.blueText
    val BlueSoft get() = pal.blueSoft
    val Warn get() = pal.warn
    val WarnSoft get() = pal.warnSoft
    val WarnText get() = pal.warnText
    val WarnSolid get() = pal.warnSolid
    val WarnSolidInk get() = pal.warnSolidInk
    val Danger get() = pal.danger
    val DangerText get() = pal.dangerText
    val DangerSoft get() = pal.dangerSoft

    // ---- 开关 / 勾选 ----
    val SwitchOn get() = pal.switchOn
    val SwitchOnInk get() = pal.switchOnInk
    val SwitchOff get() = pal.switchOff
    val KnobOff get() = pal.knobOff
    /** 危险色实底上的字 */
    val DangerInk get() = pal.dangerInk
    val Seg get() = pal.seg
    /** 书架页顶部氛围光 */
    val Ambient get() = pal.ambient

    // ---- 弹层 ----
    val Scrim get() = pal.scrim
    val SheetFrom get() = pal.sheetFrom
    val SheetTo get() = pal.sheetTo
    /** 低透明遮罩：暗色是白、明色是黑（面板上的浅色蒙层不能写死白） */
    val Veil get() = pal.veil
    val Veil2 get() = pal.veil2

    // ---- 协议主色 ----
    val IconLocal get() = pal.iconLocal
    val IconLocal2 get() = pal.iconLocal2
    val Smb get() = pal.smb
    val SmbDark get() = pal.smbDark
    val Dav get() = pal.dav
    val DavDark get() = pal.davDark
    val Ftp get() = pal.ftp
    val FtpDark get() = pal.ftpDark
    val Nfs get() = pal.nfs
    val NfsDark get() = pal.nfsDark
}


/** 舞台逻辑尺寸（原型 #stage 固定 1920×1080） */
const val STAGE_W = 1920f
const val STAGE_H = 1080f
/** 顶栏条本身高度（不含上方留白） */
const val TOPBAR_H = 80
/** 顶栏上方留白：把顶栏「抬」离屏幕上沿，视觉位置约屏高 11.7%，不再贴边 */
const val TOPBAR_PAD = 46

/**
 * 全站唯一焦点框。
 *
 * 视觉：**白色硬环 + 品牌橙内光晕**（用户要求参考 iOS/Apple TV 的选中态）。
 * 三条实现约束（都是踩过的坑，不能改）：
 * 1. 用 `drawWithContent` 在内容**之后**画，环才不会被卡片自带背景盖住；
 * 2. 环画在**边界之内**（CSS 的 box-shadow 是向外扩的，平移后会被父容器 clip 裁掉）；
 * 3. 圆角**不随外扩量放大**，否则四个角会鼓出去。
 *
 * 另外不做真实 blur/外发光：TV 盒子 GPU 上开模糊层会让方向键明显变粘。
 * 这里用「2.5dp 白环 + 6dp 半透明橙内光」模拟，静态绘制，零额外合成开销。
 */
fun Modifier.tvFocus(
    focused: Boolean,
    radius: Dp,
    scale: Float = 1.032f,
    ring: Color = C.FocusRing,
    /** false = 只改边框/底色不出环（原型里 .obtn / .fbtn 这类小按钮就没有外环） */
    drawRing: Boolean = true,
): Modifier = this
    .graphicsLayer {
        val s = if (focused && scale != 1f) scale else 1f
        // 只在真的变化时写 layer，避免每次重组都让合成器重算
        if (scaleX != s || scaleY != s) {
            scaleX = s
            scaleY = s
        }
    }
    .drawWithContent {
        drawContent()
        if (!focused || !drawRing) return@drawWithContent
        val r = radius.toPx()

        // 1) 品牌橙内光晕：给焦点一点「发光」的温度，但不至于盖住内容
        if (size.width > 120.dp.toPx() && size.height > 40.dp.toPx()) {
            val inset = 5.dp.toPx()
            drawRoundRect(
                color = C.FocusGlow.copy(alpha = 0.34f),
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius(
                    (r - inset / 2f).coerceAtLeast(0f),
                    (r - inset / 2f).coerceAtLeast(0f),
                ),
                style = Stroke(6.dp.toPx()),
            )
        }

        // 2) 白色硬环：贴边内描 2.5dp，任何父容器裁剪都不会切到
        val w = 2.5.dp.toPx()
        drawRoundRect(
            color = ring.copy(alpha = 0.95f),
            topLeft = Offset(w / 2f, w / 2f),
            size = Size((size.width - w).coerceAtLeast(1f), (size.height - w).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(w),
        )
    }

/** 常用圆角（数值对齐原型，本轮整体上调一档） */
object R {
    val r4 = RoundedCornerShape(4.dp)
    val r5 = RoundedCornerShape(5.dp)
    val r6 = RoundedCornerShape(6.dp)
    val r7 = RoundedCornerShape(7.dp)
    val r8 = RoundedCornerShape(8.dp)
    val r9 = RoundedCornerShape(9.dp)
    val r10 = RoundedCornerShape(10.dp)
    val r11 = RoundedCornerShape(11.dp)
    val r12 = RoundedCornerShape(12.dp)
    val r14 = RoundedCornerShape(14.dp)
    val r16 = RoundedCornerShape(16.dp)
    val r18 = RoundedCornerShape(18.dp)
    val r20 = RoundedCornerShape(20.dp)
    val r22 = RoundedCornerShape(22.dp)
    val r24 = RoundedCornerShape(24.dp)
    val r28 = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(99.dp)
}
