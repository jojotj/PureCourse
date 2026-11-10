package com.chunting.purecourse.data

/**
 * 发布包（flavor = `clean`）的预置数据：**故意全空**。
 *
 * 对外分发的安装包不携带任何本机痕迹：没有预置数据源、没有快捷共享位置、
 * 没有本机 IP / 账号 / 共享名。首次启动就是干净空状态，全部由用户自己填。
 * （SMB 端口等表单默认值仍在 SourceStore.Cfg 里保持 445 等常规值，不属于本机数据。）
 */
object BuiltinPresets {

    const val HOST = ""
    const val SHARE = ""
    const val USER = ""
    const val PASS = ""
    const val ROOT = ""

    val shortcuts: List<RealCatalog.Shortcut> = emptyList()
}
