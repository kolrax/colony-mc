package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * « Va me chercher 64 fers », dit au bûcheron (cahier 14.1) : ce n'est pas son ouvrage. Il nomme l'ouvrier du bon métier
 * chez le même chef et propose d'aller le prévenir ; « oui », il y va et lui transmet la commande comme si le chef la lui
 * avait donnée. Ici, le pur Java : qui fournit quoi, lequel choisir, ce que le chef répond, et les mots de la phrase.
 * Côté jeu : {@code Travailleur.proposerRelais}, {@code prevenir}, {@code transmettre} et {@code Actions.Prevenir}.
 */
final class Relais {
    private Relais() {}

    /** La commande à porter : à qui (identifiant, prénom, métier), la mission telle que le chef l'a dite, et de quel chef. */
    record Commande(UUID cible, String prenom, String metier, Ordres.Mission mission, boolean ajouter, boolean quotidien, UUID chef) {}

    /** Un ouvrier du même chef : son métier et sa distance au carré (très grande dans un autre monde). */
    record Candidat(String metier, double distance2) {}

    /**
     * Les métiers qui font cet objet, dans l'ordre où on les cherche : celui que la phrase a reconnu, puis ceux qui ont le
     * même objet à leur catalogue (la viande : l'éleveur, puis le chasseur). Le cuisinier transforme, il ne remplace
     * personne. Jamais celui qui demande.
     */
    static List<String> fournisseurs(String metier, String id, String demandeur) {
        List<String> r = new ArrayList<>(List.of(metier));
        if (!metier.equals("cuisinier"))
            for (var e : Ordres.NOMS.entrySet())
                if (!r.contains(e.getKey()) && !e.getKey().equals("cuisinier") && e.getValue().containsKey(id)) r.add(e.getKey());
        r.remove(demandeur);
        return r;
    }

    /** Le candidat à prévenir : le plus proche du premier métier qui a quelqu'un ; -1 si personne. */
    static int choisir(List<String> fournisseurs, List<Candidat> candidats) {
        for (String m : fournisseurs) {
            int meilleur = -1;
            for (int i = 0; i < candidats.size(); i++)
                if (candidats.get(i).metier().equals(m) && (meilleur < 0 || candidats.get(i).distance2() < candidats.get(meilleur).distance2())) meilleur = i;
            if (meilleur >= 0) return meilleur;
        }
        return -1;
    }

    /**
     * L'IA a lu la phrase à sa façon (ses missions, sans ce qui n'est pas du métier) : on y remet les commandes pour les
     * autres métiers que l'interpréteur a lues, sauf un objet que l'IA a déjà pris pour une mission à lui.
     */
    static void reprendre(Ordres.Lecture ia, Ordres.Lecture seul) {
        seul.pourAutres.forEach((met, m) -> {
            if (ia.missions.stream().noneMatch(x -> x.id().equals(m.id()))) ia.pourAutres.putIfAbsent(met, m);
        });
    }

    /** Le chef répond oui à « je vais le prévenir ? » (« oui », « vas-y », « d'accord », « file »…). */
    static boolean accepte(String texte) {
        if (refuse(texte)) return false;
        String n = Ordres.normaliser(texte);
        return Obeissance.accepte(texte) || Ordres.dit(n, "vas y", "va y", "allez y", "va le prevenir", "va la prevenir", "previens le", "previens la",
                "cours y", "file", "fais donc", "volontiers", "je veux bien", "bonne idee", "s il te plait", "fais le", "c est ca");
    }

    /** Le chef répond non (« non », « pas la peine », « laisse tomber », « je m'en occupe »). */
    static boolean refuse(String texte) {
        String n = Ordres.normaliser(texte);
        return Obeissance.abandonne(texte) || Ordres.dit(n, "non", "nan", "pas la peine", "inutile", "non merci", "je m en occupe",
                "je m en charge", "ne le previens pas", "ne la previens pas", "surtout pas", "pas besoin");
    }

    /** « Bertrand le mineur », « Aude l'éleveur ». */
    static String qui(String prenom, String nomMetier) {
        String m = nomMetier.toLowerCase(Locale.ROOT);
        String plat = Ordres.normaliser(m).strip();
        boolean voyelle = !plat.isEmpty() && "aeiouyh".indexOf(plat.charAt(0)) >= 0;
        return prenom + (voyelle ? " l'" : " le ") + m;
    }

    /** Le nom d'un objet du catalogue dans une phrase : en minuscules, sans ce qui est entre parenthèses (« Zinc (Create) » → « zinc »). */
    static String objet(String nomCatalogue) {
        return nomCatalogue.replaceAll("\\s*\\(.*?\\)", "").strip().toLowerCase(Locale.ROOT);
    }

    /** Ce que personne ne fait chez ce chef, pour « Personne ici ne mine, chef. » */
    static String personne(String metier) {
        return switch (metier) {
            case "mineur" -> "ne mine";
            case "bucheron" -> "ne coupe de bois";
            case "fermier" -> "ne cultive la terre";
            case "eleveur" -> "n'élève de bêtes";
            case "pecheur" -> "ne pêche";
            case "chasseur" -> "ne chasse";
            case "cuisinier" -> "ne tient les fourneaux";
            case "guerrier" -> "ne porte les armes";
            case "transporteur" -> "ne fait de charrois";
            case "guerisseur" -> "ne soigne";
            default -> "ne fait ce travail";
        };
    }
}
