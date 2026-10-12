package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Ce que disent et écrivent les ouvriers : plusieurs formulations pour chaque situation, et un ton qui dépend du
 * tempérament (calme, peureux, fourbe, colérique). Les textes ont des trous {0}, {1}… remplis au moment d'écrire.
 */
final class Messages {
    private Messages() {}

    /** Pour chaque situation, ses formulations. « situation|tempérament » : celles propres à un tempérament (choisies une fois sur deux). */
    static final Map<String, String[]> CORPS = new HashMap<>();
    /** L'objet de la lettre, tel qu'il apparaît dans la liste du courrier. */
    static final Map<String, String> OBJETS = new HashMap<>();
    /** Les lettres qui ne peuvent pas attendre qu'il passe au poste : un corbeau part tout de suite. */
    static final java.util.Set<String> URGENTES = java.util.Set.of("a_terre", "greve", "coince", "sans_place");
    /**
     * Les lettres importantes : en tête du courrier, signalées, jamais regroupées. Les autres sont de la routine : une
     * lettre du même ouvrier et de la même situation, encore non lue, compte simplement une fois de plus (voir {@link Boite}).
     */
    static final java.util.Set<String> IMPORTANTES = java.util.Set.of("a_terre", "greve", "coince", "sans_place", "ramene_attend",
            "poste_detruit", "mission_finie");
    /** Ni importantes ni regroupées : chacune répond à une lettre du chef, ou dit quelle mission est finie. */
    static final java.util.Set<String> SEULES = java.util.Set.of("nouvelles", "mission_finie_suite");

    static void m(String type, String objet, String... variantes) {
        CORPS.put(type, variantes);
        if (objet != null) OBJETS.put(type, objet);
    }

    static void t(String type, String temperament, String... variantes) { CORPS.put(type + "|" + temperament, variantes); }

