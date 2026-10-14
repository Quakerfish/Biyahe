package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import com.biyahe.app.databinding.ActivityForgotPasswordBinding
import org.json.JSONObject

/**
 * Two steps on one screen: (1) ask the server to email a reset code,
 * (2) enter that code + a new password. The server creates, emails (EmailJS)
 * and checks the code; the app never sees it.
 */
class ForgotPasswordActivity : BaseActivity() {

    private lateinit var binding: ActivityForgotPasswordBinding
    private var resendTimer: CountDownTimer? = null
    private var email: String = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.getStringExtra(EXTRA_EMAIL)?.let { binding.etFpEmail.setText(it) }

        binding.tvBackToLogin.setOnClickListener { finish() }
        binding.btnSendCode.setOnClickListener { requestCode(isResend = false) }
        binding.etFpEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                requestCode(isResend = false); true
            } else false
        }
        binding.btnResetPassword.setOnClickListener { attemptReset() }
        binding.etFpConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                attemptReset(); true
            } else false
        }
    }

    private fun requestCode(isResend: Boolean) {
        if (busy) return
        val typed = (if (isResend) email else binding.etFpEmail.text.toString()).trim()
        binding.etFpEmail.error = null
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(typed).matches()) {
            binding.etFpEmail.error = "Enter a valid email address"
            binding.etFpEmail.requestFocus()
            return
        }

        setBusy(true)
        AuthHttp.post(this, ApiConfig.FORGOT_PASSWORD_URL, JSONObject().put("email", typed), sendCookie = false) { result ->
            setBusy(false)
            when {
                result.networkError != null -> showNetworkError(result.networkError)
                result.ok -> {
                    email = typed
                    // Same wording whether or not the account exists (the server does not say).
                    Toast.makeText(this, result.message("Code sent."), Toast.LENGTH_LONG).show()
                    showResetStep()
                    startResendCountdown(result.json?.optInt("resend_cooldown_seconds", 45) ?: 45)
                }
                else -> Toast.makeText(this, result.message(getString(R.string.error_generic)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showResetStep() {
        binding.llStepEmail.visibility = View.GONE
        binding.llStepReset.visibility = View.VISIBLE
        binding.tvFpTitle.text = "Reset password"
        binding.tvFpSubtitle.text = "If $email has an account, a 6-digit code was sent. Enter it below with your new password."
        binding.etFpCode.requestFocus()
    }

    private fun startResendCountdown(seconds: Int) {
        resendTimer?.cancel()
        binding.tvFpResend.isEnabled = false
        binding.tvFpResend.setOnClickListener(null)
        resendTimer = object : CountDownTimer(seconds * 1_000L, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val s = millisUntilFinished / 1000
                binding.tvFpResend.text = String.format("Resend code in %02d:%02d", s / 60, s % 60)
            }

            override fun onFinish() {
                binding.tvFpResend.text = "Resend code"
                binding.tvFpResend.isEnabled = true
                binding.tvFpResend.setOnClickListener { requestCode(isResend = true) }
            }
        }.start()
    }

    private fun attemptReset() {
        if (busy) return
        val code = binding.etFpCode.text.toString().trim()
        val newPassword = binding.etFpNewPassword.text.toString()
        val confirm = binding.etFpConfirmPassword.text.toString()

        binding.etFpCode.error = null
        binding.etFpNewPassword.error = null
        binding.etFpConfirmPassword.error = null

        if (code.length != 6 || !code.all { it.isDigit() }) {
            binding.etFpCode.error = "Enter the 6-digit code"
            binding.etFpCode.requestFocus()
            return
        }
        if (newPassword.length < 8) {
            binding.etFpNewPassword.error = "At least 8 characters"
            binding.etFpNewPassword.requestFocus()
            return
        }
        if (newPassword != confirm) {
            binding.etFpConfirmPassword.error = "Passwords do not match"
            binding.etFpConfirmPassword.requestFocus()
            return
        }

        setBusy(true)
        val body = JSONObject().apply {
            put("email", email)
            put("code", code)
            put("newPassword", newPassword)
            put("confirmPassword", confirm)
        }
        AuthHttp.post(this, ApiConfig.RESET_PASSWORD_URL, body, sendCookie = false) { result ->
            setBusy(false)
            when {
                result.networkError != null -> showNetworkError(result.networkError)
                result.ok -> {
                    Toast.makeText(this, result.message("Password updated."), Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                else -> {
                    Toast.makeText(this, result.message(getString(R.string.error_generic)), Toast.LENGTH_LONG).show()
                    binding.etFpCode.text = null
                    binding.etFpCode.requestFocus()
                }
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.btnSendCode.isEnabled = !value
        binding.btnResetPassword.isEnabled = !value
        binding.btnSendCode.text = if (value) "Sending…" else "Send reset code"
        binding.btnResetPassword.text = if (value) "Resetting…" else "Reset password"
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_EMAIL = "EMAIL"
    }
}