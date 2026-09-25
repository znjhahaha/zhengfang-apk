package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginSourceDetailsTest {
    private val pkg = PluginPackage(PluginManifest(JSONObject("""{"id":"test.source","name":"Source","version":"1.0.0","apiVersion":2,"kind":"configuration","extends":"builtin.zf","network":[],"capabilities":[],"school":{},"files":{}}""")), "", "digest", true)
    @Test fun installedReleaseCannotInheritNewerSourceHash() {
        val newer = JSONObject().put("id", pkg.manifest.id).put("version", "2.0.0").put("source", JSONObject().put("version", "2.0.0").put("sha256", "a".repeat(64)))
        assertNull(PluginSourceDetails.source(pkg, newer))
        val older = JSONObject().put("version", "1.0.0").put("source", JSONObject().put("version", "1.0.0").put("sha256", "b".repeat(64)))
        newer.put("releases", JSONArray().put(newer.toString().let(::JSONObject)).put(older))
        assertEquals("b".repeat(64), PluginSourceDetails.source(pkg, newer)!!.getString("sha256"))
        assertTrue(PluginSourceDetails.download(pkg).endsWith("/1.0.0/source"))
        assertNull(PluginSourceDetails.source(pkg.copy(official = false), newer))
    }
    @Test fun repositoryCannotExecuteScriptOrEmbedCredentials() {
        assertNull(PluginSourceDetails.repository("javascript:alert(1)"))
        assertNull(PluginSourceDetails.repository("https://user:secret@example.test/repo"))
        assertEquals("https://example.test/repo", PluginSourceDetails.repository("https://example.test/repo"))
    }
}
