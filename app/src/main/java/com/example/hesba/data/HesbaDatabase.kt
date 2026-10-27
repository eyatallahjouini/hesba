package com.example.hesba.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Utilisateur::class,
        Ingredient::class,
        Recette::class,
        LigneRecette::class,
        Vente::class
    ],
    version = 1,
    exportSchema = true
)
abstract class HesbaDatabase : RoomDatabase() {

    abstract fun utilisateurDao(): UtilisateurDao
    abstract fun ingredientDao(): IngredientDao
    abstract fun recetteDao(): RecetteDao
    abstract fun ligneRecetteDao(): LigneRecetteDao

    companion object {
        @Volatile private var INSTANCE: HesbaDatabase? = null

        fun getInstance(context: Context): HesbaDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    HesbaDatabase::class.java,
                    "hesba.db"
                ).build().also { INSTANCE = it }
            }
    }
}
