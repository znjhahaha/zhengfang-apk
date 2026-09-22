package com.tyust.course.academic.plugin.runtime;
import android.os.ParcelFileDescriptor;
oneway interface IPluginResult {
    void complete(String operationId, in ParcelFileDescriptor result);
}
