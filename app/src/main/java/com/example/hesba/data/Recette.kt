package com.example.hesba.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recette")
data class Recette(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val nombreParts: Int,
    val margeVoulue: Double,            // ex. 30.0 pour 30 %
    val prixVente: Double? = null,      // null = pas encore fixé
    val photoUri: String? = null,
    val dateCreation: Long = System.currentTimeMillis()
)