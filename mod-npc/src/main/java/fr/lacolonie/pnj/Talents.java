package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Lot 13, cahier 5.8 (« PNJ un peu plus rares, et avec des compétences propres à eux ») : un ou deux talents tirés à
 * l'arrivée d'un chercheur d'emploi, gardés toute sa vie (sauvegardés « Talents »). Chacun a un effet simple, appliqué à
 * un seul endroit de {@link Travailleur} : la vitesse de travail, la vie, la fatigue ou la faim. Pur Java.
 */
final class Talents {
    static final String HABILE = "habile", ROBUSTE = "robuste", ENDURANT = "endurant", SOBRE = "sobre";
    static final List<String> TOUS = List.of(HABILE, ROBUSTE, ENDURANT, SOBRE);
    /** Habile : travaille 20 % plus vite. Robuste : 6 points de vie de plus. Endurant : 40 % de fatigue en moins. Sobre : la faim vient moitié moins vite. */
    static final double VITESSE = 1.2, FATIGUE = 0.6, FAIM = 1.5;
    static final int VIE = 6;
    /** Un chercheur sur quatre a deux talents. */
    static final double DEUX = 0.25;

    private Talents() {}

    /** Un ou deux talents distincts, au hasard. */
    static List<String> tirer(DoubleSupplier hasard) {
        List<String> r = new ArrayList<>();
        r.add(TOUS.get(index(hasard.getAsDouble(), TOUS.size())));
        if (hasard.getAsDouble() < DEUX) {
            List<String> reste = new ArrayList<>(TOUS);
            reste.removeAll(r);
            r.add(reste.get(index(hasard.getAsDouble(), reste.size())));
        }
        return List.copyOf(r);
    }

    private static int index(double h, int n) { return Math.clamp((int) (h * n), 0, n - 1); }

    static double vitesse(List<String> t) { return t.contains(HABILE) ? VITESSE : 1; }

    static int vie(List<String> t) { return t.contains(ROBUSTE) ? VIE : 0; }

    /** La fatigue gagnée en une heure de travail (jamais nulle s'il en gagnait). */
    static int fatigue(int gain, List<String> t) { return gain > 0 && t.contains(ENDURANT) ? Math.max(1, (int) Math.round(gain * FATIGUE)) : gain; }

    /** Le compte à atteindre avant de perdre un point de faim. */
    static int seuilFaim(int seuil, List<String> t) { return t.contains(SOBRE) ? (int) Math.round(seuil * FAIM) : seuil; }

    /** Pour la sauvegarde : « habile,robuste ». */
    static String texte(List<String> t) { return String.join(",", t); }

    /** Relu de la sauvegarde : seulement des talents connus, sans doublon, deux au plus (vide pour un ouvrier d'avant ce lot). */
    static List<String> lire(String s) {
        List<String> r = new ArrayList<>();
        if (s == null) return List.of();
        for (String x : s.split(",")) {
            String k = x.trim();
            if (TOUS.contains(k) && !r.contains(k) && r.size() < 2) r.add(k);
        }
        return List.copyOf(r);
    }

    /** Ce qu'on voit de lui sur sa fiche (texte de l'attitude), vide sans talent. */
    static String portrait(List<String> t) {
        List<String> p = new ArrayList<>();
        for (String k : t)
            p.add(switch (k) {
                case HABILE -> "Il a la main vive : l'ouvrage avance vite avec lui.";
                case ROBUSTE -> "Il est bâti comme un chêne.";
                case ENDURANT -> "Il tient la peine longtemps sans faiblir.";
                case SOBRE -> "Il mange peu et ne s'en plaint pas.";
                default -> "";
            });
        return String.join(" ", p).trim();
    }
}
