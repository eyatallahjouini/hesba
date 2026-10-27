package com.example.hesba.data
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ligne_recette",
    foreignKeys = [
        ForeignKey(
            entity = Recette::class,
            parentColumns = ["id"],
            childColumns = ["recetteId"],
            onDelete = ForeignKey.CASCADE      // supprimer une recette supprime ses lignes
        ),
        ForeignKey(
            entity = Ingredient::class,
            parentColumns = ["id"],
            childColumns = ["ingredientId"],
            onDelete = ForeignKey.RESTRICT     // empêche de supprimer un ingrédient utilisé
        )
    ],
    indices = [
        Index("ingredientId"),
        Index(value = ["recetteId", "ingredientId"], unique = true)
    ]
)
data class LigneRecette(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recetteId: Long,
    val ingredientId: Long,
    val quantite: Double,
    val unite: String
)
