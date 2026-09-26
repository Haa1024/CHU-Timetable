package com.seu.timetable.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 对外的链接。
 *
 * 收在一处而不是散在各界面：这几个地址同时出现在「首次公告」与「我的」页，
 * 各写一份迟早会漏改一处——而链接写错的后果是用户点开一个 404，
 * 那比没有这个入口更糟。
 */
object AppLinks {

    /** 本应用仓库。公告里"去点个 star"指向它 */
    const val REPO = "https://github.com/Haa1024/CHU-Timetable"

    /** 作者主页。「我的」页那条入口指向它 */
    const val AUTHOR = "https://github.com/Haa1024"

    /**
     * 同一作者的另一个作品：东南大学课表。
     *
     * 本项目与它是**同一作者的两个版本**（共用课表骨架，教务对接各写各的），
     * 不是"改编自某开源项目"——公告里按这个口径说。
     */
    const val ORIGIN = "https://github.com/Haa1024/SEU-Timetable"
}

/**
 * 交给系统浏览器打开。
 *
 * 不预先判断"设备上有没有浏览器"：那样要查 PackageManager，而这属于极少数情况。
 * 直接 try 更省事——真没有浏览器时 [Context.startActivity] 抛
 * `ActivityNotFoundException`，包住即可，不该因为一次点击让 App 崩。
 *
 * 不用 CustomTabs：那要多一个依赖，而这里只是偶尔点一次，系统浏览器足够。
 */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { DebugLog.w("打不开链接 $url：${it.message}") }
}