    static {
        m("faim", "J'ai faim",
                "J'ai faim, et je n'ai plus rien à manger. Laisse-moi quelque chose au dépôt, s'il te plaît.",
                "Mon ventre crie famine. Il me faudrait du pain, de la viande, n'importe quoi : je t'attends au poste.",
                "Je n'ai plus de vivres. Sans manger, je ne tiendrai plus longtemps à la tâche.",
                "Je n'ai plus rien dans mon sac à manger. Un peu de nourriture au dépôt et je reprends.");
        t("faim", "peureux", "J'ai si faim que mes mains tremblent… Je t'en prie, laisse-moi à manger au dépôt.");
        t("faim", "colerique", "Je crève de faim ! On ne travaille pas le ventre vide : mets de quoi manger au dépôt.");
        t("faim", "fourbe", "Un ouvrier bien nourri est un ouvrier loyal, n'est-ce pas ? Il me faudrait de quoi manger.");

        m("pioche", "Il me faut une pioche",
                "Je n'ai pas de pioche : je ne peux pas miner. Laisse-m'en une au dépôt, ou donne-la-moi en main propre.",
                "Sans pioche, je reste les bras croisés. Il m'en faut une.",
                "Ma pioche s'est cassée, ou je n'en ai jamais eu : il m'en faut une pour travailler.");
        m("meilleure_pioche", "Il me faut une meilleure pioche",
                "Pour le {0}, il me faut une pioche en fer ou mieux : la mienne ne suffit pas.",
                "Le {0} ne se laisse pas tailler avec ce que j'ai. Une pioche en fer, au moins, et je m'y mets.");
        m("pierre_trou", "Il me faut de la pierre",
                "Il y a un trou sous mes pieds dans ma galerie : il me faudrait de la pierre (cobblestone) pour le combler.",
                "Mon tunnel s'ouvre sur le vide. Avec un peu de pierre, je pourrai continuer sans tomber.");
        m("hache", "Je n'ai pas de hache",
                "Je n'ai pas de hache : je coupe à la main, c'est bien plus lent.",
                "Sans hache, j'abats les arbres à mains nues. Ça avance, mais doucement.");
        m("torches", "Plus de torches",
                "Je n'ai plus de torches : mes galeries vont rester sombres.",
                "Mes torches sont toutes posées. Sans elles, les monstres vont s'installer dans mes galeries.");
        t("torches", "peureux", "Je n'ai plus de torches… Il fait si noir là-dessous. Je t'en supplie, envoie-m'en.");

        m("greve", "Je me mets en grève",
                "Je ne suis plus payé : je me mets en grève. Remplis la caisse du poste ({0}) et je reprendrai.",
                "Pas de paie, pas de travail. Je pose mes outils tant que la caisse n'a pas {0}.",
                "Trop de paies en retard : je m'arrête. Il me faut {0} dans la caisse pour reprendre.");
        t("greve", "colerique", "Assez ! On me doit {0} et la caisse est vide. Je ne bougerai plus d'un pouce avant d'être payé.");
        t("greve", "fourbe", "Je suis sûr que c'est un oubli… Mais tant que la caisse n'aura pas {0}, je me repose.");
        t("greve", "peureux", "Je suis désolé, mais je ne peux plus travailler sans être payé… Il me faut {0} dans la caisse.");
        m("toujours_greve", "Toujours en grève",
                "Toujours en grève. Il me faut {0} dans la caisse du poste.",
                "Je n'ai toujours pas touché mon dû : {0}. Je reste en grève.");
        m("paie_retard", "Ma paie est en retard",
                "Ça fait un moment que je n'ai pas été payé. N'oublie pas de mettre des pièces dans la caisse du poste.",
                "La caisse était vide quand je suis passé prendre ma paie. Pense à la remplir.",
                "Ma paie n'est pas venue. Je continue pour l'instant, mais je compte sur toi.");
        t("paie_retard", "colerique", "La caisse est vide et ma paie aussi ! Remplis-la vite, je ne vais pas travailler pour rien.");
        m("reprise_greve", "Je reprends le travail",
                "J'ai touché mon dû ({0}). Merci, je reprends le travail.",
                "Paie reçue : {0}. Je retourne à l'ouvrage.");
        m("sans_poste", "Je n'ai pas de poste",
                "Il me faut un poste de travail pour travailler : pose ton bureau, je m'y rattacherai.",
                "Sans poste de travail, je ne sais ni où déposer ma récolte ni où toucher ma paie. Pose ton bureau.");
        m("poste_detruit", "Le poste a été détruit",
                "Le poste de travail a été détruit : je ne peux plus travailler. Pose un nouveau bureau et je m'y rattacherai.",
                "Plus de poste ! Où vais-je déposer mon travail et toucher ma paie ? Pose un nouveau bureau.");
        m("coince", "Je suis coincé",
                "Je n'arrive pas à rentrer au poste : je suis coincé en {0}.",
                "Je suis bloqué en {0} et je ne trouve pas de passage. Il faudra peut-être venir me chercher.");
        t("coince", "peureux", "Je suis coincé en {0}… J'ai essayé tous les passages. Viens me chercher, je t'en prie.");
        m("sans_place", "Je ne peux pas rentrer",
                "Je suis coincé en {0}, et je ne puis rentrer : il n'y a plus une place libre autour du poste. Dégage ses abords, je t'en prie.",
                "Coincé en {0}. On voudrait me ramener au poste, mais ses abords sont tout encombrés : il n'y a plus où poser le pied.");
        m("ramene_attend", "Je reste au poste",
                "Deux fois de suite, je me suis perdu en {0} et l'on m'a ramené au poste. J'y reste et j'attends tes ordres.",
                "Je me suis encore retrouvé coincé en {0}. Plutôt que de m'y perdre une troisième fois, j'attends tes ordres au poste.");
        m("fin_expedition", "Je rentre",
                "Mon expédition touche à sa fin : je rentre au poste ({0} blocs).",
                "Il est temps de rentrer : je suis à {0} blocs du poste, je me mets en route.");
        m("depart_expedition", "Je pars en expédition",
                "Je pars en expédition {0} : vers {1}, à {2} blocs. Je serai de retour dans {3} au plus tard.",
                "C'est trop loin pour un aller-retour dans la journée : je pars en expédition {0}, {1} ({2} blocs). Retour dans {3} au plus tard.",
                "Je m'en vais {0}, {1}, à {2} blocs d'ici. Ne t'inquiète pas, je serai rentré dans {3} au plus tard.");
        t("depart_expedition", "peureux", "Il faut que je parte loin, {0}, {1}, à {2} blocs… Je ferai attention. Retour dans {3} au plus tard.");
        m("retour_expedition", "Je suis de retour",
                "Me voilà de retour d'expédition.",
                "Je suis rentré de mon expédition, sain et sauf.",
                "De retour au poste après mon expédition. Il y a de quoi raconter !");
        m("camp", "Je campe dehors",
                "Je passe la nuit dehors, à {0} blocs du poste. Je serai de retour dans {1} au plus tard.",
                "La nuit tombe : je campe ici, à {0} blocs du poste. Retour dans {1} au plus tard.");
        t("camp", "peureux", "Je dois dormir dehors, à {0} blocs du poste… J'entends des bruits partout. Retour dans {1} au plus tard.");
        m("epuise", "Je suis épuisé",
                "Je n'en peux plus : je vais me reposer un peu.",
                "Mes jambes ne me portent plus. Je me repose et je reprends ensuite.",
                "Trop de fatigue : je fais une pause, sinon je vais me blesser.");
        t("epuise", "colerique", "Tu me fais trimer comme une bête ! Je m'arrête, j'ai besoin de souffler.");
        m("depot_plein", "Le dépôt est plein",
                "Le dépôt du poste est plein : vide-le, ou colle un coffre au bureau. Je ne peux plus rien ranger.",
                "Plus une place au dépôt ! Il faut le vider ou y coller un coffre.");
        m("mission_finie", "Mission terminée",
                "Mission terminée : {0}. Je n'ai plus rien à faire : écris-moi ma prochaine mission.",
                "C'est fait : {0}, au dépôt. Quelle est la suite ?",
                "Mission accomplie : {0}. J'attends tes ordres.");
        m("mission_finie_suite", "Mission terminée",
                "Mission terminée : {0}. Je passe à la suivante : {1}.",
                "C'est fait pour {0}. Je m'attaque maintenant à : {1}.");
        m("stock_plein", "Le stock est plein",
                "Le stock de {0} est plein ({1}/{2}). Je reprends dès qu'il baisse.",
                "Il y a assez de {0} au dépôt ({1}/{2}) : j'attends qu'on en prenne pour m'y remettre.");
        m("sans_mission", "Je n'ai pas de mission",
                "Je n'ai pas de mission. Écris-moi ce que tu veux, par exemple « Ramène 10 fers ».",
                "Je suis prêt, mais je ne sais pas quoi faire : écris-moi une mission.");
        m("bois_inconnu", "Je fouille les environs",
                "Je ne connais aucun endroit proche où pousse ce bois ({0}) : je fouille les environs.",
                "Je n'ai jamais vu de {0} par ici. Je cherche autour, de plus en plus loin.");
        m("plus_d_arbre", "Plus d'arbres ici",
                "Plus d'arbre de ce bois ({0}) vers {1} : je vais chercher plus loin.",
                "J'ai abattu tout le {0} des environs de {1}. Je pars chercher ailleurs.");
        m("bois_demain", "J'irai demain",
                "Il y a {0} {1} : j'irai demain matin, la journée est trop avancée.",
                "J'ai repéré {0} {1}, mais c'est trop loin pour aujourd'hui. J'y vais demain.");
        m("bois_trop_loin", "C'est trop loin",
                "Le {0} ne pousse qu'à {1} blocs : trop loin, même en expédition de {2} jours. Allonge la durée des expéditions au poste.",
                "Pour le {0}, il faudrait aller à {1} blocs. Avec {2} jours d'expédition au plus, je n'aurai pas le temps de revenir.");
        m("pas_de_sol", "Je ne trouve pas où creuser",
                "Je ne trouve aucun sol naturel où creuser sans abîmer de construction.",
                "Tout est bâti autour du poste : je ne veux rien abîmer, et je ne trouve pas où creuser.");
        m("puits_fini", "Nouveau puits",
                "J'ai fini mes galeries à cet endroit : je creuse un nouveau puits un peu plus loin.",
                "Mes galeries sont épuisées ici. Je recommence un peu plus loin.");
        m("a_terre", "À L'AIDE",
                "Je suis à terre en {0} ! Il me reste deux minutes : viens avec à manger ou une potion de soin.",
                "Blessé, à terre, en {0}… Vite, à manger ou une potion de soin, je n'en ai plus pour longtemps.");
        t("a_terre", "peureux", "Au secours ! Je suis à terre en {0}, je ne peux plus bouger… Viens vite, avec à manger !");
        m("nouvelles", "Des nouvelles",
                "Bien reçu ta lettre. {0}Je suis {1}. {2} {3}",
                "Merci pour ta lettre. {0}Je suis {1}. {2} {3}");
        m("deces", null);

        // La parole, face à face (bulle au-dessus de la tête ; fenêtre de conversation).
        m("dit_suivre", null, "Je te suis, chef.", "J'arrive, je te suis.", "Après toi, chef.");
        m("dit_arrete_suivre", null, "Bien, je retourne à mon travail.", "D'accord, je te laisse. Je reprends mon ouvrage.");
        m("dit_attendre", null, "J'attends ici.", "Je ne bouge pas.", "Entendu, je reste là.");
        m("dit_travail", null, "Je retourne au travail, chef. Je sais où chercher.", "Au travail !", "J'y retourne.");
        m("dit_rentrer", null, "Je rentre au poste.", "Je reviens au poste.", "Demi-tour : je rentre.");
        m("dit_pas_compris", null, "Je n'ai pas bien compris, chef. Tu peux me dire par exemple : « suis-moi », « au travail », « ramène 10 fers », « où en es-tu ? ».",
                "Pardon, chef ? Je ne vois pas ce que tu veux. Écris « aide » pour savoir ce que je comprends.");
        m("dit_confirmer_renvoi", null, "Tu me renvoies vraiment ? Dis « oui » pour confirmer.", "Tu es sûr de vouloir te passer de moi ? Réponds « oui » pour confirmer.");
        m("dit_garde", null, "Bon, je garde ma place alors.", "Ouf. Je reste avec toi.");
        m("dit_renvoye", null, "Bon… je m'en vais chercher du travail ailleurs.", "Très bien. Adieu, chef.");
        t("dit_renvoye", "colerique", "Après tout ce que j'ai fait ? Très bien, je m'en vais !");
        m("dit_merci", null, "Merci, chef !", "C'est gentil, chef.", "Ça fait plaisir.");
        m("dit_salut", null, "Bonjour, chef.", "Salut, chef !", "Chef !");
        m("dit_hors_metier", null, "Je suis {0}, chef : {1}, ce n'est pas mon travail.", "{1} ? Je suis {0}, je ne saurais pas faire ça.");
        m("dit_missions", null, "Compris, chef : {0}.", "Entendu : {0}.", "Bien noté : {0}.");
        m("dit_horaires", null, "Entendu : je travaillerai de {0} h à {1} h.", "D'accord, de {0} h à {1} h.");
        m("dit_toute_journee", null, "Entendu : je travaillerai toute la journée.", "D'accord, sans arrêt.");
        m("dit_jours", null, "Entendu : mes expéditions dureront {0} jours au plus.", "D'accord : {0} jours d'expédition au plus.");
        m("dit_aide", null, "Tu peux me dire : « suis-moi », « arrête de me suivre », « attends ici », « au travail », « rentre au poste », « montre ton sac », « ramène 10 fers et 1 diamant », « garde 32 charbons en stock », « travaille de 8 h à 20 h », « comment ça va ? », « où en es-tu ? », « pourquoi tu ne travailles pas ? ». Et des centaines d'autres choses : « garde à vous », « assieds-toi », « danse », « fais le cochon », « mets-toi derrière moi », « donne-moi ton pain », « raconte une blague », « où est le poste ? », « repos »… (la liste est dans l'onglet Actions du panneau).");
        m("dit_merci_objet", null, "Merci, chef !", "Merci, ça tombe bien.", "Parfait, merci.");
    }

