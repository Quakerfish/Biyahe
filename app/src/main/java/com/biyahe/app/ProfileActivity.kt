package com.biyahe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
        setupProfileBottomNav()
    }

    override fun onResume() {
        super.onResume()
        setupProfileBottomNav()
        updateAppearanceSummary()
        fetchUserProfile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setupProfileBottomNav()
    }

    private fun setupProfileBottomNav() {
        setupBottomNav(binding.bottomNav, R.id.nav_settings)
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    navigateToTab(MainActivity::class.java)
                    false
                }
                R.id.nav_routes -> {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("OPEN_ROUTES_SHEET", true)
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    disableActivityTransitions()
                    false
                }
                R.id.nav_settings -> {
                    true
                }
                else -> false
            }
        }
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
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("OPEN_ROUTES_SHEET", true)
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            disableActivityTransitions()
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
                        val rawUrl = if (json.has("profile_image") && !json.isNull("profile_image")) {
                            json.getString("profile_image")
                        } else null
                        val profileImageUrl = if (rawUrl?.startsWith("http://", ignoreCase = true) == true ||
                            rawUrl?.startsWith("https://", ignoreCase = true) == true
                        ) {
                            rawUrl
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
                val imageBytes = inputStream.use { it.readBytes() }
                val mimeType = contentResolver.getType(fileUri) ?: "image/jpeg"
                val mediaType = mimeType.toMediaTypeOrNull()

                // Previously hardcoded "avatar.jpg" regardless of the real
                // file type picked - matching the extension to the actual
                // MIME type is more correct even though the server already
                // reads the real content type separately.
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

                Log.d("ProfileActivity", "Uploading avatar with cookie: $cookie")

                val requestBuilder = Request.Builder()
                    .url(ApiConfig.UPLOAD_AVATAR_URL)
                    .post(requestBody)

                if (!cookie.isNullOrEmpty()) {
                    requestBuilder.addHeader("Cookie", cookie)
                }

                val client = OkHttpClient()
                val response = client.newCall(requestBuilder.build()).execute()
                val responseCode = response.code
                val responseText = response.body?.string() ?: ""
                Log.d("ProfileActivity", "Upload response ($responseCode): $responseText")

                val json = try { JSONObject(responseText) } catch (_: Exception) { null }

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && json?.optBoolean("success", false) == true) {
                        Toast.makeText(this@ProfileActivity, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                        fetchUserProfile()
                    } else {
                        val message = json?.optString("message")
                            ?: json?.optJSONArray("detail")?.optJSONObject(0)?.optString("msg")
                            ?: "Failed to upload image (HTTP $responseCode)."
                        Toast.makeText(this@ProfileActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("ProfileActivity", "Upload exception", e)
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
                val url = URL(ApiConfig.LOGOUT_URL)
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

    private fun extensionForMimeType(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg" // covers image/jpeg, and any unrecognized type as a safe fallback
    }
}