package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class QzRedirectLoginTest {
    private val redirect = "window.location.href='http://auth.school.test/authserver/login?service=http%3A%2F%2Fschool.test%2F';"
    @Test fun anOtherwiseEmptyPageRedirectingStraightToCasIsAnExpiredLogin() {
        assertTrue(AcademicHtml.isLoginPage("<script>$redirect</script>"))
    }
    @Test fun aLoginLinkOrAnUncalledFunctionIsNotAnExpiredSession() {
        assertFalse(AcademicHtml.isLoginPage("<a href='http://auth.school.test/authserver/login'>登录</a>"))
        assertFalse(AcademicHtml.isLoginPage("<script>function logout() {$redirect}</script>"))
    }
}
