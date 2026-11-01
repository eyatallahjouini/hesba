
package com.example.hesba
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.Recette
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.View
import android.view.LayoutInflater
import java.util.Locale
import android.graphics.Color
class MesRecettesActivity : AppCompatActivity() {

    private lateinit var db: HesbaDatabase
    private lateinit var tvNombreRecettes: TextView
    private lateinit var tvAucuneRecette: TextView
    private lateinit var containerRecettes: LinearLayout
    private lateinit var btnNouvelle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mes_recettes)

        db = HesbaDatabase.getInstance(this)

        tvNombreRecettes = findViewById(R.id.tvNombreRecettes)
        tvAucuneRecette = findViewById(R.id.tvAucuneRecette)
        containerRecettes = findViewById(R.id.containerRecettes)
        btnNouvelle = findViewById(R.id.btnNouvelle)

        // Écoute de la liste des recettes en temps réel
        lifecycleScope.launch {
            db.recetteDao().toutes().collect { recettes ->
                afficherRecettes(recettes)
            }
        }

        btnNouvelle.setOnClickListener {
                val intent = Intent(this, NouvelleRecetteActivity::class.java)
                startActivity(intent)
        }
    }

    private suspend fun afficherRecettes(recettes: List<Recette>) {
        containerRecettes.removeAllViews()

        if (recettes.isEmpty()) {
            tvNombreRecettes.text = "0 recette enregistrée"
            tvAucuneRecette.visibility = View.VISIBLE
            containerRecettes.visibility = View.GONE
        } else {
            val total = recettes.size
            tvNombreRecettes.text = "$total recette${if (total > 1) "s" else ""} enregistrée${if (total > 1) "s" else ""}"
            tvAucuneRecette.visibility = View.GONE
            containerRecettes.visibility = View.VISIBLE

            val inflater = LayoutInflater.from(this)

            for (recette in recettes) {
                val itemView = inflater.inflate(R.layout.item_recette, containerRecettes, false)

                val tvNom = itemView.findViewById<TextView>(R.id.tvNomRecette)
                val tvParts = itemView.findViewById<TextView>(R.id.tvNombreParts)
                val tvBadgeMarge = itemView.findViewById<TextView>(R.id.tvBadgeMarge)
                val tvLabelCout = itemView.findViewById<TextView>(R.id.tvLabelCout)
                val tvCoutParPart = itemView.findViewById<TextView>(R.id.tvCoutParPart)
                val tvPrixVente = itemView.findViewById<TextView>(R.id.tvPrixVente)

                tvNom.text = recette.nom
                tvParts.text = "${recette.nombreParts} parts"

                tvCoutParPart.text = String.format(Locale.FRANCE, "%.2f dt", recette.coutParPart)
                tvPrixVente.text=String.format(Locale.FRANCE, "%.2f dt", recette.prixVente)
                // Style du badge de marge
                val margeAffichage = recette.margeVoulue?.toInt() ?: 0
                if (margeAffichage >= 20) {
                    tvBadgeMarge.text = "▲ Marge $margeAffichage %"
                    tvBadgeMarge.setBackgroundResource(R.drawable.bg_badge_green)
                    tvBadgeMarge.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    tvBadgeMarge.text = "▼ Marge $margeAffichage %"
                    tvBadgeMarge.setBackgroundResource(R.drawable.bg_badge_red)
                    tvBadgeMarge.setTextColor(Color.parseColor("#C62828"))
                }

                containerRecettes.addView(itemView)
            }
        }
    }


}
