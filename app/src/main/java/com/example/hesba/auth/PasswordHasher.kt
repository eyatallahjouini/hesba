package com.example.hesba.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mot de passe → « sel:empreinte » (PBKDF2-HMAC-SHA256, 120 000 itérations, sel aléatoire de 16 octets).
 * Le mot de passe en clair n'est jamais enregistré. (Travail d'Emna, comparaison à temps constant ajoutée.)
 */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH = 256

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        return try {
            val salt = Base64.decode(parts[0], Base64.NO_WRAP)
            val expected = Base64.decode(parts[1], Base64.NO_WRAP)
            MessageDigest.isEqual(derive(password, salt), expected)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray = pbkdf2(password, salt, ITERATIONS, KEY_LENGTH)

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) avec Mac « HmacSHA256 », disponible sur tous les Android.
     * SecretKeyFactory « PBKDF2WithHmacSHA256 » n'existe qu'à partir d'Android 8 (API 26) :
     * sur Android 7 (minSdk 24) l'inscription et la connexion plantaient.
     * Même résultat octet pour octet (mot de passe encodé en UTF-8) : les comptes existants se connectent toujours.
     */
    internal fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyLengthBits: Int): ByteArray {
        val pwd = password.toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(SecretKeySpec(pwd, "HmacSHA256"))
        } finally {
            pwd.fill(0)
        }
        val hLen = mac.macLength
        val out = ByteArray(keyLengthBits / 8)
        var block = 1
        var offset = 0
        while (offset < out.size) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, offset, 0, minOf(hLen, out.size - offset))
            offset += hLen
            block++
        }
        return out
    }
}
