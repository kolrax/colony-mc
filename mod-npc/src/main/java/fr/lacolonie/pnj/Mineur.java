package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Le mineur. Commande : un minerai ou une pierre. Il se débrouille seul :
 * <ol>
 * <li>il extrait les filons qu'il voit autour de lui (le minerai demandé d'abord) ;</li>
 * <li>sinon, il choisit un sol naturel le plus près possible du bureau (jamais dans une construction ; s'il n'y en
 *   a pas, il en cherche plus loin) et creuse un escalier en colimaçon jusqu'à la profondeur du minerai ;</li>
 * <li>là, il mine en galeries parallèles espacées de 3 blocs, comme un joueur.</li>
 * </ol>
 * Il pose une torche tous les 8 blocs s'il en a, comble les trous du sol avec de la pierre, abandonne une galerie
 * devant la lave ou l'eau s'il n'a pas de quoi la boucher ; pour un filon, il bouche d'abord la lave qui le touche (ou
 * l'eau au-dessus) et ne le casse jamais en se tenant dessous s'il porte du gravier ou du sable ({@link Actions.Casser}) ;
 * et il vient demander la pioche qu'il lui faut (fer pour le diamant). Pour remonter, il
 * reprend son escalier.
 */
final class Mineur extends Metier {
    static final String PIOCHE = "#minecraft:pickaxes";
    static final String PIERRE_OU_MIEUX = "minecraft:stone_pickaxe|minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe";
    static final String FER_OU_MIEUX = "minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe";
    static final int PALIER = 6, GALERIE = 24, GALERIES = 8, TORCHES = 8;
    static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    /** Une commande de minerai : les blocs à miner, la hauteur (Y) des galeries et la pioche nécessaire. */
    static final class Minerai extends Commande {
        final Actions.Filtre blocs, outil;
        final int profondeur;

        Minerai(String id, String nom, String objet, String blocs, int profondeur, String outil) {
            super(id, nom, objet);
            this.blocs = Actions.Filtre.de(blocs);
            this.profondeur = profondeur;
            this.outil = Actions.Filtre.de(outil == null ? PIOCHE : outil);
        }
    }

    Mineur() {
        super("mineur", "Mineur", "Trouve seul le minerai demandé : filons, escalier jusqu'à la bonne profondeur, galeries parallèles.", false,
                "nourriture", "minecraft:torch", "#minecraft:pickaxes", "#c:cobblestones");
        commandes.add(new Minerai("charbon", "Charbon", "minecraft:coal", "#c:ores/coal", 48, null));
        commandes.add(new Minerai("fer", "Fer", "minecraft:raw_iron", "#c:ores/iron", 16, PIERRE_OU_MIEUX));
        commandes.add(new Minerai("cuivre", "Cuivre", "minecraft:raw_copper", "#c:ores/copper", 48, PIERRE_OU_MIEUX));
        commandes.add(new Minerai("zinc", "Zinc (Create)", "create:raw_zinc", "#c:ores/zinc", 10, PIERRE_OU_MIEUX));
        commandes.add(new Minerai("or", "Or", "minecraft:raw_gold", "#c:ores/gold", -16, FER_OU_MIEUX));
        commandes.add(new Minerai("lapis", "Lapis-lazuli", "minecraft:lapis_lazuli", "#c:ores/lapis", 0, PIERRE_OU_MIEUX));
        commandes.add(new Minerai("redstone", "Redstone", "minecraft:redstone", "#c:ores/redstone", -58, FER_OU_MIEUX));
        commandes.add(new Minerai("diamant", "Diamant", "minecraft:diamond", "#c:ores/diamond", -58, FER_OU_MIEUX));
        commandes.add(new Minerai("pierre", "Pierre", "#c:cobblestones", "minecraft:stone", 50, null));
        commandes.add(new Minerai("andesite", "Andésite", "minecraft:andesite", "minecraft:andesite", 40, null));
        commandes.add(new Minerai("granite", "Granite", "minecraft:granite", "minecraft:granite", 40, null));
        commandes.add(new Minerai("diorite", "Diorite", "minecraft:diorite", "minecraft:diorite", 40, null));
        commandes.add(new Minerai("abimes", "Ardoise des abîmes", "minecraft:cobbled_deepslate", "minecraft:deepslate", -20, null));
    }

    // --- Petits outils ------------------------------------------------------------------------------

    static String cle(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    /** Jamais creuser à moins de tant de blocs (à l'horizontale, à toute hauteur) du poste d'un autre joueur (cahier 3.3). */
    static final int BASE_VOISINE = 32;
    /** Serveur de tests : la règle des bases ne vaut que dans le lot « voisins » (ailleurs, des tests posent côte à côte les postes de faux chefs). */
    static boolean voisinsEnEssai;
    /** Délai entre deux lettres pour la même structure ou le même voisin (6 h). */
    static final int AVIS = 6 * 3600;

    /**
     * Il ne doit pas creuser en q (cahier 3.3 et 14.2) : une pièce de structure (puits de mine, village, ruines…), ou la base
     * d'un autre joueur que son chef. S'il {@code avertit}, il prévient son chef, au plus une fois toutes les 6 h pour chaque
     * structure (zone de 128 blocs) et chaque voisin : sinon les mêmes galeries le redisaient chaque demi-heure.
     */
    static boolean interdit(Travailleur t, BlockPos q, boolean avertit) {
        String s = Monde.structure(t.level(), q);
        if (s != null) {
            if (avertit) Cerveau.ecrire(t, "structure:" + (q.getX() >> 7) + "," + (q.getZ() >> 7), AVIS, "structure", s, q.toShortString());
            return true;
        }
        String b = baseVoisine(t, q);
        if (b != null && avertit) Cerveau.ecrire(t, "base_voisine:" + b, AVIS, "base_voisine", b);
        return b != null;
    }

    /** « de Robert » (pour « les terres de Robert ») si q est à moins de {@link #BASE_VOISINE} blocs du poste d'un autre joueur que son chef ; sinon null. */
    static String baseVoisine(Travailleur t, BlockPos q) {
        if (!(t.level() instanceof net.minecraft.server.level.ServerLevel sl)) return null;
        var serveur = sl.getServer();
        if (serveur instanceof net.minecraft.gametest.framework.GameTestServer && !voisinsEnEssai) return null;
        for (var e : Courrier.de(serveur).postes.entrySet()) {
            Courrier.Poste p = e.getValue();
            if (e.getKey().equals(t.chef) || p.monde() != sl.dimension()) continue;
            double dx = p.pos().getX() - q.getX(), dz = p.pos().getZ() - q.getZ();
            if (dx * dx + dz * dz >= BASE_VOISINE * BASE_VOISINE) continue;
            var joueur = serveur.getPlayerList().getPlayer(e.getKey());
            String nom = joueur != null ? joueur.getName().getString() : serveur.getProfileCache() == null ? null
                    : serveur.getProfileCache().get(e.getKey()).map(com.mojang.authlib.GameProfile::getName).orElse(null);
            if (nom == null) return "d'un voisin";
            return ("aeiouyhAEIOUYH".indexOf(nom.charAt(0)) >= 0 ? "d'" : "de ") + nom;
        }
        return null;
    }

    static void casser(Travailleur t, BlockPos p) {
        t.memoire.putString("cible", cle(p));
        Cerveau.lancer(t, new Actions.Casser(p));
    }

    /** La dernière action sur ce bloc a échoué. */
    static boolean echecSur(Travailleur t, BlockPos p) {
        return t.memoire.getString("cible").equals(cle(p)) && !t.resultat.isEmpty() && !t.resultat.equals("ok");
    }

    enum Case { PRETE, ACTION, LIQUIDE, BLOQUE }

    /** Au plus tant de blocs posés dans l'eau ou la lave pour une même case ; au-delà, il abandonne (un lac). */
    static final int BOUCHONS = 10;

    /** Au plus tant d'attentes qu'un éboulement finisse de tomber dans une même case ; au-delà, il l'abandonne. */
    static final int EBOULIS = 6;

    /**
     * Prépare une case de tunnel (pieds en p, {@code haut} blocs de hauteur). De la lave qui touche la case, ou de l'eau
     * sous terre loin de toute construction (jamais un canal ou un puits de joueur) : il la bouche avec un bloc banal de son
     * sac ({@link Actions.Boucher}) ; tout autre liquide autour, ou sans bloc : il abandonne la case (l'escalier tourne).
     * Du gravier ou du sable au-dessus : il le creuse depuis la case d'à côté, où il se tient, et n'entre qu'une fois la
     * colonne tombée (jamais dessous). Dans une structure ou sous la base d'un autre joueur ({@link #interdit}) : BLOQUE.
     */
    static Case creuserCase(Travailleur t, BlockPos p, int haut) {
        Level l = t.level();
        // Une structure (même son air : un couloir de puits de mine) ou la base d'un autre : il n'y touche pas.
        for (int h = 0; h < haut; h++) if (interdit(t, p.above(h), true)) return Case.BLOQUE;
        for (int h = -1; h <= haut; h++)
            for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos q = p.offset(d[0], h, d[1]);
                String quoi = Monde.liquide(l, q);
                if (quoi == null) continue;
                boolean touche = d[0] == 0 && d[1] == 0 || h >= 0 && h < haut;
                boolean bouchable = touche && ("lave".equals(quoi) || "eau".equals(quoi) && !l.canSeeSky(q.above()) && !Monde.construitAutour(l, q));
                if (!bouchable || echecSur(t, q) || t.memoire.getInt("bouchons") >= BOUCHONS || Itineraire.caseDeChantier(t, true) < 0) {
                    t.memoire.remove("bouchons");
                    return Case.LIQUIDE;
                }
                t.memoire.putInt("bouchons", t.memoire.getInt("bouchons") + 1);
                t.memoire.putString("cible", cle(q));
                Cerveau.lancer(t, new Actions.Boucher(q));
                return Case.ACTION;
            }
        for (int h = 0; h < haut; h++) {
            BlockPos q = p.above(h);
            if (!Monde.libre(l, q)) {
                // Le tunnel d'un joueur (briques, planches…) : il ne casse que le naturel.
                if (echecSur(t, q) || Monde.construit(l, q)) return Case.BLOQUE;
                casser(t, q);
                return Case.ACTION;
            }
        }
        BlockPos sous = p.below();
        if (Monde.libre(l, sous)) {
            if (echecSur(t, sous)) return Case.BLOQUE;
            if (t.compter(Actions.Filtre.de("#c:cobblestones")) > 0) {
                t.memoire.putString("cible", cle(sous));
                Cerveau.lancer(t, new Actions.Poser(sous, Actions.Filtre.de("#c:cobblestones")));
            } else Cerveau.lancer(t, new Actions.Demander("pierre_trou", Actions.Filtre.de("#c:cobblestones"), 8));
            return Case.ACTION;
        }
        // Du gravier ou du sable va tomber dans la case : il attend qu'il soit tombé (il le recassera) avant d'y entrer.
        if (Monde.eboulement(l, p, haut)) {
            t.memoire.putInt("eboulis", t.memoire.getInt("eboulis") + 1);
            if (t.memoire.getInt("eboulis") > EBOULIS) { t.memoire.remove("eboulis"); return Case.BLOQUE; }
            Cerveau.attendre(t, 0.5);
            return Case.ACTION;
        }
        t.memoire.remove("bouchons");
        t.memoire.remove("eboulis");
        return Case.PRETE;
    }

    /** Avance d'une case prête, en posant une torche de temps en temps. */
    static void avancer(Travailleur t, BlockPos p, int compteur) {
        CompoundTag m = t.memoire;
        if (compteur % TORCHES == 0 && t.level().getMaxLocalRawBrightness(p.above()) < 8) {
            if (t.compter(Actions.Filtre.de("minecraft:torch")) > 0) {
                m.putInt("torche", m.getInt("torche") + 1);
                if (m.getInt("torche") % 2 == 1) { Cerveau.lancer(t, new Actions.Poser(p.above(), Actions.Filtre.de("minecraft:torch"))); return; }
            } else Cerveau.ecrire(t, "torches", 1800, "torches");
        }
        m.putInt("torche", 0);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(p), 0.7));
    }

    /** Position de la marche k de l'escalier en colimaçon (k = 0 : son départ), qui part dans la direction {@code sens}. */
    static BlockPos marche(BlockPos puits, int k, int sens) {
        int x = puits.getX(), z = puits.getZ();
        for (int i = 1; i <= k; i++) {
            int[] d = DIRS[((i - 1) / PALIER + sens) % 4];
            x += d[0];
            z += d[1];
        }
        return new BlockPos(x, puits.getY() - k, z);
    }

    void nouveauPuits(Travailleur t) {
        CompoundTag m = t.memoire;
        // Le plus près possible du bureau : il aura moins de marche pour rapporter le minerai.
        // Jamais sur une structure ni près de la base d'un autre joueur.
        java.util.function.Predicate<BlockPos> refus = q -> interdit(t, q, false);
        BlockPos s = Monde.solNaturel(t, t.bureau, 64, refus);
        if (s == null && !t.chezLui(t.blockPosition())) s = Monde.solNaturel(t, t.blockPosition(), 24, refus);
        if (s == null) {
            // Tout est construit autour du bureau : il explore plus loin pour trouver un sol naturel.
            BlockPos e = Cerveau.pointAExplorer(t, "puits", t.bureau, t.porteeMax());
            if (e != null) {
                Cerveau.Portee p = Cerveau.partir(t, e, "chercher un endroit où creuser");
                if (p == Cerveau.Portee.JOURNEE || p == Cerveau.Portee.EXPEDITION) return;
                if (p == Cerveau.Portee.DEMAIN) { Cerveau.attendre(t, 60); return; }
            }
            Cerveau.ecrire(t, "pas_de_sol", 1800, "pas_de_sol");
            if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else Cerveau.attendre(t, 30);
            return;
        }
        m.putLong("puits", s.asLong());
        m.putInt("marche", 0);
        m.putInt("sens", 0);
        m.putInt("tours", 0);
        m.remove("fond");
        m.putInt("cote", 1);
        m.putInt("branche", 1);
        m.putInt("couloir", 0);
        m.putInt("pas", 0);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(s), 2));
    }

    @Override
    void travailler(Travailleur t) {
        CompoundTag m = t.memoire;
        Commande commande = Metier.commande(t);
        if (!(commande instanceof Minerai c)) {
            Cerveau.ecrire(t, "sans_commande", 1800, "sans_mission");
            Cerveau.attendre(t, 20);
            return;
        }
        int profondeur = t.profondeurForcee != Integer.MIN_VALUE ? t.profondeurForcee : c.profondeur;

        // Nouvelle commande ou nouveau bureau : on repart de zéro.
        String chantier = c.id + "@" + cle(t.bureau) + "/" + profondeur;
        if (!m.getString("chantier").equals(chantier)) {
            m.putString("chantier", chantier);
            m.remove("puits");
            m.remove("ignores");
            m.remove("cible");
        }
        CompoundTag ignores = m.getCompound("ignores");

        // La bonne pioche.
        if (!t.equiper(c.outil)) {
            if (t.compter(Actions.Filtre.de(PIOCHE)) > 0)
                Cerveau.lancer(t, new Actions.Demander("meilleure_pioche", c.outil, 1, c.nom.toLowerCase()));
            else Cerveau.lancer(t, new Actions.Demander("pioche", c.outil, 1));
            return;
        }

        // 1. Les filons qu'il voit autour de lui : le minerai demandé d'abord, puis tout autre minerai récoltable.
        for (Object[] f : new Object[][]{{c.blocs, 3}, {Actions.Filtre.de("#c:ores"), 2}}) {
            BlockPos q = Monde.chercherBloc(t, (Actions.Filtre) f[0], (int) f[1], t.blockPosition(), true, true);
            if (q != null && !ignores.contains(cle(q))) {
                if (echecSur(t, q) || interdit(t, q, true)) { ignores.putBoolean(cle(q), true); m.put("ignores", ignores); }
                else { casser(t, q); return; }
            }
        }

        // 2. Pas encore de puits : il en choisit l'entrée, sur un sol naturel.
        if (!m.contains("puits")) { nouveauPuits(t); return; }
        BlockPos puits = BlockPos.of(m.getLong("puits"));
        int cible = Math.max(-59, profondeur);

        // 3. L'escalier, marche après marche, jusqu'à la profondeur du minerai.
        if (!m.contains("fond")) {
            int k = m.getInt("marche") + 1, sens = m.getInt("sens");
            if (puits.getY() - m.getInt("marche") <= cible) {
                m.putLong("fond", marche(puits, m.getInt("marche"), sens).asLong());
            } else {
                BlockPos p = marche(puits, k, sens);
                Case r = creuserCase(t, p, 3);
                if (r == Case.ACTION) return;
                if (r != Case.PRETE) {
                    // De l'eau, de la lave ou un bloc incassable sur la prochaine marche : l'escalier tourne et repart de
                    // la marche où il est, comme un joueur. Il ne remonte creuser ailleurs que si les quatre côtés sont pris
                    // (avant, il abandonnait tout l'escalier : Bérengère remontait et redescendait sans fin).
                    if ((m.getInt("marche") > 0 || m.getInt("tours") > 0) && m.getInt("tours") < 3) {
                        m.putLong("puits", marche(puits, m.getInt("marche"), sens).asLong());
                        m.putInt("sens", (m.getInt("marche") / PALIER + sens + 1) % 4);
                        m.putInt("marche", 0);
                        m.putInt("tours", m.getInt("tours") + 1);
                        m.remove("cible");
                        Cerveau.dire(t, "escalier_tourne", 120, r == Case.LIQUIDE ? "De l'eau ou de la lave devant mon escalier : je le fais tourner." : "Un bloc que je ne peux pas casser : mon escalier tourne.");
                        Cerveau.attendre(t, 1);
                        return;
                    }
                    t.dire(r == Case.LIQUIDE ? "De l'eau ou de la lave tout autour de mon escalier : je creuse ailleurs." : "Je n'arrive pas à creuser ici : je change d'endroit.");
                    m.remove("puits");
                    Cerveau.attendre(t, 2);
                    return;
                }
                m.putInt("marche", k);
                m.putInt("tours", 0);
                avancer(t, p, k);
                return;
            }
        }

        // 4. Les galeries : un couloir le long d'un axe, une galerie tous les 3 blocs, de chaque côté.
        BlockPos f = BlockPos.of(m.getLong("fond"));
        int cote = m.getInt("cote"), branche = m.getInt("branche");
        if (branche > GALERIES) {
            if (cote == 1) {
                m.putInt("cote", -1);
                m.putInt("branche", 1);
                m.putInt("couloir", 0);
                m.putInt("pas", 0);
                Cerveau.attendre(t, 1);
                return;
            }
            t.dire(Messages.corps(t, "puits_fini")); // plus de lettre : elle revenait à chaque puits, sans rien demander au chef
            m.remove("puits");
            Cerveau.attendre(t, 2);
            return;
        }
        // 4a. Le couloir (vers le sud, puis vers le nord) jusqu'au départ de la galerie.
        int depart = 3 * branche;
        if (m.getInt("couloir") < depart) {
            int j = m.getInt("couloir") + 1;
            BlockPos p = f.offset(0, 0, cote * j);
            Case r = creuserCase(t, p, 2);
            if (r == Case.ACTION) return;
            if (r != Case.PRETE) { m.putInt("branche", GALERIES + 1); Cerveau.attendre(t, 1); return; }
            m.putInt("couloir", j);
            avancer(t, p, j);
            return;
        }
        // 4b. La galerie elle-même, vers l'est.
        int s = m.getInt("pas") + 1;
        BlockPos sortie = f.offset(0, 0, cote * depart);
        BlockPos p = sortie.offset(s, 0, 0);
        if (s > GALERIE) {
            m.putInt("branche", branche + 1);
            m.putInt("pas", 0);
            Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(sortie), 1)); // retour au couloir
            return;
        }
        Case r = creuserCase(t, p, 2);
        if (r == Case.ACTION) return;
        if (r != Case.PRETE) {
            if (r == Case.LIQUIDE) t.dire("De la lave ou de l'eau devant : j'abandonne cette galerie.");
            m.putInt("branche", branche + 1);
            m.putInt("pas", 0);
            Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(sortie), 1));
            return;
        }
        m.putInt("pas", s);
        avancer(t, p, s);
    }
}
