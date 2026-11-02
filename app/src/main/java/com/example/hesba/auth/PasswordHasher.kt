package com.example.hesba.auth

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {
        private const val ITERATIONS = 120_000
        private const val KEY_LENGTH = 256

        fun hash(password: String): String {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = derive(password, salt)
            return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(hash, Base64.NO_WRAP)
        }

        fun verify(password: String, stored: String): Boolean {
            val parts = stored.split(":")
            if (parts.size != 2) return false
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            return derive(password, salt).contentEquals(expected)
        }

        private fun derive(password: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        }
    }
