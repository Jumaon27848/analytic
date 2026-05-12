package com.analytic.atribution.gb

internal class StatisticServerAPI(
    private val host: String,
    private val secure: Boolean,
) {
    // Use this IP to access localhost from AVD (and maybe other virtual devices)
    // "10.0.2.2"
    private companion object {
        const val API_VERSION = "v1"
    }

    private fun createURL(path: String): String {
        val protocol = if (secure) "https" else "http"
        return "$protocol://$host/$API_VERSION${if (path.startsWith("/")) path else "/$path"}"
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

    suspend fun fetchAppsFlyerConfig(packageName: String): String {
        return fetchEncryptedConfig("/packages/$packageName/appsflyer_config")
    }

    suspend fun fetchClarityConfig(packageName: String): String {
        return fetchEncryptedConfig("/packages/$packageName/clarity_config")
    }

    private suspend fun fetchEncryptedConfig(path: String): String {
        val url = createURL(path)
        val response = HTTP.request(
            urlStr = url,
            method = HTTP.Method.GET,
            headers = mapOf("Content-Type" to "application/json"),
        )
        if (response.status != 200) {
            throw Exception("Http Status ${response.status} for $url")
        }
        val payload = org.json.JSONObject(response.body).getString("payload")
        return Encryption.decrypt(payload)
    }
}
