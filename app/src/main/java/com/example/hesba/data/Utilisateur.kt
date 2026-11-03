package com.example.hesba.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.hesba.auth.SessionManager
@Entity(
    tableName = "utilisateur",
    indices = [Index(value = ["email"], unique = true)]
)
data class Utilisateur(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prenom: String,
    val email: String,
    val motDePasseHash: String,
    val activite: String,// "domicile", "sans_employe", "avec_employe"
    val nombreEmployes: Int = 0,
    val salaireMoyenEmploye: Double = 0.0,
    val loyerMensuel: Double = 0.0,
    val volumeMensuel: Int = 300,//quantité de ventes(parts/mois)
    val dateCreation: Long = System.currentTimeMillis()
)