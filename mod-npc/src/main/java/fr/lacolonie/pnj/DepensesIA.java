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

    /** Numéro de la dernière version préparée, et de la dernière écrite (une écriture lente ne remplace jamais une plus récente). */
    private transient long version, ecrite;
    /** Verrou du fichier seul : le fil du serveur (qui lit {@link #permis}) n'attend jamais le disque. */
    private final transient Object disque = new Object();

    /** Ajoute le coût d'un appel, et l'écrit (sur le fil qui reçoit la réponse : jamais celui du serveur). */
    void ajouter(double usd, LocalDate d) {
        String texte;
        long v;
        synchronized (this) {
            raz(d);
            if (!(usd > 0)) return;
            usd_mois += usd;
            usd_jour += usd;
            if (fichier == null) return;
            texte = GSON.toJson(this);
            v = ++version;
        }
        synchronized (disque) {
            if (v <= ecrite) return;
            try { Incompris.ecrire(fichier, texte); ecrite = v; }
            catch (IOException | RuntimeException e) { System.err.println("Dépenses de l'IA : écriture impossible (" + e + ")"); }
        }
    }

    synchronized double mois(LocalDate d) { raz(d); return usd_mois; }

    synchronized double jour(LocalDate d) { raz(d); return usd_jour; }

    /** Ce qu'on compte quand le fichier est abîmé : bien plus que tout budget, pour ne rien payer d'inconnu jusqu'au mois suivant. */
    static final double BLOQUE = 1e6;

    /** Relit le fichier ; absent ou vide : on repart de zéro ; présent mais illisible : plus d'appel payant jusqu'au mois suivant. */
    static DepensesIA charger(Path fichier) { return charger(fichier, LocalDate.now(java.time.ZoneOffset.UTC)); }

    static DepensesIA charger(Path fichier, LocalDate d) {
        DepensesIA r = null;
        String texte;
        try { texte = Files.exists(fichier) ? Files.readString(fichier, StandardCharsets.UTF_8) : ""; }
        catch (IOException | RuntimeException e) { texte = "?"; }
        if (!texte.isBlank()) { // vide : comme absent
            try { r = GSON.fromJson(texte, DepensesIA.class); }
            catch (RuntimeException e) { r = null; }
            if (r == null || r.mois == null) {
                System.err.println("Dépenses de l'IA illisibles (" + fichier + ") : plus de conversation payante jusqu'au mois prochain");
                r = new DepensesIA();
                r.mois = d.toString().substring(0, 7);
                r.usd_mois = BLOQUE;
            }
        }
        if (r == null) r = new DepensesIA();
        if (r.mois == null) r.mois = "";
        if (r.jour == null) r.jour = "";
        r.fichier = fichier;
        return r;
    }
}
