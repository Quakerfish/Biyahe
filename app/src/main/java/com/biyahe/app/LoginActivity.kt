package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.biyahe.app.databinding.ActivityLoginBinding
import org.json.JSONObject

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                attemptLogin(); true
            } else false
        }

        binding.tvForgotPassword.setOnClickListener {
            val typed = binding.etUsername.text.toString().trim()
            startActivity(Intent(this, ForgotPasswordActivity::class.java).apply {
                if (typed.contains("@")) putExtra(ForgotPasswordActivity.EXTRA_EMAIL, typed)
            })
        }

        binding.tvGoToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        binding.tvContinueGuest.setOnClickListener {
            val sessionPrefs = getSharedPreferences("app_session", MODE_PRIVATE)
            sessionPrefs.edit().putBoolean("is_guest", true).remove("session_cookie").apply()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        binding.etUsername.error = null
        binding.etPassword.error = null

        if (username.isEmpty()) {
            binding.etUsername.error = "Enter your username or email"
            binding.etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = "Enter your password"
            binding.etPassword.requestFocus()
            return
        }

        loginUser(username, password)
    }

    /** Step 1: password check. The server emails the code; AuthActivity checks it. */
    private fun loginUser(username: String, password: String) {
        setLoading(true)
        AuthHttp.clearCookie(this) // never reuse an old session for a fresh login

        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }
        AuthHttp.post(this, ApiConfig.LOGIN_URL, body, sendCookie = false) { result ->
            setLoading(false)
            when {
                result.networkError != null -> showNetworkError(result.networkError)
                result.ok && result.json?.optBoolean("otp_required", true) == true -> {
                    Toast.makeText(this, result.message("Code sent."), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, AuthActivity::class.java).apply {
                        putExtra("USER_EMAIL", result.json?.optString("email").orEmpty())
                        putExtra(
                            AuthActivity.EXTRA_COOLDOWN,
                            result.json?.optInt("resend_cooldown_seconds", 45) ?: 45
                        )
                    })
                    finish()
                }
                else -> Toast.makeText(this, result.message(getString(R.string.error_generic)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "Logging in…" else getString(R.string.btn_login)
    }
}