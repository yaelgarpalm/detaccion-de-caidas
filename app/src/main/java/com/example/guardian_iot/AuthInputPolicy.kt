package com.example.guardian_iot

object AuthInputPolicy {
    fun validEmail(value: String): Boolean = value.trim().length <= 254 &&
        Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value.trim())

    fun validPassword(value: String): Boolean = value.length >= 8 && value.length <= 128

    fun validRegistration(name: String, email: String, password: String, confirmation: String, privacyAccepted: Boolean): Boolean =
        name.trim().isNotEmpty() && name.trim().length <= 120 && validEmail(email) &&
            validPassword(password) && password == confirmation && privacyAccepted
}
