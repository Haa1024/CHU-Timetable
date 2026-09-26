package com.seu.timetable.data.chu

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 长安大学统一身份认证（金智 CAS）的口令加密。
 *
 * ## 算法：从站点自己的 encrypt.js 抄出来的，不是猜的
 *
 * ```js
 * var $aes_chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
 * function getAesString(n, f, c) {          // n=明文 f=密钥 c=IV
 *   f = f.replace(/(^\s+)|(\s+$)/g, "");     // ← 密钥要先去掉首尾空白
 *   return CryptoJS.AES.encrypt(n, CryptoJS.enc.Utf8.parse(f), {
 *     iv: CryptoJS.enc.Utf8.parse(c), mode: CBC, padding: Pkcs7 }).toString();
 * }
 * function encryptAES(n, f) {
 *   return f ? getAesString(randomString(64) + n, f, randomString(16)) : n;
 * }
 * ```
 *
 * 对应到这里就是：密钥 = 16 字符的 `pwdEncryptSalt`（→ AES-128），
 * IV = 16 个随机字符，明文 = 64 个随机字符 + 口令，输出标准 Base64。
 * 自检基准：13 字符的口令加密后应恰好 **108 字符**（64+13=77 字节，PKCS7 补齐到 80，Base64 得 108）。
 *
 * ## 为什么 IV 不必发给服务端
 *
 * CBC 下错的 IV 只毁掉**第一个**明文块，后面的块照样解得对。那 64 个随机字符就是
 * 专门用来挨这一下的"挡枪块"——服务端随便取一个 IV，口令所在的块仍能正确还原。
 * 所以这不是设计疏漏，是把 IV 的保密性整个省掉了。
 *
 * ## 与 JS 版的两处刻意不同
 *
 * ① **salt 为空要报错，绝不发明文。** JS 的 `encryptPassword` 在异常时 `return n`，
 *    而 `encryptAES` 在 salt 为假值时直接返回明文——也就是说，站点自己会在 salt 缺失时
 *    安安静静地把口令明文发出去。这里改成抛异常：静默降级是最坏的一类 bug，
 *    它让"成功"和"失败"在日志上长得一模一样。
 * ② 随机数用 [SecureRandom] 而非 `Math.random()`。字符集与长度保持一致，
 *    只为不让**我们这一侧**成为整条链上最弱的一环。
 */
internal object ChuPasswordCrypto {

    /** 口令前缀（挡枪块）长度。JS 里硬编码 64，改动即与站点不兼容。 */
    const val PREFIX_LEN = 64

    /** IV 长度（字节）。CBC 要求等于分组大小 16。 */
    const val IV_LEN = 16

    /** CHU 的 `pwdEncryptSalt` 实测恒为 16 字符 → AES-128。 */
    const val SALT_LEN = 16

    /**
     * 随机字符集，与站点 `$aes_chars` 逐字符一致（48 个）。
     *
     * 注意它刻意**排除了容易看错的字符**（大写 I L O U V、小写 g l o q u v、数字 0 1 9）。
     * 保持一致没有任何技术必要性——这些字符进不了服务端语义——纯粹是为了与站点同形：
     * 万一将来要拿站点 JS 做对照实验，两边产出可以直接比。
     */
    private const val AES_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    private val secureRandom = SecureRandom()

    /**
     * 把口令加密成可直接放进 `password` 字段的密文（标准 Base64，带 `=` 填充）。
     *
     * @param password 明文口令。**不做 trim**——口令里的空格是口令的一部分。
     * @param salt 登录页隐藏字段 `pwdEncryptSalt` 的值，必须恰好 [SALT_LEN] 字节。
     *   它**必须与提交时同一个 JSESSIONID 配对**：拿 A 会话的 salt 到 B 会话去 POST，
     *   服务端解不出来却不报错，而是优雅返回 200 + 登录页（技能 §1.3），
     *   现象与"登录失败"完全无法区分。
     * @param prefix 前缀随机串。**只为让测试可注入**，生产走默认值。
     * @param iv IV 随机串。**只为让测试可注入**，生产走默认值。
     * @throws IllegalArgumentException salt 或 IV 长度不对（含空）
     */
    fun encrypt(
        password: String,
        salt: String,
        prefix: String = randomString(PREFIX_LEN),
        iv: String = randomString(IV_LEN),
    ): String {
        // 密钥同样要去首尾空白，与 JS 的 `f.replace(/(^\s+)|(\s+$)/g,"")` 对齐
        val keyBytes = salt.trim().toByteArray(Charsets.UTF_8)
        require(keyBytes.size == SALT_LEN) {
            if (keyBytes.isEmpty()) {
                "登录页里没取到 pwdEncryptSalt —— 绝不能退回明文提交"
            } else {
                "pwdEncryptSalt 应为 $SALT_LEN 字节，实际 ${keyBytes.size} 字节"
            }
        }
        val ivBytes = iv.toByteArray(Charsets.UTF_8)
        require(ivBytes.size == IV_LEN) { "IV 应为 $IV_LEN 字节，实际 ${ivBytes.size} 字节" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            IvParameterSpec(ivBytes),
        )
        val plain = (prefix + password).toByteArray(Charsets.UTF_8)

        // 用 java.util.Base64 而不是 android.util.Base64：前者是纯 JVM 实现，
        // 单测跑在桌面 JVM 上才能真的把加解密执行一遍，而不是撞上 "not mocked"。
        // minSdk 26 起它在 Android 上同样可用。
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }

    /**
     * 生成 [n] 个随机字符。
     *
     * 用 `nextInt(bound)` 而不是 `nextDouble() * bound`：后者在 bound 较小时
     * 有可观测的取模偏差，而且 `Math.random()` 那条路本来就是这里想摆脱的东西。
     */
    fun randomString(n: Int): String = buildString(n) {
        repeat(n) { append(AES_CHARS[secureRandom.nextInt(AES_CHARS.length)]) }
    }

    /**
     * 32 位大写十六进制，形如站点自己算出来的设备指纹。
     *
     * 服务端**只存不验内容**（技能 §3.2），所以"是什么"无所谓；
     * 要紧的是调一次 `/bfp/info` 让它把 `MULTIFACTOR_BROWSER_FINGERPRINT` 这个
     * HttpOnly cookie 种下来——少了它 POST 会被判成设备指纹缺失。
     * 用大写十六进制是为了与站点 `createHash()` 的产出（`md5(...).toUpperCase()`）同形。
     */
    fun randomFingerprint(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