    // --- Composer --------------------------------------------------------------------------------------

    /** Le texte d'une situation, avec le ton de son tempérament (une fois sur deux s'il a des formulations à lui). */
    static String corps(Travailleur t, String type, Object... vals) {
        RandomSource r = t.getRandom();
        String[] propres = CORPS.get(type + "|" + t.temperament), communes = CORPS.get(type);
        String[] choix = propres != null && (communes == null || r.nextBoolean()) ? propres : communes;
        if (choix == null || choix.length == 0) return type;
        return remplir(choix[r.nextInt(choix.length)], vals);
    }

    static String remplir(String modele, Object... vals) {
        String s = modele;
        for (int i = 0; i < vals.length; i++) s = s.replace("{" + i + "}", String.valueOf(vals[i]));
        return s.replaceAll("\\{\\d\\}", "").replaceAll(" {2,}", " ").trim();
    }

    static String objet(String type) { return OBJETS.getOrDefault(type, "Lettre"); }

    static final Map<String, String[]> SALUTATIONS = Map.of(
            "calme", new String[]{"Chef,", "Bonjour chef,", "Chef,"},
            "peureux", new String[]{"Chef…", "Pardon de te déranger, chef,", "Chef,"},
            "fourbe", new String[]{"Mon cher chef,", "Chef, mon ami,", "Très cher chef,"},
            "colerique", new String[]{"Chef !", "Chef,", "Chef, écoute-moi bien."});
    static final Map<String, String[]> FORMULES = Map.of(
            "calme", new String[]{"Bien à toi.", "À bientôt.", "Je reste à ton service."},
            "peureux", new String[]{"J'espère que tout ira bien…", "Je compte sur toi.", "Prends soin de toi, chef."},
            "fourbe", new String[]{"Ton dévoué serviteur.", "Toujours à ton service, évidemment.", "Avec toute ma loyauté."},
            "colerique", new String[]{"Fais vite.", "J'attends.", "Ne traîne pas."});

