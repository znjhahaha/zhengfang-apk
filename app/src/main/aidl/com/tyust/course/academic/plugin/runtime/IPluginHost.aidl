package com.tyust.course.academic.plugin.runtime;
import android.os.ParcelFileDescriptor;
interface IPluginHost {
    ParcelFileDescriptor call(String operationId, String method, in ParcelFileDescriptor payload);
}
