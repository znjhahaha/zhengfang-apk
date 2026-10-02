package com.tyust.course.manager

import android.app.Application
import android.content.Context
import com.tyust.course.model.SchoolConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], application=Application::class)
class StudentBindingReleaseTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("student_limit_prefs",Context.MODE_PRIVATE)
    private val user get() = UserManager.getInstance()
    private val school = SchoolConfig("quota-fixture","模拟学校","school.example.test","https")
    @Before fun setup() {
        prefs.edit().clear().commit(); user.init(app); user.clearLoginState()
        user.addCustomSchool(school); user.currentSchool = school
    }
    private fun bind(id:String) = StudentLimitManager.recordStudent(app,school.id,school.name,"模拟$id",id)
    @Test fun releasedPlaceCanBeReusedButFourthBindingIsRejected() {
        assertTrue(bind("a"));assertTrue(bind("b"));assertTrue(bind("c"));assertFalse(bind("d"))
        val record = StudentLimitManager.getBoundStudents(app).first { it.studentId == "b" }
        assertEquals(1,StudentLimitManager.release(app,listOf(record)));assertTrue(bind("d"));assertFalse(bind("b"))
    }
    @Test fun releaseAllDoesNotResurrectLegacyNamesAfterReread() {
        bind("a");bind("b")
        assertEquals(2,StudentLimitManager.release(app,StudentLimitManager.getBoundStudents(app)))
        repeat(3) { assertEquals(0,StudentLimitManager.getUsedCount(app)) }
        prefs.edit().putStringSet("used_student_names",setOf("陈旧记录")).commit()
        assertEquals(0,StudentLimitManager.getUsedCount(app))
    }
    @Test fun currentRealAccountIsProtectedEvenWhenDialogWasOpenedBeforeSwitch() {
        bind("a");bind("b");val snapshot=StudentLimitManager.getBoundStudents(app)
        user.studentId="b";user.studentName="模拟b";user.isLoggedIn=true
        assertEquals(1,StudentLimitManager.release(app,snapshot))
        assertEquals("b",StudentLimitManager.getBoundStudents(app).single().studentId)
    }
    @Test fun releaseDoesNotDeleteSavedAccountCredentialsOrCache() {
        bind("a");user.studentId="a";user.studentName="模拟a";user.saveCookieLogin("SESSION=synthetic")
        val key=user.currentAccountKey;user.clearLoginState()
        val cache=app.getSharedPreferences("synthetic-course-cache",Context.MODE_PRIVATE)
        cache.edit().putString("course","模拟课程").commit()
        val saved=user.savedAccounts.first { it.key==key }
        assertEquals(1,StudentLimitManager.release(app,StudentLimitManager.getBoundStudents(app)))
        assertEquals(saved.cookie,user.savedAccounts.first { it.key==key }.cookie)
        assertEquals("模拟课程",cache.getString("course",null))
    }
    @Test fun switchingSavedReleasedAccountReclaimsAPlaceAndCannotBypassFullQuota() {
        bind("a");user.studentId="a";user.studentName="模拟a";user.saveCookieLogin("SESSION=synthetic-a")
        val key=user.currentAccountKey;user.clearLoginState()
        StudentLimitManager.release(app,StudentLimitManager.getBoundStudents(app))
        assertTrue(user.switchToAccount(key));assertEquals(1,StudentLimitManager.getUsedCount(app))
        user.clearLoginState();StudentLimitManager.release(app,StudentLimitManager.getBoundStudents(app))
        bind("b");bind("c");bind("d")
        user.studentId="b";user.studentName="模拟b";user.saveCookieLogin("SESSION=synthetic-b")
        val before=user.sessionState.token
        assertFalse(user.switchToAccount(key));assertEquals("b",user.studentId);assertEquals(before,user.sessionState.token)
    }
    @Test fun restoreDoesNotReactivateAnExplicitlyLoggedOutSavedAccount() {
        bind("a");user.studentId="a";user.studentName="模拟a";user.saveCookieLogin("SESSION=synthetic")
        user.clearLoginState();user.loadLoginState();assertFalse(user.isLoggedIn)
    }
    @Test fun legacyNamesMigrateOnlyOnceAndCanThenBeReleased() {
        prefs.edit().putStringSet("used_student_names",setOf("旧账号")).commit()
        val records=StudentLimitManager.getBoundStudents(app);assertEquals(1,records.size)
        assertEquals(1,StudentLimitManager.release(app,records));assertTrue(StudentLimitManager.getBoundStudents(app).isEmpty())
    }
    @Test fun concurrentAdmissionsCannotExceedThreePlaces() {
        val workers=(1..12).map { i -> Thread { bind("concurrent$i") } }
        workers.forEach { it.start() };workers.forEach { it.join() }
        assertEquals(3,StudentLimitManager.getUsedCount(app))
    }
}
