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
    static final java.util.Set<String> URGENTES = java.util.Set.of("a_terre", "greve", "coince", "sans_place", "menace");
    /**
     * Les lettres importantes : en tête du courrier, signalées, jamais regroupées. Les autres sont de la routine : une
     * lettre du même ouvrier et de la même situation, encore non lue, compte simplement une fois de plus (voir {@link Boite}).
     */
    static final java.util.Set<String> IMPORTANTES = java.util.Set.of("a_terre", "greve", "coince", "sans_place", "ramene_attend",
            "poste_detruit", "mission_finie", "menace", "fuite");
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
        m("houe", "Il me faut une houe",
                "Je n'ai pas de houe : la terre de nos champs reste dure. Laisse-m'en une au dépôt.",
                "Sans houe, je ne peux pas labourer les champs que tu m'as marqués. Il m'en faudrait une au dépôt.");
        m("graines", "Plus de semences",
                "Je n'ai plus de {0} pour semer nos champs. Laisse-m'en au dépôt.",
                "Les champs attendent leurs semences : il n'y a plus de {0}, ni sur moi ni au dépôt.");
        m("sans_enclos", "Pas de bêtes à soigner",
                "Je ne trouve aucune bête dans un enclos autour du poste. Il me faut des vaches, des moutons, des cochons ou des poules, derrière une barrière avec un portillon.",
                "Mes enclos sont vides, ou il n'y en a pas : sans bêtes à garder, je tourne en rond.");
        m("fourrage", "Plus de fourrage",
                "Je n'ai plus de {0} pour nourrir nos bêtes. Laisse-m'en au dépôt.",
                "Les bêtes attendent leur pâture : il n'y a plus de {0}, ni sur moi ni au dépôt.");
        m("cisailles", "Il me faut des cisailles",
                "Nos moutons portent toute leur laine, et je n'ai pas de cisailles pour les tondre. Laisse-m'en une paire au dépôt.",
                "Sans cisailles, la laine reste sur le dos des moutons. Il m'en faudrait une paire au dépôt.");
        m("sans_combustible", "Plus de combustible",
                "Mon four est froid : je n'ai plus de charbon ni de bois pour le chauffer. Laisse-m'en au dépôt.",
                "Sans combustible, pas de cuisson. Un peu de charbon, ou du bois, au dépôt, et je rallume le four.",
                "Le feu s'est éteint, et il n'y a plus rien à brûler, ni sur moi ni au dépôt.");
        t("sans_combustible", "colerique", "Un four sans feu, c'est une boîte en pierre ! Du charbon au dépôt, tout de suite.");
        m("sans_ble", "Plus de blé",
                "Je n'ai plus de blé pour faire le pain. Laisse-m'en au dépôt, ou demande au fermier de m'en porter.",
                "La huche est vide : plus un grain de blé. Sans lui, pas de pain demain matin.",
                "Il me faut du blé pour pétrir. Il n'y en a plus, ni sur moi ni au dépôt.");
        t("sans_ble", "peureux", "Plus de blé… Tout le monde va me réclamer du pain, et je n'aurai rien à leur donner. Laisse-m'en au dépôt, je t'en prie.");
        m("rien_a_cuire", "Rien à cuire",
                "Je n'ai rien à mettre au four : ni {0}, ni rien d'autre de cru. Laisse-m'en au dépôt.",
                "Plus de {0} à cuire. Le four est chaud, mais la marmite est vide.",
                "Mon four attend, et moi aussi : il me faudrait du {0} cru au dépôt.");
        t("rien_a_cuire", "fourbe", "Plus rien à cuire. Je pourrais bien faire rôtir une poule de l'éleveur, mais il compte ses bêtes. Laisse-moi de quoi cuisiner au dépôt.");
        m("four_inaccessible", "Je n'atteins plus mon four",
                "Je n'arrive plus jusqu'à mon four, vers {0} : le passage est barré. Ma fournée y est restée ; prends-la, ou dégage le chemin.",
                "Mon four, vers {0}, est hors de ma portée. J'y ai laissé une fournée : je ne peux pas aller la chercher.");
        m("sans_four", "Il me faut un four",
                "Je ne trouve aucun four libre près du poste : ni fourneau, ni fumoir. Bâtis-m'en un à quelques pas, et je cuirai ce qu'il y a au dépôt.",
                "Sans four, je ne peux rien cuire. Un fourneau ou un fumoir près du poste, et je m'y mets.");

        m("canne_peche", "Il me faut une canne à pêche",
                "Je n'ai pas de canne à pêche : je regarde les poissons sans pouvoir les prendre. Laisse-m'en une au dépôt.",
                "Ma canne s'est cassée, ou je n'en ai jamais eu. Sans elle, pas de poisson à la table. Il m'en faudrait une au dépôt.",
                "Je peux attendre au bord de l'eau toute la journée, mais sans canne à pêche, rien ne mordra. Laisse-m'en une au dépôt.");
        t("canne_peche", "colerique", "Pêcher à mains nues ? Très peu pour moi ! Une canne à pêche au dépôt, et vite.");
        t("canne_peche", "peureux", "Je n'ai plus de canne… Je n'ose pas rentrer les mains vides. Laisse-m'en une au dépôt, s'il te plaît.");
        m("sans_eau_peche", "Où dois-je pêcher ?",
                "Je ne trouve pas d'eau assez grande pour pêcher autour du poste. Montre-moi un étang, une rivière ou la mer, et dis-moi « pêche ici ».",
                "Pas d'eau poissonneuse par ici : une flaque ne nourrit personne. Mène-moi au bord d'une vraie eau et dis « pêche ici ».",
                "Je tourne autour du poste sans trouver où lancer ma ligne. Il me faut une étendue d'eau digne de ce nom.");
        m("coin_peche_inaccessible", "Je n'atteins pas mon coin de pêche",
                "Je n'arrive pas jusqu'au bord de l'eau que tu m'as montré : le chemin est barré. J'essaie encore de temps en temps ; montre-moi un autre coin, si tu veux.",
                "Trois fois je suis parti pour mon coin de pêche, trois fois je n'ai pas pu en approcher. Un passage à ouvrir, ou un autre coin à me montrer ?");
        t("sans_eau_peche", "fourbe", "Je pourrais pêcher dans l'abreuvoir des vaches, mais l'éleveur n'aimerait pas. Montre-moi une vraie eau.");
        m("arc", "Il me faut un arc",
                "Je n'ai pas d'arc : le gibier me voit venir et file avant que j'approche. Laisse-m'en un au dépôt.",
                "Sans arc, je cours après les bêtes sans jamais les attraper. Il m'en faudrait un au dépôt.",
                "Mon arc s'est brisé, ou je n'en ai jamais eu. Laisse-m'en un au dépôt et je reprends la chasse.");
        t("arc", "colerique", "Chasser sans arc, c'est courir pour rien ! Mets-m'en un au dépôt.");

        m("fleches", "Plus de flèches",
                "Mon carquois est vide : plus une flèche. Laisse-m'en au dépôt.",
                "J'ai tiré ma dernière flèche. Sans elles, mon arc ne sert plus à rien. Il m'en faudrait au dépôt.",
                "Plus de flèches, ni sur moi ni au dépôt. Le gibier peut dormir tranquille, pour l'instant.");
        t("fleches", "fourbe", "Plus de flèches. Certaines sont restées dans les arbres, je l'avoue. Il m'en faudrait d'autres au dépôt.");
        t("fleches", "peureux", "Je n'ai plus une seule flèche… Je n'ose plus m'enfoncer dans les bois comme ça. Laisse-m'en au dépôt.");

        m("sans_gibier", "Pas de gibier",
                "J'ai battu les bois autour du poste : pas une bête sauvage à chasser. Il faudra chercher plus loin, ou attendre que le gibier revienne.",
                "Le gibier a déserté les environs. Je rentre bredouille. Envoie-moi chasser ailleurs, si tu connais un meilleur coin.",
                "Pas une trace, pas un bruit dans les bois d'ici. Je ne chasserai pas les bêtes de nos enclos, rassure-toi.");
        t("sans_gibier", "calme", "Les bêtes se sont éloignées. Elles reviendront. En attendant, montre-moi un autre bois si tu veux de la viande.");
        m("coffre_source_vide", "Rien à porter",
                "Je reviens du coffre {0} : il est vide. Je n'ai rien à porter pour l'instant.",
                "Le coffre {0} ne contient rien. J'attends qu'il se remplisse avant de refaire le chemin.",
                "Rien à prendre au coffre {0}. Je reste au poste et j'y retourne plus tard.");
        t("coffre_source_vide", "fourbe", "Le coffre {0} est vide. Je n'y ai pas touché, je le précise : il était vide en arrivant.");

        m("coffre_plein", "Le coffre est plein",
                "Le coffre {0} est plein à craquer : je ne peux plus rien y déposer. Il faudrait le vider, ou en poser un autre à côté.",
                "Plus une place dans le coffre {0}. Je rapporte ma charge d'où elle vient, et je réessaierai plus tard.",
                "Le coffre {0} déborde. Je ne pose rien par terre : je rapporte tout, et il me faudrait un autre coffre.");
        t("coffre_plein", "colerique", "Le coffre {0} est plein ! J'ai porté cette charge pour rien. Vide-le, ou donne-m'en un autre !");
        t("coffre_plein", "peureux", "Le coffre {0} est plein… J'ai rapporté ce que je portais. Dis-moi quoi faire, s'il te plaît.");

        m("coffre_introuvable", "Je ne trouve pas le coffre",
                "Je ne trouve plus le coffre {0}. Il a été cassé ou déplacé ? Montre-moi le nouveau.",
                "J'ai fait le chemin jusqu'au coffre {0}, mais il n'y est plus. Je ne peux ni prendre ni déposer.");
        m("coffre_interdit", "Un coffre que je laisse",
                "Le coffre {0} n'est plus à nous, ou il est trop loin du poste : je n'y touche plus. Montre-m'en un autre si tu veux.",
                "Je laisse le coffre {0} : il est chez un voisin, ou trop loin de chez nous.");
        m("coffre_inaccessible", "Je n'arrive pas au coffre",
                "Je n'arrive pas jusqu'au coffre {0} : le chemin est barré. Je réessaierai plus tard.",
                "Impossible d'atteindre le coffre {0}. Un mur, un trou ? Ouvre-moi un passage, et je reprends mes trajets.");
        m("sans_champ", "Où dois-je cultiver ?",
                "Tu ne m'as marqué aucun champ : je ne touche à rien. Regarde une terre près de l'eau et dis-moi « plante un champ ici ».",
                "Je n'ai pas encore de champ à moi. Montre-moi une bonne terre, près de l'eau, et dis « plante un champ ici ».");
        m("plant_inaccessible", "Un plant hors d'atteinte",
                "Je n'arrive pas à atteindre un plant mûr, vers {0} : le champ est clos, ou le chemin barré. Je le laisse pour l'instant.",
                "Un plant mûr, vers {0}, est hors de ma portée : une clôture, un mur ? Je n'y retourne pas avant un moment.");
        m("culture_absente", "Rien de cette culture",
                "Tu me demandes une récolte de {0}, mais il n'en pousse dans aucun de nos champs. Marque-moi un champ de {0} et laisse des semences au dépôt.",
                "Pas un seul plant de {0} dans nos champs : il faudra d'abord en semer.");
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
        m("moral_bas", "Le cœur n'y est plus",
                "Je te l'écris franchement : je n'ai plus le cœur à l'ouvrage. Ce qui me pèse le plus : {0}.",
                "Ça ne va pas fort, ici. Ce qui me ronge : {0}. Fais quelque chose, je t'en prie.",
                "Je travaille encore, mais de mauvais gré. La raison, surtout : {0}.");
        t("moral_bas", "colerique", "Je n'en peux plus ! Ce qui ne va pas : {0}. Change ça, ou tu le regretteras.");
        t("moral_bas", "peureux", "Je n'ose pas trop le dire… mais une chose me mine : {0}.");
        m("moral_avertit", "Je pense à partir",
                "Je te l'ai déjà écrit, et rien n'a changé ({0}). Si ça dure, je m'en irai chercher du travail ailleurs.",
                "Deuxième lettre, et rien n'a bougé : {0}. Je ne resterai plus longtemps comme ça.",
                "Je t'avais prévenu. Rien n'a changé : {0}. Encore quelques jours, et je rends mes outils.");
        t("moral_avertit", "colerique", "Rien n'a changé : {0} ! Je te préviens une dernière fois : bientôt, je pars.");
        m("demission", "Je m'en vais",
                "J'ai rendu au dépôt ce que j'avais pris. Je m'en vais, je n'en pouvais plus ({0}). Adieu.{1}",
                "Je pars. J'ai laissé au dépôt mes outils et le reste. Ce qui m'a fait partir : {0}.{1}",
                "Je t'avais écrit deux fois, et rien n'a changé : {0}. Je m'en vais chercher du travail ailleurs. Mes outils sont au dépôt.{1}");
        // Méfaits au plus bas (Humeur, 2e partie) : l'avertissement avant la révolte, la fuite, et le collègue qui dénonce.
        m("menace", "Prends garde", "{0} Ce qui ne va pas : {1}.{2}");
        m("fuite", "Je suis parti",
                "Quand tu liras ceci, je serai loin. J'ai pris de quoi faire la route. Ce qui m'a fait partir : {0}.",
                "Je m'en vais sans prévenir. Ne me cherche pas. La raison : {0}.",
                "Je pars, et j'emporte un peu de ce qu'on me devait. Ce qui m'a chassé : {0}.");
        t("fuite", "fourbe", "Merci pour tout, chef. J'ai pris un petit souvenir au dépôt. Ce qui m'a fait partir : {0}.");
        t("fuite", "peureux", "Pardon, chef… Je n'osais pas te le dire en face. Je suis parti. C'était trop dur : {0}.");
        m("denonce", "Ce que j'ai vu",
                "Je n'aime pas rapporter, mais tu dois le savoir : j'ai vu {0} {1}.",
                "Un mot, chef, entre nous : j'ai vu {0} {1}. De mes yeux.");
        t("denonce", "fourbe", "Je n'en dis rien aux autres, chef, mais j'ai vu {0} {1}. Tu t'en souviendras ?");
        // Amitiés et couples (Relations) : deux grands amis se mettent en couple.
        m("couple", "Une bonne nouvelle",
                "{0} et moi, nous avons décidé de partager notre vie. Le travail n'en souffrira pas, chef : bien au contraire.",
                "Je tenais à te le dire moi-même : {0} et moi, désormais, nous faisons route ensemble.",
                "Une nouvelle au poste : {0} et moi, nous sommes ensemble. Nous t'en remercions, c'est ici que nous nous sommes trouvés.");
        t("couple", "peureux", "Je n'osais pas te le dire… {0} et moi, nous sommes ensemble. J'espère que tu n'y vois pas d'objection, chef.");
        t("couple", "colerique", "Autant que tu l'apprennes de ma bouche : {0} et moi, c'est sérieux. Que personne n'y trouve à redire.");
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
        m("mission_jour_finie", "Livraison du jour",
                "C'est fait pour aujourd'hui : {0}, au dépôt. Je recommence demain.",
                "{0} livrés pour aujourd'hui. Je m'y remets demain matin.");
        m("vu", null, "J'ai vu {0}, {1}.", "J'ai aperçu {0}, {1}. Prends garde, chef.", "Attention, chef : {0}, {1}.");
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
        m("structure", "Une construction ancienne",
                "Je suis tombé sur {0} en creusant, en {1}. Je n'y touche pas : je creuse ailleurs.",
                "En creusant, j'ai trouvé {0}, en {1}. Je n'y touche pas et je vais creuser ailleurs.");
        m("base_voisine", "Je creuse ailleurs",
                "Je suis tout près des terres {0} : je n'y creuse pas, je vais ailleurs.",
                "Mes galeries arrivaient sous les terres {0}. Je n'ai pas le droit d'y creuser : je vais ailleurs.");
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
        // Ce que son métier ne fait pas : il propose d'aller prévenir l'ouvrier du bon métier (Relais). {0} l'objet, {1} « Bertrand le mineur ».
        m("relais_propose", null, "C'est le travail de {1}, chef. Je vais le prévenir ?",
                "« {0} » ? Ce n'est pas mon ouvrage, chef : c'est celui de {1}. Veux-tu que j'aille le prévenir ?",
                "Je n'y entends rien, chef, mais {1} s'en chargera. Je cours le prévenir ?");
        t("relais_propose", "colerique", "Est-ce que j'ai une tête à ça ? C'est l'affaire de {1}. Je vais le prévenir, si tu y tiens ?");
        t("relais_propose", "peureux", "Oh, je ne saurais pas, chef… Mais {1}, lui, saura. Je vais le prévenir ?");
        // {0} « ne mine », {1} le métier, {2} l'objet.
        m("relais_personne", null, "Personne ici {0}, chef. Il faudrait embaucher un {1}.",
                "« {2} » ? Aucun des nôtres {0}, chef. Il nous faudrait un {1}.");
        m("relais_oui", null, "J'y cours, chef. Je préviens {0}.", "Bien, chef : je vais trouver {0}.", "Entendu. Je vais dire ça à {0}.");
        m("relais_loin", null, "{0} est loin d'ici, chef : je lui fais porter le mot.", "Trop loin pour y courir, chef : je fais passer le mot à {0}.");
        m("relais_non", null, "Bien, chef. Je retourne à mon ouvrage.", "Comme tu voudras, chef.", "Soit. Je reprends ma tâche.");
        m("relais_lettre", null, "Ce n'est pas mon ouvrage, chef : j'ai fait passer ta demande à {0}.");
        m("relais_absent", null, "C'est le travail de {0}, chef, mais il est bien trop loin d'ici pour que je le prévienne.");
        m("relais_deja", null, "{0} s'en occupe déjà, chef.", "C'est déjà en route : {0} y travaille, chef.");
        // {0} le collègue, {1} la commande, {2} ce qu'il a répondu.
        m("relais_refuse", null, "J'ai porté ta commande à {0} ({1}), chef, mais il ne veut pas s'y mettre ainsi. Il m'a dit : « {2} » C'est à toi de lui répondre.",
                "{0} n'a pas pris ta commande ({1}), chef. Sa réponse : « {2} »");
        m("relais_perdu", null, "Je ne trouve plus {0}, chef. Ta demande n'a pas pu lui parvenir.");
        // Le messager arrivé : {0} celui qu'il prévient, {1} la commande (« 64 fer »). Puis la réponse : {0} le messager.
        m("relais_message", null, "{0} ! Le chef te fait dire : {1}.", "{0}, le chef te demande {1}.");
        m("relais_recu", null, "Merci, {0}. Je m'y mets.", "Bien reçu, {0}. J'y vais.", "Entendu, {0}.");
        m("dit_missions", null,"Compris, chef : {0}.", "Entendu : {0}.", "Bien noté : {0}.");
        m("dit_regle", null, "Entendu : {0}, {1}.", "Bien noté : {0}, {1}.", "C'est compris : {0}, {1}.");
        m("dit_oublie_regles", null, "Entendu, j'oublie tes consignes.", "Bien, je ne tiens plus compte de ce que tu m'avais demandé.");
        m("dit_horaires", null, "Entendu : je travaillerai de {0} h à {1} h.", "D'accord, de {0} h à {1} h.");
        m("dit_toute_journee", null, "Entendu : je travaillerai toute la journée.", "D'accord, sans arrêt.");
        m("dit_jours", null, "Entendu : mes expéditions dureront {0} jours au plus.", "D'accord : {0} jours d'expédition au plus.");
        m("dit_aide", null, "Tu peux me dire : « suis-moi », « arrête de me suivre », « attends ici », « au travail », « rentre au poste », « montre ton sac », « ramène 10 fers et 1 diamant », « garde 32 charbons en stock », « travaille de 8 h à 20 h », « comment ça va ? », « où en es-tu ? », « pourquoi tu ne travailles pas ? ». Et des centaines d'autres choses : « garde à vous », « assieds-toi », « danse », « fais le cochon », « mets-toi derrière moi », « donne-moi ton pain », « raconte une blague », « où est le poste ? », « repos »… (la liste est dans l'onglet Actions du panneau).");
        m("dit_merci_objet", null, "Merci, chef !", "Merci, ça tombe bien.", "Parfait, merci.");

        // La mémoire longue (cahier 7.5, 14.7, 14.14) : ce qu'il répond tout de suite, et dont il se souviendra.
        m("souv_insulte", null, "Je ne mérite pas ça, chef.", "Voilà qui est dur à entendre, chef.", "Je m'en souviendrai, chef.");
        t("souv_insulte", "colerique", "Répète un peu ça, chef ? Je ne suis pas ton chien !", "Garde tes injures pour toi, chef. Je ne les oublierai pas.");
        t("souv_insulte", "peureux", "Pardon, chef… je ferai mieux, je te le jure.", "Ne te fâche pas, chef… je fais de mon mieux.");
        t("souv_insulte", "fourbe", "Comme tu voudras, chef… On verra bien qui rira le dernier.", "Je note, chef. Je note tout.");
        m("souv_compliment", null, "Merci, chef ! Ça fait chaud au cœur.", "C'est bon à entendre, chef.", "Merci, chef. Je fais de mon mieux.");
        t("souv_compliment", "colerique", "Hmpf… Merci, chef. C'est mérité.");
        t("souv_compliment", "peureux", "Vraiment ? Merci, chef… ça me rassure.");
        t("souv_compliment", "fourbe", "Tu as l'œil, chef. Un ouvrier comme moi, ça ne court pas les chemins.");
        m("souv_promesse", null, "Je m'en souviendrai, chef.", "C'est promis, alors. Je ne l'oublierai pas.", "Entendu, chef : je retiens ta parole.");
        t("souv_promesse", "fourbe", "Une promesse de chef, ça vaut de l'or… Je m'en souviendrai.");
        t("souv_promesse", "colerique", "Tiens parole, chef. Je n'oublie rien.");
        m("souv_cadeau", null, "Merci, chef ! Que dois-je en faire ?", "Merci, chef. Où veux-tu que je mette cela ?", "Bien reçu, chef. Qu'en fais-je ?");
        m("souv_cadeau_content", null, "Pour moi ? Merci, chef, je le garderai précieusement !", "Un présent ? Merci, chef, tu es bon.",
                "Merci, chef ! Je n'en attendais pas tant.");
        t("souv_cadeau_content", "fourbe", "Ah, chef, tu sais parler aux hommes ! Merci bien.");

        // L'obéissance (cahier 8.4) : il râle, marchande ou refuse, selon son moral et son caractère.
        m("rale", null, "Encore moi… Bon, j'y vais, chef.", "Toujours les mêmes qui triment… Soit, j'y vais.", "Hmpf. Si tu le dis, chef.");
        t("rale", "peureux", "Je n'aime pas ça du tout, chef… mais j'y vais.", "Brr… Bon, puisqu'il le faut.");
        t("rale", "colerique", "Grr… Toujours les sales besognes ! J'y vais, chef.");
        t("rale", "fourbe", "Mais bien sûr, chef… comme toujours, c'est pour moi.");
        m("force", null, "Bien, bien… puisque c'est un ordre. Mais je m'en souviendrai.", "Soit. J'obéis, chef, mais le cœur n'y est pas.");
        t("force", "colerique", "Grr… À tes ordres, chef. Mais ce n'est pas juste !");
        t("force", "peureux", "D'accord, d'accord, ne te fâche pas, chef… J'y vais.");
        m("negocie", null, "Je veux bien, chef, mais ce sera {0} de plus à la paie. Marché conclu ?",
                "C'est une rude besogne, chef. Pour {0} de plus à la paie, je m'y mets. D'accord ?");
        t("negocie", "fourbe", "Pour toi, chef, tout est possible… contre {0} de plus à la paie. Marché conclu ?");
        t("negocie", "peureux", "C'est dangereux, chef… Pour {0} de plus à la paie, je veux bien m'y risquer. D'accord ?");
        m("marche", null, "Marché conclu, chef !", "Topons là, chef !");
        t("marche", "fourbe", "Tope là, chef ! Tu ne le regretteras pas.");
        m("pas_de_marche", null, "Alors ce sera sans moi, chef.", "Tant pis, chef : sans rien de plus, je ne le ferai pas.");
        t("pas_de_marche", "fourbe", "Dommage… Trouve-toi un autre benêt, alors.");
        m("abandon", null, "Bien, chef : n'en parlons plus.", "Comme tu voudras, chef.");
        m("sans_marche", null, "Bon… Je le ferai quand même, mais le cœur n'y est pas.", "Soit. Pour rien, alors… J'y vais.");
        m("refus_paie", null, "Non, chef : ma paie est en retard. Paie-moi ce que tu me dois, et on en reparlera.",
                "Travailler, oui, mais pas pour rien : ma paie n'est pas venue, chef.");
        t("refus_paie", "colerique", "Trimer pour rien ? Pas question ! Paie-moi d'abord.");
        m("refus_faim", null, "J'ai le ventre vide, chef : je ne partirai pas ainsi. Donne-moi d'abord à manger.",
                "Le ventre creux, je n'irai pas loin, chef. Un morceau de pain d'abord.");
        m("refus_fatigue", null, "Je tiens à peine debout, chef. Laisse-moi me reposer avant ça.",
                "Je suis rompu de fatigue, chef. Pas avant une bonne nuit de sommeil.");
        m("refus_coups", null, "Tu me frappes, et tu voudrais que je trime pour toi ? Non, chef.",
                "On ne mène pas les hommes à coups de poing, chef. Ce sera non.");
        t("refus_coups", "peureux", "Tu… tu m'as frappé, chef. Je n'ose plus… Non, pas ça.");
        m("refus_moral", null, "Non, chef. Le cœur n'y est plus : je ne ferai pas ça aujourd'hui.",
                "Pas cette fois, chef. Je n'en ai plus la force ni l'envie.");
        t("refus_moral", "colerique", "Pas question ! Trouve quelqu'un d'autre pour cette besogne.");
        m("refus_ferme", null, "Non, chef, c'est non. {0}", "Crie tant que tu voudras, chef : c'est non. {0}");
        // Cahier 8.5 : un ordre qui le tuerait, il refuse, même en insistant.
        m("mortel_lave", null, "Dans la lave ? On n'en ressort qu'en fumée, chef. Non.", "La lave ? Même pour double paie, je tiens à ma peau. Non, chef.");
        t("mortel_lave", "peureux", "Dans la lave ?! Rien que d'y penser, mes genoux tremblent. Jamais !");
        t("mortel_lave", "colerique", "Dans la lave ? Vas-y donc le premier, chef, je te regarde !");
        t("mortel_lave", "fourbe", "Dans la lave ? Bien sûr, chef… juste après toi.");
        m("mortel_vide", null, "Sauter dans le vide ? Je n'ai pas d'ailes, chef. Non.", "D'aussi haut, on ne retombe qu'une fois, chef. Non.");
        t("mortel_vide", "peureux", "Rien qu'à regarder en bas, j'ai le vertige… Jamais, chef !");
        t("mortel_vide", "colerique", "Saute toi-même si ça te chante ! Moi, je reste là.");
        t("mortel_vide", "fourbe", "Volontiers, chef… dès qu'il me poussera des ailes.");
        m("mortel_feu", null, "Dans le feu ? Je ne suis pas une bûche, chef. Non.", "Le feu, c'est pour cuire le pain, chef, pas les ouvriers. Non.");
        t("mortel_feu", "peureux", "Le feu ?! Non, non et non, chef !");
        t("mortel_feu", "colerique", "Jette-toi au feu toi-même, chef ! Moi, je n'y vais pas.");
        m("mortel_noyade", null, "Me noyer ? Je bois l'eau, chef, je ne la laisse pas me boire. Non.", "Je ne suis pas un poisson, chef. Non.");
        t("mortel_noyade", "peureux", "Je nage déjà si mal… Non, chef, par pitié !");
        t("mortel_noyade", "fourbe", "Me noyer ? Quelle drôle d'idée, chef. Non, merci.");
        m("mortel_creuser", null, "Creuser droit sous mes pieds ? Tout mineur sait où ça mène : dans la lave ou au fond d'un trou. Non, chef.",
                "On ne creuse jamais sous ses pieds, chef : c'est la première chose qu'on apprend à la mine. Non.");
        t("mortel_creuser", "peureux", "Sous mes pieds ? Et si c'était la lave en dessous ? Jamais, chef !");
    }

    /** Ce qu'il dit pour refuser encore, même en insistant : ce qui le ferait changer d'avis. */
    static String remede(String raison) {
        return switch (raison) {
            case "paie" -> "Paie-moi d'abord.";
            case "faim" -> "Donne-moi d'abord à manger.";
            case "fatigue" -> "Laisse-moi d'abord me reposer.";
            default -> "Paie-moi bien et laisse-moi souffler : on verra ensuite.";
        };
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
        b.append(t.moral >= 75 ? " J'ai le cœur à l'ouvrage." : t.moral < 25 ? " Mais j'ai le moral au plus bas." : t.moral < 50 ? " Le moral n'y est pas trop." : "");
        String cause = t.moral < 50 ? Humeur.cause(t) : ""; // ce qui lui pèse le plus, au dernier bilan (Moral)
        if (!cause.isEmpty()) b.append(" Ce qui me pèse le plus : ").append(cause).append('.');
        String aveu = Humeur.aveu(t); // un sabotage avoué, ou un butin qui se trahit
        if (!aveu.isEmpty()) b.append(' ').append(aveu);
        return b.toString();
    }

    /** « Jour 12, 14 h » : la date du jeu. */
    static String date(net.minecraft.world.level.Level l) {
        long jour = l.getDayTime() / 24000 + 1;
        int heure = (int) ((l.getDayTime() + 6000) % 24000 / 1000);
        return "Jour " + jour + ", " + heure + " h";
    }
}
