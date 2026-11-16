package com.kingzcheung.xime.security

import android.content.Context
import android.content.pm.PackageManager
import com.kingzcheung.xime.BuildConfig
import java.security.MessageDigest

/**
 * 安装包自检（用户 11-02：防止别人拿代码改了再发）。
 *
 * 系统装 APK 时已经核对过每个文件都没被动过，改了内容就必须重新签名；别人没有作者的签名密钥，
 * 只能换成自己的。所以这里只看装着的这个包是谁签的：不是作者那把就提示。
 *
 * 防得住：拆包改代码、加广告或偷字后重新签名再发的包，以及别人用源码自己编了换签名的包。
 * 防不住：改包的人同时把这段检查删掉；系统本身被改（root、改过的系统）。所以还要靠只从官方下载页下载。
 *
 * 代价：进程启动后在后台读一次签名、算一次 SHA-256（约 1 KB 证书），不到 5 ms，结果缓存，打字时不经过。
 * 没有开关，不进 perf_costs.json 那张按开关登记的表。
 */
object InstallIntegrity {

    /**
     * 作者发布用的签名证书 SHA-256。公开版、好友包、老人包、CI 构建都是这一把（2026-09-22 生成，永不更换，
     * 换了老用户就无法覆盖升级）。以后 F-Droid 等商店若用自己的密钥重签，把它的证书加到这里。
     */
    internal val OFFICIAL_CERTS: Set<String> = setOf(
        "e0d419cbfc7715e0be5bd98e7e5a26ab7d147a67b15d0961797197e6c76c05e2",
    )

    /**
     * 丹麦语并装包（包名 com.cyletix.cyime.dk）的发布签名，只在 [BuildConfig.SIDE_BY_SIDE] 的包里认：
     * 原版包仍只认作者那把，DK 密钥签的包冒充不了原版 CyIME。
     */
    internal const val DK_CERT = "051b0750162e8fc64687606bddf185c879674ef7ca224eb5ee8cf934bf7bb594"

    internal fun officialCerts(sideBySide: Boolean): Set<String> = if (sideBySide) OFFICIAL_CERTS + DK_CERT else OFFICIAL_CERTS

    val DOWNLOAD_URL: String = if (BuildConfig.SIDE_BY_SIDE) "https://github.com/tml10085/CyIME-DK/releases"
        else "https://github.com/Cyletix/CyIME/releases"

    enum class Result {
        /** 作者签名。 */
        OFFICIAL,
        /** 别的签名：提示用户。 */
        FOREIGN,
        /** 个别系统读不出签名：不提示，宁可漏报也不误报。 */
        UNKNOWN,
        /** debug 包不检查：贡献者自己编的调试包本来就是自己的签名。 */
        SKIPPED,
    }

    @Volatile
    private var cached: Result? = null

    /** 第一次调用要读包信息，放后台线程；之后直接返回缓存。 */
    fun check(context: Context): Result {
        cached?.let { return it }
        val result = if (BuildConfig.DEBUG) Result.SKIPPED else readSigners(context).let { signers ->
            if (signers == null) Result.UNKNOWN else classify(signers.first, signers.second)
        }
        cached = result
        return result
    }

    /**
     * [current] 是现在给包签名的证书；[history] 是单签名时系统记下的轮换历史（含现在这把）。
     * 多签名时每一把都要是作者的；单签名时现在这把或它轮换前的任一把是作者的就算官方。
     */
    internal fun classify(current: List<String>, history: List<String>,
        official: Set<String> = officialCerts(BuildConfig.SIDE_BY_SIDE)): Result {
        if (current.isEmpty()) return Result.UNKNOWN
        if (current.all { it.lowercase() in official }) return Result.OFFICIAL
        if (current.size == 1 && history.any { it.lowercase() in official }) return Result.OFFICIAL
        return Result.FOREIGN
    }

    private fun readSigners(context: Context): Pair<List<String>, List<String>>? = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return null
        val current = signing.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }
        val history = if (signing.hasMultipleSigners()) emptyList()
        else signing.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }
        current to history
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
