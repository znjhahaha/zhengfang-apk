package com.tyust.course.academic.plugin

import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Protocol
import okhttp3.Response
import okio.BufferedSink
import okio.Timeout

/**
 * OkHttp is configured with `retryOnConnectionFailure(false)` because plugin mutations must not be
 * replayed once the request has been sent. This wrapper only retries connection establishment:
 * a failed address is only followed by the next one if the request headers were never started.
 */
internal class PluginConnectFallbackCall(
    private val baseClient: OkHttpClient,
    private val baseListener: EventListener,
    private val request: Request,
    private val maxAttempts: Int,
) : Call {
    private val lock = Any()
    private var executed = false
    @Volatile
    private var canceled = false
    private var activeCall: Call? = null
    private val callTimeout = Timeout()
        .timeout(baseClient.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)

    init {
        require(maxAttempts > 0)
        require(!baseClient.retryOnConnectionFailure)
    }

    override fun request(): Request = request

    override fun execute(): Response {
        synchronized(lock) {
            check(!executed) { "Already Executed" }
            executed = true
            if (canceled) throw IOException("Canceled")
        }

        val deadline = deadlineAtStart()
        // OkHttp may follow a 503 Retry-After: 0 even with connection retries disabled.
        val wireRequest = request.body?.let { body ->
            request.newBuilder().method(request.method, object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isDuplex() = body.isDuplex()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }).build()
        } ?: request
        val attempted = mutableSetOf<InetAddress>()
        var addresses = emptyList<InetAddress>()
        for (attempt in 0 until maxAttempts) {
            requireTimeRemaining(deadline)
            val tracker = Tracker()
            val builder = baseClient.newBuilder().eventListener(tracker)
            builder.addNetworkInterceptor { chain ->
                // Bodyless requests may be mutations too. Block native follow-ups before send.
                if (tracker.requestSent) throw ProtocolException("Plugin HTTP requests cannot be replayed")
                chain.proceed(chain.request())
            }
            if (attempt > 0) {
                builder.dns(RemainingDns(request.url.host, addresses, baseClient.dns))
            }
            val attemptClient = builder.build()
            val call = attemptClient.newCall(wireRequest)
            // The native timeout stays active through response-body consumption.
            call.timeout().clearTimeout().clearDeadline()
            if (deadline != null) call.timeout().deadlineNanoTime(deadline)
            val cancelNow = synchronized(lock) {
                activeCall = call
                canceled
            }
            if (cancelNow) call.cancel()
            try {
                return call.execute()
            } catch (e: IOException) {
                release(call)
                val failed = tracker.failedAddress
                if (canceled || call.isCanceled() || tracker.requestSent || failed == null ||
                    !recoverable(e) || attempt + 1 == maxAttempts) throw e
                if (attempt == 0) addresses = tracker.addresses.distinct()
                attempted += failed
                addresses = addresses.filterNot { it in attempted }
                if (addresses.isEmpty()) throw e
            }
        }
        error("No connection attempt made")
    }

    private fun deadlineAtStart(): Long? {
        val now = System.nanoTime()
        val duration = callTimeout.timeoutNanos()
        val relativeDeadline = if (duration == 0L) null else now + duration
        if (!callTimeout.hasDeadline()) return relativeDeadline
        val explicitDeadline = callTimeout.deadlineNanoTime()
        return if (relativeDeadline == null || explicitDeadline - now < relativeDeadline - now)
            explicitDeadline else relativeDeadline
    }

    private fun requireTimeRemaining(deadline: Long?) {
        if (canceled) throw IOException("Canceled")
        if (deadline != null && deadline - System.nanoTime() <= 0) {
            // No native watchdog is active between attempts; preserve timeout cancellation here.
            cancel()
            throw InterruptedIOException("timeout")
        }
        callTimeout.throwIfReached()
    }

    private fun release(call: Call) {
        synchronized(lock) {
            if (activeCall === call) activeCall = null
        }
    }

    private fun recoverable(e: IOException): Boolean = when (e) {
        is ProtocolException -> false
        is InterruptedIOException -> e is SocketTimeoutException
        is SSLHandshakeException -> e.cause !is CertificateException
        is SSLPeerUnverifiedException -> false
        else -> true
    }

    override fun enqueue(responseCallback: okhttp3.Callback) {
        throw UnsupportedOperationException("Plugin HTTP calls execute synchronously")
    }

    override fun cancel() {
        val call = synchronized(lock) {
            canceled = true
            activeCall
        }
        call?.cancel()
    }

    override fun isExecuted(): Boolean = synchronized(lock) { executed }

    override fun isCanceled(): Boolean = synchronized(lock) { canceled }

    override fun timeout(): Timeout = callTimeout

    override fun clone(): Call = PluginConnectFallbackCall(baseClient, baseListener, request, maxAttempts)

    private inner class Tracker : EventListener() {
        var addresses = emptyList<InetAddress>()
            private set
        var failedAddress: InetAddress? = null
            private set
        var requestSent = false
            private set

        override fun callStart(call: Call) = baseListener.callStart(call)
        override fun dnsStart(call: Call, domainName: String) = baseListener.dnsStart(call, domainName)
        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            if (domainName == request.url.host) addresses = inetAddressList.toList()
            baseListener.dnsEnd(call, domainName, inetAddressList)
        }
        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
            baseListener.connectStart(call, inetSocketAddress, proxy)
        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException,
        ) {
            // Proxy routing and authentication remain under OkHttp's original no-retry policy.
            if (proxy.type() == Proxy.Type.DIRECT) failedAddress = inetSocketAddress.address
            baseListener.connectFailed(call, inetSocketAddress, proxy, protocol, ioe)
        }
        override fun secureConnectStart(call: Call) = baseListener.secureConnectStart(call)
        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) =
            baseListener.secureConnectEnd(call, handshake)
        override fun connectionAcquired(call: Call, connection: okhttp3.Connection) =
            baseListener.connectionAcquired(call, connection)
        override fun requestHeadersStart(call: Call) {
            requestSent = true
            baseListener.requestHeadersStart(call)
        }
        override fun responseHeadersStart(call: Call) = baseListener.responseHeadersStart(call)
        override fun responseHeadersEnd(call: Call, response: Response) =
            baseListener.responseHeadersEnd(call, response)
        override fun responseBodyEnd(call: Call, byteCount: Long) =
            baseListener.responseBodyEnd(call, byteCount)
        override fun canceled(call: Call) {
            canceled = true
            baseListener.canceled(call)
        }
        override fun callEnd(call: Call) {
            release(call)
            baseListener.callEnd(call)
        }
        override fun callFailed(call: Call, ioe: IOException) {
            release(call)
            baseListener.callFailed(call, ioe)
        }
    }

    private data class RemainingDns(
        val hostname: String,
        val addresses: List<InetAddress>,
        val delegate: Dns,
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == this.hostname) addresses else delegate.lookup(hostname)
    }
}

internal object PluginConnectFallback {
    const val MAX_ATTEMPTS = 4
}
