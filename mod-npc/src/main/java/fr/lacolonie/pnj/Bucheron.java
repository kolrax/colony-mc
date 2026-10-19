package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Le bûcheron. Commande : un type de bois (ou n'importe lequel). Il abat les arbres naturels de ce bois (jamais
 * les maisons en rondins : il faut des feuilles naturelles), ramasse le bois et les pousses, et replante.
 * <p>
 * Il ne cherche pas au hasard : il sait dans quels biomes pousse chaque bois (biomes des mods compris), trouve le
 * plus proche et y va directement, en sortie à la journée ou en expédition si c'est loin. Il retient les coins où il
 * a trouvé du bois et y retourne. Il prend sa hache au dépôt ; s'il n'y en a pas, il travaille quand même, bien plus lentement.
 */
final class Bucheron extends Metier {
    static final class Bois extends Commande {
        final Actions.Filtre bois;
        final String article;

        Bois(String id, String nom, String article, String bois) {
            super(id, nom, bois);
            this.bois = Actions.Filtre.de(bois);
            this.article = article;
        }
    }

    Bucheron() {
        super("bucheron", "Bûcheron", "Trouve seul le bois demandé autour du poste (jusqu'à " + Missions.LOIN + " blocs) : il sait où pousse chaque bois, l'abat, ramasse le bois et les pousses, et replante.",
                false, "nourriture", "#minecraft:axes", "#minecraft:saplings");
        commandes.add(new Bois("tout", "N'importe quel bois", "du bois", "#minecraft:logs"));
        commandes.add(new Bois("chene", "Chêne", "du chêne", "minecraft:oak_log"));
        commandes.add(new Bois("bouleau", "Bouleau", "du bouleau", "minecraft:birch_log"));
        commandes.add(new Bois("sapin", "Sapin", "du sapin", "minecraft:spruce_log"));
        commandes.add(new Bois("chene_noir", "Chêne noir", "du chêne noir", "minecraft:dark_oak_log"));
        commandes.add(new Bois("jungle", "Bois de la jungle", "du bois de la jungle", "minecraft:jungle_log"));
        commandes.add(new Bois("acacia", "Acacia", "de l'acacia", "minecraft:acacia_log"));
        commandes.add(new Bois("cerisier", "Cerisier", "du cerisier", "minecraft:cherry_log"));
        commandes.add(new Bois("paletuvier", "Palétuvier", "du palétuvier", "minecraft:mangrove_log"));
    }

    @Override
    void travailler(Travailleur t) {
        CompoundTag m = t.memoire;
        Bois c = Metier.commande(t) instanceof Bois b ? b : (Bois) commandes.get(0); // sans commande : n'importe quel bois
        ServerLevel l = (ServerLevel) t.level();

        // Sa hache : prise au dépôt s'il y en a une (ou une meilleure) ; sinon, il le dit et travaille sans.
        if (outilAuDepot(t, HACHES, HACHES, "hache")) return;
        if (!equiperMeilleur(t, HACHES::objet)) Cerveau.ecrire(t, "hache", 1800, "hache");

        // 1. Ramasser ce qui traîne (bois, pousses, pommes tombées des feuilles), pas plus d'une fois par 45 s
        //    (un objet coincé dans les feuilles ne doit pas l'occuper indéfiniment) ; tout de suite après un abattage.
        if (Cerveau.temps(t) >= m.getLong("ramassage") && !l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, t.getBoundingBox().inflate(10)).isEmpty()) {
            m.putLong("ramassage", Cerveau.temps(t) + 45);
            Cerveau.lancer(t, new Actions.Ramasser(10, Actions.Filtre.de("tout")));
            return;
        }

