package com.biyahe.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.biyahe.app.databinding.ActivitySignupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class SignupActivity : BaseActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupPasswordToggle(binding.btnTogglePassword, binding.etPassword)
        setupPasswordToggle(binding.btnToggleConfirmPassword, binding.etConfirmPassword)

        binding.btnCreateAccount.setOnClickListener { attemptSignup() }

        binding.tvGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun attemptSignup() {
        val fullName = binding.etFullName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()
        val confirmPassword = binding.etConfirmPassword.text.toString().trim()

        binding.etFullName.error = null
        binding.etEmail.error = null
        binding.etPassword.error = null
        binding.etConfirmPassword.error = null

        if (fullName.isEmpty()) {
            binding.etFullName.error = "Full name is required"
            binding.etFullName.requestFocus()
            return
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email address"
            binding.etEmail.requestFocus()
            return
        }
        if (password.length < 8) {
            binding.etPassword.error = "Password must be at least 8 characters"
            binding.etPassword.requestFocus()
            return
        }
        if (password != confirmPassword) {
            binding.etConfirmPassword.error = "Passwords do not match"
            binding.etConfirmPassword.requestFocus()
            return
        }
        if (!binding.cbAgreeTerms.isChecked) {
            Toast.makeText(this, "Please agree to the Terms of Service to continue.", Toast.LENGTH_SHORT).show()
            return
        }

        signUpUser(fullName, email, password, confirmPassword)
    }

    private fun signUpUser(fullName: String, email: String, password: String, confirmPassword: String) {
        setLoading(true)

        lifecycleScope.launch(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(ApiConfig.SIGNUP_URL)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                val jsonBody = JSONObject().apply {
                    put("fullName", fullName)
                    put("username", fullName) // kept for backends that accept username or fullName
                    put("email", email)
                    put("password", password)
                    put("confirmPassword", confirmPassword)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                // Handles 200 (OK) and 201 (Created) properly
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (responseText.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setLoading(false)
                        Toast.makeText(this@SignupActivity, "Server returned an empty response (Code $responseCode)", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val jsonResponse = JSONObject(responseText)
                val success = jsonResponse.optBoolean("success", false)
                val message = jsonResponse.optString("message", getString(R.string.error_generic))

                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(this@SignupActivity, message, Toast.LENGTH_LONG).show()

                    if (success) {
                        val intent = Intent(this@SignupActivity, LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }
            } catch (e: SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    showNetworkError("timed out")
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    showNetworkError("Connection failed: ${e.localizedMessage ?: "Network error"}")
                }
            } catch (e: JSONException) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    showNetworkError("Invalid response format")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    val errorMsg = e.localizedMessage ?: e.javaClass.simpleName
                    Toast.makeText(this@SignupActivity, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnCreateAccount.isEnabled = !loading
        binding.btnCreateAccount.text = if (loading) "Creating account…" else getString(R.string.btn_create_account)
    }

    /** Toggles an EditText between masked and plain password input, flipping the eye icon's alpha as a simple visual cue. */
    private fun setupPasswordToggle(button: ImageButton, field: EditText) {
        var isVisible = false
        button.setOnClickListener {
            isVisible = !isVisible
            field.inputType = if (isVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            field.setSelection(field.text.length)
            button.alpha = if (isVisible) 1.0f else 0.5f
        }
    }
}