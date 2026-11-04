package com.example.hesba.data

import androidx.room.withTransaction
import com.example.hesba.RecipeCosting
import com.example.hesba.domain.RecipeImpact
import com.example.hesba.domain.TextNormalizer
import com.example.hesba.domain.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Toutes les écritures de l'appli passent ici, limitées aux données de l'utilisateur connecté.
 * Les opérations composées (prix + historique + recalcul) sont faites dans UNE transaction.
 */
class HesbaRepository(private val db: HesbaDatabase, val userId: Long) {

    // ---------- lecture ----------

    fun user(): Flow<Utilisateur?> = db.utilisateurDao().parId(userId)

    fun workspace(): Flow<Workspace> = combine(
        db.utilisateurDao().parId(userId),
        db.ingredientDao().parUtilisateur(userId),
        db.recetteDao().parUtilisateur(userId),
        db.ligneRecetteDao().toutesPour(userId)
    ) { user, ingredients, recipes, lines -> Workspace(user, ingredients, recipes, lines) }

    suspend fun snapshot(): Workspace = Workspace(
        db.utilisateurDao().trouver(userId),
        db.ingredientDao().listePour(userId),
        db.recetteDao().listePour(userId),
        db.ligneRecetteDao().listeToutesPour(userId)
    )

    fun history(since: Long): Flow<List<PrixHistorique>> = db.prixHistoriqueDao().depuis(userId, since)

    // ---------- ingrédients ----------

    sealed class IngredientSave {
        data class Saved(val id: Long, val recalculated: Int) : IngredientSave()
        data class Duplicate(val existing: Ingredient) : IngredientSave()
        data class UnitLocked(val recipes: List<Recette>) : IngredientSave()
    }

    /** Ajoute ou modifie un ingrédient. Un changement de prix/emballage est écrit dans l'historique. */
    suspend fun saveIngredient(existing: Ingredient?, draft: Ingredient): IngredientSave {
        val key = TextNormalizer.normalize(draft.nom)
        db.ingredientDao().listePour(userId).firstOrNull { TextNormalizer.normalize(it.nom) == key && it.id != existing?.id }
            ?.let { return IngredientSave.Duplicate(it) }
        if (existing != null && existing.unite != draft.unite) {
            val used = db.recetteDao().utilisant(existing.id)
            if (used.isNotEmpty()) return IngredientSave.UnitLocked(used)
        }
        return db.withTransaction {
            if (existing == null) {
                val id = db.ingredientDao().inserer(draft.copy(id = 0, utilisateurId = userId, dateMiseAJour = System.currentTimeMillis()))
                IngredientSave.Saved(id, 0)
            } else {
                val updated = draft.copy(id = existing.id, utilisateurId = existing.utilisateurId, dateMiseAJour = System.currentTimeMillis())
                db.ingredientDao().modifier(updated)
                if (existing.prixMillimes != updated.prixMillimes || existing.quantiteEmballage != updated.quantiteEmballage) {
                    db.prixHistoriqueDao().inserer(
                        PrixHistorique(
                            ingredientId = existing.id,
                            ancienPrixMillimes = existing.prixMillimes,
                            nouveauPrixMillimes = updated.prixMillimes,
                            ancienneQuantite = existing.quantiteEmballage,
                            nouvelleQuantite = updated.quantiteEmballage,
                            source = PrixHistorique.SOURCE_MANUEL
                        )
                    )
                }
                IngredientSave.Saved(existing.id, refreshSnapshots(setOf(existing.id)))
            }
        }
    }

    /** Recettes qui utilisent l'ingrédient (pour avertir avant suppression). */
    suspend fun recipesUsing(ingredientId: Long): List<Recette> = db.recetteDao().utilisant(ingredientId)

    /** Supprime l'ingrédient. Refusé s'il est encore utilisé (la base l'interdit aussi : RESTRICT). */
    suspend fun deleteIngredient(ingredient: Ingredient): List<Recette> {
        val used = db.recetteDao().utilisant(ingredient.id)
        if (used.isEmpty()) db.ingredientDao().supprimer(ingredient)
        return used
    }