        // 2. Replanter les souches proches, avec la pousse de leur bois (une autre s'il n'en a pas) ; un géant 2 x 2 :
        //    4 pousses en carré s'il en a 4 de son bois (un chêne noir ne repousse pas autrement), sinon une seule.
        ListTag souches = m.getList("plants", Tag.TAG_COMPOUND);
        if (!souches.isEmpty() && t.compter(POUSSES) > 0) {
            CompoundTag s = souches.getCompound(0);
            souches.remove(0);
            BlockPos p = BlockPos.of(s.getLong("p"));
            Actions.Filtre f = Actions.Filtre.de(s.getString("f"));
            if (s.getBoolean("carre")) {
                // Moins de 4 pousses de son bois : un chêne noir ne pousserait pas d'une seule, et un autre bois n'est pas le
                // sien : il garde la souche pour plus tard (20 minutes au plus).
                if (t.compter(f) < 4 && (t.compter(f) == 0 || SEUL_IMPOSSIBLE.contains(f.toString()))) {
                    if (!s.contains("garde")) s.putLong("garde", Cerveau.temps(t) + 1200);
                    if (Cerveau.temps(t) < s.getLong("garde")) souches.add(s);
                    m.put("plants", souches);
                    s = null;
                } else {
                    List<BlockPos> carre = t.compter(f) >= 4 ? List.of(p, p.east(), p.south(), p.south().east()) : List.of(p);
                    for (int i = carre.size() - 1; i >= 0; i--) souches.add(0, souche(carre.get(i), f.toString(), false));
                    s = souches.getCompound(0);
                    souches.remove(0);
                }
            }
            m.put("plants", souches);
            if (s != null) p = BlockPos.of(s.getLong("p"));
            if (s != null && t.compter(f) == 0) f = POUSSES;
            if (s != null && p.distSqr(t.blockPosition()) < 24 * 24 && l.isLoaded(p) && Monde.libre(l, p) && l.getBlockState(p.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
                Cerveau.lancer(t, new Actions.Poser(p, f));
                return;
            }
        }

        // 3. L'arbre naturel du bon bois le plus proche de lui : hors de la colonie, ou dans la zone « Travaille ici », jamais
        //    près d'une construction (Lieux). Désigné « Travaille ici » mais loin de ce lieu : il y va d'abord.
        if (t.lieuImpose != null && !Lieux.ici(t, t.blockPosition())) {
            if (!aller(t, t.lieuImpose, c)) t.travailleIci(null);
            return;
        }
        BlockPos arbre = arbreEnVue(t, c);
        if (arbre != null) {
            Lieux.choisi(t, arbre);
            t.debloquer(Statut.SANS_BOIS);
            m.remove("sans_bois_" + c.id);
            if (souches.size() < 16) {
                BlockPos coin = carre(l, arbre);
                souches.add(souche(coin != null ? coin : arbre, pousse(l.getBlockState(arbre)), coin != null));
                m.put("plants", souches);
            }
            if (!t.chezLui(arbre)) m.putLong("coin_" + c.id, arbre.asLong()); // un bon coin loin de chez lui : il y reviendra
            m.putLong("ramassage", 0); // ce qui tombera des feuilles : ramassé juste après
            Cerveau.lancer(t, new Actions.Abattre(arbre));
            return;
        }

        // Là où son chef l'a mis, plus d'arbre : il le dit et attend (il ne part pas ailleurs de lui-même).
        if (t.lieuImpose != null) {
            Cerveau.dire(t, "plus_d_arbre_ici", 600, "Plus d'arbre à couper là où tu m'as dit.");
            Cerveau.attendre(t, 30);
            return;
        }

        // 4. Un coin où il a déjà trouvé ce bois.
        if (m.contains("coin_" + c.id)) {
            BlockPos k = BlockPos.of(m.getLong("coin_" + c.id));
            if (k.distSqr(t.blockPosition()) <= 20 * 20 || !aPortee(t, k, 0)) m.remove("coin_" + c.id); // il y est et il n'y a plus rien, ou trop loin du poste
            else if (aller(t, k, c)) return;
            else m.remove("coin_" + c.id);
        }

        // 5. Là où il pousse, à portée du poste (aPortee) : le biome le plus proche où pousse ce bois, qu'il explore en spirale.
        //    Un coin vidé est retenu, et la recherche repart d'un autre point autour du poste ; quand il ne reste rien à portée,
        //    il le dit, bloqué, et réessaie le lendemain (ses pousses auront grandi).
        if (Cerveau.temps(t) < m.getLong("sans_bois_" + c.id)) {
            t.bloquer(Statut.SANS_BOIS);
            if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else Cerveau.attendre(t, 30);
            return;
        }
        String cle = t.getUUID() + "/" + c.id;
        int essai = m.getInt("essai_" + c.id), rayon = (int) Math.min(Missions.LOIN, t.porteeMax());
        BlockPos foret = Biomes.chercher(l, cle, c.bois, depart(t, essai), rayon);
        if (foret == null) { Cerveau.attendre(t, 2); return; } // il réfléchit (la recherche prend quelques secondes)
        boolean fouille = foret.equals(BlockPos.ZERO) && essai == 0; // aucun biome connu à portée : il fouille autour du poste
        if (fouille) Cerveau.ecrire(t, "inconnu", 3600, "bois_inconnu", c.nom.toLowerCase(), Missions.LOIN);
        else if (foret.equals(BlockPos.ZERO) || !aPortee(t, foret, 0) || vide(m, c.id, foret)) { autreDepart(t, c, cle, essai); return; }
        BlockPos centre = fouille ? t.bureau : foret;
        int tour = fouille ? rayon : 160;
        BlockPos e = Cerveau.pointAExplorer(t, c.id, centre, tour);
        for (int i = 0; e != null && !aPortee(t, e, 0) && i < 64; i++) e = Cerveau.pointAExplorer(t, c.id, centre, tour); // hors de portée : sauté
        if (e != null && !aPortee(t, e, 0)) { Cerveau.attendre(t, 1); return; }
        if (e == null) {
            // Il a fouillé ce coin sans rien trouver (ou tout a été coupé) : il le retient, et cherche un autre coin à portée.
            if (fouille) { sansBois(t, c, cle); return; }
            long[] v = m.getLongArray("vides_" + c.id);
            long[] n = java.util.Arrays.copyOfRange(v, Math.max(0, v.length + 1 - DEPARTS), v.length + 1);
            n[n.length - 1] = foret.asLong();
            m.putLongArray("vides_" + c.id, n);
            Cerveau.ecrire(t, "plus_d_arbre", 1800, "plus_d_arbre", c.nom.toLowerCase(), foret.getX() + " " + foret.getZ());
            autreDepart(t, c, cle, essai);
            return;
        }
        if (!aller(t, e, c)) { Cerveau.oublierExploration(t, c.id); Cerveau.attendre(t, 5); }
    }

