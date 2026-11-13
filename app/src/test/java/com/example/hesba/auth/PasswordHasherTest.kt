package com.example.hesba.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 calculé avec Mac (disponible dès Android 1), car SecretKeyFactory
 * « PBKDF2WithHmacSHA256 » n'existe qu'à partir d'Android 8 (API 26) alors que minSdk = 24.
 */
class PasswordHasherTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun rfc7914TestVector() {
        // RFC 7914 §11 : PBKDF2-HMAC-SHA256 (P="passwd", S="salt", c=1, dkLen=64)
        val expected = hex(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b6459916" + "64b39d77ef317c71b845b1e30bd509112041d3a19783"
        )
        assertArrayEquals(expected, PasswordHasher.pbkdf2("passwd", "salt".toByteArray(), 1, 512))
    }

    @Test
    fun sameResultAsJavaPbkdf2_soExistingAccountsStillLogIn() {
        // les comptes créés avec SecretKeyFactory doivent toujours se connecter
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val password = "Mot de passe ١٢٣ é"
        val spec = PBEKeySpec(password.toCharArray(), salt, 1000, 256)
        val reference = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        assertArrayEquals(reference, PasswordHasher.pbkdf2(password, salt, 1000, 256))
    }
}
