package fr.lacolonie.pnj;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Le chef demande l'impossible (cahier 14.9 : « il en rit, fait une blague ») : voler, aller sur la lune, casser la roche
 * du fond du monde… {@link #genre} trouve le genre par mots-clés, sans IA ; l'ouvrier répond par une réplique
 * {@code impossible_<genre>} de remarques.txt, sans obéir ({@code Travailleur.obeir}). Un ordre ordinaire (« va chercher
 * des diamants », « coupe du bois ») n'y est jamais pris. Pur Java.
 */
final class Impossible {
    private Impossible() {}

    /** Les genres, dans l'ordre où on les cherche (« change-toi en dragon » : de la magie avant la créature). */
    static final List<Object[]> GENRES = List.of(
            new Object[]{"roche", Pattern.compile(" (?:casse|cassez|casser|creuse|creusez|creuser|mine|minez|miner|perce|percez|percer|brise|brisez|briser|detruis|detruisez|"
                    + "detruire|traverse|traverser|pioche|piocher) (?:moi |nous )?(?:la |le |de la |du |dans la |dans le |a travers la |a travers le |sous la |sous le )?"
                    + "(?:bedrock|roche mere|roche du fond|fond du monde|pierre du fond|socle du monde|roche indestructible|pierre indestructible) ")},
            new Object[]{"voler", Pattern.compile(" (?:s |t |vous |te )?envole| (?:des|tes|vos|ses|mes) ailes | (?:vole|volez|voler) (?:dans le ciel|dans les airs|dans l air|comme un oiseau|"
                    + "jusqu|au dessus|par dessus|la haut|tout la haut|vers le ciel|au ciel|dans les nuages)| (?:apprends|apprenez|sais tu|savez vous|tu sais|vous savez) (?:a )?voler "
                    + "| (?:vole|volez|voler) (?:\\? )?$")},
            new Object[]{"lune", Pattern.compile(" (?:sur|decroche|decrocher|decrochez|attrape|attraper|ramene|ramener|rapporte|apporte|cueille|cherche|chercher|touche|jusqu a|"
                    + "va|aller) (?:moi |nous )?(?:la lune|le soleil|une etoile|les etoiles|des etoiles) | (?:va|vas|allez|aller|monte|montez|vole) (?:jusqu )?au soleil (?:\\? )?$")},
            new Object[]{"nature", Pattern.compile(" (?:arrete|arretez|arreter|stoppe|empeche|chasse|fais cesser|faites cesser) (?:la pluie|l orage|la neige|le vent|les nuages|la nuit|"
                    + "le tonnerre|le temps|l hiver) | (?:fais|faites) (?:lever|venir|revenir|briller|tomber|cesser) (?:le soleil|la pluie|la neige|la nuit|le jour|l orage) "
                    + "| (?:fais|faites) (?:pleuvoir|neiger|le beau temps|le jour|la nuit) | change (?:le temps|la meteo|le ciel) | (?:remonte|recule) le temps ")},
            new Object[]{"magie", Pattern.compile(" (?:change|changer|changez|transforme|transformer|transformez) (?:la |le |les |cette |ce |ces |du |de la )?(?:pierre|pierres|terre|bois|"
                    + "sable|fer|cuivre|charbon|eau|plomb|caillou|cailloux|roche|gravier|terre) en (?:or|diamant|diamants|emeraude|emeraudes|argent|pieces) "
                    + "| (?:sois|soyez|etre) a deux endroits | (?:deviens|devenir|devenez|rends toi|rendez vous) invisible | (?:jette|lance|jeter|lancer) un sort "
                    + "| fais de la magie | (?:change|transforme) toi en ")},
            new Object[]{"creature", Pattern.compile(" (?:dragon|dragons|licorne|licornes|phenix|griffon|griffons|chimere|hydre|sirene|sirenes|basilic|pegase|kraken) ")},
            new Object[]{"quantite", Pattern.compile(" (?:mille|un million|des millions|un milliard|des milliards|des milliers de|cent mille|\\d{4,}) (?:d )?(?:diamant|diamants|emeraude|"
                    + "emeraudes|lingots d or|pepites d or|netherite) | (?:un million|des millions|un milliard|des milliards) | (?:toute la foret|toutes les forets|toute la montagne|"
                    + "toutes les montagnes|tous les arbres du monde|tous les diamants du monde|le monde entier|toute la terre|toute la mer|tout l ocean) ")},
            new Object[]{"duree", Pattern.compile(" (?:(?:2[5-9]|[3-9]\\d|\\d{3,}) ?h|(?:2[5-9]|[3-9]\\d|\\d{3,}) heures|quarante huit heures|cent heures|mille heures) "
                    + "| sans (?:jamais )?(?:dormir|manger|te reposer|t arreter de travailler|aucun repos|repos) | sans jamais t arreter | pour l eternite "
                    + "| jusqu a la fin (?:des temps|du monde) ")},
            new Object[]{"vite", Pattern.compile(" (?:chateau|cathedrale|forteresse|palais|donjon|ville|cite|muraille|rempart|empire) .*(?:en|d ici|dans) (?:une|1|deux|2|trois|3|cinq|5|dix|10) "
                    + "(?:minute|minutes|seconde|secondes) | en (?:une|1|deux|2) secondes? | en (?:un|1) (?:clin d oeil|claquement de doigts|eclair) | en un rien de temps "
                    + "| (?:finis|termine|fais|acheve) tout (?:tout de suite|immediatement|sur le champ|maintenant) ")},
            new Object[]{"general", Pattern.compile(" l impossible | (?:un|des|le) miracles? | (?:deplace|souleve) (?:la montagne|les montagnes|le chateau|la maison) "
                    + "| (?:vide|bois|asseche) (?:la mer|l ocean|la riviere|le lac) | marche sur l eau | respire sous l eau ")});

    /** Le genre d'impossible que demande cette phrase (« lune », « voler »… ou « general »), ou null pour un ordre ordinaire. */
    static String genre(String texte) {
        if (texte == null || texte.isBlank()) return null;
        String n = Ordres.normaliser(texte);
        for (Object[] g : GENRES) if (((Pattern) g[1]).matcher(n).find()) return (String) g[0];
        return null;
    }
}
