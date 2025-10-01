package com.analytic.atribution.gb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.collections.iterator

internal object HTTP {
    private const val TIMEOUT = 5000

    enum class Method(val value: String) {
        GET("GET"),
        POST("POST"),

    }

    class HTTPResponse(
        val body: String,
        val status: Int,
        val headers: Map<String, List<String>>
    )

    suspend fun post(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ) = request(urlStr, Method.POST, headers, body)

    suspend fun request(
        urlStr: String,
        method: Method = Method.GET,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HTTPResponse = withContext(Dispatchers.IO) {
        val url = URL(urlStr)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method.value
            this.connectTimeout = TIMEOUT
            this.readTimeout = TIMEOUT
            doOutput = body != null

            for ((key, value) in headers) {
                setRequestProperty(key, value)
            }
        }

        body?.let {
            connection.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(it)
                    writer.flush()
                }
            }
        }

        try {
            val inputStream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            connection.headerFields

            return@withContext HTTPResponse(
                inputStream.bufferedReader().use { it.readText() },
                connection.responseCode,
                connection.headerFields,
            )
        } finally {
            connection.disconnect()
        }
    }
}