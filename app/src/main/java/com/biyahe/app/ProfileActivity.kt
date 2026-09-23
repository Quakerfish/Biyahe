package com.biyahe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.biyahe.app.databinding.ActivityProfileBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var currentProfileImageUrl: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            Glide.with(this@ProfileActivity)
                .load(selectedUri)
                .circleCrop()
                .into(binding.ivProfileAvatar)

            uploadAvatarToServer(selectedUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        setupBottomNav(binding.bottomNav, R.id.nav_profile)
    }

    override fun onResume() {
        super.onResume()
        setupBottomNav(binding.bottomNav, R.id.nav_profile)
        updateAppearanceSummary()
        fetchUserProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupBottomNav(binding.bottomNav, R.id.nav_profile)
    }

    private fun setupClickListeners() {
        binding.btnChangeAvatar.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnEditProfile.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java).apply {
                putExtra("EXTRA_USERNAME", binding.tvFullName.text.toString())
                putExtra("EXTRA_EMAIL", binding.tvUsername.text.toString())
                putExtra("EXTRA_AVATAR_URL", currentProfileImageUrl)
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            disableActivityTransitions()
        }

        binding.rowSavedPlaces.setOnClickListener {
            navigateToTab(SavedActivity::class.java)
        }

        binding.rowPreferences.setOnClickListener {
            Toast.makeText(this, "Transit Preferences coming soon.", Toast.LENGTH_SHORT).show()
        }

        binding.rowAppearance.setOnClickListener {
            showAppearanceDialog()
        }

        binding.rowNotifications.setOnClickListener {
            Toast.makeText(this, "Notifications coming soon.", Toast.LENGTH_SHORT).show()
        }

        binding.rowHelp.setOnClickListener {
            Toast.makeText(this, "Help & Support coming soon.", Toast.LENGTH_SHORT).show()
        }

        binding.btnLogout.setOnClickListener {
            showLogoutConfirmationDialog()
        }
    }

    /** Fetch live user details from PostgreSQL database endpoint */
    private fun fetchUserProfile() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(ApiConfig.GET_PROFILE_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000

                    val cookie = getSharedPreferences("app_session", MODE_PRIVATE)
                        .getString("session_cookie", null)
                    if (!cookie.isNullOrEmpty()) {
                        setRequestProperty("Cookie", cookie)
                    }
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)

                    if (json.optBoolean("success", false)) {
                        val username = json.optString("username", "")
                        val email = json.optString("email", "")
                        val profileImageUrl = if (json.has("profile_image") && !json.isNull("profile_image")) {
                            json.getString("profile_image")
                        } else null

                        currentProfileImageUrl = profileImageUrl

                        withContext(Dispatchers.Main) {
                            // 1. Primary Name: Username
                            binding.tvFullName.text = username.ifBlank { "Commuter" }

                            // 2. Secondary Name: Email address directly below
                            binding.tvUsername.text = email

                            // 3. Hide extra email text field if not used
                            binding.tvEmail.text = ""
                            binding.tvEmail.visibility = View.GONE

                            if (!profileImageUrl.isNullOrEmpty()) {
                                Glide.with(this@ProfileActivity)
                                    .load(profileImageUrl)
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_person)
                                    .into(binding.ivProfileAvatar)
                            }
                        }
                    }
                } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    withContext(Dispatchers.Main) {
                        performLocalLogout()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun uploadAvatarToServer(fileUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(fileUri) ?: return@launch
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

                val responseCode = connection.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val json = JSONObject(responseText)

                withContext(Dispatchers.Main) {
                    if (responseCode == HttpURLConnection.HTTP_OK && json.optBoolean("success", false)) {
                        Toast.makeText(this@ProfileActivity, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    } else {
                        val message = json.optString("message", "Failed to upload image.")
                        Toast.makeText(this@ProfileActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProfileActivity, "Upload failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Logout confirmation dialog */
    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ -> performServerLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Invalidate session on backend and route to LoginActivity */
    private fun performServerLogout() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("${ApiConfig.GET_PROFILE_URL}?action=logout")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000
                    readTimeout = 5000

                    val cookie = getSharedPreferences("app_session", MODE_PRIVATE)
                        .getString("session_cookie", null)
                    if (!cookie.isNullOrEmpty()) {
                        setRequestProperty("Cookie", cookie)
                    }
                }
                connection.responseCode // Triggers the HTTP request
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    performLocalLogout()
                }
            }
        }
    }

    /** Clear local persistent preferences and reset stack to Login */
    private fun performLocalLogout() {
        getSharedPreferences("app_session", MODE_PRIVATE).edit().clear().apply()
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showAppearanceDialog() {
        val options = arrayOf(
            getString(R.string.menu_appearance_desc_light),
            getString(R.string.menu_appearance_desc_dark),
            getString(R.string.menu_appearance_desc_system)
        )
        val current = ThemeManager.getMode(this)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_appearance_title)
            .setSingleChoiceItems(options, current) { dialog, which ->
                ThemeManager.setMode(this, which)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateAppearanceSummary() {
        binding.tvAppearanceValue.setText(ThemeManager.labelFor(ThemeManager.getMode(this)))
    }
}
