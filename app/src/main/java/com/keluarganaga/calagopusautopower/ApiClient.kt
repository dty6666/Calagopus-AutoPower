package com.keluarganaga.calagopusautopower

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

class ApiClient(
    private var baseUrl: String,
    private var username: String,
    private var password: String
) {
    fun updateCredentials(baseUrl: String, username: String, password: String) {
        this.baseUrl = baseUrl.trimEnd('/')
        this.username = username
        this.password = password
    }

    private fun request(path: String, method: String = "GET", body: String? = null): String {
        val url = URI.create(baseUrl.trimEnd('/') + path).toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("X-AutoPower-Username", username)
            setRequestProperty("X-AutoPower-Password", password)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }

        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optString("error", text)
                } catch (_: Exception) { text }
                throw ApiException(code, message.ifBlank { "HTTP $code" })
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun status(): JSONObject = JSONObject(request("/api/status"))
    fun resources(): JSONObject = JSONObject(request("/api/resources"))
    fun config(): JSONObject = JSONObject(request("/api/config"))
    fun minecraftLogs(): String = request("/api/logs/minecraft")
    fun velocityLogs(): String = request("/api/logs/velocity")

    fun action(path: String): JSONObject = JSONObject(request(path, "POST"))

    fun command(command: String): JSONObject {
        val body = "command=" + URLEncoder.encode(command, "UTF-8")
        return JSONObject(request("/api/command", "POST", body))
    }

    fun saveConfig(values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return JSONObject(request("/api/config", "POST", body))
    }
}

class ApiException(val code: Int, message: String) : Exception(message)
