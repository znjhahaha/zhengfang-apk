package com.tyust.course.academic.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host-side widget rules: declaration validation, snapshot bounds and action page confinement. */
class PluginWidgetContractTest {
    private val schema = PluginSchema(JSONObject(File("src/main/assets/academic-plugin/manifest.schema.json").readText()))

    private fun manifest(
        widgets: String = DEFAULT_WIDGETS,
        permissions: String = """["widget"]""",
        requires: String = """[{"name":"widget.render","version":1},{"name":"widget.publish","version":1}]""",
        capabilities: String = "[]",
        dataProviders: String = "[]",
        pages: String = "[]",
        minAppVersionCode: Int = 129,
        kind: String = "widget",
    ) = PluginManifest(JSONObject(
        """
        {"id":"example.template.native-widget","name":"桌面小组件示例","version":"1.0.0","apiVersion":3,
         "kind":"$kind","entry":"index.js","description":"仅合成数据的只读快照示例",
         "permissions":$permissions,"requires":$requires,"capabilities":$capabilities,
         "contributes":{"pages":$pages,"entries":[],"dataProviders":$dataProviders,"widgets":$widgets},
         "network":[],"files":{},"minAppVersionCode":$minAppVersionCode}
        """.trimIndent(),
    ))

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected PluginException(VALIDATION_FAILED)")
        } catch (error: PluginException) {
            assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code)
        }
    }

    @Test fun `widget kind behaves like a native plugin and passes the manifest schema`() {
        val m = manifest()
        m.validate(schema)
        assertTrue(m.isWidget)
        assertTrue(m.isNative)
        assertFalse(m.isAcademic)
        val widgets = PluginWidgetContract.declarations(m)
        assertEquals(1, widgets.size)
        assertEquals(PluginWidgetContract.Family.Small, PluginWidgetContract.Family.of("2x1"))
        assertEquals(PluginWidgetContract.Family.Large, PluginWidgetContract.Family.of("4x2"))
        assertEquals("default", widgets.first().viewKey(null))
    }

    @Test fun `widget declarations reject incomplete or unsafe contributions`() {
        fun variants(): List<Pair<String, PluginManifest>> = listOf(
            "kind widget without contributes.widgets" to manifest(widgets = "[]"),
            "widget without widget permission" to manifest(permissions = "[]"),
            "widget declared as a plugin capability" to manifest(capabilities = """["widget.render"]"""),
            "push widget without widget publish" to manifest(
                requires = """[{"name":"widget.render","version":1}]""",
            ),
            "pull widget without data providers" to manifest(
                widgets = DEFAULT_WIDGETS.replace("push", "pull"),
                requires = """[{"name":"widget.render","version":1}]""",
            ),
            "widget below the first supporting app version" to manifest(minAppVersionCode = 128),
            "duplicate widget ids" to manifest(widgets = "[$DEFAULT_WIDGETS,$DEFAULT_WIDGETS]".replace("{\"id\":\"board\"", "{\"id\":\"board\"")),
            "unknown widget template" to manifest(widgets = DEFAULT_WIDGETS.replace("\"lines\"", "\"html\"")),
            "duplicate declared views" to manifest(
                widgets = DEFAULT_WIDGETS.replace(
                    """[{"key":"default","title":"公告"}]""",
                    """[{"key":"default","title":"A"},{"key":"default","title":"B"}]""",
                ),
            ),
        )
        variants().forEach { (reason, m) ->
            try {
                m.validate(schema)
                throw AssertionError("Expected rejection: $reason")
            } catch (error: PluginException) {
                assertEquals(reason, PluginErrorCode.VALIDATION_FAILED, error.code)
            }
        }
    }

    @Test fun `published snapshots are bounded and stripped of control characters`() {
        val widget = PluginWidgetContract.declarations(manifest()).first()
        val ok = PluginWidgetContract.sanitize(widget, JSONObject("""{"schema":1,"header":{"title":"公告"},"rows":[{"id":"r1","title":"A"}]}"""))
        assertEquals("公告", ok.getJSONObject("header").getString("title"))

        val dirty = PluginWidgetContract.sanitize(widget, JSONObject("""{"schema":1,"header":{"title":"a\u0001b"}}"""))
        assertEquals("ab", dirty.getJSONObject("header").getString("title"))

        val clamped = PluginWidgetContract.sanitize(widget, JSONObject().put("schema", 1).put("header", JSONObject().put("title", "x".repeat(5_000))))
        assertEquals(2_000, clamped.getJSONObject("header").getString("title").length)

        rejects { PluginWidgetContract.sanitize(widget, JSONObject("""{"schema":2}""")) }
        val big = JSONObject().put("schema", 1).put("rows", org.json.JSONArray().also { rows ->
            repeat(6) { index -> rows.put(JSONObject().put("id", "r$index").put("title", "标".repeat(2_000)).put("subtitle", "副".repeat(2_000))) }
        })
        rejects { PluginWidgetContract.sanitize(widget, big) }
    }

    @Test fun `timer bounds are enforced and expired countdowns stop ticking`() {
        val widget = PluginWidgetContract.declarations(manifest()).first()
        val now = 1_000_000L
        rejects { PluginWidgetContract.sanitize(widget, JSONObject("""{"schema":1,"timer":{"startAt":20,"endAt":10}}"""), now) }
        rejects { PluginWidgetContract.sanitize(widget, JSONObject("""{"schema":1,"timer":{"startAt":10,"endAt":${now + 31L * 24 * 3600_000}}}"""), now) }
        val expired = PluginWidgetContract.sanitize(
            widget,
            JSONObject("""{"schema":1,"timer":{"startAt":10,"endAt":20,"countdown":true}}"""),
            now,
        )
        assertFalse(expired.getJSONObject("timer").getBoolean("countdown"))
    }

    @Test fun `widget actions cannot escape the plugin's own pages`() {
        val m = manifest(pages = """[{"id":"main","title":"面板","renderer":"native"}]""")
        val action = JSONObject().put("actions", org.json.JSONArray().put(JSONObject().put("id", "open").put("label", "打开").put("kind", "openPage").put("pageId", "main")))
        PluginWidgetContract.validateActions(m, action)
        rejects { PluginWidgetContract.validateActions(m, JSONObject().put("actions", org.json.JSONArray().put(JSONObject().put("id", "open").put("label", "打开").put("kind", "openPage").put("pageId", "other")))) }
        rejects { PluginWidgetContract.validateActions(m, JSONObject().put("actions", org.json.JSONArray().put(JSONObject().put("id", "open").put("label", "打开").put("kind", "script")))) }
    }

    @Test fun `sdk native widget template matches the host contract`() {
        val file = File("../../sdk/templates/native-widget/manifest.json")
        assumeTrue("SDK 模板不在本机 checkout 中", file.isFile)
        val m = PluginManifest(JSONObject(file.readText()))
        m.validate(schema)
        assertTrue(m.isWidget)
        assertEquals(3, PluginWidgetContract.declarations(m).size)
        val pull = PluginWidgetContract.declarations(m).filter { !it.push }
        assertEquals(1, pull.size)
        assertTrue(pull.first().sizes.contains("1x1"))
    }

    private companion object {
        const val DEFAULT_WIDGETS =
            """[{"id":"board","title":"校园公告板","sizes":["2x1","4x2"],"template":"lines","defaultView":"default","views":[{"key":"default","title":"公告"}],"options":[{"id":"count","title":"显示条数","type":"number","default":3,"scope":"instance"}],"refresh":{"mode":"push","staleAfterSeconds":21600}}]"""
    }
}
