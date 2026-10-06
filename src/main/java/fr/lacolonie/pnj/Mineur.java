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
 * devant la lave ou l'eau, et vient demander la pioche qu'il lui faut (fer pour le diamant). Pour remonter, il
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

    static void casser(Travailleur t, BlockPos p) {
        t.memoire.putString("cible", cle(p));
        Cerveau.lancer(t, new Actions.Casser(p));
    }

    /** La dernière action sur ce bloc a échoué. */
    static boolean echecSur(Travailleur t, BlockPos p) {
        return t.memoire.getString("cible").equals(cle(p)) && !t.resultat.isEmpty() && !t.resultat.equals("ok");
    }

    enum Case { PRETE, ACTION, LIQUIDE, BLOQUE }

    /** Prépare une case de tunnel (pieds en p, {@code haut} blocs de hauteur). */
    static Case creuserCase(Travailleur t, BlockPos p, int haut) {
        Level l = t.level();
        for (int h = -1; h <= haut; h++)
            for (int[] d : new int[][]{{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (Monde.liquide(l, p.offset(d[0], h, d[1])) != null) return Case.LIQUIDE;
        for (int h = 0; h < haut; h++) {
            BlockPos q = p.above(h);
            if (!Monde.libre(l, q)) {
                if (echecSur(t, q)) return Case.BLOQUE;
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
            } else Cerveau.lancer(t, new Actions.Demander("Il y a un trou sous mes pieds : il me faudrait de la pierre (cobblestone) pour le combler.",
                    Actions.Filtre.de("#c:cobblestones"), 8));
            return Case.ACTION;
        }
        return Case.PRETE;
    }

    /** Avance d'une case prête, en posant une torche de temps en temps. */
    static void avancer(Travailleur t, BlockPos p, int compteur) {
        CompoundTag m = t.memoire;
        if (compteur % TORCHES == 0 && t.level().getMaxLocalRawBrightness(p.above()) < 8) {
            if (t.compter(Actions.Filtre.de("minecraft:torch")) > 0) {
                m.putInt("torche", m.getInt("torche") + 1);
                if (m.getInt("torche") % 2 == 1) { Cerveau.lancer(t, new Actions.Poser(p.above(), Actions.Filtre.de("minecraft:torch"))); return; }
            } else Cerveau.parfois(t, "torches", "Je n'ai plus de torches, chef : mes galeries vont rester sombres.", true, 1800);
        }
        m.putInt("torche", 0);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(p), 0.7));
    }

    /** Position de la marche k de l'escalier en colimaçon (k = 0 : l'entrée, en surface). */
    static BlockPos marche(BlockPos puits, int k) {
        int x = puits.getX(), z = puits.getZ();
        for (int i = 1; i <= k; i++) {
            int[] d = DIRS[((i - 1) / PALIER) % 4];
            x += d[0];
            z += d[1];
        }
        return new BlockPos(x, puits.getY() - k, z);
    }

    void nouveauPuits(Travailleur t) {
        CompoundTag m = t.memoire;
        // Le plus près possible du bureau : il aura moins de marche pour rapporter le minerai.
        BlockPos s = Monde.solNaturel(t, t.bureau, 64);
        if (s == null && !t.chezLui(t.blockPosition())) s = Monde.solNaturel(t, t.blockPosition(), 24);
        if (s == null) {
            // Tout est construit autour du bureau : il explore plus loin pour trouver un sol naturel.
            BlockPos e = Cerveau.pointAExplorer(t, "puits", t.bureau, t.porteeMax());
            if (e != null) {
                Cerveau.Portee p = Cerveau.partir(t, e, "chercher un endroit où creuser");
                if (p == Cerveau.Portee.JOURNEE || p == Cerveau.Portee.EXPEDITION) return;
                if (p == Cerveau.Portee.DEMAIN) { Cerveau.attendre(t, 60); return; }
            }
            Cerveau.parfois(t, "pas_de_sol", "Je ne trouve aucun sol naturel où creuser sans abîmer de construction.", true, 1800);
            if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else Cerveau.attendre(t, 30);
            return;
        }
        m.putLong("puits", s.asLong());
        m.putInt("marche", 0);
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
            Cerveau.parfois(t, "sans_commande", "Je n'ai pas de commande, chef : choisis ce que je dois miner dans l'écran du bureau.", true, 1800);
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
                Cerveau.lancer(t, new Actions.Demander("Pour le " + c.nom.toLowerCase() + ", il me faut une meilleure pioche (en fer ou mieux).", c.outil, 1));
            else Cerveau.lancer(t, new Actions.Demander("Il me faut une pioche pour miner.", c.outil, 1));
            return;
        }

        // 1. Les filons qu'il voit autour de lui : le minerai demandé d'abord, puis tout autre minerai récoltable.
        for (Object[] f : new Object[][]{{c.blocs, 3}, {Actions.Filtre.de("#c:ores"), 2}}) {
            BlockPos q = Monde.chercherBloc(t, (Actions.Filtre) f[0], (int) f[1], t.blockPosition(), true, true);
            if (q != null && !ignores.contains(cle(q))) {
                if (echecSur(t, q)) { ignores.putBoolean(cle(q), true); m.put("ignores", ignores); }
                else { casser(t, q); return; }
            }
        }

        // 2. Pas encore de puits : il en choisit l'entrée, sur un sol naturel.
        if (!m.contains("puits")) { nouveauPuits(t); return; }
        BlockPos puits = BlockPos.of(m.getLong("puits"));
        int cible = Math.max(-59, profondeur);

        // 3. L'escalier, marche après marche, jusqu'à la profondeur du minerai.
        if (!m.contains("fond")) {
            int k = m.getInt("marche") + 1;
            if (puits.getY() - m.getInt("marche") <= cible) {
                m.putLong("fond", marche(puits, m.getInt("marche")).asLong());
            } else {
                BlockPos p = marche(puits, k);
                Case r = creuserCase(t, p, 3);
                if (r == Case.ACTION) return;
                if (r != Case.PRETE) {
                    t.dire(r == Case.LIQUIDE ? "De l'eau ou de la lave dans mon escalier : je creuse ailleurs." : "Je n'arrive pas à creuser ici : je change d'endroit.");
                    m.remove("puits");
                    Cerveau.attendre(t, 2);
                    return;
                }
                m.putInt("marche", k);
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
            t.direAuChef("J'ai fini mes galeries à cet endroit : je creuse un nouveau puits un peu plus loin.");
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
