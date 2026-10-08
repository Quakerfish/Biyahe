package com.biyahe.app

object ApiConfig {
    // Deployed FastAPI backend (replaces the old PHP backend + local Wi-Fi IP).
    // This one line is the only thing to change if the API ever moves again
    // (e.g. a custom domain later).
    private const val BASE_URL = "https://biyahe-api.vercel.app"

    // Dynamic endpoints built from BASE_URL
    const val LOGIN_URL = "$BASE_URL/api/login"
    const val SIGNUP_URL = "$BASE_URL/api/signup"
    const val ROUTES_URL = "$BASE_URL/api/commuter-routes"
    const val SAVED_ROUTES_URL = "$BASE_URL/api/saved-routes"
    const val GET_PROFILE_URL = "$BASE_URL/api/profile"
    const val UPDATE_PROFILE_URL = "$BASE_URL/api/profile"
    const val UPLOAD_AVATAR_URL = "$BASE_URL/api/profile/image"

    // NEW: logout used to be a query param on get_profile.php
    // (?action=logout); it's now its own endpoint.
    const val LOGOUT_URL = "$BASE_URL/api/logout"

    fun getRouteRatingUrl(routeId: Int) = "$BASE_URL/api/routes/$routeId/ratings"

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