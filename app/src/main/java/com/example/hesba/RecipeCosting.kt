package com.example.hesba

import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.Recette
import com.example.hesba.data.Utilisateur
import java.math.BigDecimal

/**
 * Passerelle entre les tables Room et [CostCalculator].
 * Les lignes ne gardent que l'ingrédient, la quantité et l'unité : le prix vient TOUJOURS
 * de la table ingredient, donc un changement de prix se répercute partout.
 */
object RecipeCosting {

    fun priceOf(ingredient: Ingredient) = CostCalculator.IngredientPrice(
        id = ingredient.id,
        name = ingredient.nom,
        baseUnit = ingredient.unite,
        packageQuantity = BigDecimal(ingredient.quantiteEmballage.toString()),
        packagePriceMillimes = ingredient.prixMillimes,
        yieldPercent = BigDecimal(ingredient.rendementPourcent.toString())
    )

    fun lineOf(ligne: LigneRecette) =
        CostCalculator.Line(ligne.ingredientId, BigDecimal(ligne.quantite.toString()), ligne.unite)

    /** Part des charges fixes du profil (loyer ÷ parts par mois). 0 si le profil ne les renseigne pas. */
    fun fixedPerPortion(user: Utilisateur?): Long =
        if (user == null) 0 else CostCalculator.fixedCostPerPortion(user.loyerMensuelMillimes, user.volumeMensuel)

    /** Estimation de la main-d'œuvre d'un lot depuis le profil ; null si le profil ne suffit pas. */
    fun laborEstimate(user: Utilisateur?, minutes: Int): Long? {
        if (user == null) return null
        val people = if (user.activite == "avec_employe") user.nombreEmployes else 1
        if (people <= 0) return null
        return CostCalculator.laborCost(user.salaireMoyenMillimes * people, user.heuresTravailMois * people, minutes)
    }

    fun inputOf(recette: Recette, lignes: List<LigneRecette>, user: Utilisateur?) = CostCalculator.RecipeInput(
        portions = recette.nombreParts,
        targetMarginPercent = BigDecimal(recette.margeVoulue.toString()),
        lines = lignes.map(::lineOf),
        hidden = CostCalculator.HiddenCosts(
            packagingPerPortionMillimes = recette.emballageParPartMillimes,
            energyPerBatchMillimes = recette.gazParLotMillimes,
            laborPerBatchMillimes = recette.mainOeuvreParLotMillimes,
            fixedPerPortionMillimes = fixedPerPortion(user)
        ),
        sellingPriceMillimes = recette.prixVenteMillimes
    )

    fun compute(
        recette: Recette,
        lignes: List<LigneRecette>,
        ingredientsById: Map<Long, Ingredient>,
        user: Utilisateur? = null
    ): CostCalculator.RecipeCost =
        CostCalculator.compute(inputOf(recette, lignes, user), ingredientsById.mapValues { priceOf(it.value) })
}
