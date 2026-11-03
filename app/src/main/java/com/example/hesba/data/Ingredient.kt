package com.example.hesba.data
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.hesba.auth.SessionManager

@Entity(tableName = "ingredient")
data class Ingredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val unite: String,                  // toujours "g", "ml" ou "piece"
    val quantiteEmballage: Double,      // en unité de base (1 kg = 1000 g)
    val prixPaye: Double,
    val dateMiseAJour: Long = System.currentTimeMillis(),
    val utilisateurId: Long = SessionManager.ID_UTILISATEUR_INVITE
) {
    // Calculé, non stocké : 1000 g à 6,00 dt donne 0,006 dt/g
    val prixParUnite: Double
        get() = if (quantiteEmballage > 0) prixPaye / quantiteEmballage else 0.0
}
