package cn.qaiu.nfd.android.ui

import cn.qaiu.nfd.android.daemon.Endpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebLoginTest {
    @Test
    fun allowsProviderRedirectsButReadsOnlyTheDriveOrigin() {
        assertTrue(WebLogin.QUARK.allowsNavigation("https://account.uc.cn/"))
        assertFalse(WebLogin.QUARK.canRead("https://account.uc.cn/"))
        assertTrue(WebLogin.QUARK.canRead("https://pan.quark.cn:443/"))
        for (url in listOf(
            "http://pan.quark.cn/",
            "https://pan.quark.cn:8443/",
            "https://pan.quark.cn.example.org/",
            "https://example.org@pan.quark.cn/",
        )) {
            assertFalse(WebLogin.QUARK.allowsNavigation(url))
            assertFalse(WebLogin.QUARK.canRead(url))
        }
        assertTrue(WebLogin.PAN123.canRead("https://yun.123pan.cn/"))
        assertFalse(WebLogin.PAN123.canRead("https://user.123pan.cn/"))
    }

    @Test
    fun checksCookieNamesAnd123TokenShape() {
        assertTrue(WebLogin.UC.hasSession("__pus=one; __puus=two; foo=x"))
        assertFalse(WebLogin.UC.hasSession("not__pus=one; __puus=two"))
        assertFalse(WebLogin.UC.hasSession("__pus=one; __puus="))
        assertEquals("one.two.three", pan123Token("Bearer one.two.three"))
        assertNull(pan123Token("undefined"))
    }

    @Test
    fun importsCredentialsOnlyIntoLoopbackDaemon() {
        assertEquals("127.0.0.1", Endpoint("localhost", 17891, "test").localLoginEndpoint()?.host)
        assertEquals("::1", Endpoint("::1", 17891, "test").localLoginEndpoint()?.host)
        assertNull(Endpoint("192.168.1.2", 17891, "test").localLoginEndpoint())
    }
}
