package com.seu.timetable.data

import com.seu.timetable.data.chu.ChuAuthClient
import com.seu.timetable.data.chu.ChuClient

/**
 * 登录态管理。
 *
 * ## 会话存在哪
 *
 * **完全由 WebView 的 `CookieManager` 持有**，本类不自行实现 cookie 存储。
 * 理由：教务那边的会话本来就会服务端超时，本地存得再稳也挽不回；
 * 而 Android 的 `CookieManager` 本身就会落盘（配合 `flush()`），
 * 再叠一层只是多一份可能不一致的真相。
 *
 * 因此策略是「用到才发现过期 → 引导重登」，而不是「提前缓存」。
 *
 * ## 关于账号密码
 *
 * 本类**不碰**它们。是否保存、存在哪，一律由 [CredentialStore] 负责
 * （密码经 Android Keystore 的 AES-256-GCM 加密后落盘，密钥不出硬件）。
 */
object SessionManager {

    /**
     * 只用它来清骨架页缓存——判断登录态走的是真实接口调用，见 [isLoggedIn]。
     *
     * 这里再 new 一个 [ChuClient] 是安全的：骨架页缓存是**进程级共享**的
     * （见 `ChuClient.cachedSkeleton`），所以不存在"两份缓存互相矛盾"的问题。
     */
    private val client = ChuClient()

    /**
     * 通过一次真实接口调用来判断登录态。
     *
     * 不依赖"猜某个 cookie 在不在"——cookie 的名字和有效期都会变，
     * 而服务端会话过期后 cookie 未必同步消失。
     */
    suspend fun isLoggedIn(): Boolean = client.hasSession()

    /**
     * 退出登录：清掉两个域下的会话 cookie，并把缓存一起作废。
     *
     * 缓存**必须**一起清：骨架页那份缓存里含 `ids`，留着它下一次 `isLoggedIn()` 之外的
     * 路径仍会认为登录着，表现为「退出登录了却还能拉到课表」。
     */
    fun signOut() {
        ChuAuthClient().signOut()
        client.clearSessionCache()
    }
}
