package com.analytic.atribution.gb

internal class StatisticServerAPI(private val host: String) {
    // Use this IP to access localhost from AVD (and maybe other virtual devices)
    // "10.0.2.2"
    private companion object {
        const val API_VERSION = "v1"
    }

    private fun createURL(path: String): String {
        return "http://$host/$API_VERSION${if (path.startsWith("/")) path else "/$path"}"
    }

    suspend fun makeRequest(
        path: String,
        httpMethod: HTTP.Method,
        body: String,
        onSuccess: (() -> Unit)? = null
    ) {
        val url: String = createURL(path)
        try {
            val response = HTTP.request(
                urlStr = createURL(path),
                method = httpMethod,
                headers = mapOf("Content-Type" to "application/json"),
                body = "{\"payload\":\"" + Encryption.encrypt(body) + "\"}"
            )
            if (response.status == 200) {
                onSuccess?.invoke()
            } else {
                throw Exception("Http Status " + response.status.toString())
            }
        } catch (e: Exception) {
            throw Exception("Request to $url failed ($e)")
        }
    }
}
