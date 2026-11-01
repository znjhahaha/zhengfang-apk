package com.tyust.course.academic.plugin

import android.app.Application
import android.net.Uri
import com.tyust.course.academic.AcademicSession
import com.tyust.course.academic.AcademicSessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginUploadNameTest {
    private val interaction=object:NativePluginInteraction {
        override suspend fun confirm(title:String,message:String)=true
        override suspend fun authenticate(challenge:JSONObject,image:File?):JSONObject?=null
        override suspend fun pick(types:Array<String>):Uri?=null
        override suspend fun notificationPermission()=false
        override fun haptic() {}
        override fun navigate(pageId:String,params:JSONObject) {}
        override fun back() {}
    }
    private fun pkg(origin:String)=PluginPackage(PluginManifest(JSONObject().put("id","test.upload-name").put("name","Synthetic upload").put("version","0.1.0")
        .put("apiVersion",3).put("kind","native").put("capabilities",JSONArray()).put("permissions",JSONArray(listOf("network","files")))
        .put("contributes",JSONObject().put("pages",JSONArray()).put("entries",JSONArray())).put("requires",JSONArray().put(JSONObject().put("name","files.upload").put("version",3)))
        .put("network",JSONArray().put(JSONObject().put("origin",origin).put("pathPrefix","/upload").put("methods",JSONArray(listOf("POST"))).put("purposes",JSONArray(listOf("mutation")))))),"","a".repeat(64),false)
    @Test fun policyFilenameChangesOnlyMultipartNameAndRetainsCapturedBytes()=runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try { MockWebServer().use { server ->
            server.start(); val origin=server.url("/").toString().trimEnd('/')
            val app=RuntimeEnvironment.getApplication(); val pkg=pkg(origin)
            val session=AcademicSession(AcademicSessionKey("plugin:test.upload-name","fixture"),origin)
            val host=NativeCapabilityHost(app,pkg,session,interaction){true}
            try {
                val bytes="synthetic captured file bytes".toByteArray()
                val info=host.files.create("capture.jpg","image/jpeg",bytes)
                val input=JSONObject().put("handle",info.getString("handle")).put("filename","policy-object.jpg").put("field","file")
                    .put("request",JSONObject().put("url","$origin/upload").put("method","POST").put("purpose","mutation").put("form",JSONObject().put("key","folder/policy-object.jpg")))
                val effect=JSONObject().put("id","synthetic-upload").put("capability","files.upload").put("version",3).put("input",input)
                server.enqueue(MockResponse().setBody("ok"))
                assertEquals(200,(host.execute(effect,NativeFlow(true)) as JSONObject).getInt("status"))
                val sent=server.takeRequest(1,TimeUnit.SECONDS)!!.body.readUtf8()
                assertTrue(sent.contains("filename=\"policy-object.jpg\""));assertTrue(sent.contains("Content-Type: image/jpeg"));assertTrue(sent.contains(String(bytes)))
                assertEquals("capture.jpg",host.files.info(info.getString("handle")).getString("name"))
                for (bad in listOf("../escape.jpg","bad\r\nname.jpg","a/b.jpg","a\\b.jpg","..")) {
                    input.put("filename",bad)
                    assertThrows(PluginException::class.java) { runBlocking { host.execute(effect,NativeFlow(true)) } }
                }
                input.put("filename","policy-object.jpg"); effect.put("version",2)
                assertThrows(PluginException::class.java) { runBlocking { host.execute(effect,NativeFlow(true)) } }
                assertEquals(1,server.requestCount)
            } finally { host.files.clear(); host.close(); session.retire() }
        } } finally { Dispatchers.resetMain() }
    }
}
