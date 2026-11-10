package com.chunting.purecourse.core

import androidx.compose.ui.graphics.Color

/**
 * 主题令牌：明暗两套 × 6 套强调色。
 *
 * 为什么放在这里而不是 Compose 的 MaterialTheme：
 *  全工程 465 处颜色引用都写成 `C.Acc` / `C.Panel` 这种静态写法。把 `C` 的每个字段
 *  改成读取当前调色板的 getter，就能一次性让全部页面跟随明暗与强调色变化，
 *  不需要把 465 处改写成 CompositionLocal —— 改动面小，回归风险也小。
 *
 * 明色不是把暗色「反相」：底色走暖米白（#F6F3EC）而不是纯白，面板才用纯白，
 * 靠「面板比底更亮」维持和暗色一致的层级关系；强调色在明色下一律压深，
 * 保证在米白底上 ≥4.5:1。
 */

/** 强调色 6 选。dk* 用于暗色（亮一点更跳），lt* 用于明色（压深保证对比度） */
enum class Accent(
    val label: String,
    val dkAcc: Long, val dkAcc2: Long, val dkDeep: Long,
    val ltAcc: Long, val ltAcc2: Long, val ltDeep: Long,
) {
    Orange("琥珀橙", 0xFFFF8E52, 0xFFFFC49A, 0xFFE9662B, 0xFFC2551F, 0xFF8A3A12, 0xFFA9481A),
    Gold("暖金", 0xFFFFC24D, 0xFFFFDDA0, 0xFFE09A1F, 0xFF9A680F, 0xFF6E4A07, 0xFF855A0C),
    Cyan("青碧", 0xFF4FD1C5, 0xFF9FE8E1, 0xFF2AA79C, 0xFF0E7A72, 0xFF08564F, 0xFF0B6A63),
    Blue("湖蓝", 0xFF6FB4FF, 0xFFA9D2FF, 0xFF3D86D6, 0xFF1D63B8, 0xFF12488A, 0xFF18529C),
    Purple("暮紫", 0xFFB08BFF, 0xFFD3BEFF, 0xFF8358E0, 0xFF6640B2, 0xFF472C7E, 0xFF56379A),
    Brick("砖红", 0xFFF0736B, 0xFFFFB3AC, 0xFFC94F47, 0xFFA83A33, 0xFF7A2721, 0xFF8E3029);

    companion object {
        fun byIndex(i: Int): Accent = entries[((i % entries.size) + entries.size) % entries.size]

        /** 设置页「强调色」下拉用的名字列表 */
        val labels: List<String> get() = entries.map { it.label }
    }
}

/** 调色板：字段与 Dims.kt 里 `object C` 的公开名一一对应 */
data class AppColors(
    // 品牌 / 强调
    val acc: Color,
    val acc2: Color,
    val accDeep: Color,
    val accSoft: Color,
    val hi: Color,
    // 文字
    val text: Color,
    val textSoft: Color,
    val dim: Color,
    val dim2: Color,
    val dim3: Color,
    // 底 / 面板
    val bg: Color,
    val bg2: Color,
    val panel: Color,
    val panel2: Color,
    val panelHi: Color,
    // 描边
    val line: Color,
    val line2: Color,
    val line3: Color,
    val warmLine: Color,
    // 焦点
    val focusRing: Color,
    val focusGlow: Color,
    /** 焦点「反白实底」：暗色=近白，明色=近墨 */
    val focusSolid: Color,
    /** 反白实底上的字 */
    val focusInk: Color,
    // 主按钮
    val btnFrom: Color,
    val btnTo: Color,
    val btnInk: Color,
    // ---- 选中态专用 ----
    // 之所以单独出一套，是因为「强调色当文字」「强调色当实底」「浅底上的浅字」这三种
    // 组合在明暗 × 6 强调色下不能共用一个令牌：同一个 acc 在暗色底上是亮块，
    // 在明色底上又太浅。这里按用途拆开，每个令牌都已经在 12 套主题下验过对比度。
    /** 强调色系**文字**（放在 Bg / Panel / AccSoft / Hi 上都看得清） */
    val accText: Color,
    /** 强调色**实底**（明色压深、暗色提亮），用于开关轨、勾选框、选中胶囊 */
    val accSolid: Color,
    /** 强调色实底上的字（明色=白、暗色=近墨） */
    val accSolidInk: Color,
    /** 未选中卡片的底：明色叠淡黑、暗色叠淡白，两种模式下都能和选中态区分开 */
    val softCard: Color,
    /** 输入框 / 列表区这类「玻璃底」 */
    val glassPanel: Color,

    // 语义色
    val ok: Color,
    val okBg: Color,
    val okText: Color,
    val okSoft: Color,
    val blue: Color,
    val blueText: Color,
    val blueSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val warnText: Color,
    val warnSolid: Color,
    val warnSolidInk: Color,
    val danger: Color,
    val dangerText: Color,
    val dangerSoft: Color,

    // 开关 / 勾选
    val switchOn: Color,
    val switchOnInk: Color,
    val switchOff: Color,
    val knobOff: Color,
    /** 危险色实底上的字（暗色=深墨、明色=白） */
    val dangerInk: Color,
    val seg: Color,
    /** 书架页顶部氛围光 */
    val ambient: Color,
    // 弹层
    val scrim: Color,
    val sheetFrom: Color,
    val sheetTo: Color,
    // 低透明白/黑遮罩（暗色=白、明色=黑）
    val veil: Color,
    val veil2: Color,
    // 协议主色
    /** 本地存储图标块（原先是页面里写死的深灰蓝，明色下白字会糊） */
    val iconLocal: Color,
    val iconLocal2: Color,
    val smb: Color,
    val smbDark: Color,
    val dav: Color,
    val davDark: Color,
    val ftp: Color,
    val ftpDark: Color,
    val nfs: Color,
    val nfsDark: Color,
    val black: Color,
)

