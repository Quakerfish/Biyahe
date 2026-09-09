package com.example.biyahe

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SignupActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSignup: Button

    private val SIGNUP_URL = "http://10.73.15.173/biyahe/signup.php"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        etUsername = findViewById(R.id.etUsername)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)
        btnSignup = findViewById(R.id.btnSignUp)

        btnSignup.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            signupUser(username, email, password, confirmPassword)
        }
    }

    private fun signupUser(username: String, email: String, password: String, confirmPassword: String) {
        btnSignup.isEnabled = false

        Thread {
            try {
                val url = URL(SIGNUP_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val jsonBody = JSONObject().apply {
                    put("username", username)
                    put("email", email)
                    put("password", password)
                    put("confirmPassword", confirmPassword)
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
                    btnSignup.isEnabled = true
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()

                    if (success) {
                        // Navigate to LoginActivity
                        val intent = Intent(this, LoginActivity::class.java)
                        startActivity(intent)
                        finish() // remove SignupActivity from the back stack
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    btnSignup.isEnabled = true
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}