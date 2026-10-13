package fr.lacolonie.pnj;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Obéit-il ? (cahier 8.4 et 8.5) Selon son moral, son tempérament et la peine que l'ordre lui coûte, un ouvrier obéit,
 * obéit en râlant, demande une contrepartie ou refuse. Un ouvrier content obéit toujours ; les ordres légers (suivre,
 * attendre, rentrer, manger, parler, gestes…) aussi. Un ordre qui le tuerait (sauter dans la lave), il le refuse toujours.
 * Aussi : le moral (0 à 100) et ce qui le fait bouger. Pur Java, déterministe (testé seul).
 */
final class Obeissance {
    private Obeissance() {}

    enum Verdict { OBEIT, RALE, NEGOCIE, REFUSE }

    enum Peine { LEGER, NORMAL, PENIBLE }

    /** Ce que l'ordre lui coûte ; {@code dangereux} : un combat (pas guerrier) ou une expédition, ce qui fait peur à un peureux. */
    record Ordre(Peine peine, boolean dangereux) {}

    // --- Le moral -----------------------------------------------------------------------------------------------

    static final int DEPART = 70, REPOS = 60;
    static final int PAIE = 10, RETARD = -12, FAIM = -2, MANGER = 5, COUP_CHEF = -10, MARCHE = 5, FORCE = -8, INSULTE = -5, COMPLIMENT = 2;
    /** Lot 5 : une pause prise (loisirs), son lit cassé ou pris par un autre. */
    static final int PAUSE = 2, LIT_VOLE = -3;
    /** Au-dessous, même en insistant, il refuse : seuls la paie, la nourriture ou le repos le font remonter. */
    static final int FERME = 15;
    /** Une mission d'au moins autant d'objets est une grosse mission. */
    static final int GROSSE = 64;

    static int moral(int moral, int delta) { return Math.clamp((long) moral + delta, 0, 100); }

    /** Avec le temps (une heure du jeu), le moral revient doucement vers 60. */
    static int versLeRepos(int moral) { return moral + Integer.signum(REPOS - moral); }

    // --- La décision ----------------------------------------------------------------------------------------------

    /** Ce qu'il fait d'un ordre. {@code insiste} : le chef redonne l'ordre, ou dit « c'est un ordre ». */
    static Verdict decider(int moral, String temperament, Ordre o, boolean insiste) {
        Verdict v = premier(moral, temperament, o);
        if (!insiste || v == Verdict.OBEIT || v == Verdict.RALE) return v;
        return moral < FERME ? Verdict.REFUSE : Verdict.RALE;
    }

    static Verdict premier(int moral, String temperament, Ordre o) {
        if (o.peine() == Peine.LEGER) return Verdict.OBEIT;
        boolean peureux = "peureux".equals(temperament), colerique = "colerique".equals(temperament);
        if (moral >= 50) return peureux && o.dangereux() ? Verdict.RALE : Verdict.OBEIT;
        if (moral >= 25) {
            if (o.peine() != Peine.PENIBLE) return Verdict.OBEIT;
            if (colerique) return moral < 35 ? Verdict.REFUSE : Verdict.RALE;
            if ("fourbe".equals(temperament)) return Verdict.NEGOCIE;
            return peureux && o.dangereux() ? Verdict.NEGOCIE : Verdict.RALE;
        }
        return colerique ? Verdict.REFUSE : Verdict.NEGOCIE;
    }

    /** Ce que lui coûte l'ordre lu. {@code horsService} : la nuit, ou après ses heures. */
    static Ordre peine(Ordres.Lecture l, String metier, boolean horsService) {
        Peine p = Peine.LEGER;
        boolean danger = false;
        boolean travail = !l.missions.isEmpty() || l.intentions.contains(Ordres.Intention.TRAVAIL);
        if (travail) p = horsService ? Peine.PENIBLE : Peine.NORMAL;
        if (l.missions.stream().anyMatch(m -> m.nombre() >= GROSSE)) p = Peine.PENIBLE;
        if (l.jours > 0) { p = Peine.PENIBLE; danger = true; } // une expédition lointaine
        int heures = l.debut >= 0 && l.fin >= 0 ? Math.floorMod(l.fin - l.debut, 24) : -1;
        if (heures == 0 || heures > 12) p = Peine.PENIBLE; // des journées à rallonge (0 : toute la journée)
        for (Ordres.Faire f : l.faire) {
            Repertoire.Entree e = Repertoire.PAR_ID.get(f.id());
            if (e == null || f.fin() || !e.pour(metier)) continue; // pas de son métier : il le dira, sans marchander
            if (e.comportement().equals("travail") && p == Peine.LEGER) p = horsService ? Peine.PENIBLE : Peine.NORMAL;
            // Se battre, c'est le métier du guerrier (et ses ordres de guerre restent sûrs) ; pas celui des autres.
            if (e.comportement().equals("combat") && !metier.equals("guerrier")) { p = Peine.PENIBLE; danger = true; }
        }
        return new Ordre(p, danger);
    }

    /** Pourquoi il refuse, d'après son état : « paie », « faim », « fatigue », « coups » ou « moral ». */
    static String raison(int impayes, int faim, int fatigue, boolean frappe) {
        if (impayes > 0) return "paie";
        if (faim <= 8) return "faim";
        if (fatigue >= 70) return "fatigue";
        if (frappe) return "coups";
        return "moral";
    }

    /** La prime qu'il demande pour un ordre pénible : un quart de sa paie, une pièce au moins. */
    static long prime(long salaire) { return Math.max(1, salaire / 4); }

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

    /** Le chef insiste. */
    static boolean insiste(String texte) {
        String n = Ordres.normaliser(texte);
        // « Fais-le ! », mais pas « fais le guet » ni « fais le tour » (un autre ordre).
        if (n.matches(".* fais le (?:(?:vite|maintenant|tout de suite|donc|chef|je te dis|j ai dit) )*")) return true;
        return Ordres.dit(n, "c est un ordre", "obeis", "obeissez", "obeis moi", "j insiste", "fais ce que je dis",
                "fais ce que je te dis", "fais ce qu on te dit", "execution", "pas de discussion", "ne discute pas", "discute pas",
                "je ne te demande pas ton avis", "tu n as pas le choix", "j ai dit");
    }

    /** La phrase ne porte aucun ordre à elle (mission, action, déplacement, expédition, horaires, règle) : elle répond à l'ordre en suspens. */
    static boolean sansOrdre(Ordres.Lecture l) {
        // « Marché conclu », « tope là » : la poignée de main scelle le marché, ce n'est pas un nouvel ordre.
        return l.missions.isEmpty() && l.faire.stream().allMatch(f -> f.id().equals("serrer_main")) && l.jours < 0 && l.debut < 0 && l.fin < 0 && l.regles.isEmpty() && !l.annulerRegles
                && l.intentions.stream().noneMatch(i -> java.util.EnumSet.of(Ordres.Intention.SUIVRE, Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE,
                        Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER, Ordres.Intention.RENVOYER, Ordres.Intention.SAC).contains(i));
    }

    /** Une simple question ou politesse (« pourquoi ? », « ça va ? », « merci ») : l'ordre en suspens reste en suspens. */
    static boolean question(Ordres.Lecture l) {
        return sansOrdre(l) && l.faire.isEmpty() && !l.intentions.isEmpty() && l.intentions.stream().allMatch(i -> java.util.EnumSet.of(Ordres.Intention.ETAT,
                Ordres.Intention.AVANCEMENT, Ordres.Intention.POURQUOI, Ordres.Intention.AIDE, Ordres.Intention.SALUT, Ordres.Intention.MERCI).contains(i));
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
