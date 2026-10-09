package com.tyust.course.academic.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSubmissionGrantTest {
    private val target = "https://learning.example.test/sign/submit"
    private fun manifest(version: Int = 2) = PluginManifest(JSONObject().put("id","test.submit").put("version","1.0.0").put("kind","native").put("apiVersion",3)
        .put("requires",JSONArray().put(JSONObject().put("name","ui.components").put("version",version)))
        .put("network",JSONArray().put(JSONObject().put("origin","https://learning.example.test").put("pathPrefix","/sign")
            .put("methods",JSONArray(listOf("GET", "POST"))).put("purposes",JSONArray(listOf("mutation"))))))
    private fun node() = JSONObject().put("type","button").put("event","submit").put("enabled",true)
        .put("submission",JSONObject().put("url",target).put("method","GET").put("parameters",JSONObject().put("activeId","selected").put("classId","class")))
    private fun event(type: String = "click", name: String = "submit") = JSONObject().put("type",type).put("name",name)
    private fun request(query: String = "activeId=selected&classId=class") = JSONObject().put("url","$target?$query").put("purpose","mutation").put("method","GET")
    @Test fun onlyAnEnabledClickedSubmissionButtonCanAuthorizeItsExactSelection() {
        val grant = requireNotNull(NativeSubmissionGrant.fromButton(manifest(),node(),event(),true))
        assertTrue(grant.matches(request()))
        assertTrue(grant.matches(request("activeId=selected&classId=class&validate=synthetic")))
        for (r in listOf(request("activeId=another&classId=class"),request("activeId=selected&activeId=another&classId=class"),
            request().put("form",JSONObject().put("activeId","selected")),request().put("method","POST"),
            request().put("url","https://other.test/sign/submit?activeId=selected&classId=class"),request().put("body","{}"))) assertFalse(grant.matches(r))
        assertNull(NativeSubmissionGrant.fromButton(manifest(),node(),event(),false))
        assertNull(NativeSubmissionGrant.fromButton(manifest(),node().put("enabled",false),event(),true))
        assertNull(NativeSubmissionGrant.fromButton(manifest(),node(),event("input"),true))
        assertNull(NativeSubmissionGrant.fromButton(manifest(),node(),event(name="refresh"),true))
        assertNull(NativeSubmissionGrant.fromButton(manifest(1),node(),event(),true))
    }

    @Test fun postPhotoMetadataKeepsTheSelectedActivityAuthorizationWithoutAllowingDuplicateSelectors() {
        val button = node().apply { getJSONObject("submission").put("method", "POST") }
        val grant = requireNotNull(NativeSubmissionGrant.fromButton(manifest(), button, event(), true))
        val photoRequest = request().put("method", "POST").put("form", JSONObject().put("photoResult", "synthetic-file-metadata"))
        assertTrue(grant.matches(photoRequest))
        assertFalse(grant.matches(JSONObject(photoRequest.toString()).apply { getJSONObject("form").put("activeId", "another") }))
        assertFalse(grant.matches(JSONObject(photoRequest.toString()).put("method", "GET")))
    }
}
