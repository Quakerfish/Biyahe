package com.biyahe.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What GET /api/profile returns. Everything here comes straight from the users / saved_routes / route_ratings tables. */
data class UserProfile(
    val userId: Int,
    val username: String,
    val email: String,
    val profileImageUrl: String?,
    val dateCreated: String?,
    val savedRoutesCount: Int,
    val ratingsCount: Int
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val message: String, val code: Int = 0) : ApiResult<Nothing>()
}

/**
 * All profile-related network calls in one place, so ProfileActivity and
 * EditProfileActivity don't each carry their own copy of the cookie /
 * multipart / error-parsing code.
 */
object ProfileApi {

    const val MAX_AVATAR_BYTES = 5 * 1024 * 1024
    private val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun Request.Builder.withSession(context: Context): Request.Builder {
        val cookie = context.getSharedPreferences("app_session", Context.MODE_PRIVATE)
            .getString("session_cookie", null)
        if (!cookie.isNullOrEmpty()) addHeader("Cookie", cookie)
        return this
    }

    /** Pulls a human-readable message out of the API's error shapes ({message}, {detail: "..."} or FastAPI's {detail: [{msg}]}). */
    private fun messageFrom(body: String, code: Int, fallback: String): String {
        val json = try { JSONObject(body) } catch (_: Exception) { return "$fallback (HTTP $code)" }
        val message = json.optString("message", "")
        if (message.isNotBlank()) return message
        when (val detail = json.opt("detail")) {
            is String -> if (detail.isNotBlank()) return detail
            is JSONArray -> {
                val msg = detail.optJSONObject(0)?.optString("msg", "") ?: ""
                if (msg.isNotBlank()) return msg.removePrefix("Value error, ")
            }
        }
        return "$fallback (HTTP $code)"
    }

    suspend fun fetchProfile(context: Context): ApiResult<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(ApiConfig.GET_PROFILE_URL).get().withSession(context).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure(messageFrom(body, response.code, "Could not load profile"), response.code)
                }
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) {
                    return@withContext ApiResult.Failure("Could not load profile.", response.code)
                }
                val rawImage = if (json.isNull("profile_image")) null else json.optString("profile_image", "")
                val image = rawImage?.takeIf {
                    it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
                }
                ApiResult.Success(
                    UserProfile(
                        userId = json.optInt("user_id"),
                        username = json.optString("username", ""),
                        email = json.optString("email", ""),
                        profileImageUrl = image,
                        dateCreated = if (json.isNull("date_created")) null else json.optString("date_created", ""),
                        savedRoutesCount = json.optInt("saved_routes_count", 0),
                        ratingsCount = json.optInt("ratings_count", 0)
                    )
                )
            }
        } catch (e: IOException) {
            ApiResult.Failure("Network error. Check your connection and try again.")
        } catch (e: Exception) {
            ApiResult.Failure("Something went wrong: ${e.localizedMessage}")
        }
    }

    /** Updates username/email, and the password too when [newPassword] is not null. */
    suspend fun updateProfile(
        context: Context,
        username: String,
        email: String,
        currentPassword: String?,
        newPassword: String?
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("username", username)
                put("email", email)
                if (!newPassword.isNullOrEmpty()) {
                    put("current_password", currentPassword ?: "")
                    put("new_password", newPassword)
                }
            }
            val request = Request.Builder()
                .url(ApiConfig.UPDATE_PROFILE_URL)
                .put(payload.toString().toRequestBody(JSON_TYPE))
                .withSession(context)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val ok = response.isSuccessful && (try { JSONObject(body).optBoolean("success", false) } catch (_: Exception) { false })
                if (ok) {
                    ApiResult.Success(JSONObject(body).optString("message", "Profile updated successfully!"))
                } else {
                    ApiResult.Failure(messageFrom(body, response.code, "Could not save changes"), response.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure("Network error. Check your connection and try again.")
        } catch (e: Exception) {
            ApiResult.Failure("Something went wrong: ${e.localizedMessage}")
        }
    }

    /** Uploads a new profile picture; returns the new image URL on success. */
    suspend fun uploadAvatar(context: Context, uri: Uri): ApiResult<String?> = withContext(Dispatchers.IO) {
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mimeType.lowercase() !in ALLOWED_IMAGE_TYPES) {
                return@withContext ApiResult.Failure("Please choose a JPG, PNG or WEBP image.")
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ApiResult.Failure("Could not read that image.")
            if (bytes.size > MAX_AVATAR_BYTES) {
                return@withContext ApiResult.Failure("That image is too large. Please pick one under 5 MB.")
            }
            val extension = when (mimeType.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("profile_image", "avatar.$extension", bytes.toRequestBody(mimeType.toMediaTypeOrNull()))
                .build()
            val request = Request.Builder().url(ApiConfig.UPLOAD_AVATAR_URL).post(body).withSession(context).build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { null }
                if (response.isSuccessful && json != null && json.optBoolean("success", false)) {
                    ApiResult.Success(json.optString("profile_image", "").takeIf { it.isNotBlank() })
                } else {
                    ApiResult.Failure(messageFrom(text, response.code, "Could not upload the picture"), response.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure("Network error. Check your connection and try again.")
        } catch (e: Exception) {
            ApiResult.Failure("Upload failed: ${e.localizedMessage}")
        }
    }

    /** Best effort - the caller clears local session data regardless of the outcome. */
    suspend fun logout(context: Context) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(ApiConfig.LOGOUT_URL)
                .post("".toRequestBody(null))
                .withSession(context)
                .build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
        }
    }
}