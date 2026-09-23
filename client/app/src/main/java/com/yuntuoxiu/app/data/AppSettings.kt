package com.yuntuoxiu.app.data

import android.content.Context

/**
 * AppSettings —— 应用设置（v2.2，SharedPreferences 持久化）
 *
 * 目前包含：
 *   · deepUnpack   深度脱壳开关（dump ART 已加载 CodeItem → 真实原 dex）
 *   · useRegex     一键流水线是否启用正则步骤
 *   · useHookDump  是否启用 native Hook dump（A16 上 Dobby 会崩，默认关）
 */
object AppSettings {

    private const val PREF = "ytx_settings"

    private const val KEY_DEEP_UNPACK = "deep_unpack"
    private const val KEY_USE_REGEX = "use_regex"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 深度脱壳（真实原 dex）：默认 false */
    fun isDeepUnpack(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_DEEP_UNPACK, false)

    fun setDeepUnpack(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_DEEP_UNPACK, v).apply()
    }

    /** 正则步骤（规则修补 / 去签名校验）：默认 true */
    fun isUseRegex(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_USE_REGEX, true)

    fun setUseRegex(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean(KEY_USE_REGEX, v).apply()
    }
}