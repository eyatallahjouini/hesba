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

    @Query("SELECT * FROM utilisateur LIMIT 1")
    fun utilisateurCourant(): Flow<Utilisateur?>
    @Query("SELECT * FROM utilisateur WHERE id = :userId LIMIT 1")
    fun parId(userId: Long): Flow<Utilisateur?>
    // Mise à jour de la structure d'activité et de la paie
    @Query("""
    UPDATE utilisateur 
    SET activite = :activite, 
        nombreEmployes = :nombreEmployes, 
        salaireMoyenEmploye = :salaireMoyenEmploye 
    WHERE id = :userId
""")
    suspend fun modifierStructure(userId: Long, activite: String, nombreEmployes: Int, salaireMoyenEmploye: Double)

    // Mise à jour du mot de passe
    @Query("UPDATE utilisateur SET motDePasseHash = :nouveauHash WHERE id = :userId")
    suspend fun modifierMotDePasse(userId: Long, nouveauHash: String)
}

@Dao
interface IngredientDao {
    @Insert
    suspend fun inserer(ingredient: Ingredient): Long

    @Update
    suspend fun modifier(ingredient: Ingredient)

    @Delete
    suspend fun supprimer(ingredient: Ingredient)

    @Query("SELECT * FROM ingredient ORDER BY nom")
    fun tous(): Flow<List<Ingredient>>

    @Query("SELECT * FROM ingredient WHERE id = :id")
    suspend fun parId(id: Long): Ingredient?
    @Query("SELECT * FROM ingredient WHERE utilisateurId = :userId ORDER BY nom ASC")
    fun getIngredientsParUtilisateur(userId: Long): Flow<List<Ingredient>>
}

@Dao
interface RecetteDao {
    @Insert
    suspend fun inserer(recette: Recette): Long

    @Update
    suspend fun modifier(recette: Recette)

    @Delete
    suspend fun supprimer(recette: Recette)

    @Query("SELECT * FROM recette ORDER BY nom")
    fun toutes(): Flow<List<Recette>>
    // Récupère uniquement les recettes de l'utilisateur connecté
    @Query("SELECT * FROM recette WHERE utilisateurId = :utilisateurId ORDER BY nom")
    fun parUtilisateur(utilisateurId: Long): Flow<List<Recette>>
    @Query("SELECT * FROM recette WHERE id = :id")
    suspend fun parId(id: Long): Recette?
    @Query("UPDATE recette SET prixVente = :prixVente WHERE id = :id")

    suspend fun mettreAJourPrixVente(id: Long, prixVente: Double)
    @Query("SELECT * FROM recette WHERE utilisateurId = :userId ORDER BY nom ASC")
    fun getRecettesParUtilisateur(userId: Long): Flow<List<Recette>>

}

@Dao
interface LigneRecetteDao {
    @Insert
    suspend fun inserer(ligne: LigneRecette): Long

    @Insert
    suspend fun insererToutes(lignes: List<LigneRecette>)
    @Insert
    suspend fun insererTout(lignes: List<LigneRecette>)

    @Update
    suspend fun modifier(ligne: LigneRecette)

    @Delete
    suspend fun supprimer(ligne: LigneRecette)

    @Query("SELECT * FROM ligne_recette WHERE recetteId = :recetteId")
    fun parRecette(recetteId: Long): Flow<List<LigneRecette>>

    @Query("DELETE FROM ligne_recette WHERE recetteId = :recetteId")
    suspend fun supprimerParRecette(recetteId: Long)
}
