package com.example.hesba

import androidx.room.withTransaction
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import kotlinx.coroutines.flow.first

// Calcule le coût d'une recette à partir de ses lignes et des prix ACTUELS des ingrédients.
// Les lignes ne gardent que l'ingrédient et la quantité : le prix vient toujours de la table ingredient.
object RecipeCosting {

    data class Result(val costPerPiece: Double, val advisedPrice: Double)

    fun compute(recette: Recette, lignes: List<LigneRecette>, ingredientsById: Map<Long, Ingredient>): Result {
        val batch = CostCalculator.recipeCost(lignes.map { ligne ->
            val ingredient = ingredientsById[ligne.ingredientId]
            if (ingredient == null) 0.0
            else CostCalculator.lineCost(
                ligne.quantite,
                CostCalculator.pricePerUnit(ingredient.prixPaye, ingredient.quantiteEmballage)
            )
        })
        val perPiece = CostCalculator.costPerPiece(batch, recette.nombreParts.toDouble())
        val price = CostCalculator.sellingPriceHT(perPiece, recette.margeVoulue / 100.0)
        return Result(perPiece, price)
    }

    // Après un changement de prix : met à jour coutParPart et prixVente des recettes qui utilisent l'ingrédient.
    // Renvoie le nombre de recettes recalculées.
    suspend fun refreshRecipesUsing(db: HesbaDatabase, ingredientId: Long): Int {
        // lectures d'abord (hors transaction), puis toutes les écritures ensemble
        val ingredientsById = db.ingredientDao().tous().first().associateBy { it.id }
        val updated = db.recetteDao().toutes().first().mapNotNull { recette ->
            val lignes = db.ligneRecetteDao().parRecette(recette.id).first()
            if (lignes.none { it.ingredientId == ingredientId }) return@mapNotNull null
            val result = compute(recette, lignes, ingredientsById)
            recette.copy(coutParPart = result.costPerPiece, prixVente = result.advisedPrice)
        }
        db.withTransaction {
            for (recette in updated) db.recetteDao().modifier(recette)
        }
        return updated.size
    }
}
