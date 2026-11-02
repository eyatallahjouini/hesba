package com.example.hesba.auth

import com.example.hesba.data.Utilisateur
import com.example.hesba.data.UtilisateurDao

sealed class AuthResult {
    data class Success(val userId: Long) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthRepository(private val dao: UtilisateurDao) {

    suspend fun login(email: String, motDePasse: String): AuthResult {
        val mail = email.trim().lowercase()
        if (mail.isEmpty() || motDePasse.isEmpty())
            return AuthResult.Error("Champs obligatoires")

        val user = dao.parEmail(mail)
            ?: return AuthResult.Error("Email ou mot de passe incorrect")

        return if (PasswordHasher.verify(motDePasse, user.motDePasseHash))
            AuthResult.Success(user.id)
        else AuthResult.Error("Email ou mot de passe incorrect")
    }

    suspend fun inscrire(
        prenom: String,
        email: String,
        motDePasse: String,
        activite: String,
        politiqueAcceptee: Boolean
    ): AuthResult {
        val mail = email.trim().lowercase()
        if (prenom.isBlank()) return AuthResult.Error("Prénom obligatoire")
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(mail).matches())
            return AuthResult.Error("Email invalide")
        if (motDePasse.length < 6)
            return AuthResult.Error("Mot de passe : 6 caractères minimum")
        if (!politiqueAcceptee)
            return AuthResult.Error("Tu dois accepter la politique de confidentialité")
        if (dao.parEmail(mail) != null)
            return AuthResult.Error("Cet email est déjà utilisé")

        val id = dao.inserer(
            Utilisateur(
                prenom = prenom.trim(),
                email = mail,
                motDePasseHash = PasswordHasher.hash(motDePasse),
                activite = activite,
                politiqueConfidentialiteAcceptee = true
            )
        )
        return AuthResult.Success(id)
    }
}