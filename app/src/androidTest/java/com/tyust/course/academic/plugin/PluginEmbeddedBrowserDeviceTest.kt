package com.tyust.course.academic.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Uses a synthetic profile and the real, separate Gecko service; never reads plugin accounts. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PluginEmbeddedBrowserDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile() = "plugin_" + PluginJson.sha256(UUID.randomUUID().toString().toByteArray())

    @Test fun restoredExtensionConnectsBeforeOpeningAnyCourseAndKeepsCookies() {
        val profile = profile()
        try {
            repeat(2) { pass ->
                Client(profile).use { client ->
                    client.configure()
                    // Let last-window and restored-extension disconnect callbacks settle.
                    Thread.sleep(1200)
                    val before = client.call("cookies.read", JSONObject().put("url", "https://example.com/"))
                    assertEquals(if (pass == 0) "" else "browser_fixture=retained", before.getString("header"))
                    client.call("cookies.write", JSONObject().put("url", "https://example.com/")
                        .put("cookie", "browser_fixture=retained; Path=/; Secure; Max-Age=3600"))
                    assertEquals("browser_fixture=retained", client.call("cookies.read",
                        JSONObject().put("url", "https://example.com/")).getString("header"))
                }
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    /** Only the public example.com document is downloaded. GM requests use synthetic Binder replies. */
    @Test fun nativeGmRepliesReachTheRealUserscriptAfterAProcessRestart() {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        try {
            repeat(2) { pass ->
                val received = CountDownLatch(1)
                val observed = AtomicReference<JSONObject>()
                val events = ConcurrentLinkedQueue<String>()
                Client(profile) { event ->
                    events.add(event.optString("kind") + ":" + event.optString("key"))
                    when (event.optString("kind")) {
                        "request" -> JSONObject().put("ok", true).put("response", JSONObject()
                            .put("status", 200).put("url", "https://example.com/native-fixture")
                            .put("headers", JSONObject().put("x-fixture", "native"))
                            .put("body", """{"text":"原生回调","items":[null,7,"done"],"empty":null}"""))
                        "storage" -> {
                            if (event.optString("key") == "callback-probe") {
                                observed.set(event.getJSONObject("value"))
                                received.countDown()
                            }
                            JSONObject().put("ok", true)
                        }
                        else -> JSONObject().put("ok", true)
                    }
                }.use { client ->
                    client.configure()
                    val handle = "native-fixture-$pass"
                    val declaration = JSONObject("""{
                        "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                        "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                        "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                    }""")
                    val source = """
                        window.wrappedJSObject.__zfPageFixture = {text:'page-global'};
                        GM_xmlhttpRequest({url:'https://example.com/native-fixture',responseType:'json',
                          onload(r){Promise.resolve().then(()=>GM_setValue('callback-probe',{status:r.status,text:r.response.text,
                            count:r.response.items.length,last:r.response.items[2],empty:r.response.empty===null,
                            pageGlobal:unsafeWindow.__zfPageFixture?.text || '',
                            header:r.responseHeaders.includes('x-fixture: native')}));},
                          onerror(r){GM_setValue('callback-probe',{failed:r.error});}});
                    """.trimIndent()
                    client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                        .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                        .put("values", JSONObject()).put("runAt", "document-end"))
                    client.call("page.open", JSONObject().put("url", "https://example.com/")
                        .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle)
                        .put("active", false))
                    val completed = received.await(25, TimeUnit.SECONDS)
                    assertTrue("The native GM callback did not reach the script on pass $pass; events=$events", completed)
                    val result = observed.get()
                    assertFalse(result.toString(), result.has("failed"))
                    assertEquals(200, result.getInt("status"))
                    assertEquals("原生回调", result.getString("text"))
                    assertEquals("page-global", result.getString("pageGlobal"))
                    assertEquals(3, result.getInt("count"))
                    assertEquals("done", result.getString("last"))
                    assertTrue(result.getBoolean("empty"))
                    assertTrue(result.getBoolean("header"))
                    client.call("script.stop")
                }
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeControlsReachUserscriptDomListenersAfterSettingsAreWritten() {
        assumeTrue("Opt in to the public test document with -e allowBrowserNetwork true",
            InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val ready = CountDownLatch(1)
        val available = CountDownLatch(1)
        val clicked = CountDownLatch(1)
        val setup = AtomicReference<JSONObject>()
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status" && event.optString("stage") == "ready") available.countDown()
                if (event.optString("kind") == "storage") when (event.optString("key")) {
                    "dom-ready" -> { setup.set(event.getJSONObject("value")); ready.countDown() }
                    "dom-clicked" -> clicked.countDown()
                }
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "dom-fixture"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log"}
                }""")
                val source = """
                    GM_setValue('initial-setting',true);
                    setTimeout(()=>{
                      try {
                        const d=unsafeWindow.document, button=d.createElement('button');
                        button.id='fixture-start'; button.textContent='Start fixture'; d.body.appendChild(button);
                        button.addEventListener('click',()=>{
                          button.id='fixture-running'; GM_setValue('dom-clicked',true);
                        });
                        GM_setValue('dom-ready',{ready:unsafeWindow.top===unsafeWindow});
                      } catch(error) {GM_setValue('dom-ready',{error:String(error)});}
                    },250);
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                    .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle)
                    .put("active", false))
                assertTrue("UserScript timer did not run after writing settings", ready.await(20, TimeUnit.SECONDS))
                assertTrue(setup.get().toString(), setup.get().optBoolean("ready"))
                assertTrue("The document did not expose the start control", available.await(5, TimeUnit.SECONDS))
                val result = client.call("script.control", JSONObject().put("handle", handle).put("action", "start"))
                assertTrue(result.toString(), result.getBoolean("accepted"))
                assertTrue("Native click did not reach the userScript DOM listener", clicked.await(5, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeDialogActionsAndBrowserInputsWorkWithoutOpeningABrowserActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val dom = CountDownLatch(1)
        val browserPrompts = java.util.concurrent.LinkedBlockingQueue<JSONObject>()
        val domId = AtomicReference<String>()
        val complete = CountDownLatch(1)
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status") event.optJSONObject("interaction")?.let { domId.set(it.getString("id")); dom.countDown() }
                if (event.optString("kind") == "browser-prompt") browserPrompts.offer(event)
                if (event.optString("kind") == "storage" && event.optString("key") == "native-complete" && event.optBoolean("value")) complete.countDown()
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "prompt-fixture"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"browserPrompts":true,"version":"fixture","settings":[],"startSelector":"#fixture-start",
                    "runningSelector":"#fixture-running","logSelector":"#fixture-log",
                    "dialogs":[{"id":"notice","selector":"#native-dialog","title":"Native fixture","kind":"notice",
                    "messageSelector":"p","actions":[{"id":"confirm","label":"Continue","selector":"button"}]}]}
                }""")
                val source = """
                    const d=unsafeWindow.document, box=d.createElement('div');
                    box.id='native-dialog';box.innerHTML='<p>Native controls only</p><button>Continue</button>';d.body.appendChild(box);
                    box.querySelector('button').addEventListener('click',()=>{
                      box.remove();setTimeout(()=>{
                        const confirmed=unsafeWindow.confirm('Synthetic confirmation');
                        const text=unsafeWindow.prompt('Synthetic private input');
                        GM_setValue('native-complete',confirmed && text==='fixture-private-input');
                      },100);
                    });
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source).put("declaration", declaration)
                    .put("info", JSONObject().put("version", "1.0.0")).put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/").put("origins", JSONArray().put("https://example.com"))
                    .put("scriptHandle", handle).put("active", false))
                assertTrue("DOM notice was not reported", dom.await(20, TimeUnit.SECONDS))
                val answer = JSONObject().put("handle", handle).put("interactionId", domId.get()).put("actionId", "confirm")
                assertTrue(client.call("script.interact", answer).getBoolean("accepted"))
                assertThrows(PluginException::class.java) { client.call("script.interact", answer) }
                val confirmation = browserPrompts.poll(10, TimeUnit.SECONDS)
                assertNotNull("Browser confirmation was silently dismissed", confirmation)
                assertEquals("notice", confirmation!!.getString("promptKind"))
                val confirm = JSONObject().put("handle", handle).put("interactionId", confirmation.getString("interactionId")).put("actionId", "confirm")
                assertTrue(client.call("script.interact", confirm).getBoolean("accepted"))
                assertThrows(PluginException::class.java) { client.call("script.interact", confirm) }
                val input = browserPrompts.poll(10, TimeUnit.SECONDS)
                assertNotNull("Browser text prompt was not forwarded", input)
                assertEquals("input", input!!.getString("promptKind"))
                assertFalse(input.has("defaultValue"))
                assertTrue(client.call("script.interact", JSONObject().put("handle", handle).put("interactionId", input.getString("interactionId"))
                    .put("actionId", "confirm").put("text", "fixture-private-input")).getBoolean("accepted"))
                assertTrue("Responses did not reach the original script", complete.await(10, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    @Test fun nativeStartWaitsForDelayedEntryAndTaskListeners() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowBrowserNetwork") == "true")
        val profile = profile()
        val entryReady = CountDownLatch(1)
        val taskReady = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            Client(profile) { event ->
                if (event.optString("kind") == "status") when (event.optString("stage")) {
                    "entry" -> entryReady.countDown()
                    "ready" -> taskReady.countDown()
                }
                if (event.optString("kind") == "storage") when (event.optString("key")) {
                    "entered" -> entered.countDown()
                    "started" -> started.countDown()
                }
                JSONObject().put("ok", true)
            }.use { client ->
                client.configure()
                val handle = "delayed-controls"
                val declaration = JSONObject("""{
                    "matches":[{"host":"example.com","pathPrefix":"/"}],"exclude":[],
                    "adapter":{"version":"fixture","settings":[],"entrySelector":"#delayed-entry",
                    "startSelector":"#delayed-start","runningSelector":"#delayed-running","logSelector":"#delayed-log"}
                }""")
                val source = """
                    const d=unsafeWindow.document, entry=d.createElement('button');
                    entry.id='delayed-entry';entry.textContent='Enter';d.body.appendChild(entry);
                    setTimeout(()=>entry.addEventListener('click',()=>{
                      entry.remove();GM_setValue('entered',true);
                      const start=d.createElement('button');
                      start.id='delayed-start';start.textContent='Start';d.body.appendChild(start);
                      setTimeout(()=>{start.onclick=()=>{
                        start.id='delayed-running';GM_setValue('started',true);
                      };},800);
                    }),800);
                """.trimIndent()
                client.call("script.start", JSONObject().put("handle", handle).put("source", source)
                    .put("declaration", declaration).put("info", JSONObject().put("version", "1.0.0"))
                    .put("values", JSONObject()).put("runAt", "document-end"))
                client.call("page.open", JSONObject().put("url", "https://example.com/")
                    .put("origins", JSONArray().put("https://example.com")).put("scriptHandle", handle).put("active", false))
                assertTrue("Entry was not ready", entryReady.await(20, TimeUnit.SECONDS))
                val input = JSONObject().put("handle", handle).put("action", "start")
                assertTrue(client.call("script.control", input).getBoolean("accepted"))
                assertTrue("Entry was clicked before its listener was attached", entered.await(5, TimeUnit.SECONDS))
                assertTrue("Task control was not ready", taskReady.await(10, TimeUnit.SECONDS))
                assertTrue(client.call("script.control", input).getBoolean("accepted"))
                assertTrue("Task was clicked before its listener was attached", started.await(5, TimeUnit.SECONDS))
                client.call("script.stop")
            }
        } finally { File(context.noBackupFilesDir, "embedded-browser/$profile").deleteRecursively() }
    }

    private inner class Client(
        private val profile: String,
        private val respond: (JSONObject) -> JSONObject = { JSONObject().put("ok", true) }
    ) : AutoCloseable {
        private val connected = CountDownLatch(1)
        private val died = CountDownLatch(1)
        private val remote = AtomicReference<IBinder>()
        private var initialized = false
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(PluginBrowserWire.DESCRIPTOR)
                val event = PluginBrowserWire.read(data)
                data.readStrongBinder()
                val value = respond(event)
                reply!!.writeNoException()
                PluginBrowserWire.write(reply, JSONObject().put("ok", true).put("data", value))
                return true
            }
        }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote.set(service)
                service.linkToDeath({ died.countDown() }, 0)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { died.countDown() }
        }
        init {
            assertTrue(context.bindService(Intent(context, PluginEmbeddedBrowserService::class.java),
                connection, Context.BIND_AUTO_CREATE))
            assertTrue("Browser service did not bind", connected.await(10, TimeUnit.SECONDS))
        }
        fun configure() {
            call("configure", JSONObject("""{
                "title":"Synthetic Gecko test","origins":["https://example.com"],
                "network":[{"origin":"https://example.com","pathPrefix":"/","methods":["GET"],"purposes":["query"]}]
            }"""))
            initialized = true
        }
        fun call(action: String, input: JSONObject = JSONObject()): JSONObject =
            PluginBrowserWire.call(remote.get(), JSONObject().put("profile", profile)
                .put("action", action).put("input", input), callback)

        override fun close() {
            try { if (initialized && remote.get().isBinderAlive) call("shutdown") }
            finally { context.unbindService(connection) }
            if (initialized) assertTrue("Browser process did not stop", died.await(10, TimeUnit.SECONDS))
        }
    }
}