    /**
     * L'arbre du bon bois qu'il peut abattre, le plus proche de lui (24 blocs), où il a le temps d'aller ; null sinon. Sur
     * les lieux de sa mission, seulement dans leur zone : arrivé au bord, il ne rase pas d'abord les arbres d'à côté.
     */
    static BlockPos arbreEnVue(Travailleur t, Bois c) {
        BlockPos a = Actions.chercherArbre((ServerLevel) t.level(), t.blockPosition(), 24, c.bois, t, q -> Lieux.arbrePermis(t, q) && (t.lieuImpose != null ? Lieux.ici(t, q) : aPortee(t, q, 0)));
        return a != null && t.peutAller(a) ? a : null;
    }

    /**
     * En chemin vers un coin à explorer ou vers le lieu de sa mission ({@link #aller}) : toutes les 2 secondes, s'il voit un
     * arbre qu'il peut abattre (sur les lieux de sa mission : une fois entré dans leur zone), il s'arrête, et son travail
     * suivant le prend. Avant, il ne regardait qu'arrivé au point exact : il passait à côté des arbres, puis revenait ou
     * cherchait plus loin (F2 : Orianne, à quelques blocs des cerisiers, errait).
     */
    @Override
    void vigilance(Travailleur t) {
        if (!(t.action instanceof Actions.Aller a) || (t.tickCount + t.getId()) % 40 != 0 || !t.memoire.contains("cherche_vers")) return;
        BlockPos vers = BlockPos.of(t.memoire.getLong("cherche_vers"));
        if (Math.floor(a.cible.x) != vers.getX() || Math.floor(a.cible.z) != vers.getZ()) return; // une autre marche (rentrer, déposer…)
        if (t.lieuImpose != null && !Lieux.ici(t, t.blockPosition())) return; // pas encore sur les lieux
        Bois c = Metier.commande(t) instanceof Bois b ? b : (Bois) commandes.get(0);
        if (arbreEnVue(t, c) != null) t.finirAction("arbre_en_vue");
    }

    /** Les points d'où repart la recherche de forêt, après le poste : 8 directions, à 240 puis à 400 blocs du poste. */
    static final int DEPARTS = 16;

    /** Le point d'où part la recherche n° {@code essai} : le poste (0), puis autour de lui ({@link #DEPARTS}). */
    static BlockPos depart(Travailleur t, int essai) {
        if (essai <= 0) return t.bureau;
        double a = (essai - 1) % 8 * Math.PI / 4, r = essai <= 8 ? 240 : 400;
        return t.bureau.offset((int) (Math.cos(a) * r), 0, (int) (Math.sin(a) * r));
    }

    /** Ce coin est à moins de 160 blocs d'une forêt qu'il a déjà vidée (sa fouille finie sans rien). */
    static boolean vide(CompoundTag m, String id, BlockPos p) {
        for (long v : m.getLongArray("vides_" + id)) {
            BlockPos q = BlockPos.of(v);
            if (LieuxRegles.dist2(q.getX(), q.getZ(), p.getX(), p.getZ()) <= 160 * 160) return true;
        }
        return false;
    }

    /** La recherche repart du point suivant autour du poste ; après le dernier, il n'y a plus rien à portée. */
    static void autreDepart(Travailleur t, Bois c, String cle, int essai) {
        Biomes.oublier(cle);
        if (essai >= DEPARTS) { sansBois(t, c, cle); return; }
        t.memoire.putInt("essai_" + c.id, essai + 1);
        Cerveau.attendre(t, 1);
    }

