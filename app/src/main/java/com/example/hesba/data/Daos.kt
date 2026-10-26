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

    @Query("SELECT * FROM recette WHERE id = :id")
    suspend fun parId(id: Long): Recette?
}

@Dao
interface LigneRecetteDao {
    @Insert
    suspend fun inserer(ligne: LigneRecette): Long

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
