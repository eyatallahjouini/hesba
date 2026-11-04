package com.example.hesba.auth

import androidx.annotation.StringRes
import com.example.hesba.R
import com.example.hesba.data.Utilisateur
import com.example.hesba.data.UtilisateurDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AuthResult {
    data class Success(val userId: Long) : AuthResult()
    data class Error(@StringRes val message: Int, val field: Field = Field.NONE) : AuthResult()

    enum class Field { NONE, NAME, EMAIL, PASSWORD, PRIVACY }
}

/** Comptes locaux (aucun serveur). Le hachage PBKDF2 est lent exprès : il tourne hors du fil principal. */
class AuthRepository(private val dao: UtilisateurDao) {

    companion object {
        const val MIN_PASSWORD = 8
        private val EMAIL = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
        fun isEmail(text: String) = EMAIL.matches(text.trim())
    }

    suspend fun login(email: String, motDePasse: String): AuthResult {
        val mail = email.trim().lowercase()
        if (mail.isEmpty()) return AuthResult.Error(R.string.error_email_required, AuthResult.Field.EMAIL)
        if (motDePasse.isEmpty()) return AuthResult.Error(R.string.error_password_required, AuthResult.Field.PASSWORD)
        val user = dao.parEmail(mail) ?: return AuthResult.Error(R.string.error_login_failed, AuthResult.Field.PASSWORD)
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(motDePasse, user.motDePasseHash) }
        return if (ok) AuthResult.Success(user.id) else AuthResult.Error(R.string.error_login_failed, AuthResult.Field.PASSWORD)
    }

    suspend fun inscrire(
        prenom: String,
        nomCommerce: String,
        email: String,
        motDePasse: String,
        activite: String,
        politiqueAcceptee: Boolean
    ): AuthResult {
        val mail = email.trim().lowercase()
        if (prenom.isBlank()) return AuthResult.Error(R.string.error_name_required, AuthResult.Field.NAME)
        if (!isEmail(mail)) return AuthResult.Error(R.string.error_email_invalid, AuthResult.Field.EMAIL)
        if (motDePasse.length < MIN_PASSWORD) return AuthResult.Error(R.string.error_password_short, AuthResult.Field.PASSWORD)
        if (!politiqueAcceptee) return AuthResult.Error(R.string.error_privacy, AuthResult.Field.PRIVACY)
        if (dao.parEmail(mail) != null) return AuthResult.Error(R.string.error_email_taken, AuthResult.Field.EMAIL)

        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(motDePasse) }
        val id = dao.inserer(
            Utilisateur(
                prenom = prenom.trim(),
                email = mail,
                motDePasseHash = hash,
                activite = activite,
                politiqueConfidentialiteAcceptee = true,
                nomCommerce = nomCommerce.trim()
            )
        )
        return AuthResult.Success(id)
    }

    /** Change le mot de passe après vérification de l'ancien. */
    suspend fun changePassword(user: Utilisateur, current: String, new: String): AuthResult {
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(current, user.motDePasseHash) }
        if (!ok) return AuthResult.Error(R.string.error_password_wrong, AuthResult.Field.PASSWORD)
        if (new.length < MIN_PASSWORD) return AuthResult.Error(R.string.error_password_short, AuthResult.Field.PASSWORD)
        val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(new) }
        dao.modifierMotDePasse(user.id, hash)
        return AuthResult.Success(user.id)
    }
}
