package dev.aiui.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Marker tag: this request may use plain HTTP to a public host. */
object InsecureAllowed

class CleartextBlockedException(val host: String) :
    IOException("Plain HTTP to $host is blocked. Use https, or turn on Allow insecure connection for this profile.")

/** Private ranges, loopback, link-local, carrier-grade NAT (Tailscale) and local DNS names. */
fun isLocalNetworkHost(host: String): Boolean {
    val h = host.lowercase().trim('[', ']')
    if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
    if (h.contains(':')) {
        return h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    }
    val octets = h.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 10 ||
        a == 127 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 169 && b == 254) ||
        (a == 100 && b in 64..127)
}

class LanOnlyCleartextInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (!url.isHttps && request.tag(InsecureAllowed::class.java) == null && !isLocalNetworkHost(url.host)) {
            throw CleartextBlockedException(url.host)
        }
        return chain.proceed(request)
    }
}

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    // Reasoning models can stay silent for a long time before the first token.
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .addInterceptor(LanOnlyCleartextInterceptor())
    .build()

fun joinUrl(base: String, path: String): String = base.trim().trimEnd('/') + path

fun Request.Builder.applyEndpoint(endpoint: Endpoint): Request.Builder {
    if (endpoint.allowInsecure) tag(InsecureAllowed::class.java, InsecureAllowed)
    endpoint.extraHeaders.forEach { (name, value) -> header(name, value) }
    return this
}

/** A request to [url] that sends the endpoint's key as a bearer token. */
fun bearerRequest(endpoint: Endpoint, url: String): Request.Builder {
    val builder = Request.Builder().url(url)
    endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
    return builder.applyEndpoint(endpoint)
}

/** Runs [request] and hands a successful response to [read]; anything else fails as an [LlmException]. */
suspend fun <T> OkHttpClient.fetch(request: Request, read: (Response) -> T): T {
    val response = try {
        newCall(request).await()
    } catch (e: Throwable) {
        throw mapThrowable(e)
    }
    response.use {
        if (!it.isSuccessful) throw httpError(it)
        return read(it)
    }
}

/** Generated files usually sit on a storage host, so the API key is not sent with them. */
suspend fun OkHttpClient.download(endpoint: Endpoint, url: String): ByteArray {
    val request = Request.Builder().url(url).get()
    if (endpoint.allowInsecure) request.tag(InsecureAllowed::class.java, InsecureAllowed)
    return fetch(request.build()) { it.body.bytes() }
}

fun validateUrl(url: String): String? {
    val parsed = url.trim().toHttpUrlOrNull() ?: return "Enter a full URL starting with https:// or http://"
    if (parsed.host.isBlank()) return "The URL has no host"
    return null
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}

/** Runs [call], hands each SSE event to [onEvent], and cancels the call when the collector goes away. */
fun <T> sseFlow(call: Call, onEvent: suspend (SseEvent, emit: suspend (T) -> Unit) -> Boolean, onEnd: suspend (emit: suspend (T) -> Unit) -> Unit): Flow<T> =
    callbackFlow {
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw httpError(response)
                    val source = response.body.source()
                    val parser = SseParser()
                    val emit: suspend (T) -> Unit = { send(it) }
                    var finished = false
                    while (!finished) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.feed(line) ?: continue
                        finished = onEvent(event, emit)
                    }
                    if (!finished) {
                        parser.finish()?.let { finished = onEvent(it, emit) }
                    }
                    if (!finished) onEnd(emit)
                }
                close()
            } catch (e: Throwable) {
                close(mapThrowable(e))
            }
        }
        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

fun mapThrowable(e: Throwable): Throwable = when (e) {
    is LlmException -> e
    is kotlinx.coroutines.CancellationException -> e
    is CleartextBlockedException -> LlmException(ErrorKind.BLOCKED, e.message.orEmpty(), cause = e)
    is UnknownHostException -> LlmException(ErrorKind.NETWORK, "Can't find the server ${e.message.orEmpty()}. Check the base URL and your connection.", cause = e)
    is SocketTimeoutException -> LlmException(ErrorKind.NETWORK, "The server stopped responding (timed out).", cause = e)
    is SSLException -> LlmException(ErrorKind.NETWORK, "Secure connection failed: ${e.message.orEmpty()}", cause = e)
    is IOException -> LlmException(ErrorKind.NETWORK, "Connection problem: ${e.message ?: e.javaClass.simpleName}", cause = e)
    else -> LlmException(ErrorKind.STREAM, e.message ?: e.javaClass.simpleName, cause = e)
}

/** Builds an error from a non-2xx response, keeping the server's own message. */
fun httpError(response: Response): LlmException {
    val code = response.code
    val body = runCatching { response.body.string() }.getOrDefault("")
    val serverMessage = extractErrorMessage(body) ?: body.take(500).ifBlank { response.message }
    val retryAfter = response.header("retry-after")?.toLongOrNull()
    val kind = when (code) {
        401, 403 -> ErrorKind.AUTH
        404 -> ErrorKind.NOT_FOUND
        408, 409, 429 -> ErrorKind.RATE_LIMIT
        in 500..599 -> ErrorKind.SERVER
        else -> ErrorKind.BAD_REQUEST
    }
    val prefix = when (kind) {
        ErrorKind.AUTH -> "The API key was rejected ($code)"
        ErrorKind.NOT_FOUND -> "Not found at ${response.request.url} ($code)"
        ErrorKind.RATE_LIMIT -> if (code == 429) "Rate limited (429)" else "Request conflict ($code)"
        ErrorKind.SERVER -> "Server error ($code)"
        else -> "Request rejected ($code)"
    }
    return LlmException(kind, "$prefix: $serverMessage", httpCode = code, retryAfterSeconds = retryAfter)
}

fun extractErrorMessage(body: String): String? = runCatching {
    val root = LenientJson.parseToJsonElement(body).jsonObject
    val error = root["error"]
    when {
        error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
        error != null -> error.jsonPrimitive.contentOrNull
        else -> root["message"]?.jsonPrimitive?.contentOrNull ?: root["detail"]?.toString()
    }
}.getOrNull()
