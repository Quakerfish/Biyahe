package com.biyahe.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biyahe.app.databinding.ActivityEditProfileBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

class EditProfileActivity : AppCompatActivity() {

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
                // 1. Upload avatar if a new one was selected
                selectedImageUri?.let { uri ->
                    uploadAvatarSync(uri)
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
                        Toast.makeText(this@EditProfileActivity, message, Toast.LENGTH_SHORT).show()
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

    private fun uploadAvatarSync(fileUri: Uri) {
        val inputStream = contentResolver.openInputStream(fileUri) ?: return
        val boundary = "*****" + System.currentTimeMillis() + "*****"
        val url = URL(ApiConfig.UPLOAD_AVATAR_URL)

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            val cookie = getSharedPreferences("app_session", MODE_PRIVATE)
                .getString("session_cookie", null)
            if (!cookie.isNullOrEmpty()) {
                setRequestProperty("Cookie", cookie)
            }
        }

        val outputStream = DataOutputStream(connection.outputStream)
        outputStream.writeBytes("--$boundary\r\n")
        outputStream.writeBytes("Content-Disposition: form-data; name=\"profile_image\"; filename=\"avatar.jpg\"\r\n")
        outputStream.writeBytes("Content-Type: image/jpeg\r\n\r\n")

        inputStream.copyTo(outputStream)

        outputStream.writeBytes("\r\n--$boundary--\r\n")
        outputStream.flush()
        outputStream.close()
        connection.responseCode
    }
}
