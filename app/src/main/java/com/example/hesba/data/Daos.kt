package com.example.hesba.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UtilisateurDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserer(utilisateur: Utilisateur): Long

    @Update
    suspend fun modifier(utilisateur: Utilisateur)

    @Query("SELECT * FROM utilisateur WHERE email = :email LIMIT 1")
    suspend fun parEmail(email: String): Utilisateur?

    @Query("SELECT * FROM utilisateur WHERE id = :userId LIMIT 1")
    fun parId(userId: Long): Flow<Utilisateur?>

    @Query("SELECT * FROM utilisateur WHERE id = :userId LIMIT 1")
    suspend fun trouver(userId: Long): Utilisateur?

    @Query("UPDATE utilisateur SET motDePasseHash = :nouveauHash WHERE id = :userId")
    suspend fun modifierMotDePasse(userId: Long, nouveauHash: String)
}

/** Ligne d'ingrédient + nombre de recettes qui l'utilisent. */
data class IngredientUsage(val ingredientId: Long, val recettes: Int)

@Dao
interface IngredientDao {
    @Insert
    suspend fun inserer(ingredient: Ingredient): Long

    @Update
    suspend fun modifier(ingredient: Ingredient)

    @Delete
    suspend fun supprimer(ingredient: Ingredient)

    @Query("SELECT * FROM ingredient WHERE id = :id")
    suspend fun parId(id: Long): Ingredient?

    @Query("SELECT * FROM ingredient WHERE utilisateurId = :userId ORDER BY nom COLLATE NOCASE")
    fun parUtilisateur(userId: Long): Flow<List<Ingredient>>

    @Query("SELECT * FROM ingredient WHERE utilisateurId = :userId ORDER BY nom COLLATE NOCASE")
    suspend fun listePour(userId: Long): List<Ingredient>

    @Query("SELECT ingredientId, COUNT(DISTINCT recetteId) AS recettes FROM ligne_recette GROUP BY ingredientId")
    fun usages(): Flow<List<IngredientUsage>>
}

@Dao
interface RecetteDao {
    @Insert
    suspend fun inserer(recette: Recette): Long

    @Update
    suspend fun modifier(recette: Recette)

    @Delete
    suspend fun supprimer(recette: Recette)

    @Query("SELECT * FROM recette WHERE id = :id")
    suspend fun parId(id: Long): Recette?

    @Query("SELECT * FROM recette WHERE id = :id")
    fun suivre(id: Long): Flow<Recette?>

    @Query("SELECT * FROM recette WHERE utilisateurId = :userId ORDER BY nom COLLATE NOCASE")
    fun parUtilisateur(userId: Long): Flow<List<Recette>>

    @Query("SELECT * FROM recette WHERE utilisateurId = :userId ORDER BY nom COLLATE NOCASE")
    suspend fun listePour(userId: Long): List<Recette>

    @Query(
        "SELECT r.* FROM recette r WHERE r.id IN " +
            "(SELECT DISTINCT recetteId FROM ligne_recette WHERE ingredientId = :ingredientId) ORDER BY r.nom COLLATE NOCASE"
    )
    suspend fun utilisant(ingredientId: Long): List<Recette>

    @Query("UPDATE recette SET coutParPartMillimes = :cout, dateMiseAJour = :date WHERE id = :id")
    suspend fun mettreAJourCout(id: Long, cout: Long, date: Long = System.currentTimeMillis())
}

@Dao
interface LigneRecetteDao {
    @Insert
    suspend fun inserer(ligne: LigneRecette): Long

    @Insert
    suspend fun insererToutes(lignes: List<LigneRecette>)

    @Query("SELECT * FROM ligne_recette WHERE recetteId = :recetteId ORDER BY id")
    fun parRecette(recetteId: Long): Flow<List<LigneRecette>>

    @Query("SELECT * FROM ligne_recette WHERE recetteId = :recetteId ORDER BY id")
    suspend fun listePour(recetteId: Long): List<LigneRecette>

    @Query(
        "SELECT l.* FROM ligne_recette l INNER JOIN recette r ON r.id = l.recetteId " +
            "WHERE r.utilisateurId = :userId ORDER BY l.id"
    )
    fun toutesPour(userId: Long): Flow<List<LigneRecette>>

    @Query(
        "SELECT l.* FROM ligne_recette l INNER JOIN recette r ON r.id = l.recetteId " +
            "WHERE r.utilisateurId = :userId ORDER BY l.id"
    )
    suspend fun listeToutesPour(userId: Long): List<LigneRecette>

    @Query("DELETE FROM ligne_recette WHERE recetteId = :recetteId")
    suspend fun supprimerParRecette(recetteId: Long)
}

@Dao
interface PrixHistoriqueDao {
    @Insert
    suspend fun inserer(entree: PrixHistorique): Long

    @Query(
        "SELECT h.* FROM prix_historique h INNER JOIN ingredient i ON i.id = h.ingredientId " +
            "WHERE i.utilisateurId = :userId AND h.date >= :depuis ORDER BY h.date DESC"
    )
    fun depuis(userId: Long, depuis: Long): Flow<List<PrixHistorique>>

    @Query(
        "SELECT h.* FROM prix_historique h INNER JOIN ingredient i ON i.id = h.ingredientId " +
            "WHERE i.utilisateurId = :userId ORDER BY h.date DESC"
    )
    fun tout(userId: Long): Flow<List<PrixHistorique>>

    @Query("SELECT * FROM prix_historique WHERE ingredientId = :ingredientId ORDER BY date DESC")
    fun pourIngredient(ingredientId: Long): Flow<List<PrixHistorique>>
}

@Dao
interface FactureDao {
    @Insert
    suspend fun inserer(facture: Facture): Long

    @Update
    suspend fun modifier(facture: Facture)

    @Delete
    suspend fun supprimer(facture: Facture)

    @Query("SELECT * FROM facture WHERE utilisateurId = :userId ORDER BY dateImport DESC")
    fun parUtilisateur(userId: Long): Flow<List<Facture>>
}

@Dao
interface SynonymeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enregistrer(synonyme: Synonyme): Long

    @Delete
    suspend fun supprimer(synonyme: Synonyme)

    @Query("SELECT * FROM synonyme WHERE utilisateurId = :userId ORDER BY texte COLLATE NOCASE")
    fun parUtilisateur(userId: Long): Flow<List<Synonyme>>

    @Query("SELECT * FROM synonyme WHERE utilisateurId = :userId")
    suspend fun listePour(userId: Long): List<Synonyme>
}

/** Rattache les données du mode essai (invité) à un compte qui vient d'être créé. */
@Dao
interface TransfertDao {
    @Query("UPDATE ingredient SET utilisateurId = :to WHERE utilisateurId = :from")
    suspend fun ingredients(from: Long, to: Long): Int

    @Query("UPDATE recette SET utilisateurId = :to WHERE utilisateurId = :from")
    suspend fun recettes(from: Long, to: Long): Int

    @Query("UPDATE facture SET utilisateurId = :to WHERE utilisateurId = :from")
    suspend fun factures(from: Long, to: Long): Int

    @Query("UPDATE synonyme SET utilisateurId = :to WHERE utilisateurId = :from")
    suspend fun synonymes(from: Long, to: Long): Int

    @Query("SELECT (SELECT COUNT(*) FROM ingredient WHERE utilisateurId = :userId) + (SELECT COUNT(*) FROM recette WHERE utilisateurId = :userId)")
    suspend fun compter(userId: Long): Int
}
