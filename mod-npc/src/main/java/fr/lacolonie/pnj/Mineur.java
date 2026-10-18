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
 * Il pose une torche tous les 8 blocs s'il en a, contre une paroi de côté (et en reprend au dépôt quand il n'en a plus),
 * comble les trous du sol avec de la pierre, bouche la lave ou l'eau autour de sa case, et abandonne une galerie seulement
 * s'il n'a pas de quoi la boucher. Couloir barré ou galeries finies : il ne remonte pas, il ouvre une galerie de côté à son
 * niveau vers un nouveau coin ({@link #detour}), et ne creuse un autre escalier qu'en dernier recours. Pour un filon, il bouche d'abord la lave qui le touche (ou
 * l'eau au-dessus) et ne le casse jamais en se tenant dessous s'il porte du gravier ou du sable ({@link Actions.Casser}) ;
 * et il prend au dépôt la pioche qu'il lui faut (fer pour le diamant), ou vient la demander s'il n'y en a pas. Pour remonter, il
 * reprend son escalier.
 */
final class Mineur extends Metier {
    static final String PIOCHE = "#minecraft:pickaxes";
    static final Actions.Filtre PIOCHES = Actions.Filtre.de(PIOCHE);
    static final String PIERRE_OU_MIEUX = "minecraft:stone_pickaxe|minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe";
    static final String FER_OU_MIEUX = "minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe";
    static final int PALIER = 6, GALERIE = 24, GALERIES = 8, TORCHES = 8;
    static final int[][] DIRS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
    /** La terre de son escalier : ce qu'il pose d'abord pour boucher un trou. */
    static final Actions.Filtre TERRE = Actions.Filtre.de("minecraft:dirt|minecraft:coarse_dirt");
    static final Actions.Filtre TORCHE = Actions.Filtre.de("minecraft:torch");

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
                // Toute la lave autour, et l'eau sous terre loin des constructions : même sans toucher la case, elle coulerait dans
                // la galerie par une paroi creuse (une grotte) ; avant, il abandonnait la galerie, puis le puits, et remontait (A4).
                boolean bouchable = "lave".equals(quoi) || "eau".equals(quoi) && !l.canSeeSky(q.above()) && !Monde.construitAutour(l, q);
                if (!bouchable || echecSur(t, q) || t.memoire.getInt("bouchons") >= BOUCHONS || Itineraire.caseDeChantier(t, true) < 0) {
                    t.memoire.remove("bouchons");
                    return Case.LIQUIDE;
                }
                t.memoire.putInt("bouchons", t.memoire.getInt("bouchons") + 1);
                t.memoire.putString("cible", cle(q));
                Lieux.pose(t, q);
                Cerveau.lancer(t, new Actions.Boucher(q));
                return Case.ACTION;
            }
        for (int h = 0; h < haut; h++) {
            BlockPos q = p.above(h);
            if (!Monde.libre(l, q)) {
                // Le tunnel d'un joueur (briques, planches…), le tampon d'une construction, sous une fondation : il n'y touche pas (Lieux).
                if (echecSur(t, q) || !Lieux.peutCasser(t, q)) return Case.BLOQUE;
                casser(t, q);
                return Case.ACTION;
            }
        }
        BlockPos sous = p.below();
        if (Monde.libre(l, sous)) {
            if (echecSur(t, sous)) return Case.BLOQUE;
            // La terre de l'escalier d'abord (elle ne compte pas comme une construction), sinon un bloc banal de son sac qui
            // n'est pas ce que sa mission demande de livrer (Itineraire.caseDeChantier) ; sans rien, il en demande au chef.
            int i = Itineraire.caseDeChantier(t, true);
            if (i >= 0) {
                t.memoire.putString("cible", cle(sous));
                String bloc = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.inventaire.getItem(i).getItem()).toString();
                Lieux.pose(t, sous);
                Cerveau.lancer(t, new Actions.Poser(sous, Actions.Filtre.de(t.compter(TERRE) > 0 ? TERRE.texte : bloc)));
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

    /** Avance d'une case prête (dans la direction dx, dz), en posant une torche de temps en temps. */
    static void avancer(Travailleur t, BlockPos p, int compteur, int dx, int dz) {
        if (compteur % TORCHES == 0) eclairer(t, p, dx, dz);
        Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(p), 0.7));
    }

    /**
     * S'il fait sombre, une torche de son sac à hauteur de tête, contre une paroi de côté (E3). Avant, elle se collait au
     * bloc qu'il regardait, celui qu'il cassait juste après : elle tombait, et sa galerie restait noire.
     */
    static void eclairer(Travailleur t, BlockPos p, int dx, int dz) {
        Level l = t.level();
        BlockPos q = p.above();
        if (l.getMaxLocalRawBrightness(q) >= 8 || !l.getBlockState(q).isAir()) return;
        if (t.compter(TORCHE) == 0) { Cerveau.ecrire(t, "torches", 1800, "torches"); return; }
        net.minecraft.core.Direction avant = net.minecraft.core.Direction.fromDelta(dx, 0, dz);
        if (avant == null) return;
        for (net.minecraft.core.Direction cote : new net.minecraft.core.Direction[]{avant.getClockWise(), avant.getCounterClockWise()}) {
            var torche = net.minecraft.world.level.block.Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, cote.getOpposite());
            if (!torche.canSurvive(l, q) || Actions.sortir(t, TORCHE).isEmpty()) continue;
            l.setBlockAndUpdate(q, torche);
            Lieux.pose(t, q);
            t.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }
    }

    /**
     * Plus de torche : il en reprend au dépôt, s'il y en a (regardé au plus toutes les 2 minutes, et seulement si le poste
     * est chargé). Vrai s'il y va.
     */
    static boolean torchesAuDepot(Travailleur t) {
        CompoundTag m = t.memoire;
        long maintenant = Cerveau.temps(t);
        if (m.getLong("torches_vu") > maintenant) return false;
        m.putLong("torches_vu", maintenant + 120);
        if (t.compter(TORCHE) > 0) return false;
        Bureau.Entite b = Bureau.entite(t);
        if (b == null || b.compter(TORCHE) == 0) return false;
        Cerveau.lancer(t, new Actions.Prendre(TORCHE, Math.max(1, Reglages.actuel.mineur_torches_prises)));
        return true;
    }

    /** Les galeries de côté, dans l'ordre : vers l'ouest (ses galeries vont vers l'est), puis le nord, puis le sud. */
    static final int[][] DETOURS = {{-1, 0}, {0, -1}, {0, 1}};

    /** Leur longueur : juste au-delà des galeries déjà creusées (24 vers l'est ; 24 au nord comme au sud). */
    static int longueurDetour(int i) { return i == 0 ? GALERIE + 3 : 2 * 3 * GALERIES + 3; }

    /**
     * A4 : couloir barré des deux côtés, ou galeries finies. Au lieu de remonter creuser un autre escalier, il ouvre une
     * galerie de côté, à son niveau, depuis son fond, et y recommence ses galeries ; barrée, il essaie la direction suivante.
     * Faux s'il n'y a plus de détour possible (il remontera, en dernier recours).
     */
    static boolean detour(Travailleur t, BlockPos f) {
        CompoundTag m = t.memoire;
        if (!m.contains("detour")) {
            if (m.getInt("detours") >= Reglages.actuel.mineur_detours) return false;
            m.putInt("detour", 0);
            m.putInt("detour_pas", 0);
            Cerveau.dire(t, "detour", 120, "Plus rien à creuser ici : j'ouvre une galerie de côté, sans remonter.");
        }
        int i = m.getInt("detour");
        if (i >= DETOURS.length) { m.remove("detour"); m.putInt("detours", Reglages.actuel.mineur_detours); return false; }
        int[] d = DETOURS[i];
        int s = m.getInt("detour_pas") + 1;
        if (s > longueurDetour(i)) {
            // Arrivé : un nouveau fond, à son niveau ; il y recommence couloir et galeries.
            m.putLong("fond", f.offset(d[0] * longueurDetour(i), 0, d[1] * longueurDetour(i)).asLong());
            m.putInt("detours", m.getInt("detours") + 1);
            m.remove("detour");
            m.remove("detour_pas");
            m.putInt("cote", 1);
            m.putInt("branche", 1);
            m.putInt("couloir", 0);
            m.putInt("pas", 0);
            Cerveau.attendre(t, 1);
            return true;
        }
        BlockPos p = f.offset(d[0] * s, 0, d[1] * s);
        Case r = creuserCase(t, p, 2);
        if (r == Case.ACTION) return true;
        if (r != Case.PRETE) {
            // Barrée aussi : la direction suivante, depuis son fond.
            m.putInt("detour", i + 1);
            m.putInt("detour_pas", 0);
            m.remove("cible");
            Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(f), 1));
            return true;
        }
        m.putInt("detour_pas", s);
        avancer(t, p, s, d[0], d[1]);
        return true;
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

    /** Au plus tant de puits ratés de suite (escalier qui ne descend pas) avant de se dire bloqué. */
    static final int RATES = 3;

    /**
     * Un puits raté : il oublie l'entrée de la colonie (il en cherchera une autre) ; après {@link #RATES} de suite, il est
     * bloqué (« ⚠ Aucun lieu sûr pour miner ») au lieu de se dire « Mine » sans rien faire, et n'essaie plus qu'une
     * fois toutes les 5 minutes.
     */
    static void puitsRate(Travailleur t) {
        CompoundTag m = t.memoire;
        m.remove("puits_colonie");
        m.putInt("rates", m.getInt("rates") + 1);
        if (m.getInt("rates") >= RATES) {
            t.bloquer(Statut.LIEU_MINE);
            m.putLong("rates_t", Cerveau.temps(t) + 300);
        }
    }

    /**
     * Nouveau lieu (« Travaille ici ») ou nouveau chantier : il oublie ses puits ratés et sa mine, et n'est plus bloqué
     * « Aucun lieu sûr pour miner » ; il cherche aussitôt une entrée.
     */
    static void oublierMine(Travailleur t) {
        t.memoire.remove("rates");
        t.memoire.remove("rates_t");
        t.memoire.remove("mine");
        t.debloquer(Statut.LIEU_MINE);
    }

    /**
     * Sans lieu où miner, il attend au poste, pas au fond d'une galerie : pris sous un éboulement, il y restait des jours,
     * le sac plein (fantôme « sacs-3-jours »). S'il ne peut pas rentrer, le temps coincé compte, et il y est ramené (Coince).
     * Mis à « Travaille ici », ou en expédition, il attend sur place.
     */
    static void attendreAuPoste(Travailleur t) {
        if (t.lieuImpose == null && !t.expedition && t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 6 * 6) Cerveau.rentrer(t);
        else Cerveau.attendre(t, 30);
    }

    void nouveauPuits(Travailleur t) {
        CompoundTag m = t.memoire;
        if (m.getInt("rates") >= RATES) {
            if (m.getLong("rates_t") > Cerveau.temps(t)) {
                t.bloquer(Statut.LIEU_MINE);
                attendreAuPoste(t);
                return;
            }
            m.putInt("rates", RATES - 1); // un nouvel essai ; raté, il est de nouveau bloqué 5 minutes
        }
        // Le plus près possible du bureau : il aura moins de marche pour rapporter le minerai.
        // Jamais sur une structure ni près de la base d'un autre joueur.
        // Loin des constructions, jamais dans une structure ni près d'un autre poste ({@link Lieux#puitsPermis}). Dans la
        // colonie, un seul puits : il reprend toujours la même entrée, son escalier part dans une autre direction.
        java.util.function.Predicate<BlockPos> refus = q -> !Lieux.puitsPermis(t, q);
        BlockPos s = null;
        int sens = 0;
        if (t.lieuImpose != null) s = Monde.solNaturel(t, t.lieuImpose, Lieux.RAYON_ICI, refus);
        else if (m.contains("puits_colonie") && Lieux.dansColonie(t, BlockPos.of(m.getLong("puits_colonie")))) {
            s = BlockPos.of(m.getLong("puits_colonie"));
            sens = (m.getInt("sens_colonie") + 1) % 4;
        }
        if (s == null && t.lieuImpose == null) s = Monde.solNaturel(t, t.bureau, 64, refus);
        if (s == null && t.lieuImpose == null && !t.chezLui(t.blockPosition())) s = Monde.solNaturel(t, t.blockPosition(), 24, refus);
        if (s != null && t.lieuImpose == null && Lieux.dansColonie(t, s)) { m.putLong("puits_colonie", s.asLong()); m.putInt("sens_colonie", sens); }
        if (s == null && t.lieuImpose != null) {
            t.bloquer(Statut.LIEU_MINE);
            Cerveau.dire(t, "pas_de_lieu", 600, "Aucun lieu sûr pour miner là où tu m'as dit : il y a des constructions trop près.");
            Cerveau.attendre(t, 30);
            return;
        }
        if (s == null) {
            // Tout est construit autour du bureau : il explore plus loin pour trouver un sol naturel.
            BlockPos e = Cerveau.pointAExplorer(t, "puits", t.bureau, t.porteeMax());
            if (e != null) {
                Cerveau.Portee p = Cerveau.partir(t, e, "chercher un endroit où creuser");
                if (p == Cerveau.Portee.JOURNEE || p == Cerveau.Portee.EXPEDITION) return;
                if (p == Cerveau.Portee.DEMAIN) { Cerveau.attendre(t, 60); return; }
            }
            Cerveau.ecrire(t, "pas_de_sol", 1800, "pas_de_sol");
            t.bloquer(Statut.LIEU_MINE);
            if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else attendreAuPoste(t);
            return;
        }
        m.putLong("puits", s.asLong());
        m.putLong("mine", s.asLong()); // sa mine : ses torches et son pavé n'y comptent pas comme constructions (Lieux.saMine)
        m.putInt("entree", s.getY());
        Lieux.choisi(t, s);
        m.putInt("marche", 0);
        m.putInt("sens", sens);
        m.putInt("tours", 0);
        m.remove("fond");
        m.putInt("cote", 1);
        m.putInt("branche", 1);
        m.putInt("couloir", 0);
        m.putInt("pas", 0);
        m.remove("detour");
        m.remove("detours");
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
            oublierMine(t);
        }
        CompoundTag ignores = m.getCompound("ignores");

        // La bonne pioche : prise au dépôt s'il y en a une (ou une meilleure), sinon demandée au chef.
        if (outilAuDepot(t, PIOCHES, c.outil, "pioche")) return;
        if (!equiperMeilleur(t, s -> convient(s, PIOCHES, c.outil))) {
            if (t.compter(Actions.Filtre.de(PIOCHE)) > 0)
                Cerveau.lancer(t, new Actions.Demander("meilleure_pioche", c.outil, 1, c.nom.toLowerCase()));
            else Cerveau.lancer(t, new Actions.Demander("pioche", c.outil, 1));
            return;
        }
        if (torchesAuDepot(t)) return;

        // 1. Les filons qu'il voit autour de lui : le minerai demandé d'abord, puis tout autre minerai récoltable.
        for (Object[] f : new Object[][]{{c.blocs, 3}, {Actions.Filtre.de("#c:ores"), 2}}) {
            BlockPos q = Monde.chercherBloc(t, (Actions.Filtre) f[0], (int) f[1], t.blockPosition(), true, true);
            if (q != null && !ignores.contains(cle(q))) {
                if (echecSur(t, q) || interdit(t, q, true) || !Lieux.peutCasser(t, q)) { ignores.putBoolean(cle(q), true); m.put("ignores", ignores); }
                else { casser(t, q); return; }
            }
        }

        // 2. Pas encore de puits : il en choisit l'entrée, sur un sol naturel.
        if (!m.contains("puits")) { nouveauPuits(t); return; }
        BlockPos puits = BlockPos.of(m.getLong("puits"));
        // Sous terre : au moins 10 blocs sous l'entrée du puits (Lieux).
        int cible = Math.max(-59, Lieux.regles(t.level()) ? Math.min(profondeur, (m.contains("entree") ? m.getInt("entree") : puits.getY()) - Lieux.SOUS_SURFACE) : profondeur);

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
                    puitsRate(t);
                    Cerveau.attendre(t, 2);
                    return;
                }
                m.putInt("marche", k);
                m.putInt("tours", 0);
                if (k >= PALIER) { m.remove("rates"); t.debloquer(Statut.LIEU_MINE); } // il descend : ce puits marche
                int[] d = DIRS[((k - 1) / PALIER + sens) % 4];
                avancer(t, p, k, d[0], d[1]);
                return;
            }
        }

        // 4. Les galeries : un couloir le long d'un axe, une galerie tous les 3 blocs, de chaque côté.
        BlockPos f = BlockPos.of(m.getLong("fond"));
        if (m.contains("detour") && detour(t, f)) return;
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
            if (detour(t, f)) return; // A4 : une galerie de côté à son niveau, plutôt que remonter
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
            avancer(t, p, j, 0, cote);
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
        avancer(t, p, s, 1, 0);
    }
}
