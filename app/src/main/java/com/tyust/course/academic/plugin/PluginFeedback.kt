package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tyust.course.BuildConfig

object PluginFeedback {
    fun url(pkg: PluginPackage? = null, errorCode: String? = null): Uri = Uri.parse(AcademicProviderRegistry.OFFICIAL_WEBSITE + "/feedback").buildUpon()
        .appendQueryParameter("appVersion", BuildConfig.VERSION_NAME).appendQueryParameter("platform", "Android ${android.os.Build.VERSION.SDK_INT}")
        .apply { pkg?.let { appendQueryParameter("plugin", it.manifest.id); appendQueryParameter("pluginVersion", it.manifest.version) }; errorCode?.takeIf { it.matches(Regex("[A-Z_]{2,50}")) }?.let { appendQueryParameter("errorCode", it) } }.build()
    fun open(context: Context, pkg: PluginPackage? = null, errorCode: String? = null) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url(pkg, errorCode)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