/** 按「强调色序号 + 是否暗色」生成整套令牌 */
fun palette(accentIndex: Int, dark: Boolean): AppColors {
    val a = Accent.byIndex(accentIndex)
    return if (dark) {
        val acc = Color(a.dkAcc)
        val acc2 = Color(a.dkAcc2)
        val deep = Color(a.dkDeep)
        AppColors(
            acc = acc, acc2 = acc2, accDeep = deep,
            accSoft = acc.copy(alpha = 0.12f), hi = acc.copy(alpha = 0.12f),
            text = Color(0xFFF6F3EF), textSoft = Color(0xFFD8D1C9),
            // dim2 从 #746D66 提到 #948D85：原值压在面板上只有 3.5:1，副标题很糊
            dim = Color(0xFFA09890), dim2 = Color(0xFF9C958D), dim3 = Color(0xFF4E4944),
            bg = Color(0xFF0A0909), bg2 = Color(0xFF0F0E10),
            panel = Color(0xFF16151A), panel2 = Color(0xFF1D1C22), panelHi = Color(0xFF27252D),
            line = Color(0x12FFFFFF), line2 = Color(0x22FFFFFF), line3 = Color(0x3AFFFFFF),
            warmLine = Color(0x1AFFB07A),
            // 暗色焦点：白硬环 + 品牌内光晕
            focusRing = Color(0xF2FFFFFF), focusGlow = acc.copy(alpha = 0.34f),
            focusSolid = Color(0xFFF5F2EE), focusInk = Color(0xFF1B1512),
            btnFrom = Color(0xFFFDFBF8), btnTo = Color(0xFFE9E3DB), btnInk = Color(0xFF2A1405),
            ok = Color(0xFF57D68D), okBg = Color(0xE657D68D), blue = Color(0xFF6FB4FF),
            okText = Color(0xFF8FE8B8), okSoft = Color(0xFF57D68D).copy(alpha = 0.15f),
            blueText = Color(0xFFA9D2FF), blueSoft = Color(0xFF6FB4FF).copy(alpha = 0.16f),
            warn = Color(0xFFFFB05A), warnSoft = Color(0xFFFFCB86),
            warnText = Color(0xFFFFCB86), warnSolid = Color(0xFFFFB05A), warnSolidInk = Color(0xFF241005),
            danger = Color(0xFFF0736B), dangerText = Color(0xFFFFA79E),
            dangerSoft = Color(0xFFF0736B).copy(alpha = 0.15f),
            dangerInk = Color(0xFF2A0D0A),
            seg = Color(0xFF54D6A0), ambient = Color(0x1FFFB07A),
            scrim = Color(0x9E06070A), sheetFrom = Color(0xFF211F26), sheetTo = Color(0xFF121116),
            veil = Color(0x14FFFFFF), veil2 = Color(0x24FFFFFF),
            // 图标块用固定深色渐变 + 白字：亮橙/亮绿配白字只有 2.4:1，字形会糊在一起
            iconLocal = Color(0xFF67737F), iconLocal2 = Color(0xFF262D34),
            smb = Color(0xFF357AB4), smbDark = Color(0xFF1B4E72),
            dav = Color(0xFF7E52C8), davDark = Color(0xFF402472),
            ftp = Color(0xFFB77F26), ftpDark = Color(0xFF63400F),
            nfs = Color(0xFF378A66), nfsDark = Color(0xFF194530),
            black = Color(0xFF000000),
            accText = acc2, accSolid = acc, accSolidInk = Color(0xFF1B1512),
            softCard = Color(0x0DFFFFFF), glassPanel = Color(0x08FFFFFF),
            switchOn = acc, switchOnInk = Color(0xFF1B1512),
            switchOff = Color(0xFF565049), knobOff = Color(0xFFF6F3EF),
        )
    } else {
        val acc = Color(a.ltAcc)
        val acc2 = Color(a.ltAcc2)
        val deep = Color(a.ltDeep)
        AppColors(
            acc = acc, acc2 = acc2, accDeep = deep,
            accSoft = acc.copy(alpha = 0.11f), hi = acc.copy(alpha = 0.12f),
            text = Color(0xFF1B1A18), textSoft = Color(0xFF3A3733),
            // dim2 从 #7C766C 压到 #655F59：原值在纯白面板上只有 4.2:1，读起来发虚
            dim = Color(0xFF5C574F), dim2 = Color(0xFF655F59), dim3 = Color(0xFFA8A29A),
            bg = Color(0xFFF6F3EC), bg2 = Color(0xFFEFEBE2),
            panel = Color(0xFFFFFFFF), panel2 = Color(0xFFF5F1E9), panelHi = Color(0xFFE8E1D5),
            line = Color(0x14000000), line2 = Color(0x1F000000), line3 = Color(0x33000000),
            warmLine = Color(0x33A85A2A),
            // 明色焦点：白环在米白底上等于消失，换成墨环
            focusRing = Color(0xF226221F), focusGlow = acc.copy(alpha = 0.30f),
            focusSolid = Color(0xFF26221F), focusInk = Color(0xFFFFFDF8),
            // 主按钮底色不能再取强调色：明色的 acc 是亮橙，白字压上去只有 1.9:1。
            // 改成「近墨渐变 + 近白字」，和 FocusSolid 同一套语言，明暗都稳在 12:1 以上。
            btnFrom = Color(0xFF26221F), btnTo = Color(0xFF3A342E), btnInk = Color(0xFFFFFDF8),
            ok = Color(0xFF17844F), okBg = Color(0xE64FC98A), blue = Color(0xFF1F6FC0),
            okText = Color(0xFF0E6B3E), okSoft = Color(0xFF17844F).copy(alpha = 0.13f),
            blueText = Color(0xFF12488A), blueSoft = Color(0xFF1D63B8).copy(alpha = 0.14f),
            warn = Color(0xFFB0721F), warnSoft = Color(0xFF8A5A16),
            warnText = Color(0xFF7A4E10), warnSolid = Color(0xFF8A5A16), warnSolidInk = Color(0xFFFFFDF8),
            danger = Color(0xFFC0433B), dangerText = Color(0xFF9E2F28),
            dangerSoft = Color(0xFFC0433B).copy(alpha = 0.12f),
            dangerInk = Color(0xFFFFFDF8),
            seg = Color(0xFF12875A), ambient = acc.copy(alpha = 0.10f),
            scrim = Color(0x66000000), sheetFrom = Color(0xFFFFFFFF), sheetTo = Color(0xFFF7F4ED),
            veil = Color(0x14000000), veil2 = Color(0x24000000),
            // 第二色原来是 #DCEAF6 这类极浅色，配白字等于白上白，统一换成深色端
            iconLocal = Color(0xFF67737F), iconLocal2 = Color(0xFF262D34),
            smb = Color(0xFF357AB4), smbDark = Color(0xFF1B4E72),
            dav = Color(0xFF7E52C8), davDark = Color(0xFF402472),
            ftp = Color(0xFFB77F26), ftpDark = Color(0xFF63400F),
            nfs = Color(0xFF378A66), nfsDark = Color(0xFF194530),
            black = Color(0xFF000000),
            accText = deep, accSolid = deep, accSolidInk = Color(0xFFFFFDF8),
            softCard = Color(0x0A000000), glassPanel = Color(0x08000000),
            switchOn = deep, switchOnInk = Color(0xFFFFFDF8),
            switchOff = Color(0xFF7A736B), knobOff = Color(0xFFFFFFFF),
        )
    }
}

/** 主题模式（持久化成 0/1/2） */
object ThemeMode {
    const val FOLLOW = 0
    const val LIGHT = 1
    const val DARK = 2
    val labels: List<String> = listOf("跟随系统", "明亮", "夜晚")

    fun labelOf(i: Int): String = labels[((i % labels.size) + labels.size) % labels.size]
}
