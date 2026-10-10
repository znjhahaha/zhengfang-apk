package com.tyust.course.schedule

/** Data refreshes may replace a request, but must not replace the schedule's browsing session. */
fun schedulePageEntryKey(route: String, account: String, requestToken: String, revision: Long): String =
    if (route == "app.schedule") "$route:$account" else "$route:$requestToken:$revision"
