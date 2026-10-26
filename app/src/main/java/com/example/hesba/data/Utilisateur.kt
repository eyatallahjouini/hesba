package com.example.hesba.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "utilisateur",
    indices = [Index(value = ["email"], unique = true)]
)
data class Utilisateur(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prenom: String,
    val email: String,
    val motDePasseHash: String,
    val activite: String,
    val politiqueConfidentialiteAcceptee: Boolean = false,
    val dateCreation: Long = System.currentTimeMillis()
)