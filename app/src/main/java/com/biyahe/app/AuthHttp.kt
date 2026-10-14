package com.biyahe.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Small shared helper for the login / OTP / forgot-password screens. */
object AuthHttp {
    private const val PREFS = "app_session"
    private const val KEY_COOKIE = "session_cookie"

    // Generous read timeout: sending the email happens inside these requests.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val main = Handler(Looper.getMainLooper())

    class Result(val code: Int, val json: JSONObject?, val networkError: String?) {
        val ok get() = networkError == null && code in 200..299 && json?.optBoolean("success", false) == true
        fun message(fallback: String): String = json?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback
    }

    /** Keep the signed session cookie the server hands back (pending-OTP, then real login). */
    private fun saveCookie(context: Context, response: Response) {
        val raw = response.headers("Set-Cookie")
            .firstOrNull { it.startsWith("biyahe_session=") }
            ?.substringBefore(";") ?: return
        if (raw.length <= "biyahe_session=".length) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_COOKIE, raw).apply()
    }

    fun clearCookie(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_COOKIE).apply()
    }

    /** POST JSON; [onResult] always runs on the main thread. */
    fun post(context: Context, url: String, body: JSONObject, sendCookie: Boolean, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        if (sendCookie) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIE, null)
                ?.let { builder.header("Cookie", it) }
        }
        client.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { onResult(Result(0, null, e.localizedMessage ?: "network error")) }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = it.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (_: Exception) { null }
                    if (it.isSuccessful) saveCookie(app, it)
                    main.post { onResult(Result(it.code, json, if (json == null) "bad response" else null)) }
                }
            }
        })
    }
}