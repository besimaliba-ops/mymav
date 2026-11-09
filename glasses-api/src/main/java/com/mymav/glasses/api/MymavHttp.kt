package com.mymav.glasses.api

import java.net.HttpURLConnection
import java.net.URL

/** Small blocking HTTP helper for the assistant providers. Call off the main thread. Never logs headers or bodies. */
internal object MymavHttp {
    class Failure(val code: Int, message: String, val body: String = "") : Exception(message)

    fun request(
        method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 20_000,
    ): String {
        val host = runCatching { URL(url).host }.getOrDefault("server")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = timeoutMs
            doOutput = body != null
            setRequestProperty("User-Agent", "MyMav/1.0 (Everysight glasses companion)")
            if (body != null) setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Failure(code, "$host answered HTTP $code", text.take(4000))
            return text
        } catch (e: java.net.SocketTimeoutException) {
            throw Failure(-1, "Timed out waiting for $host")
        } catch (e: java.net.UnknownHostException) {
            throw Failure(-2, "No internet connection (can't reach $host)")
        } catch (e: java.io.IOException) {
            throw Failure(-3, "Network error talking to $host")
        } finally {
            conn.disconnect()
        }
    }

    /** GET returning raw bytes (vector map tiles). Transparent gzip is handled by the platform; a raw gzip body is unzipped here. */
    fun getBytes(url: String, timeoutMs: Int = 25_000): ByteArray {
        val host = runCatching { URL(url).host }.getOrDefault("server")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12_000; readTimeout = timeoutMs
            setRequestProperty("User-Agent", "MyMav/1.0 (Everysight glasses companion)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Failure(code, "$host answered HTTP $code")
            var b = conn.inputStream.use { it.readBytes() }
            if (b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) b = java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() }
            return b
        } catch (e: java.net.SocketTimeoutException) {
            throw Failure(-1, "Timed out waiting for $host")
        } catch (e: java.net.UnknownHostException) {
            throw Failure(-2, "No internet connection (can't reach $host)")
        } catch (e: java.io.IOException) {
            throw Failure(-3, "Network error talking to $host")
        } finally {
            conn.disconnect()
        }
    }
}
