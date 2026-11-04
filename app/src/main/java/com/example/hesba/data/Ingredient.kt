package com.example.hesba.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.hesba.auth.SessionManager

/**
 * Matière première achetée.
 * Prix de l'EMBALLAGE en millimes (ex. sac de 1000 g à 1,200 DT → quantiteEmballage = 1000, prixMillimes = 1200).
 * rendementPourcent : part réellement utilisable (épluchage, os, pertes). 100 = aucune perte.
 */
@Entity(tableName = "ingredient", indices = [Index("utilisateurId")])
data class Ingredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId: Long = SessionManager.ID_UTILISATEUR_INVITE,
    val nom: String,
    val unite: String,                  // unité de base : "g", "ml" ou "piece"
    val quantiteEmballage: Double,      // en unité de base (1 kg = 1000 g)
    val prixMillimes: Long?,            // null = prix pas encore connu
    val rendementPourcent: Double = 100.0,
    val dateMiseAJour: Long = System.currentTimeMillis()
)
