package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
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

        // 1. Replanter les souches vides proches s'il a des pousses.
        List<Long> souches = new ArrayList<>(Arrays.stream(m.getLongArray("souches")).boxed().toList());
        if (!souches.isEmpty() && t.compter(Actions.Filtre.de("#minecraft:saplings")) > 0) {
            BlockPos s = BlockPos.of(souches.remove(0));
            m.putLongArray("souches", souches);
            if (s.distSqr(t.blockPosition()) < 24 * 24 && l.isLoaded(s) && Monde.libre(l, s) && l.getBlockState(s.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
                Cerveau.lancer(t, new Actions.Poser(s, Actions.Filtre.de("#minecraft:saplings")));
                return;
            }
        }

        // 2. Ramasser ce qui traîne (bois, pousses, pommes), pas plus d'une fois par 45 s
        //    (un objet coincé dans les feuilles ne doit pas l'occuper indéfiniment).
        if (Cerveau.temps(t) >= m.getLong("ramassage") && !l.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, t.getBoundingBox().inflate(10)).isEmpty()) {
            m.putLong("ramassage", Cerveau.temps(t) + 45);
            Cerveau.lancer(t, new Actions.Ramasser(10, Actions.Filtre.de("tout")));
            return;
        }

        // 3. L'arbre du bon bois le plus proche de lui.
        BlockPos arbre = Actions.chercherArbre(l, t.blockPosition(), 24, c.bois, t);
        if (arbre != null && t.peutAller(arbre)) {
            if (souches.size() < 16) { souches.add(arbre.asLong()); m.putLongArray("souches", souches); }
            if (!t.chezLui(arbre)) m.putLong("coin_" + c.id, arbre.asLong()); // un bon coin loin de chez lui : il y reviendra
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
