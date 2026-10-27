package com.example.hesba.data
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "vente",
    foreignKeys = [
        ForeignKey(
            entity = Recette::class,
            parentColumns = ["id"],
            childColumns = ["recetteId"],
            onDelete = ForeignKey.SET_NULL   // supprimer une recette garde l'historique des ventes
        )
    ],
    indices = [Index("recetteId"), Index("date")]
)
data class Vente(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recetteId: Long?,
    val nomRecette: String,              // copie du nom au moment de la vente
    val quantite: Int,
    val prixVenteUnitaire: Double,       // prix réellement encaissé
    val coutUnitaire: Double,            // coût de revient à ce moment-là
    val date: Long = System.currentTimeMillis()
)
