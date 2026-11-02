package com.tyust.course.academic.plugin

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginConnectFallbackLifecycleTest {
    @Test
    fun `native call cancellation interrupts response body consumption`() {
        SlowBodyFixture(wrapped = false).use { fixture ->
            fixture.call.execute().use { response ->
                assertBodyReadCanceled(fixture.call, fixture.nativeCall.get(), response)
            }
        }
    }

    @Test
    fun `fallback cancellation interrupts response body after execute returns`() {
        SlowBodyFixture(wrapped = true).use { fixture ->
            fixture.call.execute().use { response ->
                assertBodyReadCanceled(fixture.call, fixture.nativeCall.get(), response)
            }
        }
    }

    @Test
    fun `all connection attempts share one call timeout`() {
        assertConnectionBudget(callTimeoutMillis = 500) {}
    }

    @Test
    fun `caller configured timeout applies across all attempts`() {
        assertConnectionBudget(callTimeoutMillis = 5_000) { call ->
            call.timeout().timeout(500, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun `timeout between connection attempts reports cancellation`() {
        val sockets = DelayedFailureSocketFactory(0)
        val client = failingClient(sockets, callTimeoutMillis = 5_000)
        val nativeCall = AtomicReference<Call>()
        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                nativeCall.set(call)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                // The native timeout has exited before callFailed. Exhaust the shared budget
                // here so the wrapper, rather than the native watchdog, detects the timeout.
                val deadline = call.timeout().deadlineNanoTime()
                val wait = CountDownLatch(1)
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    wait.await(remaining, TimeUnit.NANOSECONDS)
                }
            }
        }
        try {
            val call = fallbackCall(client, listener)
            call.timeout().timeout(500, TimeUnit.MILLISECONDS)

            assertThrows(InterruptedIOException::class.java) { call.execute() }

            assertFalse("The completed native call must not supply cancellation", nativeCall.get().isCanceled())
            assertEquals("An expired budget must prevent the next connection", 1, sockets.connects.get())
            assertTrue("A call timeout must report cancellation", call.isCanceled())
        } finally {
            closeClient(client)
        }
    }

    @Test
    fun `timeout returns the persistent configuration object`() {
        val sockets = DelayedFailureSocketFactory(0)
        val client = failingClient(sockets, callTimeoutMillis = 5_000)
        try {
            val call = fallbackCall(client)
            val timeout = call.timeout()
            timeout.timeout(750, TimeUnit.MILLISECONDS)

            assertSame(timeout, call.timeout())
            assertEquals(TimeUnit.MILLISECONDS.toNanos(750), call.timeout().timeoutNanos())
        } finally {
            closeClient(client)
        }
    }

    @Test
    fun `explicit deadline applies while consuming the response body`() {
        SlowBodyFixture(wrapped = true).use { fixture ->
            fixture.call.timeout().deadline(500, TimeUnit.MILLISECONDS)
            fixture.call.execute().use { response ->
                assertBodyReadTimesOut(fixture.nativeCall.get(), response)
            }
        }
    }

    @Test
    fun `configured call timeout applies while consuming the response body`() {
        SlowBodyFixture(wrapped = true, callTimeoutMillis = 500).use { fixture ->
            fixture.call.execute().use { response ->
                assertBodyReadTimesOut(fixture.nativeCall.get(), response)
                assertTrue("The wrapper lost the native timeout cancellation", fixture.call.isCanceled())
                assertEquals(1, fixture.cancelEvents.get())
            }
        }
    }

    @Test
    fun `headers and response body share one timeout budget`() {
        SlowBodyFixture(wrapped = true, callTimeoutMillis = 700, headersDelayMillis = 350).use { fixture ->
            val start = System.nanoTime()
            fixture.call.execute().use { response ->
                assertBodyReadTimesOut(fixture.nativeCall.get(), response)
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("A 700ms headers and body budget took ${elapsedMillis}ms", elapsedMillis < 950)
        }
    }

    @Test
    fun `failed first route and response body share the same deadline`() {
        val sockets = DelayedFailureSocketFactory(250, reachableAddress = addresses.first())
        SlowBodyFixture(
            wrapped = true,
            callTimeoutMillis = 500,
            socketFactory = sockets,
            dnsAddresses = listOf(addresses[1], addresses[0]),
        ).use { fixture ->
            val start = System.nanoTime()
            fixture.call.execute().use { response ->
                assertBodyReadTimesOut(fixture.nativeCall.get(), response)
            }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue("A 500ms connect and body budget took ${elapsedMillis}ms", elapsedMillis < 900)
            assertEquals(2, sockets.connects.get())
            assertEquals(2, fixture.nativeDeadlines.size)
            assertNotNull("The native call must receive the shared deadline", fixture.nativeDeadlines.first())
            assertEquals("Fallback restarted the deadline", fixture.nativeDeadlines.first(), fixture.nativeDeadlines.last())
        }
    }

    @Test
    fun `shorter call duration takes priority over a later explicit deadline`() {
        SlowBodyFixture(wrapped = true, callTimeoutMillis = 500).use { fixture ->
            fixture.call.timeout().deadline(5, TimeUnit.SECONDS)
            fixture.call.execute().use { response ->
                assertBodyReadTimesOut(fixture.nativeCall.get(), response)
            }
        }
    }

    @Test
    fun `clearing the caller timeout removes the client timeout`() {
        SlowBodyFixture(wrapped = true, callTimeoutMillis = 200, throttleMillis = 750).use { fixture ->
            fixture.call.timeout().clearTimeout()
            fixture.call.execute().use { response ->
                val source = response.body!!.source()
                assertEquals('a'.code.toByte(), source.readByte())
                assertEquals('b'.code.toByte(), source.readByte())
                assertFalse(fixture.call.isCanceled())
            }
        }
    }

    @Test
    fun `canceling after body completion does not cancel a finished native call`() {
        SlowBodyFixture(wrapped = true, throttleMillis = 0).use { fixture ->
            fixture.call.execute().use { response ->
                assertEquals("abcd", response.body!!.string())
            }
            val nativeCall = fixture.nativeCall.get()
            assertFalse(nativeCall.isCanceled())

            fixture.call.cancel()
            fixture.call.cancel()

            assertTrue(fixture.call.isCanceled())
            assertFalse("The wrapper retained the completed call", nativeCall.isCanceled())
            assertEquals(0, fixture.cancelEvents.get())
        }
    }

    @Test
    fun `closing an unfinished body releases the active native call`() {
        SlowBodyFixture(wrapped = true).use { fixture ->
            fixture.call.execute().use { response ->
                assertEquals('a'.code.toByte(), response.body!!.source().readByte())
            }
            val nativeCall = fixture.nativeCall.get()
            assertFalse(nativeCall.isCanceled())

            fixture.call.cancel()

            assertTrue(fixture.call.isCanceled())
            assertFalse("The wrapper retained the call after response.close", nativeCall.isCanceled())
            assertEquals(0, fixture.cancelEvents.get())
        }
    }

    @Test
    fun `canceling an active body reports one native cancellation event`() {
        SlowBodyFixture(wrapped = true).use { fixture ->
            fixture.call.execute().use { response ->
                assertEquals('a'.code.toByte(), response.body!!.source().readByte())
                fixture.call.cancel()
                fixture.call.cancel()

                assertTrue(fixture.nativeCall.get().isCanceled())
                assertEquals(1, fixture.cancelEvents.get())
            }
        }
    }

    @Test
    fun `cancellation before execution performs no dns lookup or connection`() {
        val sockets = DelayedFailureSocketFactory(0)
        val lookups = AtomicInteger()
        val client = failingClient(sockets, callTimeoutMillis = 0).newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    lookups.incrementAndGet()
                    return addresses
                }
            })
            .build()
        try {
            val call = fallbackCall(client)
            call.cancel()

            assertThrows(IOException::class.java) { call.execute() }
            assertTrue(call.isExecuted())
            assertTrue(call.isCanceled())
            assertEquals(0, lookups.get())
            assertEquals(0, sockets.connects.get())
        } finally {
            closeClient(client)
        }
    }

    @Test
    fun `cancellation between attempts prevents another connection`() {
        val sockets = DelayedFailureSocketFactory(0)
        val client = failingClient(sockets, callTimeoutMillis = 0)
        lateinit var wrapper: Call
        val listener = object : EventListener() {
            override fun connectFailed(
                call: Call,
                inetSocketAddress: java.net.InetSocketAddress,
                proxy: Proxy,
                protocol: okhttp3.Protocol?,
                ioe: IOException,
            ) {
                wrapper.cancel()
            }
        }
        try {
            wrapper = fallbackCall(client, listener)

            assertThrows(IOException::class.java) { wrapper.execute() }
            assertTrue(wrapper.isCanceled())
            assertEquals(1, sockets.connects.get())
        } finally {
            closeClient(client)
        }
    }

    @Test
    fun `cancellation closes a socket blocked in connection establishment`() {
        val sockets = DelayedFailureSocketFactory(5_000)
        val nativeCall = AtomicReference<Call>()
        val listener = object : EventListener() {
            override fun callStart(call: Call) {
                nativeCall.set(call)
            }
        }
        val client = failingClient(sockets, callTimeoutMillis = 0)
        val call = fallbackCall(client, listener)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<IOException?> {
                try {
                    call.execute().close()
                    null
                } catch (e: IOException) {
                    e
                }
            }
            assertTrue("The socket never started connecting", sockets.started.await(1, TimeUnit.SECONDS))

            call.cancel()

            assertNotNull(result.get(1, TimeUnit.SECONDS))
            assertTrue(nativeCall.get().isCanceled())
            assertEquals(1, sockets.connects.get())
            assertEquals(1, sockets.closed.get())
        } finally {
            call.cancel()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            closeClient(client)
        }
    }

    private fun assertConnectionBudget(callTimeoutMillis: Long, configure: (Call) -> Unit) {
        val sockets = DelayedFailureSocketFactory(300)
        val client = failingClient(sockets, callTimeoutMillis)
        try {
            val call = fallbackCall(client)
            configure(call)
            val start = System.nanoTime()

            assertThrows(IOException::class.java) { call.execute() }

            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(
                "A 500ms call budget took ${elapsedMillis}ms across ${sockets.connects.get()} attempts",
                elapsedMillis < 950,
            )
            assertTrue(
                "The 500ms budget allowed too many 300ms connections",
                sockets.connects.get() in 1..2,
            )
            assertTrue("A call timeout must report cancellation", call.isCanceled())
        } finally {
            closeClient(client)
        }
    }

    private fun assertBodyReadCanceled(call: Call, nativeCall: Call, response: Response) {
        val source = response.body!!.source()
        assertEquals('a'.code.toByte(), source.readByte())
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<IOException?> {
                started.countDown()
                try {
                    source.readByte()
                    null
                } catch (e: IOException) {
                    e
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { result.get(100, TimeUnit.MILLISECONDS) }

            call.cancel()

            assertTrue("Cancel did not reach the active native call", nativeCall.isCanceled())
            assertNotNull("Cancel did not interrupt the blocked body reader", result.get(1, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
        } finally {
            nativeCall.cancel()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun assertBodyReadTimesOut(nativeCall: Call, response: Response) {
        val source = response.body!!.source()
        assertEquals('a'.code.toByte(), source.readByte())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<IOException?> {
                try {
                    source.readByte()
                    null
                } catch (e: IOException) {
                    e
                }
            }

            val failure = result.get(1_500, TimeUnit.MILLISECONDS)
            assertNotNull("The deadline did not interrupt the blocked body reader", failure)
            assertTrue("The blocked reader must fail with a timeout", failure is InterruptedIOException)
            assertTrue("The timeout did not cancel the native call", nativeCall.isCanceled())
        } finally {
            nativeCall.cancel()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private class SlowBodyFixture(
        wrapped: Boolean,
        callTimeoutMillis: Long = 0,
        throttleMillis: Long = 2_000,
        headersDelayMillis: Long = 0,
        socketFactory: SocketFactory? = null,
        dnsAddresses: List<InetAddress> = listOf(addresses.first()),
    ) : AutoCloseable {
        private val server = MockWebServer()
        val nativeCall = AtomicReference<Call>()
        val nativeDeadlines = mutableListOf<Long?>()
        val cancelEvents = AtomicInteger()
        private val listener = object : EventListener() {
            override fun callStart(call: Call) {
                nativeCall.set(call)
                nativeDeadlines += call.timeout().let { if (it.hasDeadline()) it.deadlineNanoTime() else null }
            }

            override fun canceled(call: Call) {
                cancelEvents.incrementAndGet()
            }
        }
        private val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = dnsAddresses
            })
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .eventListener(listener)
            .apply { socketFactory?.let(::socketFactory) }
            .build()
        val call: Call

        init {
            server.start(addresses.first(), 0)
            server.enqueue(
                MockResponse().setBody("abcd")
                    .setHeadersDelay(headersDelayMillis, TimeUnit.MILLISECONDS)
                    .apply {
                        if (throttleMillis > 0) throttleBody(1, throttleMillis, TimeUnit.MILLISECONDS)
                    },
            )
            val request = Request.Builder()
                .url("http://plugin-lifecycle.test:${server.port}/")
                .build()
            call = if (wrapped) {
                PluginConnectFallbackCall(client, listener, request, PluginConnectFallback.MAX_ATTEMPTS)
            } else {
                client.newCall(request)
            }
        }

        override fun close() {
            call.cancel()
            nativeCall.get()?.cancel()
            closeClient(client)
            server.shutdown()
        }
    }

    private class DelayedFailureSocketFactory(
        private val delayMillis: Long,
        private val reachableAddress: InetAddress? = null,
    ) : SocketFactory() {
        val connects = AtomicInteger()
        val closed = AtomicInteger()
        val started = CountDownLatch(1)

        override fun createSocket(): Socket = object : Socket() {
            private val monitor = Object()
            private var socketClosed = false

            override fun connect(endpoint: SocketAddress, timeout: Int) {
                connects.incrementAndGet()
                started.countDown()
                if ((endpoint as InetSocketAddress).address == reachableAddress) {
                    super.connect(endpoint, timeout)
                    return
                }
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis)
                synchronized(monitor) {
                    while (!socketClosed) {
                        val remainingNanos = deadline - System.nanoTime()
                        if (remainingNanos <= 0) break
                        TimeUnit.NANOSECONDS.timedWait(monitor, remainingNanos)
                    }
                    if (socketClosed) throw SocketException("Socket closed during connection")
                }
                throw ConnectException("Simulated unreachable address")
            }

            override fun close() {
                synchronized(monitor) {
                    if (!socketClosed) {
                        socketClosed = true
                        closed.incrementAndGet()
                        monitor.notifyAll()
                    }
                }
                super.close()
            }
        }

        override fun createSocket(host: String, port: Int): Socket = unsupported()

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            unsupported()

        override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            unsupported()

        private fun unsupported(): Nothing = error("OkHttp must use the unconnected socket overload")
    }

    companion object {
        private val addresses = (1..4).map { suffix ->
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, suffix.toByte()))
        }

        private fun failingClient(
            socketFactory: SocketFactory,
            callTimeoutMillis: Long,
        ): OkHttpClient = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = addresses
            })
            .socketFactory(socketFactory)
            .proxy(Proxy.NO_PROXY)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build()

        private fun fallbackCall(client: OkHttpClient, listener: EventListener = EventListener.NONE): Call =
            PluginConnectFallbackCall(
                client,
                listener,
                Request.Builder().url("http://plugin-lifecycle.test/").build(),
                PluginConnectFallback.MAX_ATTEMPTS,
            )

        private fun closeClient(client: OkHttpClient) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            assertTrue(client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
