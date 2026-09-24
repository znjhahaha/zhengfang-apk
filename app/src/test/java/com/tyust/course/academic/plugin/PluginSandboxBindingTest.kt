package com.tyust.course.academic.plugin

import android.app.Application
import android.content.*
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import org.robolectric.RuntimeEnvironment
import com.tyust.course.academic.AcademicSessionStore
import com.tyust.course.academic.plugin.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSandboxBindingTest {
    private val app = RuntimeEnvironment.getApplication()
    private fun operation() = PluginOperation(
        AcademicSessionStore().session("school", "account", "https://school.test"),
        PluginManifest(JSONObject("""{"id":"binding.test","kind":"independent","version":"1.0.0","network":[]}""")),
        "study.terms", development = true
    )
    private class BindingContext(base: Context, val bind: (ServiceConnection) -> Boolean) : ContextWrapper(base) {
        lateinit var connection: ServiceConnection
        var unbound = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return bind(conn)
        }
        override fun unbindService(conn: ServiceConnection) { assertSame(connection, conn); unbound++ }
    }
    private suspend fun invoke(context: Context, op: PluginOperation, timeout: Long = 100) =
        PluginSandboxClient(context, timeout).execute("", JSONObject(), op, PluginHost(op, app.cacheDir))

    @Test fun privateServiceKeepsSeparateProcessWithoutAnIsolatedUid() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, PluginSandboxService::class.java), 0)
        assertEquals(app.packageName + ":academic_plugin", info.processName)
        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS)
    }
    @Test fun failedBindDoesNotExecuteOrUnbindANonexistentConnection() = runTest {
        val context = BindingContext(app) { false }
        val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
        assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
        assertTrue(error.message!!.contains("启动失败"))
        assertEquals(0, context.unbound)
    }
    @Test fun noCallbackIsAStartupFailureAndLateConnectionCannotExecute() = runTest {
        val context = BindingContext(app) { true }
        val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
        assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
        assertTrue(error.message!!.contains("启动超时"))
        assertEquals(1, context.unbound)
        var calls = 0
        context.connection.onServiceConnected(ComponentName(app, PluginSandboxService::class.java), object : IPluginSandbox.Stub() {
            override fun execute(input: ParcelFileDescriptor, host: IPluginHost, callback: IPluginResult) { calls++; input.close() }
            override fun cancel(operationId: String) { calls++ }
        })
        assertEquals(0, calls)
    }
    @Test fun nullAndDeadBindingsFailImmediatelyAndAlwaysUnbind() = runTest {
        for (nullBinding in listOf(true, false)) {
            val context = BindingContext(app) {
                val name = ComponentName(app, PluginSandboxService::class.java)
                if (nullBinding) it.onNullBinding(name) else it.onBindingDied(name)
                true
            }
            val error = runCatching { invoke(context, operation()) }.exceptionOrNull() as PluginException
            assertEquals(PluginErrorCode.RUNTIME_EXITED, error.code)
            assertEquals(1, context.unbound)
            assertEquals(0, testScheduler.currentTime)
        }
    }
    @Test fun cancellationWhileConnectingClosesTheOperation() = runTest {
        val context = BindingContext(app) { true }
        val op = operation()
        val task = launch { invoke(context, op) }
        runCurrent()
        task.cancelAndJoin()
        assertEquals(1, context.unbound)
        assertEquals(PluginErrorCode.CANCELLED, (runCatching { op.requireActive() }.exceptionOrNull() as PluginException).code)
    }
    @Test fun callerDeadlineRemainsCancellationInsteadOfAPluginTimeout() = runTest {
        val context = BindingContext(app) { true }
        val error = runCatching { withTimeout(20) { invoke(context, operation(), 100) } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertEquals(1, context.unbound)
    }
}
