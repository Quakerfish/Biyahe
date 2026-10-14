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

    // Login 2FA + forgot password. The server generates, emails (via EmailJS) and
    // checks every code, so no EmailJS keys live in this app any more.
    const val VERIFY_OTP_URL = "$BASE_URL/api/login/verify-otp"
    const val RESEND_OTP_URL = "$BASE_URL/api/login/resend-otp"
    const val FORGOT_PASSWORD_URL = "$BASE_URL/api/forgot-password"
    const val RESET_PASSWORD_URL = "$BASE_URL/api/reset-password"

    // NEW: logout used to be a query param on get_profile.php
    // (?action=logout); it's now its own endpoint.
    const val LOGOUT_URL = "$BASE_URL/api/logout"

    fun getRouteRatingUrl(routeId: Int) = "$BASE_URL/api/routes/$routeId/ratings"
}