package fr.lacolonie.pnj;

import com.google.gson.JsonObject;

/** Partie pure de /rapport (sans Minecraft), testée par RapportsTest. */
final class Rapport {
    private Rapport() {}

    static final int MAX = 500;
    static final long ATTENTE_MS = 10_000;
    /** Pourquoi le rapport est refusé, ou null s'il passe (pur, testé). */
    static String refus(String texte, long dernier, long maintenant) {
        if (texte == null || texte.isBlank()) return "Écris ce que tu as vu : /rapport <texte>";
        if (texte.length() > MAX) return "Trop long : " + MAX + " caractères au plus.";
        if (maintenant - dernier < ATTENTE_MS) return "Attends quelques secondes avant un autre rapport.";
        return null;
    }

    /** La ligne JSON du rapport (pure, testée). {@code pnj} peut être null. */
    static String ligne(String date, String joueur, String dimension, int x, int y, int z, String version, String texte, JsonObject pnj) {
        JsonObject o = new JsonObject();
        o.addProperty("date", date);
        o.addProperty("joueur", joueur);
        o.addProperty("dimension", dimension);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("version", version);
        o.addProperty("texte", texte);
        if (pnj != null) o.add("pnj", pnj);
        return o.toString();
    }
}
