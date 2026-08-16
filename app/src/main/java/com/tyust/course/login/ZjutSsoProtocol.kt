package com.tyust.course.login

import org.jsoup.Jsoup
import java.math.BigInteger

/**
 * 浙江工业大学统一身份认证平台(oauth.zjut.edu.cn, 正方定制 CAS)协议解析与密码加密。
 *
 * 登录页表单 fm1 提交字段: username / password(加密后) / execution / _eventId=submit
 * 验证码开关由 v2/getKaptchaStatus 下发, 图片地址为登录页同目录下的 kaptcha?time=<ts>。
 */
internal object ZjutSsoProtocol {
    class ProtocolException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class LoginPage(
        val execution: String,
        val formAction: String?
    )

    data class PublicKey(
        val modulusHex: String,
        val exponentHex: String
    )

    fun parseLoginPage(html: String): LoginPage {
        val document = Jsoup.parse(html)
        val execution = document.select("form input[name=execution]")
            .firstOrNull()
            ?.attr("value")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw ProtocolException("ZJUT login page is missing execution")
        val formAction = document.select("form#fm1")
            .firstOrNull()
            ?.attr("action")
            ?.trim()
            ?.takeIf(String::isNotBlank)
        return LoginPage(execution = execution, formAction = formAction)
    }

    fun parsePublicKey(json: String): PublicKey {
        return try {
            val obj = org.json.JSONObject(json)
            PublicKey(
                modulusHex = obj.getString("modulus").trim(),
                exponentHex = obj.getString("exponent").trim()
            )
        } catch (error: Exception) {
            throw ProtocolException("ZJUT public key response is invalid", error)
        }
    }

    fun parseKaptchaStatus(body: String): Boolean = body.trim().equals("true", ignoreCase = true)

    /**
     * 按登录页 security.js (David Shapiro RSA) 的规格加密密码:
     * 反转 UTF-16 码元序列 → 按 chunkSize 分块 → 块内每两个码元组成小端 16 位数字、
     * 数字按 2^16 进位组成大整数 → m^e mod n (无填充) → 每块输出 16 进制, 块间以空格连接。
     *
     * chunkSize = 2 × (模数的 16 位数字个数 - 1), 与密钥长度联动, 必须动态计算。
     */
    fun encryptPassword(password: String, modulusHex: String, exponentHex: String): String {
        val modulus = bigIntFromHex(modulusHex) ?: throw ProtocolException("ZJUT modulus is invalid")
        val exponent = bigIntFromHex(exponentHex) ?: throw ProtocolException("ZJUT exponent is invalid")
        if (modulus.signum() <= 0 || exponent.signum() <= 0) {
            throw ProtocolException("ZJUT public key is not positive")
        }
        val digitCount = (modulus.bitLength() + 15) / 16
        val chunkSize = 2 * (digitCount - 1)
        if (chunkSize <= 0) {
            throw ProtocolException("ZJUT modulus is too small")
        }

        val units = password.reversed().map { it.code }.toMutableList()
        while (units.size % chunkSize != 0) units.add(0)

        val blocks = mutableListOf<String>()
        val digitsPerBlock = chunkSize / 2
        for (start in units.indices step chunkSize) {
            var value = BigInteger.ZERO
            for (j in (digitsPerBlock - 1) downTo 0) {
                val digit = units[start + 2 * j] + (units[start + 2 * j + 1] shl 8)
                value = value.shiftLeft(16).add(BigInteger.valueOf(digit.toLong()))
            }
            blocks.add(value.modPow(exponent, modulus).toString(RADIX_HEX))
        }
        return blocks.joinToString(" ")
    }

    private fun bigIntFromHex(hex: String): BigInteger? =
        hex.takeIf { it.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } }
            ?.let { BigInteger(it, RADIX_HEX) }

    private const val RADIX_HEX = 16
}
