package com.example.biyahe

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SignupActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var etEmail: EditText
    private lateinit var btnTogglePassword: ImageView
    private lateinit var btnToggleConfirmPassword: ImageView
    private lateinit var btnSignUp: AppCompatButton
    private lateinit var tvLogin: TextView

    private var isPasswordVisible = false
    private var isConfirmPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_signup)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.signup)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        etEmail = findViewById(R.id.etEmail)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        btnToggleConfirmPassword = findViewById(R.id.btnToggleConfirmPassword)
        btnSignUp = findViewById(R.id.btnSignUp)
        tvLogin = findViewById(R.id.tvLogin)

        // 1. Password Visibility Toggle Listener
        btnTogglePassword.setOnClickListener {
            if (isPasswordVisible) {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                isPasswordVisible = false
            } else {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                isPasswordVisible = true
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // 2. Confirm Password Visibility Toggle Listener
        btnToggleConfirmPassword.setOnClickListener {
            if (isConfirmPasswordVisible) {
                etConfirmPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                isConfirmPasswordVisible = false
            } else {
                etConfirmPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                isConfirmPasswordVisible = true
            }
            etConfirmPassword.setSelection(etConfirmPassword.text.length)
        }

        // 3. Navigation back to LoginActivity
        tvLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }

        btnSignUp.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()
            val email = etEmail.text.toString().trim()

            if (username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty() || email.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (password != confirmPassword) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            signUpUser(username, password, confirmPassword, email)
        }
    }

    private fun signUpUser(username: String, password: String, confirmPassword: String, email: String) {
        btnSignUp.isEnabled = false

        Thread {
            try {
                val url = URL(ApiConfig.SIGNUP_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("password", password)
                    put("confirmPassword", confirmPassword)
                    put("email", email)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(jsonBody.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }

                val response = stream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                val success = jsonResponse.optBoolean("success", false)
                val message = jsonResponse.optString("message", "Unknown error")

                runOnUiThread {
                    btnSignUp.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    if (success) {
                        val intent = Intent(this, LoginActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    btnSignUp.isEnabled = true
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}