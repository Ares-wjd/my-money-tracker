package com.mymoneytracker.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 외부 API 호출 결과. 오류 메시지는 사용자에게 그대로 보여줄 수 있는 문장이다. */
class ApiException(message: String) : Exception(message)

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, String>)

/** 간단한 JSON HTTP 호출 (백그라운드 스레드에서 호출할 것). */
object HttpJson {

    fun get(url: String, params: Map<String, String>, headers: Map<String, String>): HttpResponse =
        request("GET", url + "?" + query(params), headers, null)

    fun post(url: String, body: JSONObject, headers: Map<String, String>): HttpResponse =
        request("POST", url, headers, body.toString())

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key.lowercase() }
                .mapValues { it.value.firstOrNull().orEmpty() }
            return HttpResponse(code, text, responseHeaders)
        } finally {
            connection.disconnect()
        }
    }

    private fun query(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    fun JSONObject.str(name: String): String = optString(name, "").trim()

    fun JSONObject.num(name: String): Double? = optString(name, "").replace(",", "").trim().toDoubleOrNull()

    fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
}
