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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

class AuthActivity : BaseActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var otpBoxes: List<EditText>
    private var resendTimer: CountDownTimer? = null

    private var generatedOtp: String? = null
    private var userEmail: String = ""

    private val httpClient = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userEmail = intent.getStringExtra("USER_EMAIL") ?: ""

        otpBoxes = listOf(
            binding.etDigit1, binding.etDigit2, binding.etDigit3,
            binding.etDigit4, binding.etDigit5, binding.etDigit6
        )

        // Restore OTP state if activity was recreated (e.g., screen rotation)
        if (savedInstanceState != null) {
            generatedOtp = savedInstanceState.getString("SAVED_OTP")
        }

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupOtpAutoAdvance()

        // Generate and send OTP only if it hasn't been generated yet
        if (generatedOtp == null) {
            generatedOtp = String.format("%06d", Random.nextInt(0, 1000000))
            sendOtpViaEmailJS()
        }

        startResendCountdown()

        binding.btnVerify.setOnClickListener { attemptVerify() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("SAVED_OTP", generatedOtp)
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

    private fun sendOtpViaEmailJS(isResend: Boolean = false) {
        if (userEmail.isBlank()) {
            Toast.makeText(this, "Recipient email is missing.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isResend) {
            generatedOtp = String.format("%06d", Random.nextInt(0, 1000000))
        }

        val jsonPayload = JSONObject().apply {
            put("service_id", ApiConfig.EMAILJS_SERVICE_ID)
            put("template_id", ApiConfig.EMAILJS_TEMPLATE_ID)
            put("user_id", ApiConfig.EMAILJS_PUBLIC_KEY)
            put("accessToken", ApiConfig.EMAILJS_PRIVATE_KEY)
            put("template_params", JSONObject().apply {
                put("to_email", userEmail)
                put("passcode", generatedOtp)
            })
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(ApiConfig.EMAILJS_API_URL)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@AuthActivity, "Failed to send OTP: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val isSuccess = it.isSuccessful
                    val errBody = if (!isSuccess) it.body?.string() ?: "Unknown error" else ""

                    runOnUiThread {
                        if (isSuccess) {
                            Toast.makeText(this@AuthActivity, "OTP sent", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@AuthActivity, "Error sending email: $errBody", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    private fun startResendCountdown() {
        resendTimer?.cancel()
        binding.tvResendTimer.isEnabled = false
        binding.tvResendTimer.setOnClickListener(null)

        resendTimer = object : CountDownTimer(45_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000)
                binding.tvResendTimer.text =
                    String.format("Resend in %02d:%02d", seconds / 60, seconds % 60)
            }

            override fun onFinish() {
                binding.tvResendTimer.text = getString(R.string.resend_code_action)
                binding.tvResendTimer.isEnabled = true
                bindResendClick()
            }
        }.start()
    }

    private fun bindResendClick() {
        binding.tvResendTimer.setOnClickListener {
            sendOtpViaEmailJS(isResend = true)
            startResendCountdown()
        }
    }

    private fun attemptVerify() {
        val enteredCode = otpBoxes.joinToString("") { it.text.toString().trim() }

        if (enteredCode.length < otpBoxes.size) {
            Toast.makeText(this, "Enter the full 6-digit code.", Toast.LENGTH_SHORT).show()
            return
        }

        if (enteredCode == generatedOtp) {
            Toast.makeText(this, "Verification Successful!", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        } else {
            Toast.makeText(this, "Invalid verification code. Please try again.", Toast.LENGTH_SHORT).show()
            otpBoxes.forEach { it.text = null }
            otpBoxes.first().requestFocus()
        }
    }

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}