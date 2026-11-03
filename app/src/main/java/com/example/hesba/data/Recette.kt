package com.example.hesba.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.hesba.auth.SessionManager

@Entity(tableName = "recette")
data class Recette(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId : Long = SessionManager.ID_UTILISATEUR_INVITE,
    val nom: String,
    val nombreParts: Int,
    val margeVoulue: Double = 40.0,
    val coutParPart: Double = 0.0,
    val prixVente: Double? = null,
    val tempsPreparationMinutes: Int = 30, //  Temps de prépa en minutes
    val dateCreation: Long = System.currentTimeMillis()
)