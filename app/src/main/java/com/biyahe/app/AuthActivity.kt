package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import com.biyahe.app.databinding.ActivityAuthBinding
import org.json.JSONObject

/**
 * Login step 2. The 6-digit code is created, emailed and checked by the server;
 * this screen only collects what the user types. Nothing secret lives in the app.
 */
class AuthActivity : BaseActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var otpBoxes: List<EditText>
    private var resendTimer: CountDownTimer? = null
    private var verifying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        otpBoxes = listOf(
            binding.etDigit1, binding.etDigit2, binding.etDigit3,
            binding.etDigit4, binding.etDigit5, binding.etDigit6
        )

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnVerify.setOnClickListener { attemptVerify() }

        setupOtpAutoAdvance()
        startResendCountdown(intent.getIntExtra(EXTRA_COOLDOWN, 45))
    }

    private fun setupOtpAutoAdvance() {
        otpBoxes.forEachIndexed { index, box ->
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!s.isNullOrEmpty() && index < otpBoxes.lastIndex) {
                        otpBoxes[index + 1].requestFocus()
                    }
                }
            })

            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isNullOrEmpty() && index > 0
                ) {
                    otpBoxes[index - 1].apply {
                        requestFocus()
                        text = null
                    }
                    true
                } else {
                    false
                }
            }
        }
        otpBoxes.first().requestFocus()
    }

    private fun startResendCountdown(seconds: Int) {
        resendTimer?.cancel()
        binding.tvResendTimer.isEnabled = false
        binding.tvResendTimer.setOnClickListener(null)

        resendTimer = object : CountDownTimer(seconds * 1_000L, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val s = (millisUntilFinished / 1000)
                binding.tvResendTimer.text = String.format("Resend in %02d:%02d", s / 60, s % 60)
            }

            override fun onFinish() {
                binding.tvResendTimer.text = getString(R.string.resend_code_action)
                binding.tvResendTimer.isEnabled = true
                binding.tvResendTimer.setOnClickListener { resendCode() }
            }
        }.start()
    }

    private fun resendCode() {
        binding.tvResendTimer.isEnabled = false
        AuthHttp.post(this, ApiConfig.RESEND_OTP_URL, JSONObject(), sendCookie = true) { result ->
            when {
                result.networkError != null -> {
                    showNetworkError(result.networkError)
                    binding.tvResendTimer.isEnabled = true
                }
                result.ok -> {
                    Toast.makeText(this, result.message("A new code was sent."), Toast.LENGTH_SHORT).show()
                    startResendCountdown(result.json?.optInt("resend_cooldown_seconds", 45) ?: 45)
                }
                result.code == 401 -> backToLogin(result.message("Please log in again."))
                else -> {
                    Toast.makeText(this, result.message(getString(R.string.error_generic)), Toast.LENGTH_SHORT).show()
                    startResendCountdown(10)
                }
            }
        }
    }

    private fun attemptVerify() {
        if (verifying) return
        val enteredCode = otpBoxes.joinToString("") { it.text.toString().trim() }

        if (enteredCode.length < otpBoxes.size) {
            Toast.makeText(this, "Enter the full 6-digit code.", Toast.LENGTH_SHORT).show()
            return
        }

        setVerifying(true)
        val body = JSONObject().put("code", enteredCode)
        AuthHttp.post(this, ApiConfig.VERIFY_OTP_URL, body, sendCookie = true) { result ->
            setVerifying(false)
            when {
                result.networkError != null -> showNetworkError(result.networkError)
                result.ok -> {
                    getSharedPreferences("app_session", MODE_PRIVATE).edit().putBoolean("is_guest", false).apply()
                    Toast.makeText(this, "Verification successful!", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                    finish()
                }
                // Expired, locked out or session gone: the server ended this login attempt.
                result.code == 429 || (result.code == 401 && result.json?.optString("message")?.contains("log in again", true) == true) ->
                    backToLogin(result.message("Please log in again."))
                else -> {
                    Toast.makeText(this, result.message("Invalid verification code."), Toast.LENGTH_SHORT).show()
                    otpBoxes.forEach { it.text = null }
                    otpBoxes.first().requestFocus()
                }
            }
        }
    }

    private fun setVerifying(value: Boolean) {
        verifying = value
        binding.btnVerify.isEnabled = !value
    }

    private fun backToLogin(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        AuthHttp.clearCookie(this)
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_COOLDOWN = "RESEND_COOLDOWN"
    }
}