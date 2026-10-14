package fr.lacolonie.pnj;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Le chef demande l'impossible (cahier 14.9 : « il en rit, fait une blague ») : voler, aller sur la lune, casser la roche
 * du fond du monde… {@link #demande} trouve le genre par mots-clés, sans IA ; l'ouvrier répond par une réplique
 * {@code impossible_<genre>} de remarques.txt, sans obéir ({@code Travailleur.obeir}). Prudent : seulement si
 * l'interpréteur n'a rien trouvé de faisable dans la phrase, jamais sur une question, et chaque proposition est lue à part
 * (« Arrête, la pluie arrive » n'est pas « arrête la pluie »). Un ordre ordinaire n'y est jamais pris. Pur Java.
 */
final class Impossible {
    private Impossible() {}

    /** Les genres, dans l'ordre où on les cherche (« change-toi en dragon » : de la magie avant la créature). */
    static final List<Object[]> GENRES = List.of(
            new Object[]{"roche", Pattern.compile(" (?:casse|cassez|casser|creuse|creusez|creuser|mine|minez|miner|perce|percez|percer|brise|brisez|briser|detruis|detruisez|"
                    + "detruire|traverse|traverser|pioche|piocher) (?:moi |nous )?(?:la |le |de la |du |dans la |dans le |a travers la |a travers le |sous la |sous le )?"
                    + "(?:bedrock|roche mere|roche du fond|fond du monde|pierre du fond|socle du monde|roche indestructible|pierre indestructible) ")},
            new Object[]{"voler", Pattern.compile(" (?:s |t |vous |te )?envole| (?:tes|vos) ailes | (?:te|toi|vous) (?:faire )?pousser des ailes | (?:vole|volez|voler) (?:dans le ciel|"
                    + "dans les airs|dans l air|comme un oiseau|jusqu|au dessus|par dessus|la haut|tout la haut|vers le ciel|au ciel|dans les nuages)| (?:apprends|apprenez) a voler "
                    + "| pour voler $")},
            new Object[]{"lune", Pattern.compile(" (?:sur|decroche|decrocher|decrochez|attrape|attraper|ramene|ramener|rapporte|apporte|cueille|cherche|chercher|touche|jusqu a|va|aller) "
                    + "(?:moi |nous )?(?:la lune|une etoile(?! du nether)|les etoiles(?! du nether)|des etoiles(?! du nether)) | (?:decroche|decrocher|attrape|attraper) (?:moi )?le soleil ")},
            new Object[]{"nature", Pattern.compile("^ (?:arrete|arretez|arreter|stoppe|empeche|fais cesser|faites cesser|fais partir|chasse) (?:la pluie|l orage|la neige|le vent|les nuages|"
                    + "le tonnerre|le temps|l hiver) $|^ (?:fais|faites) (?:lever|venir|revenir|briller) (?:le soleil|le jour) $|^ (?:fais|faites) (?:pleuvoir|neiger|le beau temps) $"
                    + "|^ change (?:le temps|la meteo|le ciel) $|^ (?:remonte|recule) le temps $")},
            new Object[]{"magie", Pattern.compile(" (?:change|changer|changez|transforme|transformer|transformez) (?:la |le |les |cette |ce |ces |du |de la )?(?:pierre|pierres|terre|bois|"
                    + "sable|fer|cuivre|charbon|eau|plomb|caillou|cailloux|roche|gravier) en (?:or|diamant|diamants|emeraude|emeraudes) "
                    + "| (?:sois|soyez|etre) a deux endroits | (?:deviens|devenir|devenez|rends toi|rendez vous) invisible | (?:jette|lance|jeter|lancer) un sort "
                    + "| fais de la magie | (?:change|transforme) toi en (?:un |une )?(?:oiseau|poisson|chat|loup|cochon|grenouille|crapaud|souris|statue|arbre|dragon) ")},
            new Object[]{"creature", Pattern.compile(" (?:ramene|ramener|rapporte|apporte|attrape|attraper|capture|capturer|apprivoise|apprivoiser|dresse|dresser|chevauche|monte sur|"
                    + "deviens|trouve|va chercher|vas chercher|eleve) (?:moi |nous )?(?:un |une |des |le |la |les )?(?:dragon|dragons|licorne|licornes|phenix|griffon|griffons|"
                    + "chimere|hydre|sirene|sirenes|basilic|pegase|kraken) ")},
            new Object[]{"quantite", Pattern.compile(" (?:mille|un million|des millions|un milliard|des milliards|des milliers de|cent mille|\\d{4,}) (?:d )?(?:diamant|diamants|emeraude|"
                    + "emeraudes|lingots d or|pepites d or|netherite) | (?:un million|des millions|un milliard|des milliards) | (?:tous les arbres du monde|tous les diamants du monde|"
                    + "le monde entier|toute la mer|tout l ocean) ")},
            new Object[]{"duree", Pattern.compile(" (?:(?:2[5-9]|[3-9]\\d|\\d{3,}) ?h|(?:2[5-9]|[3-9]\\d|\\d{3,}) heures|quarante huit heures|cent heures|mille heures) "
                    + "(?!cette semaine|par semaine|dans la semaine|ce mois|par mois|en tout)| sans jamais dormir | pour l eternite | jusqu a la fin (?:des temps|du monde) ")},
            new Object[]{"vite", Pattern.compile(" (?:construis|construire|construisez|batis|batir|batissez|eleve|fais|monte) (?:moi |nous )?(?:un |une |le |la |mon |ma |ton |ta )?"
                    + "(?:chateau|cathedrale|forteresse|palais|donjon|ville|cite|muraille|rempart) en (?:une|1|deux|2|trois|3|cinq|5|dix|10) (?:minute|minutes|seconde|secondes) ")},
            new Object[]{"general", Pattern.compile(" (?:un|des|le) miracles? | (?:deplace|souleve) (?:la montagne|les montagnes|le chateau|la maison) "
                    + "| (?:vide|bois|asseche) (?:la mer|l ocean) | marche sur l eau | respire sous l eau ")});

    /** Les genres qui restent impossibles même si l'interpréteur y lit un ordre (« ramène mille diamants » : une mission ; « travaille 48 h » : au travail). */
    static final Set<String> MALGRE_ORDRE = Set.of("quantite", "duree", "magie");

    /**
     * Le genre d'impossible que le chef demande (« lune », « voler »… ou « general »), ou null : une question, un ordre
     * faisable lu par l'interpréteur ({@code lu}), ou rien d'impossible.
     */
    static String demande(String texte, Ordres.Lecture lu) {
        if (texte == null || texte.contains("?")) return null;
        String g = genre(texte);
        return g == null || faisable(lu) && !MALGRE_ORDRE.contains(g) ? null : g;
    }

    /** Vrai si l'interpréteur a lu quelque chose à faire : intention, mission, horaires, règle, ou une action du répertoire qui n'est pas qu'une information (« la lune » : sa phase). */
    static boolean faisable(Ordres.Lecture l) {
        if (!l.intentions.isEmpty() || !l.missions.isEmpty() || l.debut >= 0 || l.fin >= 0 || l.jours >= 0 || !l.horsMetier.isEmpty() || !l.regles.isEmpty() || l.annulerRegles) return true;
        for (Ordres.Faire f : l.faire) {
            Repertoire.Entree e = Repertoire.PAR_ID.get(f.id());
            if (e == null || !e.comportement().equals("info")) return true;
        }
        return false;
    }

    /** Le genre d'impossible de la phrase, proposition par proposition (sans regarder si l'interpréteur y lit un ordre), ou null. */
    static String genre(String texte) {
        if (texte == null || texte.isBlank()) return null;
        for (String morceau : texte.split("(?i)[,.;:!?\\n]+|\\s(?:et|puis|mais|ensuite)\\s")) {
            String n = Ordres.normaliser(morceau);
            for (Object[] g : GENRES) if (((Pattern) g[1]).matcher(n).find()) return (String) g[0];
        }
        return null;
    }
}
