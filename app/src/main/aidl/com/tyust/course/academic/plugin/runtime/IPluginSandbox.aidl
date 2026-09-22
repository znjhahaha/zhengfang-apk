package com.tyust.course.academic.plugin.runtime;
import android.os.ParcelFileDescriptor;
import com.tyust.course.academic.plugin.runtime.IPluginHost;
import com.tyust.course.academic.plugin.runtime.IPluginResult;
interface IPluginSandbox {
    void execute(in ParcelFileDescriptor input, IPluginHost host, IPluginResult callback);
    void cancel(String operationId);
}
