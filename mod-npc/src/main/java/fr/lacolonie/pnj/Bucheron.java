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
 * a trouvé du bois et y retourne. Sans hache il travaille quand même, bien plus lentement.
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
        super("bucheron", "Bûcheron", "Trouve seul le bois demandé, même loin : il sait où pousse chaque bois, l'abat, ramasse le bois et les pousses, et replante.",
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

        if (!t.equiper(Actions.Filtre.de("#minecraft:axes")))
            Cerveau.ecrire(t, "hache", 1800, "hache");

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
                List<BlockPos> carre = t.compter(f) >= 4 ? List.of(p, p.east(), p.south(), p.south().east()) : List.of(p);
                for (int i = carre.size() - 1; i >= 0; i--) souches.add(0, souche(carre.get(i), f.toString(), false));
                s = souches.getCompound(0);
                souches.remove(0);
            }
            m.put("plants", souches);
            p = BlockPos.of(s.getLong("p"));
            if (t.compter(f) == 0) f = POUSSES;
            if (p.distSqr(t.blockPosition()) < 24 * 24 && l.isLoaded(p) && Monde.libre(l, p) && l.getBlockState(p.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
                Cerveau.lancer(t, new Actions.Poser(p, f));
                return;
            }
        }

        // 3. L'arbre du bon bois le plus proche de lui.
        BlockPos arbre = Actions.chercherArbre(l, t.blockPosition(), 24, c.bois, t);
        if (arbre != null && t.peutAller(arbre)) {
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

        // 4. Un coin où il a déjà trouvé ce bois.
        if (m.contains("coin_" + c.id)) {
            BlockPos k = BlockPos.of(m.getLong("coin_" + c.id));
            if (k.distSqr(t.blockPosition()) <= 20 * 20) m.remove("coin_" + c.id); // il y est, et il n'y a plus rien
            else if (aller(t, k, c)) return;
            else m.remove("coin_" + c.id);
        }

        // 5. Là où il pousse : le biome le plus proche où pousse ce bois, qu'il explore ensuite en spirale.
        String cle = t.getUUID() + "/" + c.id;
        BlockPos depuis = m.contains("depuis_" + c.id) ? BlockPos.of(m.getLong("depuis_" + c.id)) : t.bureau;
        BlockPos foret = Biomes.chercher(l, cle, c.bois, depuis, (int) Math.min(6400, t.porteeMax()));
        if (foret == null) { Cerveau.attendre(t, 2); return; } // il réfléchit (la recherche prend quelques secondes)
        if (foret.equals(BlockPos.ZERO)) {                         // aucun biome connu dans le rayon : il fouille autour de chez lui
            Cerveau.ecrire(t, "inconnu", 3600, "bois_inconnu", c.nom.toLowerCase());
            foret = t.bureau;
        }
        BlockPos e = Cerveau.pointAExplorer(t, c.id, foret, foret.equals(t.bureau) ? t.porteeMax() : 160);
        if (e == null) {
            // Il a fouillé ce coin sans rien trouver (ou tout a été coupé) : la prochaine recherche partira plus loin.
            Biomes.oublier(cle);
            double dx = foret.getX() - t.bureau.getX(), dz = foret.getZ() - t.bureau.getZ(), n = Math.max(1, Math.sqrt(dx * dx + dz * dz));
            if (foret.equals(t.bureau)) { dx = Math.cos(t.getRandom().nextDouble() * 6.28); dz = Math.sin(t.getRandom().nextDouble() * 6.28); n = 1; }
            m.putLong("depuis_" + c.id, foret.offset((int) (dx / n * 320), 0, (int) (dz / n * 320)).asLong());
            Cerveau.ecrire(t, "plus_d_arbre", 1800, "plus_d_arbre", c.nom.toLowerCase(), foret.getX() + " " + foret.getZ());
            Cerveau.attendre(t, 5);
            return;
        }
        if (!aller(t, e, c)) Cerveau.oublierExploration(t, c.id);
    }

    static final Actions.Filtre POUSSES = Actions.Filtre.de("#minecraft:saplings");

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

    /** Part vers ce point (en sortie ou en expédition). Faux si c'est trop loin : il le dit à son chef. */
    boolean aller(Travailleur t, BlockPos dest, Bois c) {
        switch (Cerveau.partir(t, dest, "chercher " + c.article)) {
            case JOURNEE, EXPEDITION -> { return true; }
            case DEMAIN -> {
                Cerveau.ecrire(t, "demain", 1800, "bois_demain", c.article, Messages.direction(t.bureau, dest) + " (vers " + dest.getX() + " " + dest.getZ() + ")");
                if (!t.chezLui(t.blockPosition())) Cerveau.rentrer(t); else Cerveau.attendre(t, 60);
                return true;
            }
            default -> {
                Cerveau.ecrire(t, "trop_loin", 1800, "bois_trop_loin", c.nom.toLowerCase(), (int) Math.sqrt(t.bureau.distSqr(dest)), t.joursExpedition);
                return false;
            }
        }
    }
}