    /** Plus rien de ce bois à portée du poste : il le dit, bloqué, jusqu'au lendemain ; il recommencera alors du poste. */
    static void sansBois(Travailleur t, Bois c, String cle) {
        Biomes.oublier(cle);
        Cerveau.oublierExploration(t, c.id);
        t.memoire.remove("essai_" + c.id);
        t.memoire.remove("vides_" + c.id);
        t.memoire.putLong("sans_bois_" + c.id, Cerveau.temps(t) + Cerveau.UN_JOUR);
        t.bloquer(Statut.SANS_BOIS);
        Cerveau.ecrire(t, "sans_bois", 1800, "sans_bois", c.nom.toLowerCase(), Missions.LOIN);
        Cerveau.attendre(t, 5);
    }

    static final Actions.Filtre POUSSES = Actions.Filtre.de("#minecraft:saplings");
    static final Actions.Filtre HACHES = Actions.Filtre.de("#minecraft:axes");
    /** Les pousses qui ne donnent un arbre qu'à 4 en carré. */
    static final java.util.Set<String> SEUL_IMPOSSIBLE = java.util.Set.of("minecraft:dark_oak_sapling");

    static CompoundTag souche(BlockPos p, String pousse, boolean carre) {
        CompoundTag s = new CompoundTag();
        s.putLong("p", p.asLong());
        s.putString("f", pousse);
        if (carre) s.putBoolean("carre", true);
        return s;
    }

    /** Le coin nord-ouest du tronc 2 x 2 (chêne noir, grand sapin…) dont fait partie ce pied, ou null pour un arbre ordinaire. */
    static BlockPos carre(ServerLevel l, BlockPos pied) {
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -1; dz <= 0; dz++) {
                BlockPos c = pied.offset(dx, 0, dz);
                boolean ok = true;
                for (BlockPos q : List.of(c, c.east(), c.south(), c.south().east()))
                    ok &= l.getBlockState(q).is(net.minecraft.tags.BlockTags.LOGS) && l.getBlockState(q.below()).is(net.minecraft.tags.BlockTags.DIRT);
                if (ok) return c;
            }
        return null;
    }

    /** La pousse de ce bois (« minecraft:birch_log » → « minecraft:birch_sapling », palétuvier : sa propagule), sinon n'importe laquelle. */
    static String pousse(String bois) {
        ResourceLocation id = ResourceLocation.tryParse(bois);
        if (id == null || !id.getPath().endsWith("_log")) return POUSSES.toString();
        String base = id.getPath().substring(0, id.getPath().length() - 4);
        for (String p : List.of(base + "_sapling", base + "_propagule")) {
            ResourceLocation r = ResourceLocation.fromNamespaceAndPath(id.getNamespace(), p);
            if (BuiltInRegistries.ITEM.containsKey(r)) return r.toString();
        }
        return POUSSES.toString();
    }

    static String pousse(BlockState bois) { return pousse(BuiltInRegistries.BLOCK.getKey(bois.getBlock()).toString()); }

    /** La pousse d'une essence du catalogue (« bouleau »), sinon n'importe laquelle. */
    static String pousseDe(String essence) {
        Metier.Commande c = Metier.catalogue("bucheron", essence);
        return c instanceof Bois b ? pousse(b.bois.toString()) : POUSSES.toString();
    }

    /**
     * De lui-même, il ne va jamais à plus de {@link Missions#LOIN} blocs de son poste (moins {@code marge}, à l'horizontale) :
     * son chef non plus ne peut pas lui imposer un lieu plus loin. Avant, chaque coin fouillé en vain repoussait la recherche
     * de 320 blocs, sans fin, et il abattait arbre après arbre en s'éloignant (Enguerrand, 18/10 : à 2 000 blocs du poste).
     */
    static boolean aPortee(Travailleur t, BlockPos p, int marge) {
        return LieuxRegles.dansColonie(t.bureau.getX(), t.bureau.getZ(), Missions.LOIN - marge, p.getX(), p.getZ());
    }

    /** Part vers ce point (en sortie ou en expédition). Faux si c'est trop loin : il le dit à son chef. */
    boolean aller(Travailleur t, BlockPos dest, Bois c) {
        switch (Cerveau.partir(t, dest, "chercher " + c.article)) {
            case JOURNEE, EXPEDITION -> { t.memoire.putLong("cherche_vers", dest.asLong()); return true; } // en chemin, il regarde les arbres (vigilance)
            case DEMAIN -> {
                Cerveau.ecrire(t, "demain", 1800, "bois_demain", c.article, Messages.direction(t.bureau, dest) + " (vers " + dest.getX() + " " + dest.getZ() + ")");
                if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else Cerveau.attendre(t, 60);
                return true;
            }
            default -> {
                if (!t.chezLui(t.blockPosition()) && aPortee(t, dest, 0)) { Cerveau.rentrer(t); return true; } // c'est lui qui est loin : il rentre d'abord
                Cerveau.ecrire(t, "trop_loin", 1800, "bois_trop_loin", c.nom.toLowerCase(), (int) Math.sqrt(t.bureau.distSqr(dest)), t.joursExpedition);
                return false;
            }
        }
    }
}
