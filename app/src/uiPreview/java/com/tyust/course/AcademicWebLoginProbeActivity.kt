package com.tyust.course

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File

/** Preview-only bridge for opt-in device tests of the separate login-browser process. */
class AcademicWebLoginProbeActivity : ComponentActivity() {
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = JSONObject().put("completed", result.resultCode == Activity.RESULT_OK)
            .put("cookie", result.data?.getStringExtra(AcademicWebViewActivity.EXTRA_COOKIE_RESULT).orEmpty())
            .put("url", result.data?.getStringExtra(AcademicWebViewActivity.EXTRA_PAGE_URL).orEmpty())
        // Private, short-lived input for the instrumentation process. Never part of the report.
        File(cacheDir, "huel-web-result.private.json").writeText(data.toString())
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        browser.launch(Intent(this, AcademicWebViewActivity::class.java)
            .putExtra(AcademicWebViewActivity.EXTRA_START_URL, "https://xk.huel.edu.cn/jwglxt/xtgl/login_slogin.html")
            .putExtra(AcademicWebViewActivity.EXTRA_COOKIE_URL, "https://xk.huel.edu.cn/jwglxt/xtgl/index_initMenu.html")
            .putStringArrayListExtra(AcademicWebViewActivity.EXTRA_ALLOWED_HOSTS, arrayListOf("xk.huel.edu.cn")))
    }
}
