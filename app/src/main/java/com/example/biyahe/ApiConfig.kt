package com.example.biyahe

object ApiConfig {
    // CHANGE YOUR IP / BASE URL HERE ONLY
    private const val BASE_URL = "http://10.123.94.151/biyahe"

    // Dynamic endpoints built from BASE_URL
    const val LOGIN_URL = "$BASE_URL/login.php"
    const val SIGNUP_URL = "$BASE_URL/signup.php"
}