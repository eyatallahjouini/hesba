package com.example.hesba.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recette")
data class Recette(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId : Int ,
    val nom: String,
    val nombreParts: Int,
    val margeVoulue: Double = 40.0,
    val coutParPart: Double = 0.0,
    val prixVente: Double? = null,
    val dateCreation: Long = System.currentTimeMillis()
)