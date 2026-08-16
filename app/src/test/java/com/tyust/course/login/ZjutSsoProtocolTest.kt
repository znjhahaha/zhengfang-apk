package com.tyust.course.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ZjutSsoProtocolTest {
    private val modulus =
        "b1d2af160ebaa47adfef6e4f98a6f1045bd6dc5bfba4bf427c9a653307424d955bf9bbe79a6f5444b7bb04dc8fd4d4c36063d67fbab5c6732b6a2ed6bbf6f45d"
    private val exponent = "10001"

    @Test
    fun parseLoginPage_readsExecutionAndFormAction() {
        val html = """
            <html><body>
              <form id="fm1" method="post" action="/cas/login">
                <input id="username" name="username" type="text" value="" />
                <input id="password" name="password" type="password" value="" />
                <input type="hidden" name="execution" value="e-1755162949_ZXlKaGJHY2lPaUp" />
                <input type="hidden" name="_eventId" value="submit" />
              </form>
            </body></html>
        """.trimIndent()

        val page = ZjutSsoProtocol.parseLoginPage(html)

        assertEquals("e-1755162949_ZXlKaGJHY2lPaUp", page.execution)
        assertEquals("/cas/login", page.formAction)
    }

    @Test
    fun parseLoginPage_throwsWhenExecutionMissing() {
        assertThrows(ZjutSsoProtocol.ProtocolException::class.java) {
            ZjutSsoProtocol.parseLoginPage("<form><input name='_eventId' value='submit'/></form>")
        }
    }

    @Test
    fun parsePublicKey_readsModulusAndExponent() {
        val key = ZjutSsoProtocol.parsePublicKey("""{"modulus":"$modulus","exponent":"$exponent"}""")

        assertEquals(modulus, key.modulusHex)
        assertEquals(exponent, key.exponentHex)
    }

    @Test
    fun parsePublicKey_throwsOnInvalidJson() {
        assertThrows(ZjutSsoProtocol.ProtocolException::class.java) {
            ZjutSsoProtocol.parsePublicKey("not-json")
        }
    }

    @Test
    fun parseKaptchaStatus_interpretsBody() {
        assertTrue(ZjutSsoProtocol.parseKaptchaStatus("true"))
        assertTrue(!ZjutSsoProtocol.parseKaptchaStatus("false"))
        assertTrue(ZjutSsoProtocol.parseKaptchaStatus("  true \n"))
        assertTrue(!ZjutSsoProtocol.parseKaptchaStatus(""))
    }

    @Test
    fun encryptPassword_matchesReferenceVectors() {
        // 向量由登录页 security.js 算法规格(反转+块内小端+无填充RSA+hex)独立实现生成
        assertEquals(
            "67c1c3ced32581ae2625105404296e398ce4c021eec4483f55ba63b7f4752c0970b92d0c17c50481346d8d2fc5d89ff906d35091a0792f74e0b0ff5ce977a370",
            ZjutSsoProtocol.encryptPassword("Test1234", modulus, exponent)
        )
        assertEquals(
            "6f4d2eb2551782bb43dddc6355a1f93612fffca2139f9adb4a09ccec2a5cc2f1dc9e80ce249a301eeb7476bfbbe243cf74b9171c146bba106639288d214a7a6a",
            ZjutSsoProtocol.encryptPassword("a", modulus, exponent)
        )
    }

    @Test
    fun encryptPassword_chunksLongInputWithSpaces() {
        // 130 字符超过 chunkSize(62 字节), 应分 3 块并以空格连接
        val result = ZjutSsoProtocol.encryptPassword("A".repeat(130), modulus, exponent)
        val blocks = result.split(" ")

        assertEquals(3, blocks.size)
        assertTrue(blocks.all { block -> block.isNotEmpty() && block.all { it.isDigit() || it in 'a'..'f' } })
    }

    @Test
    fun encryptPassword_reversesBeforeEncrypting() {
        // encrypt("ab") = RSA(小端字节序 "ba"); 小端值 = 大端读取其反转数组 ['a','b']
        val n = java.math.BigInteger(modulus, 16)
        val e = java.math.BigInteger(exponent, 16)
        val leOfReversed = java.math.BigInteger(
            1,
            byteArrayOf('a'.code.toByte(), 'b'.code.toByte())
        )
        val expected = leOfReversed.modPow(e, n).toString(16)

        assertEquals(expected, ZjutSsoProtocol.encryptPassword("ab", modulus, exponent))
    }

    @Test
    fun encryptPassword_throwsOnInvalidKey() {
        assertThrows(ZjutSsoProtocol.ProtocolException::class.java) {
            ZjutSsoProtocol.encryptPassword("Test1234", "not-hex!", exponent)
        }
        assertThrows(ZjutSsoProtocol.ProtocolException::class.java) {
            ZjutSsoProtocol.encryptPassword("Test1234", modulus, "")
        }
    }
}
