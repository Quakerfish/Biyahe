package com.biyahe.app

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.biyahe.app.databinding.ActivityEditProfileBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EditProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private var selectedImageUri: Uri? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectedImageUri = selectedUri
            Glide.with(this)
                .load(selectedUri)
                .circleCrop()
                .into(binding.ivEditAvatar)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUsername = intent.getStringExtra("EXTRA_USERNAME") ?: ""
        val currentEmail = intent.getStringExtra("EXTRA_EMAIL") ?: ""
        val currentAvatarUrl = intent.getStringExtra("EXTRA_AVATAR_URL")
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

        binding.etUsername.setText(currentUsername)
        binding.etEmail.setText(currentEmail)

        if (!currentAvatarUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(currentAvatarUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(binding.ivEditAvatar)
        }

        binding.btnChangeAvatar.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { showSaveConfirmationDialog() }
    }

    private fun showSaveConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Save Changes?")
            .setMessage("Do you want to save changes?")
            .setPositiveButton("Save") { _, _ -> saveChanges() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveChanges() {
        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val currentPassword = binding.etCurrentPassword.text.toString().trim()
        val newPassword = binding.etNewPassword.text.toString().trim()

        if (username.isEmpty() || email.isEmpty()) {
            Toast.makeText(this, "Username and email cannot be empty.", Toast.LENGTH_SHORT).show()
            return
        }

        if (newPassword.isNotEmpty() && currentPassword.isEmpty()) {
            Toast.makeText(this, "Please enter your current password to set a new one.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Upload avatar if a new one was selected. Previously this
                // result was discarded - if the avatar upload silently
                // failed, the user still saw "Profile updated successfully"
                // because only the username/email save below was checked.
                var avatarUploadFailed = false
                selectedImageUri?.let { uri ->
                    avatarUploadFailed = !uploadAvatarSync(uri)
                }

                // 2. Update profile details
                val url = URL(ApiConfig.UPDATE_PROFILE_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; utf-8")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000

                    val cookie = getSharedPreferences("app_session", MODE_PRIVATE)
                        .getString("session_cookie", null)
                    if (!cookie.isNullOrEmpty()) {
                        setRequestProperty("Cookie", cookie)
                    }
                }

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("email", email)
                    put("current_password", currentPassword)
                    put("new_password", newPassword)
                }

                connection.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = JSONObject(responseText)
                val message = json.optString("message", "An error occurred.")

                withContext(Dispatchers.Main) {
                    binding.btnSave.isEnabled = true
                    if (responseCode == HttpURLConnection.HTTP_OK && json.optBoolean("success", false)) {
                        val finalMessage = if (avatarUploadFailed) {
                            "$message (Your profile picture, however, failed to upload - please try that again.)"
                        } else {
                            message
                        }
                        Toast.makeText(this@EditProfileActivity, finalMessage, Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        Toast.makeText(this@EditProfileActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.btnSave.isEnabled = true
                    Toast.makeText(this@EditProfileActivity, "Network error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Returns true on a confirmed successful upload, false on any failure (network, non-2xx, or success:false in the response body). */
    private fun uploadAvatarSync(fileUri: Uri): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(fileUri) ?: return false
            val imageBytes = inputStream.use { it.readBytes() }
            val mimeType = contentResolver.getType(fileUri) ?: "image/jpeg"
            val mediaType = mimeType.toMediaTypeOrNull()

            // Previously hardcoded "avatar.jpg" regardless of the real file
            // type - a PNG/WEBP pick would still be tagged .jpg server-side.
            // The server reads the actual content type separately, so this
            // was cosmetic rather than a visible bug, but it's still wrong:
            // match the filename's extension to what was actually picked.
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "profile_image",
                    "avatar.${extensionForMimeType(mimeType)}",
                    imageBytes.toRequestBody(mediaType)
                )
                .build()

            val cookie = getSharedPreferences("app_session", MODE_PRIVATE)
                .getString("session_cookie", null)

            val requestBuilder = Request.Builder()
                .url(ApiConfig.UPLOAD_AVATAR_URL)
                .post(requestBody)

            if (!cookie.isNullOrEmpty()) {
                requestBuilder.addHeader("Cookie", cookie)
            }

            val client = OkHttpClient()
            val response = client.newCall(requestBuilder.build()).execute()
            val responseText = response.body?.string() ?: ""
            Log.d("EditProfileActivity", "Upload response (${response.code}): $responseText")

            response.isSuccessful &&
                    (try { JSONObject(responseText).optBoolean("success", false) } catch (_: Exception) { false })
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("EditProfileActivity", "Upload exception", e)
            false
        }
    }

    private fun extensionForMimeType(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg" // covers image/jpeg, and any unrecognized type as a safe fallback
    }
}