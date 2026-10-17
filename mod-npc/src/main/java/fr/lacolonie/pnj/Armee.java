package fr.lacolonie.pnj;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
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

    /**
     * Un ennemi de son chef : un ouvrier ennemi debout, ou le chef ennemi en survie ; jamais un joueur qu'on lui a dit d'épargner.
     * Un garde ne s'en prend à un homme que sur mission « défendre » ou « attaquer » (refonte, partie 6) ; les autres ouvriers
     * ne font que le voir (pour fuir et prévenir la garde).
     */
    static boolean ennemi(Travailleur t, Map<UUID, String> en, Entity e) {
        if (!(e instanceof LivingEntity l) || !l.isAlive() || !en.containsKey(camp(e))) return false;
        if ("guerrier".equals(t.metier) && !aOrdre(t, true)) return false;
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
        return null; // jamais le cache des profils : il peut interroger Mojang sur le fil du serveur

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
    static void ordre(Travailleur t, Guerre.Lu g, List<String> r, boolean agir) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return;
        // Dite à plusieurs (une lettre lue devant tous, une phrase entendue de tous) : le premier agit, les autres répondent seulement.
        if (!agir) {
            if (g.declare != null) r.add(texte(t, "guerre_declaree", g.declare, null, "C'est donc la guerre contre " + g.declare + "."));
            if (g.paix != null || g.paixToutes) r.add(texte(t, "guerre_paix", g.paix, null, "La paix. Enfin."));
            if (g.retraite) r.add(texte(t, "guerre_retraite", null, null, "Retraite ! On rentre !"));
            Metier mt = Metier.de(t.metier);
            if (g.lever && mt != null && mt.combattant && Famille.mineur(t)) r.add(texte(t, "guerre_trop_jeune", null, null, "Je suis trop jeune pour la guerre, chef."));
            else if (g.lever && mt != null && mt.combattant && t.memoire.contains("guerre_assaut")) r.add(texte(t, "guerre_levee", null, null, "Présent ! Je rejoins les rangs."));
            return;
        }
        MinecraftServer s = sl.getServer();
        Courrier c = Courrier.de(s);
        String moi = t.chefNom.isEmpty() ? "ton voisin" : t.chefNom;
        if (g.declare != null) {
            Map.Entry<UUID, String> x = chefNomme(s, g.declare);
            if (x == null || x.getKey().equals(t.chef)) r.add("Je ne connais pas de colonie à ce nom, chef. Contre qui donc ?");
            else {
                c.declarer(t.chef, moi, x.getKey(), x.getValue());
                Espions.evenement(s, t.chef, moi, Rumeurs.Genre.GUERRE, x.getValue()); // les ouvriers présents des deux côtés le savent (Rumeurs)
                Espions.evenement(s, x.getKey(), x.getValue(), Rumeurs.Genre.GUERRE, moi);
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
                Espions.paix(s, t.chef, moi, u, en.get(u)); // plus de guerre entre eux : le fait est oublié des deux côtés (Rumeurs)
                for (Travailleur w : armee(s, t.chef)) if (u.toString().equals(w.memoire.getString("guerre_assaut"))) finir(w);
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
            if ("guerrier".equals(t.metier) && !armee.contains(t) && !Famille.mineur(t)) armee.add(t); // tout juste arrivé, il n'est pas encore dans la liste du monde
            for (Travailleur w : armee) {
                w.memoire.putString("guerre_assaut", u.toString());
                w.memoire.putString("guerre_nom", ennemi);
                w.memoire.putBoolean("guerre_casse", g.casser);
                w.memoire.putBoolean("guerre_pille", g.piller);
                w.memoire.remove("guerre_porte");
                w.memoire.putLong("guerre_depuis", w.level().getGameTime());
                if (w.action instanceof Actions.Reposer || w.action instanceof Actions.Aller) w.finirAction("guerre"); // il part tout de suite
            }
            Metier m = Metier.de(t.metier);
            String collegue = armee.stream().filter(w -> w != t).map(w -> w.prenom).findFirst().orElse(null);
            if (armee.isEmpty()) r.add("Nous n'avons pas un seul guerrier, chef. Qui irait se battre ?");
            else if (m != null && m.combattant && Famille.mineur(t)) r.add(texte(t, "guerre_trop_jeune", ennemi, null, "Je suis trop jeune pour la guerre, chef.")); // lot 13
            else if (m != null && m.combattant) r.add(texte(t, "guerre_levee", ennemi, collegue, "Présent ! Je rejoins les rangs."));
            // Première passe : les autres ne partent pas en renfort, ils gardent la colonie.
            else r.add(texte(t, "guerre_renfort_refuse", ennemi, null, "Je préviens les gardes, chef. Moi, je garde la colonie."));
        }
    }

    /** Les guerriers de ce chef (dans les mondes chargés). */
    static List<Travailleur> armee(MinecraftServer s, UUID chef) {
        List<Travailleur> l = new ArrayList<>();
        for (Travailleur w : ColoniePnj.tous(s)) if (chef.equals(w.chef) && "guerrier".equals(w.metier) && w.mode != Travailleur.Mode.CHOMEUR && !Famille.mineur(w)) l.add(w); // jamais un enfant ni un apprenti (lot 13)
        return l;
    }

    static UUID uuid(String s) { try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; } }

    static void finir(Travailleur t) {
        // En marche vers l'ennemi : il s'arrête (la marche de guerre, pas un autre trajet).
        if (t.memoire.getBoolean("guerre_marche") && t.action instanceof Actions.Aller) t.finirAction("guerre");
        for (String k : List.of("guerre_assaut", "guerre_casse", "guerre_pille", "guerre_porte", "guerre_marche", "guerre_depuis")) t.memoire.remove(k);
    }

    /** Pendant une action (depuis {@link Guerrier#vigilance}) : la paix faite, il arrête sa marche de guerre. Une lecture de table. */
    static void veiller(Travailleur t) {
        if (t.memoire.contains("guerre_assaut") && !ennemis(t).containsKey(uuid(t.memoire.getString("guerre_assaut")))) finir(t);
    }

    /** Un assaut qui n'aboutit pas (chemin introuvable, poste muré) : il y renonce au bout de 8 minutes. */
    static final long LIMITE = 20 * 60 * 8;

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
        long depuis = mem.getLong("guerre_depuis");
        if (depuis > 0 && t.level().getGameTime() - depuis > LIMITE) {
            Cerveau.dire(t, "guerre_renonce", 60, "Impossible d'atteindre leur colonie, chef. Nous rentrons.");
            finir(t);
            return false;
        }
        LivingEntity x = ennemi(t, t.getBoundingBox().inflate(CHARGE, 6, CHARGE));
        if (x != null) {
            if (Cerveau.parfois(t, "guerre_assaut", 30)) dire(t, "guerre_assaut", ennemi, null);
            Cerveau.lancer(t, attaque(t, x, 2 * CHARGE));
            return true;
        }
        mem.remove("guerre_marche");
        if (t.distanceToSqr(Vec3.atBottomCenterOf(cible)) > ARRIVE * ARRIVE) {
            if (Cerveau.parfois(t, "guerre_en_marche", 90)) dire(t, "guerre_en_marche", ennemi, null);
            Cerveau.Portee p = Cerveau.partir(t, cible, "la guerre contre " + ennemi);
            mem.putBoolean("guerre_marche", true);
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

    // --- Refonte 1.0.0, partie 6 : la guerre se déclare au poste ; les missions des gardes ------------------

    /** Sa mission en cours (son identifiant), ou null. */
    static String mission(Travailleur t) {
        Travailleur.Mission m = t.missionActive();
        return m == null ? null : m.id;
    }

    /** L'ordre de frapper un homme : la guerre, et la mission « défendre » ou « attaquer » (ou l'assaut levé de l'ancienne façon). */
    static boolean aOrdre(Travailleur t, boolean enGuerre) {
        return Guerre.frappeHomme(enGuerre, mission(t)) || enGuerre && t.memoire.contains("guerre_assaut");
    }

    /** Le pseudo d'un chef : en jeu, dans une guerre, ou porté par ses ouvriers ; null si on ne le connaît pas. */
    static String nomDe(MinecraftServer s, UUID u) {
        ServerPlayer p = s.getPlayerList().getPlayer(u);
        if (p != null) return p.getGameProfile().getName();
        for (Map<UUID, String> m : Courrier.de(s).guerres.values()) if (m.containsKey(u)) return m.get(u);
        for (Travailleur w : ColoniePnj.tous(s)) if (u.equals(w.chef) && !w.chefNom.isEmpty()) return w.chefNom;
        return null;
    }

    /** Le chef désigné à l'écran du poste : son identifiant (la liste des postes) ou son pseudo ; null s'il n'a ni poste ni ouvriers. */
    static Map.Entry<UUID, String> chefDe(MinecraftServer s, String qui) {
        if (qui == null || qui.isBlank()) return null;
        UUID u = uuid(qui.strip());
        if (u == null) return chefNomme(s, qui.strip());
        String nom = nomDe(s, u);
        if (!Courrier.de(s).postes.containsKey(u) && nom == null) return null;
        return Map.entry(u, nom == null ? "un inconnu" : nom);
    }

    /** Les deux chefs le lisent : tout de suite s'ils sont là, et au journal de leur poste. */
    static void prevenir(MinecraftServer s, UUID a, UUID b, String objet, String texte) {
        for (UUID u : List.of(a, b)) {
            ServerPlayer j = s.getPlayerList().getPlayer(u);
            if (j != null) j.sendSystemMessage(Component.literal(texte).withStyle(ChatFormatting.RED));
            Courrier.avis(s, u, "guerre", true, objet, texte);
        }
    }

    /** Au poste : le chef déclare la guerre à un autre ({@code qui} : identifiant ou pseudo). Renvoie ce qu'il lit. */
    public static String declarer(ServerPlayer p, String qui) {
        MinecraftServer s = p.server;
        Courrier c = Courrier.de(s);
        Map.Entry<UUID, String> x = chefDe(s, qui);
        if (x == null) return "Aucune colonie de ce nom.";
        if (x.getKey().equals(p.getUUID())) return "On ne se fait pas la guerre à soi-même.";
        if (c.enGuerre(p.getUUID(), x.getKey())) return "Tu es déjà en guerre contre " + x.getValue() + ".";
        String moi = p.getGameProfile().getName(), texte = Guerre.declaration(moi, x.getValue());
        c.declarer(p.getUUID(), moi, x.getKey(), x.getValue());
        prevenir(s, p.getUUID(), x.getKey(), "La guerre", texte);
        return texte;
    }

    /** Au poste : la paix avec {@code qui} (identifiant ou pseudo ; vide : le seul ennemi). Les gardes des deux camps baissent les armes. */
    public static String paix(ServerPlayer p, String qui) {
        MinecraftServer s = p.server;
        Courrier c = Courrier.de(s);
        Map<UUID, String> en = c.ennemis(p.getUUID());
        if (en.isEmpty()) return "Tu n'es en guerre contre personne.";
        UUID u = qui == null || qui.isBlank() ? (en.size() == 1 ? en.keySet().iterator().next() : null)
                : uuid(qui.strip()) != null ? uuid(qui.strip()) : ennemiNomme(en, qui.strip());
        if (u == null || !en.containsKey(u)) return "Tu n'es pas en guerre contre " + (qui == null || qui.isBlank() ? "lui" : qui.strip()) + ".";
        String moi = p.getGameProfile().getName(), nom = en.get(u), texte = Guerre.paixFaite(moi, nom);
        c.paix(p.getUUID(), u);
        for (UUID camp : List.of(p.getUUID(), u))
            for (Travailleur w : armee(s, camp)) {
                finir(w);
                if (w.action instanceof Actions.Attaquer a && a.guerre) w.finirAction("paix");
            }
        prevenir(s, p.getUUID(), u, "La paix", texte);
        return texte;
    }

    /** Pour l'écran du poste : les autres chefs qui ont un poste, avec « uuid », « nom » et « guerre » (en guerre avec lui). */
    public static ListTag adversaires(ServerPlayer p) {
        Courrier c = Courrier.de(p.server);
        ListTag l = new ListTag();
        for (UUID u : c.postes.keySet()) {
            if (u.equals(p.getUUID())) continue;
            String nom = nomDe(p.server, u);
            if (nom == null) continue;
            CompoundTag x = new CompoundTag();
            x.putString("uuid", u.toString());
            x.putString("nom", nom);
            x.putBoolean("guerre", c.enGuerre(p.getUUID(), u));
            l.add(x);
        }
        return l;
    }

    // --- La mission « attaquer » : la troupe marche en rangs derrière son chef de file -----------------------

    /** Où il attaque : le lieu de sa mission (« Travaille ici », ou le poste choisi dans la liste), sinon le poste ennemi le plus proche du sien. */
    static BlockPos but(Travailleur t, Travailleur.Mission m) {
        if (m == null) return null;
        if (m.lieu != null) return m.lieu;
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return null;
        Courrier c = Courrier.de(sl.getServer());
        BlockPos chez = t.bureau != null ? t.bureau : t.blockPosition(), mieux = null;
        for (UUID u : c.ennemis(t.chef).keySet()) {
            Courrier.Poste po = c.postes.get(u);
            if (po != null && po.monde() == t.level().dimension() && (mieux == null || po.pos().distSqr(chez) < mieux.distSqr(chez))) mieux = po.pos();
        }
        return mieux;
    }

    /** Une troupe : les gardes d'un chef qui attaquent le même but, triés (le premier mène). Revue au plus toutes les 20 ticks. */
    record Troupe(long quand, List<Travailleur> gardes) {}

    static final Map<String, Troupe> TROUPES = new HashMap<>();

    static List<Travailleur> troupe(Travailleur t, BlockPos but) {
        String cle = t.chef + "@" + but.asLong();
        long maintenant = t.level().getGameTime();
        Troupe x = TROUPES.get(cle);
        if (x == null || maintenant - x.quand() >= 20 || maintenant < x.quand()) {
            List<Travailleur> l = new ArrayList<>();
            if (t.level() instanceof ServerLevel sl)
                for (Travailleur w : sl.getEntities(ColoniePnj.TRAVAILLEUR.get(), w -> w.isAlive() && t.chef.equals(w.chef) && "guerrier".equals(w.metier)))
                    if (Guerre.ATTAQUER.equals(mission(w)) && but.equals(but(w, w.missionActive()))) l.add(w);
            l.sort(Comparator.comparingInt(Entity::getId));
            TROUPES.values().removeIf(v -> maintenant - v.quand() > 200 || maintenant < v.quand());
            TROUPES.put(cle, x = new Troupe(maintenant, l));
        }
        return x.gardes();
    }

    /** Le chef ennemi dont le poste est à ce but (à 8 blocs), ou null. */
    static UUID posteEnnemi(Travailleur t, Courrier c, BlockPos but) {
        for (UUID u : c.ennemis(t.chef).keySet()) {
            Courrier.Poste po = c.postes.get(u);
            if (po != null && po.monde() == t.level().dimension() && po.pos().distSqr(but) <= 64) return u;
        }
        return null;
    }

    /**
     * La mission « attaquer » (depuis {@link Guerrier#travailler}) : sans but (ni lieu, ni guerre déclarée), elle est bloquée et
     * il garde la colonie (faux). Sinon la troupe marche en rangs vers le but ; arrivée au poste ennemi, en guerre, elle en
     * enfonce les portes (jamais le bureau), puis tient la place. Les ennemis rencontrés, la vigilance les charge.
     */
    static boolean campagne(Travailleur t) {
        if (!(t.level() instanceof ServerLevel sl)) return false;
        BlockPos but = but(t, t.missionActive());
        if (but == null) { t.bloquer(Statut.SANS_GUERRE); return false; }
        List<Travailleur> troupe = troupe(t, but);
        int rang = troupe.indexOf(t);
        Travailleur tete = rang <= 0 ? t : troupe.get(0);
        Vec3 cible = Vec3.atBottomCenterOf(but);
        if (t.distanceToSqr(cible) <= 8 * 8) {
            if (enfoncer(t, sl, but)) return true;
            if (t.distanceToSqr(cible) > ARRIVE * ARRIVE) Cerveau.lancer(t, new Actions.Aller(cible, ARRIVE - 1));
            else Cerveau.attendre(t, 2); // il tient la place
            return true;
        }
        if (Cerveau.parfois(t, "guerre_en_marche", 90)) t.dire(rang <= 0 ? "En avant ! Suivez-moi !" : "En rang, et du pas !");
        Cerveau.lancer(t, new Rang(tete == t ? null : tete, rang < 0 ? troupe.size() : rang, cible));
        return true;
    }

    /** Au poste ennemi, en guerre, sur mission « attaquer » : il enfonce une porte (jamais le bureau). Vrai s'il s'en occupe. */
    static boolean enfoncer(Travailleur t, ServerLevel sl, BlockPos but) {
        Courrier c = Courrier.de(sl.getServer());
        UUID camp = posteEnnemi(t, c, but);
        // Pas de Lieux.peutCasser dans le code : la seule casse permise ici est celle des portes d'un poste ennemi, en guerre.
        if (camp == null || !Guerre.casse(c.enGuerre(t.chef, camp), mission(t))) return false;
        BlockPos poste = c.postes.get(camp).pos();
        CompoundTag mem = t.memoire;
        BlockPos porte = mem.contains("guerre_porte") ? BlockPos.of(mem.getLong("guerre_porte")) : null;
        if (porte == null || !estPorte(sl.getBlockState(porte))) {
            if (mem.getLong("guerre_portes_vues") > sl.getGameTime()) return false; // aucune porte tout à l'heure : pas d'autre recherche avant 30 s
            porte = porte(sl, poste);
        }
        if (porte == null) { mem.remove("guerre_porte"); mem.putLong("guerre_portes_vues", sl.getGameTime() + 600); return false; }
        mem.putLong("guerre_porte", porte.asLong());
        if (t.distanceToSqr(Vec3.atCenterOf(porte)) > 3 * 3) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(porte), 2)); return true; }
        t.getLookControl().setLookAt(Vec3.atCenterOf(porte));
        t.swing(InteractionHand.MAIN_HAND);
        Sons.coup(t, porte, sl.getBlockState(porte));
        sl.destroyBlock(porte, true, t); // en guerre déclarée, sur mission « attaquer » : la casse est permise (CLAUDE.md, refonte partie 6)
        Cerveau.dire(t, "guerre_casse", 10, "Enfoncez-moi cette porte !");
        Cerveau.attendre(t, 1);
        return true;
    }

    /**
     * La marche en rangs. Le chef de file ({@code tete} null) marche droit au but ; les autres prennent leur place derrière lui
     * ({@link Guerre#place}), revue toutes les 20 ticks seulement. Chacun suit son {@link Itineraire} (refait seulement quand sa
     * place a bougé de plus de 4 blocs). Au bout de 5 s, l'action finit : sa place dans la troupe est revue.
     */
    static final class Rang extends Actions.Action {
        final Travailleur tete;
        final int rang;
        final Vec3 but;
        Vec3 place;

        Rang(Travailleur tete, int rang, Vec3 but) {
            super("rang");
            this.tete = tete;
            this.rang = rang;
            this.but = but;
            mobile = tete != null;
        }

        Actions.Etat tick(Travailleur t) {
            if (age >= 100) return Actions.Etat.OK;
            if (tete == null) {
                if (t.position().distanceToSqr(but) <= ARRIVE * ARRIVE) return Actions.Etat.OK;
                if (coince()) return echec("bloque");
                marcher(t, but, 0.9); // un peu moins vite que les autres : la troupe le suit
                return Actions.Etat.EN_COURS;
            }
            if (!tete.isAlive() || tete.level() != t.level()) return Actions.Etat.OK;
            if (place == null || age % 20 == 0) {
                double[] d = Guerre.place(rang, but.x - tete.getX(), but.z - tete.getZ(), Guerre.FRONT);
                place = new Vec3(tete.getX() + d[0], tete.getY(), tete.getZ() + d[1]);
            }
            double d2 = t.position().distanceToSqr(place);
            if (d2 < 1.5 * 1.5) { t.getNavigation().stop(); return Actions.Etat.EN_COURS; }
            marcher(t, place, d2 > 8 * 8 ? 1.25 : 1.0);
            return Actions.Etat.EN_COURS;
        }
    }

    /** Sous 30 % de sa vie, il rompt le combat et rentre au poste, où il reste jusqu'à la moitié de sa vie (une minute au plus). */
    static final class Repli extends Actions.Action {
        Repli() { super("repli"); }

        Actions.Etat tick(Travailleur t) {
            if (t.bureau == null || age > 20 * 60) return Actions.Etat.OK;
            Vec3 b = Vec3.atBottomCenterOf(t.bureau);
            if (t.position().distanceToSqr(b) > 3 * 3) {
                if (coince()) return echec("bloque");
                marcher(t, b, 1.3);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            return t.getHealth() >= t.getMaxHealth() * 0.5 ? Actions.Etat.OK : Actions.Etat.EN_COURS;
        }
    }

    /** Trop blessé : il se replie vers le poste (vrai s'il part). */
    static boolean replier(Travailleur t) {
        if (t.bureau == null || !Guerre.repli(t.getHealth(), t.getMaxHealth()) || t.action instanceof Repli) return false;
        Cerveau.dire(t, "repli", 20, "Je suis touché ! Je me replie au poste !");
        if (t.isUsingItem()) t.stopUsingItem();
        Cerveau.lancer(t, new Repli());
        return true;
    }
}
