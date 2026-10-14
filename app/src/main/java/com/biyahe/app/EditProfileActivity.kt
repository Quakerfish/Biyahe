package com.biyahe.app

import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.biyahe.app.databinding.ActivityEditProfileBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

class EditProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityEditProfileBinding
    private var selectedImageUri: Uri? = null
    private var originalUsername = ""
    private var originalEmail = ""

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectedImageUri = selectedUri
            Glide.with(this).load(selectedUri).circleCrop().into(binding.ivEditAvatar)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        originalUsername = intent.getStringExtra("EXTRA_USERNAME").orEmpty()
        originalEmail = intent.getStringExtra("EXTRA_EMAIL").orEmpty()
        val currentAvatarUrl = intent.getStringExtra("EXTRA_AVATAR_URL")
            ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

        binding.etUsername.setText(originalUsername)
        binding.etEmail.setText(originalEmail)

        if (!currentAvatarUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(currentAvatarUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(binding.ivEditAvatar)
        }

        binding.btnChangeAvatar.setOnClickListener { pickImageLauncher.launch("image/*") }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { validateThenConfirm() }
    }

    private fun clearErrors() {
        binding.etUsername.error = null
        binding.etEmail.error = null
        binding.etCurrentPassword.error = null
        binding.etNewPassword.error = null
        binding.etConfirmPassword.error = null
    }

    /** Checks everything on the phone first, so obvious mistakes never cost a network round trip. */
    private fun validateThenConfirm() {
        clearErrors()

        val username = binding.etUsername.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val current = binding.etCurrentPassword.text.toString()
        val newPass = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()
        val changingPassword = current.isNotEmpty() || newPass.isNotEmpty() || confirm.isNotEmpty()

        if (username.length !in 3..50) {
            binding.etUsername.error = "Username must be 3 to 50 characters"
            binding.etUsername.requestFocus()
            return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email address"
            binding.etEmail.requestFocus()
            return
        }
        if (changingPassword) {
            if (current.isEmpty()) {
                binding.etCurrentPassword.error = "Enter your current password"
                binding.etCurrentPassword.requestFocus()
                return
            }
            if (newPass.length < 8) {
                binding.etNewPassword.error = "At least 8 characters"
                binding.etNewPassword.requestFocus()
                return
            }
            if (newPass == current) {
                binding.etNewPassword.error = "Must be different from your current password"
                binding.etNewPassword.requestFocus()
                return
            }
            if (newPass != confirm) {
                binding.etConfirmPassword.error = "Passwords do not match"
                binding.etConfirmPassword.requestFocus()
                return
            }
        }

        val detailsChanged = username != originalUsername || email != originalEmail
        if (!detailsChanged && !changingPassword && selectedImageUri == null) {
            Toast.makeText(this, "You haven't changed anything.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Save Changes?")
            .setMessage("Do you want to save your changes?")
            .setPositiveButton("Save") { _, _ ->
                saveChanges(username, email, if (changingPassword) current else null, if (changingPassword) newPass else null, detailsChanged)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSaving(saving: Boolean) {
        binding.btnSave.isEnabled = !saving
        binding.btnSave.text = if (saving) "Saving..." else "Save Changes"
    }

    private fun saveChanges(
        username: String,
        email: String,
        currentPassword: String?,
        newPassword: String?,
        detailsChanged: Boolean
    ) {
        setSaving(true)

        lifecycleScope.launch {
            // 1. Profile picture (only if a new one was picked). A failure here doesn't block the rest.
            var avatarError: String? = null
            selectedImageUri?.let { uri ->
                val upload = ProfileApi.uploadAvatar(this@EditProfileActivity, uri)
                if (upload is ApiResult.Failure) avatarError = upload.message
            }

            // 2. Username / email / password (only if any of those changed).
            if (detailsChanged || newPassword != null) {
                when (val result = ProfileApi.updateProfile(this@EditProfileActivity, username, email, currentPassword, newPassword)) {
                    is ApiResult.Success -> {
                        val note = avatarError?.let { "\nYour picture was not updated: $it" } ?: ""
                        Toast.makeText(this@EditProfileActivity, result.data + note, Toast.LENGTH_LONG).show()
                        finish()
                    }
                    is ApiResult.Failure -> {
                        setSaving(false)
                        showSaveError(result.message)
                    }
                }
            } else {
                setSaving(false)
                if (avatarError == null) {
                    Toast.makeText(this@EditProfileActivity, "Profile picture updated!", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    Toast.makeText(this@EditProfileActivity, avatarError, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Puts the server's complaint next to the field it is about, when we can tell which one. */
    private fun showSaveError(message: String) {
        val lower = message.lowercase()
        when {
            "current password" in lower -> {
                binding.etCurrentPassword.error = message
                binding.etCurrentPassword.requestFocus()
            }
            "password" in lower -> {
                binding.etNewPassword.error = message
                binding.etNewPassword.requestFocus()
            }
            "username" in lower && "email" !in lower -> {
                binding.etUsername.error = message
                binding.etUsername.requestFocus()
            }
            "email" in lower && "username" !in lower -> {
                binding.etEmail.error = message
                binding.etEmail.requestFocus()
            }
            else -> Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}