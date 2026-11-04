package com.example.hesba.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.hesba.auth.SessionManager

/**
 * Recette. Les coûts cachés sont optionnels (0 = absent) :
 *  - emballageParPartMillimes : boîte, papier, sachet… pour UNE part
 *  - gazParLotMillimes        : gaz/électricité pour tout le lot
 *  - mainOeuvreParLotMillimes : main-d'œuvre pour tout le lot
 * coutParPartMillimes = dernier coût calculé (instantané, sert aux comparaisons et au tri).
 * prixVenteMillimes   = prix réellement pratiqué, choisi par l'utilisateur (null = pas encore fixé).
 */
@Entity(tableName = "recette", indices = [Index("utilisateurId")])
data class Recette(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val utilisateurId: Long = SessionManager.ID_UTILISATEUR_INVITE,
    val nom: String,
    val nombreParts: Int,
    val margeVoulue: Double = 40.0,
    val coutParPartMillimes: Long = 0,
    val prixVenteMillimes: Long? = null,
    val tempsPreparationMinutes: Int = 0,
    val emballageParPartMillimes: Long = 0,
    val gazParLotMillimes: Long = 0,
    val mainOeuvreParLotMillimes: Long = 0,
    val dateCreation: Long = System.currentTimeMillis(),
    val dateMiseAJour: Long = System.currentTimeMillis()
)
