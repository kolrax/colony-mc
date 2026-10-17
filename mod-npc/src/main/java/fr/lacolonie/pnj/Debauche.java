package fr.lacolonie.pnj;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lot 14, cahier 5.8 et 6.2 : un autre joueur tente de débaucher l'ouvrier d'un chef (« viens travailler pour moi, je te
 * paie 20 pièces »), ou le paie pour un ordre ponctuel (« tiens, 10 pièces pour couper ce bois »). Ce qu'il propose
 * ({@link #lire}, {@link #pourboire}) et ce que l'ouvrier en décide ({@link #decider}) : la paie proposée contre la
 * sienne, son moral, son caractère, la réputation des deux chefs et ses liens dans la colonie. Pur Java (testé par
 * {@code DebaucheTest}) ; le jeu est dans {@link Etranger}.
 */
final class Debauche {
    private Debauche() {}

    /** Ce qu'on lui propose : {@code pieces} la paie promise (et versée d'avance), -1 si aucune somme n'est dite. */
    record Offre(long pieces) {}

    /** Une paie pour un ordre : la somme, et la phrase sans elle (ses nombres ne sont pas une quantité). */
    record Pourboire(long pieces, String reste) {}

    static final Pattern PROPOSE = Pattern.compile("\\b(?:viens? (?:travailler|bosser|trimer) (?:pour|chez|avec) moi|travaille pour moi|travaillez pour moi"
            + "|rejoins moi|rejoignez moi|me rejoindre|me rejoignes|me rejoigniez|viens chez moi|venez chez moi|(?:entre|passe|entrer|passer) a mon service"
            + "|quitte (?:ton|ta|votre) (?:chef|maitre|maitresse|patron|colonie)|je t embauche|je vous embauche|je te prends a mon service"
            + "|deviens mon ouvrier|change de (?:chef|maitre|patron))\\b");
    /** Une embauche dite sans somme : travailler pour lui, entrer à son service, quitter son chef. */
    static final Pattern SERVICE = Pattern.compile("\\b(?:viens? (?:travailler|bosser|trimer) (?:pour|chez) moi|travaille pour moi|travaillez pour moi"
            + "|(?:entre|passe|entrer|passer) a mon service|quitte (?:ton|ta|votre) (?:chef|maitre|maitresse|patron|colonie)|je t embauche|je vous embauche"
            + "|je te prends a mon service|deviens mon ouvrier|change de (?:chef|maitre|patron))\\b");
    /** « 20 pièces », « 20 pieces d'or » : la somme (en pièces, comme les prix de la boutique). */
    static final Pattern SOMME = Pattern.compile("\\b(\\d{1,5}) ?(?:pieces?|sous|ecus|deniers)\\b");
    /** « Tiens, 10 pièces pour couper ce bois » : de l'argent donné avec la phrase. */
    static final Pattern DONNE = Pattern.compile("\\b(?:tiens|tenez|voici|voila|prends|prenez|je te donne|je te paie|je te paye|je vous donne|contre|pour)\\b");

    /** Une proposition de débauche, ou null. */
    static Offre lire(String texte) {
        String n = Ordres.normaliser(texte);
        if (!PROPOSE.matcher(n).find()) return null;
        Matcher m = SOMME.matcher(n);
        if (m.find()) return new Offre(Long.parseLong(m.group(1)));
        // Sans somme, il faut que ce soit clairement une embauche (« viens me rejoindre au four » n'en est pas une).
        return SERVICE.matcher(n).find() ? new Offre(-1) : null;
    }

    /** De l'argent proposé pour un ordre (pas une débauche), ou null. */
    static Pourboire pourboire(String texte) {
        if (lire(texte) != null) return null;
        Matcher m = Pattern.compile("(?i)\\b(\\d{1,5}) ?(?:pi[eè]ces?|sous|écus|ecus|deniers)\\b").matcher(texte);
        if (!m.find() || !DONNE.matcher(Ordres.normaliser(texte)).find()) return null;
        String reste = (texte.substring(0, m.start()) + texte.substring(m.end()))
                .replaceAll("(?i)\\b(?:tiens|tenez|voici|voilà|voila|prends|prenez|je te donne|je te paie|je te paye|je vous donne|contre)\\b[ ,]*", " ")
                .replaceAll("(?i)^[\\s,.;:!]*(?:pour)\\b", " ").replaceAll("\\s{2,}", " ").strip();
        return new Pourboire(Long.parseLong(m.group(1)), reste);
    }

    /** Pourquoi il accepte, ou pourquoi il refuse (la réplique {@code debauche_<raison>} d'etrangers.txt). */
    enum Raison { ACCEPTE, SOMME, GUERRE, RANCUNE, PLACE, PAIE, LIENS, REPUTATION, FIDELE }

    /**
     * Ce qu'il sait au moment de choisir. {@code reputations} : celle de son chef, puis celle de l'autre (−50 à +50) ;
     * {@code amis} : ses amis dans la colonie ; {@code place} : l'autre a un poste et de la place sous son plafond.
     */
    record Cas(long offre, long salaire, int moral, String temperament, int repChef, int repAutre, boolean couple, int amis,
               boolean enGuerre, boolean rancune, boolean place) {}

    /** Au moins 30 % de plus que sa paie, avant tout le reste ; jamais moins de 10 % de plus. */
    static final double BASE = 1.3, PLANCHER = 1.1;

    /** Ce qui le retient, en part de paie en plus à offrir : son moral et son caractère. */
    static double fidelite(Cas c) {
        double t = switch (c.temperament()) { case "fourbe" -> -0.15; case "calme" -> 0.15; case "peureux" -> 0.05; default -> 0; };
        return (Math.clamp(c.moral(), 0, 100) - 50) / 100.0 + t;
    }

    /** Ses liens : en couple +0,5, chaque ami +0,1 (0,3 au plus). */
    static double attaches(Cas c) { return (c.couple() ? 0.5 : 0) + Math.min(3, Math.max(0, c.amis())) * 0.1; }

    /** Ce qu'on dit des deux chefs : un chef mieux réputé que l'autre le retient (jusqu'à +1). */
    static double renom(Cas c) { return (Math.clamp(c.repChef(), Recrutement.MIN, Recrutement.MAX) - Math.clamp(c.repAutre(), Recrutement.MIN, Recrutement.MAX)) / 100.0; }

    /** La paie qu'il faudrait lui offrir, rapportée à la sienne. */
    static double seuil(Cas c) { return Math.max(PLANCHER, BASE + fidelite(c) + attaches(c) + renom(c)); }

    static Raison decider(Cas c) {
        if (c.enGuerre()) return Raison.GUERRE;
        if (c.rancune()) return Raison.RANCUNE;
        if (c.offre() < 0) return Raison.SOMME;
        if (c.offre() >= seuil(c) * Math.max(1, c.salaire())) return c.place() ? Raison.ACCEPTE : Raison.PLACE;
        double f = fidelite(c), a = attaches(c), r = renom(c);
        if (a >= 0.2 && a >= f && a >= r) return Raison.LIENS;
        if (r >= 0.15 && r >= f) return Raison.REPUTATION;
        if (f >= 0.15) return Raison.FIDELE;
        return Raison.PAIE;
    }

    /** Une paie pour un ordre suffit-elle ? La moitié de sa paie au moins (une pièce au moins). */
    static boolean assez(long pieces, long salaire) { return pieces >= Math.max(1, (salaire + 1) / 2); }
}
