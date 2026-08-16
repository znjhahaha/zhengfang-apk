package com.tyust.course.login

import com.tyust.course.model.SchoolConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordLoginGatewayFactoryTest {
    @Test
    fun createsTyustSsoGatewayOnlyForTyust() {
        val tyust = SchoolConfig("tyust", "TYUST", "newjwc.tyust.edu.cn", "https")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(tyust) is TyustSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is PasswordLoginManager)
    }

    @Test
    fun createsZjutSsoGatewayOnlyForZjut() {
        val zjut = SchoolConfig("zjut", "浙江工业大学", "www.gdjw.zjut.edu.cn", "http")
        val other = SchoolConfig("other", "Other", "jw.example.edu.cn", "https")

        assertTrue(PasswordLoginGatewayFactory.create(zjut) is ZjutSsoLoginManager)
        assertTrue(PasswordLoginGatewayFactory.create(other) is PasswordLoginManager)
    }
}
