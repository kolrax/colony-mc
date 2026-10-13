package fr.lacolonie.pnj;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Ce que coûte la conversation payante (OpenRouter), en dollars : la dépense du mois (calendaire, UTC) et du jour.
 * Gardée dans config/colonie_pnj/depenses-ia.json (aucune donnée de joueur), relue au démarrage. Au-delà de 95 % du
 * budget du mois, ou du plafond du jour (budget / 30 × 2), plus d'appel payant. Pur Java.
 */
final class DepensesIA {
    String mois = "", jour = "";
    double usd_mois, usd_jour;
    /** Où l'écrire ; null : en mémoire seulement. */
    transient Path fichier;

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Un nouveau mois ou un nouveau jour : on repart de zéro. */
    synchronized void raz(LocalDate d) {
        String m = d.toString().substring(0, 7), j = d.toString();
        if (!m.equals(mois)) { mois = m; usd_mois = 0; }
        if (!j.equals(jour)) { jour = j; usd_jour = 0; }
    }

    static double plafondJour(double budgetMois) { return budgetMois / 30 * 2; }

    /** Vrai s'il reste de quoi payer un appel (0 au budget : jamais). */
    synchronized boolean permis(double budgetMois, LocalDate d) {
        raz(d);
        return budgetMois > 0 && usd_mois < budgetMois * 0.95 && usd_jour < plafondJour(budgetMois);
    }

    /** Ajoute le coût d'un appel, et l'écrit (sur le fil qui reçoit la réponse : jamais celui du serveur). */
    synchronized void ajouter(double usd, LocalDate d) {
        raz(d);
        if (!(usd > 0)) return;
        usd_mois += usd;
        usd_jour += usd;
        if (fichier == null) return;
        try { Incompris.ecrire(fichier, GSON.toJson(this)); }
        catch (IOException | RuntimeException e) { System.err.println("Dépenses de l'IA : écriture impossible (" + e + ")"); }
    }

    synchronized double mois(LocalDate d) { raz(d); return usd_mois; }

    synchronized double jour(LocalDate d) { raz(d); return usd_jour; }

    /** Relit le fichier ; absent ou illisible : on repart de zéro. */
    static DepensesIA charger(Path fichier) {
        DepensesIA r = null;
        try { if (Files.exists(fichier)) r = GSON.fromJson(Files.readString(fichier, StandardCharsets.UTF_8), DepensesIA.class); }
        catch (IOException | RuntimeException e) { System.err.println("Dépenses de l'IA illisibles, on repart de zéro (" + e + ")"); }
        if (r == null) r = new DepensesIA();
        if (r.mois == null) r.mois = "";
        if (r.jour == null) r.jour = "";
        r.fichier = fichier;
        return r;
    }
}
