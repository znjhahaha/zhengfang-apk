package com.tyust.course.academic.plugin

import com.tyust.course.academic.plugin.widget.PluginWidgetPull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pull-mode widgets: providerId validation and the `data.query` items -> snapshot mapping. */
class PluginWidgetPullTest {
    private fun manifest(
        providerId: String? = "example.template.native-widget.exams",
        dataProviders: String = """[{"id":"example.template.native-widget.exams","title":"考试数据"}]""",
        rows: Boolean = true,
    ): PluginManifest {
        val refresh = JSONObject().put("mode", "pull").put("staleAfterSeconds", 1800).apply {
            if (providerId != null) put("providerId", providerId)
        }
        val widget = JSONObject(
            """
            {"id":"examList","title":"考试列表","sizes":["2x1"],"template":"text",
             "views":[{"key":"default","title":"考试"}],
             "options":[{"id":"maxRows","title":"最多行数","type":"number","default":2,"scope":"instance"}]}
            """.trimIndent(),
        ).put("refresh", refresh)
        return PluginManifest(
            JSONObject(
                """
                {"id":"example.template.native-widget","name":"桌面小组件示例","version":"1.0.0","apiVersion":3,
                 "kind":"widget","entry":"index.js",
                 "permissions":["widget","runtime"],
                 "requires":[{"name":"widget.render","version":1},{"name":"data.query","version":1}],
                 "capabilities":["data.query"],
                 "contributes":{"pages":[],"entries":[],"dataProviders":$dataProviders,"widgets":[${widget}]},
                 "network":[],"files":{},"minAppVersionCode":129}
                """.trimIndent(),
            ),
        )
    }

    private fun declaration(m: PluginManifest): PluginWidgetContract.Declaration =
        PluginWidgetContract.declarations(m).first { !it.push }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected PluginException(VALIDATION_FAILED)")
        } catch (error: PluginException) {
            assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code)
        }
    }

    @Test fun `pull widget without providerId is rejected`() {
        rejects { PluginWidgetContract.validate(manifest(providerId = null)) }
    }

    @Test fun `pull widget with dangling providerId is rejected`() {
        rejects { PluginWidgetContract.validate(manifest(providerId = "example.template.native-widget.missing")) }
    }

    @Test fun `declared providerId is parsed`() {
        val m = manifest()
        PluginWidgetContract.validate(m)
        assertEquals("example.template.native-widget.exams", declaration(m).providerId)
        assertTrue(declaration(m).push.not())
    }

    @Test fun `items map to rows in the snapshot convention`() {
        val m = manifest()
        val now = 1_700_000_000_000L
        val items = JSONArray(
            """
            [{"id":"e1","title":"高等数学","subtitle":"A101","time":"09:00","badge":"3天","tone":"warning"},
             {"id":"e2","title":"大学英语"}]
            """.trimIndent(),
        )
        val snapshot = PluginWidgetPull.snapshotFromItems(declaration(m), items, now)
        assertEquals(1, snapshot.getInt("schema"))
        assertEquals(now, snapshot.getLong("updatedAt"))
        assertEquals("考试列表", snapshot.getJSONObject("header").getString("title"))
        val rows = snapshot.getJSONArray("rows")
        assertEquals(2, rows.length())
        assertEquals("高等数学", rows.getJSONObject(0).getString("title"))
        assertEquals("A101", rows.getJSONObject(0).getString("subtitle"))
        assertEquals("3天", rows.getJSONObject(0).getString("badge"))
        assertEquals("大学英语", rows.getJSONObject(1).getString("title"))
        // The mapping result still has to pass the shared snapshot sanitizer.
        PluginWidgetContract.sanitize(declaration(m), snapshot, now)
    }

    @Test fun `rows beyond the host limit are trimmed and untitled rows dropped`() {
        val m = manifest()
        val items = JSONArray()
        for (index in 0 until 8) items.put(JSONObject().put("id", "r$index").put("title", "行$index"))
        items.put(JSONObject().put("id", "blank").put("subtitle", "无标题"))
        val rows = PluginWidgetPull.snapshotFromItems(declaration(m), items, 1L).getJSONArray("rows")
        assertEquals(6, rows.length())
        assertEquals("行0", rows.getJSONObject(0).getString("title"))
        assertEquals("行5", rows.getJSONObject(5).getString("title"))
    }

    @Test fun `empty items produce the empty state text`() {
        val snapshot = PluginWidgetPull.snapshotFromItems(declaration(manifest()), JSONArray(), 42L)
        assertEquals(0, snapshot.getJSONArray("rows").length())
        assertEquals("暂无数据", snapshot.getJSONObject("empty").getString("text"))
        assertEquals(42L, snapshot.getLong("updatedAt"))
    }
}
