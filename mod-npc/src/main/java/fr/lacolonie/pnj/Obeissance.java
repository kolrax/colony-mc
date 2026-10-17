package fr.lacolonie.pnj;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Obéit-il ? (refonte 1.0.0) Toujours, sauf à un ordre qui le tuerait (sauter dans la lave), qu'il refuse en disant
 * pourquoi. Aussi : les bornes de la satisfaction (0 à 100) et les phrases qui répondent à une proposition en suspens.
 * Pur Java, déterministe (testé seul).
 */
final class Obeissance {
    private Obeissance() {}

    // --- Le moral -----------------------------------------------------------------------------------------------

    static final int DEPART = 70, REPOS = 60;

    static int moral(int moral, int delta) { return Math.clamp((long) moral + delta, 0, 100); }

    /** La satisfaction visée (refonte 1.0.0) : la paie d'abord, puis la nourriture (faim de 0 à 20). */
    static int cible(int impayes, int faim) { return impayes > 0 ? 20 : faim <= 6 ? 40 : faim <= 12 ? 70 : 100; }

    /** Toutes les 5 s, la satisfaction va de 2 points au plus vers sa cible. */
    static int rapprocher(int moral, int cible) { return moral + Integer.signum(cible - moral) * Math.min(2, Math.abs(cible - moral)); }


    // --- Les phrases ------------------------------------------------------------------------------------------------

    /** Le chef accepte le marché (« d'accord », « marché conclu »), sans négation ni « laisse tomber ». */
    static boolean accepte(String texte) {
        String n = Ordres.normaliser(texte);
        if (n.contains("?") || refuse(n) || abandonne(texte)) return false;
        return Ordres.dit(n, "oui", "ouais", "d accord", "daccord", "marche conclu", "affaire conclue", "ca marche", "entendu", "j accepte", "accepte",
                "va pour", "tope la", "topons la", "ok", "okay", "ca me va", "c est d accord", "conclu", "bien sur");
    }

    /** Une négation dans la phrase (« pas d'accord », « je n'accepte pas », « jamais »). */
    static boolean refuse(String n) {
        return Ordres.dit(n, "non", "nan", "pas d accord", "pas question", "n accepte", "ne veux pas", "refuse", "jamais", "hors de question");
    }

    /** Le chef abandonne l'ordre (« laisse tomber », « annule », « oublie »). */
    static boolean abandonne(String texte) {
        return Ordres.dit(Ordres.normaliser(texte), "laisse tomber", "laissez tomber", "annule", "oublie", "oublie ca", "n en parlons plus", "tant pis", "pas grave");
    }

    /** La phrase ne porte aucun ordre à elle (mission, action, déplacement, expédition, horaires, règle) : elle répond à l'ordre en suspens. */
    static boolean sansOrdre(Ordres.Lecture l) {
        // « Marché conclu », « tope là » : la poignée de main scelle le marché, ce n'est pas un nouvel ordre.
        return l.missions.isEmpty() && l.faire.stream().allMatch(f -> f.id().equals("serrer_main")) && l.jours < 0 && l.debut < 0 && l.fin < 0 && l.equipe < 0 && l.regles.isEmpty() && !l.annulerRegles
                && l.intentions.stream().noneMatch(i -> java.util.EnumSet.of(Ordres.Intention.SUIVRE, Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE,
                        Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER, Ordres.Intention.RENVOYER, Ordres.Intention.SAC, Ordres.Intention.ENTERRER).contains(i));
    }

    /** Une simple question ou politesse (« pourquoi ? », « ça va ? », « merci ») : l'ordre en suspens reste en suspens. */
    static boolean question(Ordres.Lecture l) {
        return sansOrdre(l) && l.faire.isEmpty() && !l.intentions.isEmpty() && l.intentions.stream().allMatch(i -> java.util.EnumSet.of(Ordres.Intention.ETAT,
                Ordres.Intention.AVANCEMENT, Ordres.Intention.POURQUOI, Ordres.Intention.AIDE, Ordres.Intention.SALUT, Ordres.Intention.MERCI, Ordres.Intention.LIENS, Ordres.Intention.DIAMANT, Ordres.Intention.EXCUSES).contains(i));
    }

    /** Se jeter (« saute », « jette-toi »…) : vers une falaise ou un ravin, seul ce verbe est mortel. */
    static final String SAUT = "(?:saute|sautez|jette toi|jetez vous|plonge|plongez|tombe|lance toi|lancez vous)";
    /** Y aller, y entrer : mortel seulement suivi tout de suite du danger (« va dans la lave », pas « va construire un pont sur la lave »). */
    static final String ALLER = "(?:" + SAUT.substring(3, SAUT.length() - 1) + "|va|allez|marche|marchez|entre|entrez|nage|baigne toi|cours|avance|avancez|mets toi|couche toi|roule toi)";

    /** Les ordres mortels : le danger et sa tournure (le verbe, puis aussitôt le danger). */
    static final List<Object[]> MORTELS = List.of(
            new Object[]{"lave", Pattern.compile(" " + ALLER + " (?:dans|en|sur) (?:la )?lave ")},
            new Object[]{"vide", Pattern.compile(" " + ALLER + " dans le vide | " + SAUT + " (?:du haut )?(?:de la |du |d un |d une |dans le |dans la |dans un |dans une |par dessus la |par dessus le |de cette |de ce )(?:falaise|precipice|ravin|gouffre|abime) ")},
            new Object[]{"feu", Pattern.compile(" " + ALLER + " (?:dans|sur) (?:le feu|les flammes|le brasier) | (?:brule toi|immole toi) ")},
            new Object[]{"noyade", Pattern.compile(" (?:noie toi|noyez vous|va te noyer|allez vous noyer|reste sous l eau|reste au fond de l eau) ")},
            new Object[]{"creuser", Pattern.compile(" (?:creuse|creusez|mine|pioche) (?:tout |juste |bien )?(?:droit )?(?:sous toi|sous tes pieds|a tes pieds|droit vers le bas|droit en bas|a la verticale) ")});

    /** Une mise en garde (« ne va pas te noyer », « évite de sauter… », « jamais ») ou un constat (« tu vas te noyer ») : pas un ordre. */
    static final Pattern GARDE = Pattern.compile(" (?:ne|n|pas|jamais|evite|evitez|eviter|attention|gare) |^ (?:tu|il|elle|on|je|nous|vous|ils|elles) ");

    /** Le danger mortel que l'ordre demande d'affronter (« lave », « vide »…), ou null. Chaque proposition est lue à part. */
    static String mortel(String texte) {
        if (texte == null || texte.isBlank()) return null;
        for (String morceau : texte.split("(?i)[,.;:!?\\n]+|\\s(?:et|puis|mais|ensuite)\\s")) {
            String n = Ordres.normaliser(morceau);
            if (GARDE.matcher(n).find()) continue;
            for (Object[] m : MORTELS) if (((Pattern) m[1]).matcher(n).find()) return (String) m[0];
        }
        return null;
    }
}
