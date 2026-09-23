package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import com.biyahe.app.databinding.ActivityLoginBinding
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class LoginActivity : BaseActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var passwordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            binding.etPassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.etPassword.setSelection(binding.etPassword.text.length)
            binding.btnTogglePassword.alpha = if (passwordVisible) 1.0f else 0.5f
        }

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                attemptLogin(); true
            } else false
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(this, "Password recovery isn't wired up yet.", Toast.LENGTH_SHORT).show()
        }

        binding.tvGoToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
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

    private fun loginUser(username: String, password: String) {
        setLoading(true)

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ApiConfig.LOGIN_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode

                // Extract PHP session cookie if login succeeded
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val cookies = conn.headerFields["Set-Cookie"]
                    val sessionCookie = cookies?.firstOrNull { it.contains("PHPSESSID") } ?: cookies?.firstOrNull()

                    if (sessionCookie != null) {
                        val rawCookie = sessionCookie.split(";")[0]
                        getSharedPreferences("app_session", MODE_PRIVATE)
                            .edit().putString("session_cookie", rawCookie).apply()
                    }
                }

                val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", false)
                val message = jsonResponse.optString("message", getString(R.string.error_generic))
                val userEmail = jsonResponse.optString("email", username)

                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()

                    if (success) {
                        val intent = Intent(this, AuthActivity::class.java).apply {
                            putExtra("USER_EMAIL", userEmail)
                        }
                        startActivity(intent)
                        finish()
                    }
                }
            } catch (e: SocketTimeoutException) {
                runOnUiThread { setLoading(false); showNetworkError("timed out") }
            } catch (e: IOException) {
                runOnUiThread { setLoading(false); showNetworkError() }
            } catch (e: JSONException) {
                runOnUiThread { setLoading(false); showNetworkError("bad response") }
            } catch (e: Exception) {
                runOnUiThread {
                    setLoading(false)
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading) "Logging in…" else getString(R.string.btn_login)
    }
}
