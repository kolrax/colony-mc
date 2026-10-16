package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La guerre entre colonies, côté jeu (lot 9, cahier 6.5 et 3.10). La déclaration (lue par {@link Guerre}) est gardée dans le
 * {@link Courrier}, des deux côtés : c'est l'ordre du chef qui permet de frapper un homme. En guerre, les guerriers frappent
 * les ouvriers ennemis et le chef ennemi qui approchent du poste ({@link Guerrier#veille}), les autres ouvriers les signalent
 * et s'écartent ({@link #fuir}) ; « attaquez la colonie de X » envoie tous les guerriers du chef, à pied, au poste de X
 * ({@link #assaut}) : ils y frappent les ennemis rencontrés, et, si l'ordre le dit, pillent le dépôt (jamais les pièces)
 * et cassent les portes du poste (pas le bureau). Hors guerre, rien de tout cela ; jamais de téléportation.
 * Coût : en paix, une recherche dans une table par décision ; en guerre, une petite recherche d'entités de loin en loin.
 */
final class Armee {
    private Armee() {}

    // --- Qui est l'ennemi ---------------------------------------------------------------------------------

    /** Les ennemis du chef de cet ouvrier, et leur pseudo (vide en paix). */
    static Map<UUID, String> ennemis(Travailleur t) {
        return t.chef == null || !(t.level() instanceof ServerLevel sl) ? Map.of() : Courrier.de(sl.getServer()).ennemis(t.chef);
    }

    /** Le camp de cette créature : le chef d'un ouvrier, ou le joueur lui-même (pas une machine) ; null sinon. */
    static UUID camp(Entity e) {
        if (e instanceof Travailleur w) return w.chef;
        return e instanceof ServerPlayer p && !(p instanceof FakePlayer) ? p.getUUID() : null;
    }

    static String nom(Entity e) { return e instanceof Travailleur w ? w.prenom : e instanceof ServerPlayer p ? p.getGameProfile().getName() : "?"; }

    /** Un ennemi de son chef : un ouvrier ennemi debout, ou le chef ennemi en survie ; jamais un joueur qu'on lui a dit d'épargner. */
    static boolean ennemi(Travailleur t, Map<UUID, String> en, Entity e) {
        if (!(e instanceof LivingEntity l) || !l.isAlive() || !en.containsKey(camp(e))) return false;
        if (e instanceof Travailleur w && w.aTerre) return false; // on n'achève pas un homme à terre
        if (e instanceof ServerPlayer p && (p.isCreative() || p.isSpectator() || GardeChef.epargne(t.memoire.getString("epargnes"), p.getGameProfile().getName()))) return false;
        return true;
    }

    /** L'ennemi le plus proche qu'il voit dans {@code zone} ; null en paix (sans recherche). */
    static LivingEntity ennemi(Travailleur t, AABB zone) {
        Map<UUID, String> en = ennemis(t);
        if (en.isEmpty()) return null;
        return t.level().getEntitiesOfClass(LivingEntity.class, zone, e -> e != t && ennemi(t, en, e)).stream()
                .filter(e -> t.getSensing().hasLineOfSight(e)).min(Comparator.comparingDouble(t::distanceToSqr)).orElse(null);
    }

    /** Il le frappe : une attaque de guerre, qui cesse à la paix ({@link #paixFaite}). */
    static Actions.Attaquer attaque(Travailleur t, LivingEntity e, double portee) {
        Guerrier.armer(t);
        Actions.Attaquer a = new Actions.Attaquer(e).portee(portee);
        a.guerre = true;
        return a;
    }

    /** La paix est faite pendant qu'il frappe un ennemi : il baisse son arme. */
    static boolean paixFaite(Travailleur t, Actions.Attaquer a) { return a.guerre && !ennemis(t).containsKey(camp(a.cible)); }

    /** Il parle de la guerre contre {@code ennemi} ({@code nom} : un collègue ou un ennemi vu) ; vrai si la réplique existe. */
    static boolean dire(Travailleur t, String cle, String ennemi, String nom) {
        if (ennemi != null) t.memoire.putString("guerre_nom", ennemi);
        return Besoins.dire(t, cle, nom);
    }

    static String texte(Travailleur t, String cle, String ennemi, String nom, String sinon) {
        if (ennemi != null) t.memoire.putString("guerre_nom", ennemi);
        String s = Besoins.texte(t, cle, nom, null);
        return s != null ? s : sinon;
    }

    // --- Les ouvriers qui ne se battent pas -------------------------------------------------------------

    /**
     * Un ouvrier qui ne se bat pas, en guerre, voit un ennemi chez lui (une fois par seconde au plus) : il le signale (la
     * garde l'apprend, {@link Guerrier#veille}) et s'en écarte. Vrai s'il fuit.
     */
    static boolean fuir(Travailleur t) {
        if (t.bureau == null || (t.tickCount + t.getId()) % 20 >= 10) return false;
        LivingEntity e = ennemi(t, t.getBoundingBox().inflate(Defense.VUE, 3, Defense.VUE));
        if (e == null || !t.chezLui(e.blockPosition())) return false;
        Defense.signaler(t, e);
        if (Cerveau.parfois(t, "guerre_ennemi_vu", 30) && !dire(t, "guerre_ennemi_vu", ennemis(t).get(camp(e)), nom(e))) t.dire("Un ennemi ! Prévenez la garde !");
        Travailleur g = Defense.garde(t, Defense.VOIX);
        if (g != null && g.mode == Travailleur.Mode.TRAVAIL && !g.aTerre && !Blessures.horsDeCombat(g) && !(g.action instanceof Actions.Attaquer a && a.cible.isAlive()))
            Cerveau.lancer(g, attaque(g, e, 2.5 * Travailleur.ESPACE_DE_VIE));
        Vec3 loin = Defense.refuge(t, e);
        if (loin == null) return false;
        Actions.Fuir f = new Actions.Fuir(e);
        f.refuge = loin;
        Defense.retenirPlace(t);
        Cerveau.lancer(t, f);
        return true;
    }

    // --- Les ordres du chef -----------------------------------------------------------------------------

    /** Un chef qui a un poste (ou des ouvriers), d'après son pseudo : son identifiant et son pseudo exact ; null sinon. */
    static Map.Entry<UUID, String> chefNomme(MinecraftServer s, String nom) {
        Courrier c = Courrier.de(s);
        ServerPlayer p = s.getPlayerList().getPlayerByName(nom);
        if (p != null && c.postes.containsKey(p.getUUID())) return Map.entry(p.getUUID(), p.getGameProfile().getName());
        for (Travailleur w : ColoniePnj.tous(s)) if (w.chef != null && w.chefNom.equalsIgnoreCase(nom)) return Map.entry(w.chef, w.chefNom);
        var profil = s.getProfileCache() == null ? java.util.Optional.<com.mojang.authlib.GameProfile>empty() : s.getProfileCache().get(nom);
        if (profil.isPresent() && c.postes.containsKey(profil.get().getId())) return Map.entry(profil.get().getId(), profil.get().getName());
        return null;
    }

    /** L'ennemi nommé (parmi ceux en guerre avec ce chef), ou le plus récent si personne n'est nommé ; null sinon. */
    static UUID ennemiNomme(Map<UUID, String> en, String nom) {
        UUID dernier = null;
        for (var e : en.entrySet()) {
            if (nom != null && e.getValue().equalsIgnoreCase(nom)) return e.getKey();
            dernier = e.getKey();
        }
        return nom == null ? dernier : null;
    }

    /** Ce que le chef dit de la guerre à cet ouvrier : il agit, et ajoute à {@code r} ce qu'il répond (c'est le mod qui répond). */
    static void ordre(Travailleur t, Guerre.Lu g, List<String> r) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return;
        MinecraftServer s = sl.getServer();
        Courrier c = Courrier.de(s);
        String moi = t.chefNom.isEmpty() ? "ton voisin" : t.chefNom;
        if (g.declare != null) {
            Map.Entry<UUID, String> x = chefNomme(s, g.declare);
            if (x == null || x.getKey().equals(t.chef)) r.add("Je ne connais pas de colonie à ce nom, chef. Contre qui donc ?");
            else {
                c.declarer(t.chef, moi, x.getKey(), x.getValue());
                r.add(texte(t, "guerre_declaree", x.getValue(), null, "C'est donc la guerre contre " + x.getValue() + "."));
                // Le chef ennemi l'apprend par une lettre à son poste.
                Map<String, String> v = new HashMap<>(Map.of("ennemi", moi, "chef", x.getValue()));
                String lettre = Vie.tirer("guerre_recue", "", v, t.getRandom()::nextDouble);
                Courrier.avis(s, x.getKey(), "guerre", true, "La guerre", moi + " nous déclare la guerre. " + (lettre != null ? lettre : "Restons sur nos gardes."));
            }
        }
        if (g.paix != null || g.paixToutes) {
            Map<UUID, String> en = new HashMap<>(c.ennemis(t.chef));
            List<UUID> qui = new ArrayList<>();
            if (g.paix != null) { UUID u = ennemiNomme(en, g.paix); if (u != null) qui.add(u); }
            else qui.addAll(en.keySet());
            if (qui.isEmpty()) r.add("Nous ne sommes pas en guerre" + (g.paix != null ? " avec " + g.paix : "") + ", chef.");
            for (UUID u : qui) {
                c.paix(t.chef, u);
                r.add(texte(t, "guerre_paix", en.get(u), null, "La paix avec " + en.get(u) + ". Enfin."));
                Courrier.avis(s, u, "guerre", true, "La paix", moi + " fait la paix avec toi. La guerre est finie.");
            }
        }
        if (g.retraite) {
            String ennemi = null;
            for (Travailleur w : armee(s, t.chef)) {
                if (w.memoire.contains("guerre_assaut")) { ennemi = c.ennemis(t.chef).get(uuid(w.memoire.getString("guerre_assaut"))); finir(w); }
                if (w.action instanceof Actions.Attaquer a && a.guerre) w.finirAction("retraite"); // il rompt le combat
            }
            r.add(texte(t, "guerre_retraite", ennemi, null, "Retraite ! On rentre !"));
        }
        if (g.lever) {
            UUID u = ennemiNomme(c.ennemis(t.chef), g.cible);
            if (u == null) { r.add(texte(t, "guerre_refus_hors_guerre", null, null, "Nous ne sommes pas en guerre, chef. Je ne frapperai personne.")); return; }
            String ennemi = c.ennemis(t.chef).get(u);
            List<Travailleur> armee = armee(s, t.chef);
            if ("guerrier".equals(t.metier) && !armee.contains(t)) armee.add(t); // tout juste arrivé, il n'est pas encore dans la liste du monde
            for (Travailleur w : armee) {
                w.memoire.putString("guerre_assaut", u.toString());
                w.memoire.putString("guerre_nom", ennemi);
                w.memoire.putBoolean("guerre_casse", g.casser);
                w.memoire.putBoolean("guerre_pille", g.piller);
                w.memoire.remove("guerre_porte");
                if (w.action instanceof Actions.Reposer || w.action instanceof Actions.Aller) w.finirAction("guerre"); // il part tout de suite
            }
            Metier m = Metier.de(t.metier);
            String collegue = armee.stream().filter(w -> w != t).map(w -> w.prenom).findFirst().orElse(null);
            if (armee.isEmpty()) r.add("Nous n'avons pas un seul guerrier, chef. Qui irait se battre ?");
            else if (m != null && m.combattant) r.add(texte(t, "guerre_levee", ennemi, collegue, "Présent ! Je rejoins les rangs."));
            // Première passe : les autres ne partent pas en renfort, ils gardent la colonie.
            else r.add(texte(t, "guerre_renfort_refuse", ennemi, null, "Je préviens les gardes, chef. Moi, je garde la colonie."));
        }
    }

    /** Les guerriers de ce chef (dans les mondes chargés). */
    static List<Travailleur> armee(MinecraftServer s, UUID chef) {
        List<Travailleur> l = new ArrayList<>();
        for (Travailleur w : ColoniePnj.tous(s)) if (chef.equals(w.chef) && "guerrier".equals(w.metier) && w.mode != Travailleur.Mode.CHOMEUR) l.add(w);
        return l;
    }

    static UUID uuid(String s) { try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; } }

    static void finir(Travailleur t) {
        for (String k : List.of("guerre_assaut", "guerre_casse", "guerre_pille", "guerre_porte")) t.memoire.remove(k);
    }

    // --- L'assaut ---------------------------------------------------------------------------------------

    /** Jusque-là du bureau ennemi, il y est ; jusque-là, il voit et charge un ennemi pendant l'assaut ; on cherche les portes jusque-là du bureau. */
    static final double ARRIVE = 5, CHARGE = 16;
    static final int PORTES = 6;
    /** Ce qu'il emporte du dépôt ennemi : quelques piles, jamais les pièces. */
    static final int BUTIN = 3;

    /**
     * Le guerrier, depuis son travail : en assaut, il marche vers le poste ennemi, frappe les ennemis rencontrés, pille,
     * casse les portes, puis rentre ; revenu avec un butin, il le dépose. Vrai s'il a de quoi faire ici.
     */
    static boolean assaut(Travailleur t) {
        CompoundTag mem = t.memoire;
        if (!mem.contains("guerre_assaut")) {
            if (mem.getBoolean("guerre_butin") && t.bureau != null && t.chezLui(t.blockPosition())) {
                mem.remove("guerre_butin");
                Cerveau.lancer(t, new Actions.Deposer(Metier.de(t.metier).garder));
                return true;
            }
            return false;
        }
        if (!(t.level() instanceof ServerLevel sl)) return false;
        Courrier c = Courrier.de(sl.getServer());
        UUID u = uuid(mem.getString("guerre_assaut"));
        String ennemi = c.ennemis(t.chef).get(u);
        Courrier.Poste po = c.postes.get(u);
        if (ennemi == null || po == null || po.monde() != t.level().dimension()) { finir(t); return false; } // la paix, ou plus de poste
        BlockPos cible = po.pos();
        LivingEntity x = ennemi(t, t.getBoundingBox().inflate(CHARGE, 6, CHARGE));
        if (x != null) {
            if (Cerveau.parfois(t, "guerre_assaut", 30)) dire(t, "guerre_assaut", ennemi, null);
            Cerveau.lancer(t, attaque(t, x, 2 * CHARGE));
            return true;
        }
        if (t.distanceToSqr(Vec3.atBottomCenterOf(cible)) > ARRIVE * ARRIVE) {
            if (Cerveau.parfois(t, "guerre_en_marche", 90)) dire(t, "guerre_en_marche", ennemi, null);
            Cerveau.Portee p = Cerveau.partir(t, cible, "la guerre contre " + ennemi);
            if (p == Cerveau.Portee.DEMAIN || p == Cerveau.Portee.TROP_LOIN) {
                t.dire("Leur colonie est trop loin pour nos jambes, chef. Nous restons ici.");
                finir(t);
                return false;
            }
            return true;
        }
        if (mem.getBoolean("guerre_pille")) {
            mem.remove("guerre_pille");
            if (piller(t, sl, cible)) dire(t, "guerre_vol", ennemi, null);
            Cerveau.attendre(t, 1);
            return true;
        }
        if (mem.getBoolean("guerre_casse")) {
            BlockPos porte = mem.contains("guerre_porte") ? BlockPos.of(mem.getLong("guerre_porte")) : null;
            if (porte == null || !estPorte(sl.getBlockState(porte))) porte = porte(sl, cible);
            if (porte == null) { mem.remove("guerre_casse"); mem.remove("guerre_porte"); Cerveau.attendre(t, 1); return true; }
            mem.putLong("guerre_porte", porte.asLong());
            if (t.distanceToSqr(Vec3.atCenterOf(porte)) > 3 * 3) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(porte), 2)); return true; }
            t.getLookControl().setLookAt(Vec3.atCenterOf(porte));
            t.swing(InteractionHand.MAIN_HAND);
            Sons.coup(t, porte, sl.getBlockState(porte));
            sl.destroyBlock(porte, true, t); // en guerre, sur ordre du chef : la casse est permise (CLAUDE.md)
            if (Cerveau.parfois(t, "guerre_casse", 10)) dire(t, "guerre_casse", ennemi, null);
            Cerveau.attendre(t, 1);
            return true;
        }
        // La besogne est faite, personne en vue : il rentre (le travail ordinaire le ramène au poste).
        Cerveau.dire(t, "guerre_fait", 60, "Notre besogne est faite ici. On rentre.");
        finir(t);
        return false;
    }

    /** Il prend quelques piles au dépôt ennemi (jamais les pièces) et les garde pour son dépôt à lui ; vrai s'il a pris quelque chose. */
    static boolean piller(Travailleur t, ServerLevel l, BlockPos poste) {
        if (!(l.getBlockEntity(poste) instanceof Bureau.Entite b)) return false;
        int pris = 0;
        for (int i = 0; i < b.depot.getContainerSize() && pris < BUTIN; i++) {
            ItemStack s = b.depot.getItem(i);
            if (s.isEmpty() || Reglages.piece(s) >= 0) continue;
            t.ranger(b.depot.removeItemNoUpdate(i));
            pris++;
        }
        if (pris == 0) return false;
        b.depot.setChanged();
        t.memoire.putBoolean("guerre_butin", true);
        return true;
    }

    static boolean estPorte(BlockState s) { return s.getBlock() instanceof DoorBlock || s.getBlock() instanceof FenceGateBlock || s.getBlock() instanceof TrapDoorBlock; }

    /** La porte la plus proche du bureau ennemi, à {@link #PORTES} blocs au plus (une seule recherche, gardée ensuite) ; null s'il n'y en a pas. */
    static BlockPos porte(Level l, BlockPos poste) {
        BlockPos mieux = null;
        for (BlockPos p : BlockPos.betweenClosed(poste.offset(-PORTES, -2, -PORTES), poste.offset(PORTES, 3, PORTES)))
            if (estPorte(l.getBlockState(p)) && (mieux == null || p.distSqr(poste) < mieux.distSqr(poste))) mieux = p.immutable();
        return mieux;
    }
}
