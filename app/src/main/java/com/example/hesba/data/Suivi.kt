package com.example.hesba.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Chaque changement de prix d'un ingrédient (manuel, facture ou simulation confirmée). */
@Entity(
    tableName = "prix_historique",
    foreignKeys = [
        ForeignKey(entity = Ingredient::class, parentColumns = ["id"], childColumns = ["ingredientId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Facture::class, parentColumns = ["id"], childColumns = ["factureId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("ingredientId"), Index("factureId"), Index("date")]
)
data class PrixHistorique(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ingredientId: Long,
    val ancienPrixMillimes: Long?,
    val nouveauPrixMillimes: Long?,
    val ancienneQuantite: Double,
    val nouvelleQuantite: Double,
    val source: String,                 // "manuel", "facture", "simulation"
    val factureId: Long? = null,
    val date: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_MANUEL = "manuel"
        const val SOURCE_FACTURE = "facture"
        const val SOURCE_SIMULATION = "simulation"
    }
}

/** Facture importée. L'image reste dans le stockage privé de l'appli (imagePath), jamais envoyée. */
@Entity(tableName = "facture", indices = [Index("utilisateurId")])
data class Facture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId: Long,
    val fournisseur: String,
    val dateFacture: String,
    val imagePath: String?,
    val langue: String,                 // "fr" ou "ar"
    val nombreLignes: Int,
    val totalMillimes: Long?,
    val dateImport: Long = System.currentTimeMillis()
)

/** Autre nom d'un ingrédient (français, arabe, nom du fournisseur…). Appris lors des corrections. */
@Entity(
    tableName = "synonyme",
    foreignKeys = [
        ForeignKey(entity = Ingredient::class, parentColumns = ["id"], childColumns = ["ingredientId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("ingredientId"), Index(value = ["utilisateurId", "texteNormalise"], unique = true)]
)
data class Synonyme(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId: Long,
    val ingredientId: Long,
    val texte: String,
    val texteNormalise: String
)