    // ---------- recettes ----------

    /** Enregistre la recette et remplace ses lignes ; le coût par part est recalculé et mémorisé. */
    suspend fun saveRecipe(existing: Recette?, draft: Recette, lines: List<LigneRecette>): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = if (existing == null) {
            db.recetteDao().inserer(draft.copy(id = 0, utilisateurId = userId, dateCreation = now, dateMiseAJour = now))
        } else {
            db.recetteDao().modifier(draft.copy(id = existing.id, utilisateurId = existing.utilisateurId, dateCreation = existing.dateCreation, dateMiseAJour = now))
            db.ligneRecetteDao().supprimerParRecette(existing.id)
            existing.id
        }
        db.ligneRecetteDao().insererToutes(lines.map { it.copy(id = 0, recetteId = id) })
        refreshSnapshot(id)
        id
    }

    /** Copie la recette et toutes ses lignes. Renvoie l'id de la copie. */
    suspend fun duplicateRecipe(recipeId: Long, newName: String): Long? = db.withTransaction {
        val original = db.recetteDao().parId(recipeId) ?: return@withTransaction null
        val now = System.currentTimeMillis()
        val id = db.recetteDao().inserer(original.copy(id = 0, nom = newName, dateCreation = now, dateMiseAJour = now))
        db.ligneRecetteDao().insererToutes(db.ligneRecetteDao().listePour(recipeId).map { it.copy(id = 0, recetteId = id) })
        id
    }

    suspend fun deleteRecipe(recette: Recette) = db.recetteDao().supprimer(recette)

    suspend fun setSellingPrice(recipeId: Long, priceMillimes: Long?) {
        val r = db.recetteDao().parId(recipeId) ?: return
        db.recetteDao().modifier(r.copy(prixVenteMillimes = priceMillimes, dateMiseAJour = System.currentTimeMillis()))
    }

    private suspend fun refreshSnapshot(recipeId: Long) {
        val r = db.recetteDao().parId(recipeId) ?: return
        val ingredients = db.ingredientDao().listePour(r.utilisateurId).associateBy { it.id }
        val cost = RecipeCosting.compute(r, db.ligneRecetteDao().listePour(recipeId), ingredients, db.utilisateurDao().trouver(r.utilisateurId))
        db.recetteDao().mettreAJourCout(recipeId, cost.costPerPortionMillimes ?: 0)
    }

    /** Recalcule le coût mémorisé des recettes qui utilisent ces ingrédients. Renvoie leur nombre. */
    private suspend fun refreshSnapshots(ingredientIds: Set<Long>): Int {
        val recipes = ingredientIds.flatMap { db.recetteDao().utilisant(it) }.distinctBy { it.id }
        recipes.forEach { refreshSnapshot(it.id) }
        return recipes.size
    }

    /** Après un changement du profil (loyer, volume) : tous les coûts mémorisés. */
    suspend fun refreshAllSnapshots() = db.withTransaction {
        db.recetteDao().listePour(userId).forEach { refreshSnapshot(it.id) }
    }

    // ---------- changements de prix groupés (facture, simulateur) ----------

    data class PriceUpdate(val ingredientId: Long, val newPriceMillimes: Long, val newPackageQuantity: Double? = null)

    data class PriceApplyResult(val updatedIngredients: Int, val impacts: List<RecipeImpact>)

    /**
     * Applique des nouveaux prix confirmés par l'utilisateur, dans UNE transaction :
     * prix → historique → coût mémorisé des recettes. Renvoie l'effet sur chaque recette touchée.
     */
    suspend fun applyPrices(updates: List<PriceUpdate>, source: String, factureId: Long? = null): PriceApplyResult {
        if (updates.isEmpty()) return PriceApplyResult(0, emptyList())
        return db.withTransaction {
            val before = snapshot()
            var count = 0
            for (u in updates) {
                val ing = db.ingredientDao().parId(u.ingredientId) ?: continue
                if (ing.utilisateurId != userId) continue
                val qty = u.newPackageQuantity ?: ing.quantiteEmballage
                if (ing.prixMillimes == u.newPriceMillimes && ing.quantiteEmballage == qty) continue
                db.ingredientDao().modifier(ing.copy(prixMillimes = u.newPriceMillimes, quantiteEmballage = qty, dateMiseAJour = System.currentTimeMillis()))
                db.prixHistoriqueDao().inserer(
                    PrixHistorique(
                        ingredientId = ing.id,
                        ancienPrixMillimes = ing.prixMillimes,
                        nouveauPrixMillimes = u.newPriceMillimes,
                        ancienneQuantite = ing.quantiteEmballage,
                        nouvelleQuantite = qty,
                        source = source,
                        factureId = factureId
                    )
                )
                count++
            }
            val changedIds = updates.map { it.ingredientId }.toSet()
            refreshSnapshots(changedIds)
            val after = snapshot()
            val impacts = after.recipes.filter { r -> after.linesByRecipe[r.id].orEmpty().any { it.ingredientId in changedIds } }
                .map { r -> RecipeImpact(r, before.cost(before.recipes.first { it.id == r.id }), after.cost(r)) }
            PriceApplyResult(count, impacts)
        }
    }

    // ---------- factures et synonymes ----------

    suspend fun saveInvoice(facture: Facture): Long = db.factureDao().inserer(facture.copy(utilisateurId = userId))

    suspend fun updateInvoice(facture: Facture) = db.factureDao().modifier(facture)

    fun invoices(): Flow<List<Facture>> = db.factureDao().parUtilisateur(userId)

    suspend fun deleteInvoice(facture: Facture) = db.factureDao().supprimer(facture)

    fun synonyms(): Flow<List<Synonyme>> = db.synonymeDao().parUtilisateur(userId)

    suspend fun synonymList(): List<Synonyme> = db.synonymeDao().listePour(userId)

    /** Mémorise « ce texte = cet ingrédient » (ignoré si le texte est le nom même de l'ingrédient). */
    suspend fun learnSynonym(text: String, ingredientId: Long) {
        val norm = TextNormalizer.normalize(text)
        if (norm.isEmpty()) return
        val ing = db.ingredientDao().parId(ingredientId) ?: return
        if (TextNormalizer.normalize(ing.nom) == norm) return
        db.synonymeDao().enregistrer(Synonyme(utilisateurId = userId, ingredientId = ingredientId, texte = text.trim(), texteNormalise = norm))
    }

    suspend fun deleteSynonym(s: Synonyme) = db.synonymeDao().supprimer(s)

    /** Crée un ingrédient depuis une ligne de facture (refuse les doublons de nom). */
    suspend fun createIngredient(draft: Ingredient): Long {
        val key = TextNormalizer.normalize(draft.nom)
        db.ingredientDao().listePour(userId).firstOrNull { TextNormalizer.normalize(it.nom) == key }?.let { return it.id }
        return db.ingredientDao().inserer(draft.copy(id = 0, utilisateurId = userId))
    }

    // ---------- comptes ----------

    /** Données du mode essai rattachées au nouveau compte. Renvoie le nombre d'éléments déplacés. */
    suspend fun transferGuestDataTo(newUserId: Long): Int = db.withTransaction {
        val t = db.transfertDao()
        val from = com.example.hesba.auth.SessionManager.ID_UTILISATEUR_INVITE
        val n = t.ingredients(from, newUserId) + t.recettes(from, newUserId)
        t.factures(from, newUserId)
        t.synonymes(from, newUserId)
        n
    }

    suspend fun guestDataCount(): Int = db.transfertDao().compter(com.example.hesba.auth.SessionManager.ID_UTILISATEUR_INVITE)

    suspend fun updateUser(user: Utilisateur) {
        db.utilisateurDao().modifier(user)
        refreshAllSnapshots()
    }

    suspend fun currentUser(): Utilisateur? = db.utilisateurDao().trouver(userId)

    @Suppress("unused")
    suspend fun firstWorkspace(): Workspace = workspace().first()
}
