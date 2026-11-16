package com.kingzcheung.xime.security

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallIntegrityTest {

    private val official = InstallIntegrity.OFFICIAL_CERTS.first()
    private val other = "0".repeat(64)

    @Test
    fun authorKeyIsOfficial() {
        assertEquals(InstallIntegrity.Result.OFFICIAL, InstallIntegrity.classify(listOf(official), listOf(official)))
        assertEquals(InstallIntegrity.Result.OFFICIAL, InstallIntegrity.classify(listOf(official.uppercase()), emptyList()))
    }

    @Test
    fun resignedPackageIsForeign() {
        assertEquals(InstallIntegrity.Result.FOREIGN, InstallIntegrity.classify(listOf(other), listOf(other)))
    }

    @Test
    fun extraSignerNextToAuthorKeyIsForeign() {
        assertEquals(InstallIntegrity.Result.FOREIGN, InstallIntegrity.classify(listOf(official, other), emptyList()))
    }

    @Test
    fun rotatedFromAuthorKeyIsOfficial() {
        assertEquals(InstallIntegrity.Result.OFFICIAL, InstallIntegrity.classify(listOf(other), listOf(official, other)))
    }

    @Test
    fun unreadableSignatureDoesNotWarn() {
        assertEquals(InstallIntegrity.Result.UNKNOWN, InstallIntegrity.classify(emptyList(), emptyList()))
    }

    @Test
    fun dkKeyIsOfficialOnlyInSideBySidePackage() {
        val dk = InstallIntegrity.DK_CERT
        assertEquals(InstallIntegrity.Result.OFFICIAL,
            InstallIntegrity.classify(listOf(dk), listOf(dk), InstallIntegrity.officialCerts(sideBySide = true)))
        assertEquals(InstallIntegrity.Result.OFFICIAL,
            InstallIntegrity.classify(listOf(official), listOf(official), InstallIntegrity.officialCerts(sideBySide = true)))
        // 原版包名的包用 DK 密钥签：仍按被改过提示。
        assertEquals(InstallIntegrity.Result.FOREIGN,
            InstallIntegrity.classify(listOf(dk), listOf(dk), InstallIntegrity.officialCerts(sideBySide = false)))
    }
}
