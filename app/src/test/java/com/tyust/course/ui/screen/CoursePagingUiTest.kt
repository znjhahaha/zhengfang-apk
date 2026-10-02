package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tyust.course.academic.*
import com.tyust.course.model.Course
import com.tyust.course.ui.system.GlassOverlayHost
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class)
class CoursePagingUiTest {
    @get:Rule val compose=createComposeRule()
    private fun row(id:String="a")=AcademicCourseBridge.toCourse(CourseOffer(id,"模拟课程$id",scopeId="r",sectionCount=35)).apply {
        completeParams["academic_system"]="zf"
    }
    private fun show(rows:List<Course>,state:CourseBrowserState,onMore:()->Unit={},onExpand:(Set<String>)->Unit={},fontScale:Float=1f) {
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,fontScale)) {
            MaterialTheme { GlassOverlayHost { Box(Modifier.width(360.dp)) {
            CourseListScreen(courses=rows,isLoading=false,onRefresh={},onSearch={},onCourseSelect={},onAutoGrab={},
                browserState=state,onLoadMore=onMore,onExpandAll=onExpand,isDetailsReady=true)
        } } } } }
    }
    @Test fun largeFontEmptyListRetainsReachablePagingButton() {
        var clicks=0
        show(emptyList(),CourseBrowserState(hasMore=true),onMore={clicks++},fontScale=1.8f)
        compose.onNodeWithText("加载更多").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,clicks) }
    }
    @Test fun filteredEmptyListKeepsLoadMoreAction() {
        var clicks=0;show(emptyList(),CourseBrowserState(courses=listOf(row()),hasMore=true),onMore={clicks++})
        compose.onNodeWithText("加载更多").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1,clicks) }
        compose.onNodeWithText("已加载课程中暂无匹配项").assertExists()
    }
    @Test fun declaredClassCountIsDisplayedWithoutInventingOnePlaceholderClass() {
        val row=row();show(listOf(row),CourseBrowserState(courses=listOf(row)))
        compose.onNodeWithText("35 个教学班",substring=true).assertExists()
        compose.onNodeWithText("名额待加载").assertExists()
        compose.onNodeWithText("1 个教学班",substring=true).assertDoesNotExist()
    }
    @Test fun zeroSeatsAreNotUnknownAndUnknownSectionDoesNotInheritCourseCapacity() {
        val offer=CourseOffer("a","模拟",scopeId="r",capacity=100,selected=0)
        val unknown=AcademicCourseBridge.toCourse(offer,CourseSection("s","a"))
        assertEquals("false",unknown.completeParams["academic_capacity_known"])
        val known=AcademicCourseBridge.toCourse(offer,CourseSection("s","a",capacity=0,selected=0)).apply { completeParams["academic_system"]="zf" }
        show(listOf(known),CourseBrowserState(courses=listOf(known),details=mapOf(known.catalogGroupKey() to CourseDetails("ready",1))))
        compose.onNodeWithText("余量合计 0（查询时）").assertExists()
    }
    @Test fun expandAllTargetsOnlyVisibleFilteredCourseSet() {
        val a=row("a");val b=row("b");var targets=emptySet<String>()
        show(listOf(b),CourseBrowserState(courses=listOf(a,b),hasMore=true),onExpand={targets=it})
        compose.onNodeWithText("展开全部").performClick()
        compose.runOnIdle { assertEquals(setOf(b.catalogGroupKey()),targets) }
    }
}
