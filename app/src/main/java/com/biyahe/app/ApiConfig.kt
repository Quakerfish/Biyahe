package com.biyahe.app

object ApiConfig {
    // CHANGE YOUR IP / BASE URL HERE ONLY
    // Use your computer's local Wi-Fi IP (e.g., 192.168.1.X or 10.73.15.16)
    private const val BASE_URL = "http://10.73.15.16/Biyahe-Admin"

    // Dynamic endpoints built from BASE_URL
    const val LOGIN_URL = "$BASE_URL/login.php"
    const val SIGNUP_URL = "$BASE_URL/signup.php"
    const val ROUTES_URL = "$BASE_URL/user_routes.php"
    const val SAVED_ROUTES_URL = "$BASE_URL/save_routes.php"
    const val GET_PROFILE_URL = "$BASE_URL/get_profile.php"
    const val UPDATE_PROFILE_URL = "$BASE_URL/update_profile.php"
    const val UPLOAD_AVATAR_URL = "$BASE_URL/upload_profile_image.php"


    // EmailJS Configuration
    // TODO(security): these are shipped in the APK as plain strings, so anyone can extract
    // them with a decompiler and send mail through your EmailJS quota. When you have a
    // backend endpoint free, move OTP sending server-side and drop this block entirely.
    const val EMAILJS_API_URL = "https://api.emailjs.com/api/v1.0/email/send"
    const val EMAILJS_SERVICE_ID = "service_ljzft88"
    const val EMAILJS_TEMPLATE_ID = "template_0rsdbdu"
    const val EMAILJS_PUBLIC_KEY = "yGCBfHEDzOe8b14kb"
    const val EMAILJS_PRIVATE_KEY = "BljzL9_rsdOSUUByucTUg" // Added Private Key / Access Token
}