    /** La lettre complète : salutation, texte, formule et signature. */
    static String lettre(Travailleur t, String corps) {
        RandomSource r = t.getRandom();
        String[] s = SALUTATIONS.getOrDefault(t.temperament, SALUTATIONS.get("calme")), f = FORMULES.getOrDefault(t.temperament, FORMULES.get("calme"));
        return s[r.nextInt(s.length)] + "\n\n" + corps + "\n\n" + f[r.nextInt(f.length)] + "\n— " + t.prenom;
    }

    /** « au nord-ouest », « à l'est »… de a vers b (le nord est vers les z négatifs). */
    static String direction(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        if (dx * dx + dz * dz < 1) return "ici";
        String[] noms = {"à l'est", "au sud-est", "au sud", "au sud-ouest", "à l'ouest", "au nord-ouest", "au nord", "au nord-est"};
        int i = (int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4));
        return noms[Math.floorMod(i, 8)];
    }

    /** « à 420 blocs au nord-ouest du poste », « au poste ». */
    static String ou(Travailleur t) {
        if (t.bureau == null) return "sans poste";
        double d = t.distanceAuBureau();
        if (d < 6) return "au poste";
        if (t.chezLui(t.blockPosition())) return "près du poste, à " + (int) d + " blocs";
        return "à " + (int) d + " blocs " + direction(t.bureau, t.blockPosition()) + " du poste";
    }

    /** Comment il va, en quelques mots. */
    static String etat(Travailleur t) {
        StringBuilder b = new StringBuilder();
        b.append(t.getHealth() < t.getMaxHealth() / 3 ? "Je suis mal en point" : t.getHealth() < t.getMaxHealth() * 0.7 ? "J'ai quelques blessures" : "Je me porte bien");
        b.append(t.faim <= 6 ? ", et j'ai très faim" : t.faim <= 12 ? ", j'ai un peu faim" : "");
        b.append(t.fatigue >= 80 ? ", et je suis épuisé." : t.fatigue >= 50 ? ", un peu fatigué." : ".");
        return b.toString();
    }

    /** « Jour 12, 14 h » : la date du jeu. */
    static String date(net.minecraft.world.level.Level l) {
        long jour = l.getDayTime() / 24000 + 1;
        int heure = (int) ((l.getDayTime() + 6000) % 24000 / 1000);
        return "Jour " + jour + ", " + heure + " h";
    }
}
