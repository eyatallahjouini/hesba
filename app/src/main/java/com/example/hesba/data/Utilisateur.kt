package com.example.hesba.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Compte local. Le mot de passe n'est jamais stocké : seulement « sel:empreinte » PBKDF2 (voir auth/PasswordHasher). */
@Entity(
    tableName = "utilisateur",
    indices = [Index(value = ["email"], unique = true)]
)
data class Utilisateur(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val prenom: String,
    val email: String,
    val motDePasseHash: String,
    val activite: String,                         // "domicile", "sans_employe", "avec_employe"
    val politiqueConfidentialiteAcceptee: Boolean = false,
    val nomCommerce: String = "",
    val ville: String = "",
    val nombreEmployes: Int = 0,
    val salaireMoyenMillimes: Long = 0,           // salaire mensuel moyen d'un employé
    val heuresTravailMois: Int = 0,               // heures de travail par mois et par personne
    val loyerMensuelMillimes: Long = 0,           // loyer + charges fixes du local, par mois
    val volumeMensuel: Int = 0,                   // parts produites par mois (toutes recettes)
    val dateCreation: Long = System.currentTimeMillis()
)
