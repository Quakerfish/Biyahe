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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private var currentProfileImageUrl: String? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { uploadAvatar(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClickListeners()
        setupProfileBottomNav()
    }

    private val isGuest: Boolean
        get() = getSharedPreferences("app_session", MODE_PRIVATE).getBoolean("is_guest", false)

    override fun onResume() {
        super.onResume()
        setupProfileBottomNav()
        updateAppearanceSummary()
        if (isGuest) {
            setupGuestProfileUI()
        } else {
            loadProfile()
        }
    }

    private fun setupGuestProfileUI() {
        binding.tvDisplayName.text = "Guest Commuter"
        binding.tvEmail.text = "Log in or sign up to save & rate routes"
        binding.tvMemberSince.visibility = View.GONE
        binding.btnEditProfile.visibility = View.GONE
        binding.btnChangeAvatar.isEnabled = false
        binding.tvStatSaved.text = "0"
        binding.tvStatRated.text = "0"
        binding.tvSavedValue.text = "Log in required"

        binding.btnLogout.text = "Log In or Sign Up"
        binding.btnLogout.setIconResource(R.drawable.ic_person)
        binding.btnLogout.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }
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
                    openSavedRoutes()
                    false
                }
                R.id.nav_settings -> true
                else -> false
            }
        }
    }

    private fun showGuestLoginPrompt(featureName: String = "save or rate routes") {
        AlertDialog.Builder(this)
            .setTitle("Log in required")
            .setMessage("Please log in or create an account to $featureName.")
            .setPositiveButton("Log In") { _, _ ->
                getSharedPreferences("app_session", MODE_PRIVATE).edit().remove("is_guest").apply()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSavedRoutes() {
        if (isGuest) {
            showGuestLoginPrompt("view your saved routes")
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("OPEN_ROUTES_SHEET", true)
            putExtra("FILTER_SAVED", true)
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        disableActivityTransitions()
    }

    private fun openRatedRoutes() {
        if (isGuest) {
            showGuestLoginPrompt("view your rated routes")
            return
        }
        val intent = Intent(this, RatingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        disableActivityTransitions()
    }

    private fun setupClickListeners() {
        binding.btnChangeAvatar.setOnClickListener { pickImageLauncher.launch("image/*") }

        binding.btnEditProfile.setOnClickListener {
            val intent = Intent(this, EditProfileActivity::class.java).apply {
                putExtra("EXTRA_USERNAME", binding.tvDisplayName.text.toString())
                putExtra("EXTRA_EMAIL", binding.tvEmail.text.toString())
                putExtra("EXTRA_AVATAR_URL", currentProfileImageUrl)
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            disableActivityTransitions()
        }

        binding.rowSavedPlaces.setOnClickListener { openSavedRoutes() }
        binding.btnStatSavedContainer.setOnClickListener { openSavedRoutes() }
        binding.btnStatRatedContainer.setOnClickListener { openRatedRoutes() }
        binding.rowAppearance.setOnClickListener { showAppearanceDialog() }
        binding.btnLogout.setOnClickListener { showLogoutConfirmationDialog() }
    }

    /** Loads the live profile (users + saved_routes + route_ratings tables) and fills the screen. */
    private fun loadProfile() {
        lifecycleScope.launch {
            when (val result = ProfileApi.fetchProfile(this@ProfileActivity)) {
                is ApiResult.Success -> showProfile(result.data)
                is ApiResult.Failure -> {
                    if (result.code == 401) {
                        performLocalLogout()
                    } else if (binding.tvEmail.text.isNullOrEmpty()) {
                        // First load failed and there is nothing on screen yet - say so once.
                        Toast.makeText(this@ProfileActivity, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showProfile(profile: UserProfile) {
        currentProfileImageUrl = profile.profileImageUrl

        binding.tvDisplayName.text = profile.username.ifBlank { "Commuter" }
        binding.tvEmail.text = profile.email

        val since = formatMemberSince(profile.dateCreated)
        binding.tvMemberSince.apply {
            if (since != null) {
                text = "Member since $since"
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }

        binding.tvStatSaved.text = profile.savedRoutesCount.toString()
        binding.tvStatRated.text = profile.ratingsCount.toString()
        binding.tvSavedValue.text = when (profile.savedRoutesCount) {
            0 -> "No saved routes yet"
            1 -> "1 saved route"
            else -> "${profile.savedRoutesCount} saved routes"
        }

        if (!profile.profileImageUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(profile.profileImageUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(binding.ivProfileAvatar)
        }
    }

    /** "2026-10-11T08:00:00+00:00" -> "October 2026" (null when the value can't be read). */
    private fun formatMemberSince(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.length < 10) return null
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw.substring(0, 10)) ?: return null
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(parsed)
        } catch (_: Exception) {
            null
        }
    }

    private fun uploadAvatar(uri: Uri) {
        // Preview straight away; it is replaced by the server's copy after the upload.
        Glide.with(this).load(uri).circleCrop().into(binding.ivProfileAvatar)
        setAvatarBusy(true)

        lifecycleScope.launch {
            val result = ProfileApi.uploadAvatar(this@ProfileActivity, uri)
            setAvatarBusy(false)
            when (result) {
                is ApiResult.Success -> {
                    Toast.makeText(this@ProfileActivity, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    loadProfile()
                }
                is ApiResult.Failure -> {
                    Toast.makeText(this@ProfileActivity, result.message, Toast.LENGTH_LONG).show()
                    // Put the previously saved picture (or the placeholder) back.
                    if (currentProfileImageUrl.isNullOrEmpty()) {
                        binding.ivProfileAvatar.setImageResource(R.drawable.ic_person)
                    } else {
                        Glide.with(this@ProfileActivity)
                            .load(currentProfileImageUrl)
                            .circleCrop()
                            .placeholder(R.drawable.ic_person)
                            .into(binding.ivProfileAvatar)
                    }
                }
            }
        }
    }

    private fun setAvatarBusy(busy: Boolean) {
        binding.avatarProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnChangeAvatar.isEnabled = !busy
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Logout") { _, _ -> performServerLogout() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performServerLogout() {
        binding.btnLogout.isEnabled = false
        lifecycleScope.launch {
            ProfileApi.logout(this@ProfileActivity)
            performLocalLogout()
        }
    }

    /** Clear local session data and reset the back stack to Login. */
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