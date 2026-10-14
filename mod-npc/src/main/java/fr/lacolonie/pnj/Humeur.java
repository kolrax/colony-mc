package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Le moral au fil des jours, côté jeu (lot 5, cahier 5.2 et 5.3) : le bilan régulier des causes ({@link Moral}), la lenteur
 * d'un moral bas, les lettres au chef et la démission ; au plus bas, sur la durée : vol, sabotage, fuite avec un butin,
 * avertissements puis révolte armée contre le chef ({@link #mefaits}). Rien à chaque tick : le bilan revient toutes les
 * {@code Reglages.moral_minutes_bilan} minutes (vérifié avec {@link Besoins#veiller}, décalé par {@code getId()}), la
 * lenteur se revoit une fois par seconde.
 */
final class Humeur {
    private Humeur() {}

    static final ResourceLocation LENTEUR = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "moral");

    /** Combien de bilans dans un jour du jeu (20 minutes). */
    static int bilansParJour() { return Math.max(1, 20 / Reglages.actuel.moral_minutes_bilan); }

    /** Toutes les 5 s environ, depuis le cerveau : le bilan quand son heure est venue. */
    static void veiller(Travailleur t) {
        if (t.mode == Travailleur.Mode.CHOMEUR || t.chef == null) return;
        long maintenant = t.level().getGameTime();
        if (!t.memoire.contains("bilan_le")) { t.memoire.putLong("bilan_le", maintenant); return; }
        if (maintenant - t.memoire.getLong("bilan_le") < Reglages.actuel.moral_minutes_bilan * 1200L) return;
        t.memoire.putLong("bilan_le", maintenant);
        bilan(t);
    }

    /** Ce qui compte pour son moral, lu sans recherche coûteuse. */
    static Moral.Etat etat(Travailleur t) {
        CompoundTag m = t.memoire;
        var l = t.level();
        long periode = Reglages.actuel.moral_minutes_bilan * 60L; // en secondes
        CompoundTag dits = m.getCompound("dits");
        boolean blesseVu = dits.contains("blesse_vu") && Cerveau.temps(t) - dits.getLong("blesse_vu") < periode;
        // Un toit : un bloc qui arrête le pas au-dessus du lit (le verre compte), lu dans la carte des hauteurs.
        boolean toit = t.lit != null && l.isLoaded(t.lit)
                && l.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.lit.getX(), t.lit.getZ()) > t.lit.getY() + 1;
        int jourDeuil = m.contains("deuil_le") ? (int) (t.jour() - m.getLong("deuil_le")) : -1;
        if (jourDeuil >= 0 && m.getBoolean("deuil_enterre")) jourDeuil++; // enterré : le deuil pèse moins, et un jour de moins (Deuil)
        boolean pluie = l.isRaining(), orage = l.isThundering();
        int bilansPluie = pluie ? m.getInt("bilans_pluie") + 1 : 0;
        m.putInt("bilans_pluie", bilansPluie);
        // Le chef l'a frappé ou insulté ce jour-ci (le moral a déjà baissé sur le moment : ici, la rancune qui reste).
        boolean coup = m.contains("coup_chef") && l.getGameTime() - m.getLong("coup_chef") < 24000
                || t.souvenirs.liste.stream().anyMatch(x -> x.genre() == Souvenirs.Genre.INSULTE && x.jour() == t.jour());
        int ouvriers = 0;
        if (l.getServer() != null) {
            var eff = Courrier.de(l.getServer()).effectifs.get(t.chef);
            if (eff != null) ouvriers = (int) eff.values().stream().filter(x -> x.mort.isEmpty()).count();
        }
        int depot = 0;
        Bureau.Entite b = Bureau.entite(t);
        if (b != null) for (int i = 0; i < b.depot.getContainerSize(); i++) depot += b.depot.getItem(i).getCount();
        return new Moral.Etat(t.impayes, m.contains("paie_le") && t.jour() - m.getLong("paie_le") <= 1, Moral.lire(m.getString("repas")),
                t.lit != null, toit, Blessures.gravite(t) != Gravite.AUCUNE, blesseVu, jourDeuil, pluie, orage, bilansPluie,
                !pluie && l.isDay(), coup, ouvriers, depot);
    }

    /** Le bilan : le moral bouge un peu, il dit ce qui lui pèse (ou ce qui lui plaît), et il écrit au chef si ça ne va plus. */
    static Moral.Bilan bilan(Travailleur t) {
        Moral.Bilan b = Moral.bilan(etat(t));
        t.changerMoral(b.total());
        t.memoire.putInt("bilan", b.total());
        Moral.Cause pire = b.pire();
        if (pire != null) t.memoire.putString("bilan_cause", pire.name());
        else t.memoire.remove("bilan_cause");
        Moral.Cause dite = b.total() < 0 ? pire : b.total() > 0 ? b.meilleure() : null;
        String cle = dite == null ? null : b.total() < 0 ? dite.plainte : dite.joie;
        if (cle != null) Besoins.parler(t, cle, null);
        suite(t, pire);
        mefaits(t, pire);
        return b;
    }

    /** Ce qui lui pèse le plus, en toutes lettres (« la paie qui ne vient pas »), ou rien. */
    static String cause(Travailleur t) {
        String c = t.memoire.getString("bilan_cause");
        for (Moral.Cause x : Moral.Cause.values()) if (x.name().equals(c)) return x.dit;
        return "";
    }

    /** Lettres au chef sous le seuil, puis la démission après deux lettres et des jours au plus bas. */
    static void suite(Travailleur t, Moral.Cause pire) {
        CompoundTag m = t.memoire;
        Reglages r = Reglages.actuel;
        if (t.moral >= Moral.APAISE) for (String k : List.of("avertis", "avertis_depuis", "demission", "demission_essais", "chef_lu", "fuite", "fuite_etape", "fuite_dit", "menaces",
                "vol_ou", "sabotage_ou"))
            m.remove(k);
        if (m.getInt("avertis") >= 2 && t.chefJoueur() != null) m.putBoolean("chef_lu", true); // le chef a pu lire ses lettres
        int bas = t.moral < r.moral_seuil_demission ? m.getInt("bilans_bas") + 1 : 0;
        m.putInt("bilans_bas", bas);
        int depuis = m.getInt("avertis_depuis") + 1, avertis = m.getInt("avertis");
        m.putInt("avertis_depuis", depuis);
        int avantDepart = r.moral_jours_avant_demission <= 0 ? Integer.MAX_VALUE : r.moral_jours_avant_demission * bilansParJour();
        switch (Moral.suite(t.moral, avertis, depuis, bas, r.moral_seuil_lettre, bilansParJour(), avantDepart)) {
            case AVERTIT -> {
                m.putInt("avertis", avertis + 1);
                m.putInt("avertis_depuis", 0);
                if (avertis >= 1) m.putBoolean("chef_lu", t.chefJoueur() != null);
                Courrier.ecrire(t, avertis == 0 ? "moral_bas" : "moral_avertit", pire == null ? "le cœur n'y est plus" : pire.dit);
            }
            // Il ne part pas sans que son chef ait été là depuis sa deuxième lettre (connecté, il a pu la lire).
            // Décidé à partir, selon son caractère : en rendant tout, ou en douce avec un petit butin (une seule fois tiré).
            case DEMISSIONNE -> {
                if (!m.getBoolean("chef_lu") || m.getBoolean("demission")) return;
                m.putBoolean("demission", true);
                if (Moral.fuit(t.temperament, t.getRandom().nextDouble())) m.putBoolean("fuite", true);
            }
            case RIEN -> {}
        }
    }

    /**
     * Juste après le danger, depuis le cerveau : décidé à partir ({@link #suite}), il rend d'abord au dépôt tout ce qu'il a
     * sur lui (outils, armes, récolte), puis dit au revoir, écrit au chef et redevient chercheur d'emploi. Jamais en danger,
     * jamais avec quelque chose du dépôt dans son sac : dépôt absent ou plein, il attend. Vrai s'il s'en occupe.
     */
    static boolean demissionner(Travailleur t) {
        if (!t.memoire.getBoolean("demission") || t.mode == Travailleur.Mode.CHOMEUR || t.chef == null) return false;
        if (t.enDanger()) return false;
        desequiper(t);
        boolean laisse = false;
        if (!vide(t)) {
            int essais = t.memoire.getInt("demission_essais");
            if (t.bureau != null && essais < ESSAIS) {
                t.memoire.putInt("demission_essais", essais + 1);
                Cerveau.lancer(t, new Actions.Deposer(List.of()));
                return true;
            }
            // Sans poste, ou le dépôt plein (ou hors d'atteinte) trois fois : il laisse le reste là, à terre, et le dit.
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (!t.inventaire.getItem(i).isEmpty()) t.spawnAtLocation(t.inventaire.removeItemNoUpdate(i));
            for (EquipmentSlot e : PORTES) if (!t.getItemBySlot(e).isEmpty()) { t.spawnAtLocation(t.getItemBySlot(e)); t.setItemSlot(e, ItemStack.EMPTY); }
            laisse = true;
        }
        // En fuite aussi, il a d'abord tout rendu (outils, armes, armure, sac) : il ne part qu'avec son petit butin.
        if (t.memoire.getBoolean("fuite")) return fuir(t);
        Besoins.dire(t, "demission", null);
        Courrier.ecrire(t, "demission", cause(t).isEmpty() ? "le cœur n'y est plus" : cause(t),
                laisse ? " Ce qui ne tenait pas au dépôt, je l'ai laissé à terre, là où j'étais." : "");
        Courrier.deposer(t); // il est au poste : ses lettres y restent (licencier les oublierait)
        t.licencier(); // efface aussi son moral de ce chef-là (oublier)
        return true;
    }

    static final int ESSAIS = 3;
    static final List<EquipmentSlot> PORTES = List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET);

    /** Ce qu'il tient et ce qu'il porte retourne dans son sac (ce qui n'y tient pas reste porté). */
    static void desequiper(Travailleur t) {
        for (EquipmentSlot e : PORTES) {
            ItemStack p = t.getItemBySlot(e);
            if (!p.isEmpty()) t.setItemSlot(e, t.inventaire.addItem(p));
        }
    }

    /** Rien dans le sac, rien en main, rien sur le dos. */
    static boolean vide(Travailleur t) {
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (!t.inventaire.getItem(i).isEmpty()) return false;
        for (EquipmentSlot e : PORTES) if (!t.getItemBySlot(e).isEmpty()) return false;
        return true;
    }

    /** Renvoyé ou parti : le moral de ce chef-là s'oublie (sinon, réembauché, il repartirait aussitôt). */
    static void oublier(Travailleur t) {
        // Son butin caché (« butin ») et ses vols du jour, il les garde : ils partent avec lui.
        for (String k : List.of("demission", "demission_essais", "chef_lu", "avertis", "avertis_depuis", "bilans_bas", "bilan", "bilan_cause",
                "bilan_le", "deuil_le", "deuil_nom", "deuil_coup", "deuils", "deuil_apprend", "deuil_enterre", "repas", "bilans_pluie", "coup_chef", "fuite", "fuite_etape", "fuite_dit", "menaces",
                "menace_depuis", "revolte", "revolte_impayes", "vol_ou", "sabotage_ou", "sabotage"))
            t.memoire.remove(k);
        t.moral = Obeissance.DEPART;
    }

    /** Une fois par seconde : la lenteur d'un moral bas (comme {@link Blessures#lenteur}, sans toucher au sprint). */
    static void lenteur(Travailleur t) {
        var a = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        Reglages r = Reglages.actuel;
        double voulu = t.mode == Travailleur.Mode.CHOMEUR ? 0
                : -Moral.lenteur(Moral.effet(t.moral, r.moral_seuil_lettre, r.moral_seuil_demission), r.moral_lenteur / 100.0);
        AttributeModifier m = a.getModifier(LENTEUR);
        if (m != null && m.amount() == voulu) return;
        if (m != null) a.removeModifier(LENTEUR);
        if (voulu != 0) a.addTransientModifier(new AttributeModifier(LENTEUR, voulu, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Il vient de manger {@code s} : retenu parmi ses derniers repas. */
    static void repas(Travailleur t, ItemStack s) {
        t.memoire.putString("repas", Moral.retenir(t.memoire.getString("repas"), net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()));
    }

    /** Un ouvrier du même chef est mort : le deuil (moral −10 tout de suite, puis moins chaque jour). La suite (la nouvelle, les souvenirs, l'enterrement) : {@link Deuil}. */
    static void deuil(Travailleur mort) {
        if (mort.chef == null || mort.level().getServer() == null) return;
        for (Travailleur t : ColoniePnj.pnjDe(mort.level().getServer(), mort.chef)) {
            if (t == mort) continue;
            // Plusieurs morts le même jour : au plus Moral.DEUIL_JOUR en tout ce jour-là.
            int deja = t.memoire.contains("deuil_le") && t.memoire.getLong("deuil_le") == t.jour() ? t.memoire.getInt("deuil_coup") : 0;
            int coup = Moral.deuilCoup(deja);
            t.changerMoral(coup);
            t.memoire.putInt("deuil_coup", deja + coup);
            t.memoire.putLong("deuil_le", t.jour());
            t.memoire.putString("deuil_nom", mort.prenom);
            t.memoire.remove("deuil_enterre");
            Deuil.noter(t, mort, coup); // ce qu'un enterrement rendra, et la nouvelle à dire (Deuil.veiller)
        }
    }

    // --- Méfaits (2e partie) : vol, sabotage, fuite, avertissements et révolte -----------------------------------

    /** Le chef « présent » : à moins de tant de blocs ; il le frappe à moins de {@code FRAPPE} ; au-delà de {@code LOIN}, il a fui. */
    static final int PRESENT = 24, FRAPPE = 16, LOIN = 48;

    /**
     * Au bilan, juste après les lettres : au plus bas depuis un moment, un vol ou un sabotage de temps en temps, un
     * avertissement au chef, ou la révolte ({@link Moral#mefait}). Le vol et le sabotage se font ensuite, depuis le cerveau
     * ({@link #mefaire}). Rien à chercher pour un ouvrier qui va bien.
     */
    static void mefaits(Travailleur t, Moral.Cause pire) {
        CompoundTag m = t.memoire;
        Reglages r = Reglages.actuel;
        m.putInt("menace_depuis", m.getInt("menace_depuis") + 1);
        if (t.moral >= r.moral_seuil_demission || m.getBoolean("demission") || m.getBoolean("revolte") || t.level().getServer() == null) return;
        ServerPlayer chef = t.chefJoueur();
        boolean present = chef != null && chef.level() == t.level() && !chef.isSpectator() && chef.distanceTo(t) < PRESENT;
        int compagnons = 0;
        if (r.moral_revolte && m.getInt("avertis") >= 2)
            for (Travailleur x : ColoniePnj.pnjDe(t.level().getServer(), t.chef))
                if (x != t && x.mode != Travailleur.Mode.CHOMEUR && x.moral < r.moral_seuil_demission) compagnons++;
        int vols = m.getLong("vols_jour") == t.jour() ? m.getInt("vols") : 0;
        if (m.getList("butin", Tag.TAG_COMPOUND).size() >= Moral.BUTIN_MAX) vols = Integer.MAX_VALUE; // sa cachette est pleine
        Moral.Rancoeur rancoeur = new Moral.Rancoeur(t.moral, r.moral_seuil_demission, m.getInt("avertis"), m.getInt("bilans_bas"), bilansParJour(),
                t.temperament, compagnons, m.getInt("menaces"), m.getInt("menace_depuis"), chef != null, present, r.moral_revolte, vols,
                r.moral_vol_max, r.moral_mefaits);
        switch (Moral.mefait(rancoeur, t.getRandom().nextDouble(), t.getRandom().nextDouble())) {
            case MENACE -> menacer(t, pire);
            case REVOLTE -> {
                m.putBoolean("revolte", true);
                m.putInt("revolte_impayes", t.impayes);
                Besoins.dire(t, "revolte", null);
            }
            case VOL -> { BlockPos p = cibleVol(t); if (p != null) m.putLong("vol_ou", p.asLong()); }
            case SABOTAGE -> { BlockPos p = cibleSabotage(t); if (p != null) m.putLong("sabotage_ou", p.asLong()); }
            case RIEN -> {}
        }
    }

    /** Un avertissement avant la révolte : par lettre (un corbeau, tout de suite), ou en face si le chef est là. */
    static void menacer(Travailleur t, Moral.Cause pire) {
        int n = t.memoire.getInt("menaces") + 1;
        t.memoire.putInt("menaces", n);
        t.memoire.putInt("menace_depuis", 0);
        String dit = Vie.tirer("avertissement_" + Math.min(n, 3), t.temperament, valeurs(t, null), t.getRandom()::nextDouble);
        Courrier.ecrire(t, "menace", dit == null ? "" : dit, pire == null ? "le cœur n'y est plus" : pire.dit,
                n >= Moral.MENACES ? " Encore une fois, et on prend les armes." : "");
    }

    static Map<String, String> valeurs(Travailleur t, String objet) {
        String chef = t.chefNom.isEmpty() ? "chef" : t.chefNom;
        return objet == null ? Map.of("chef", chef) : Map.of("chef", chef, "objet", objet);
    }

    /** Depuis le cerveau, juste après la démission : la révolte en cours, puis le vol ou le sabotage décidé au bilan. Vrai s'il s'en occupe. */
    static boolean mefaire(Travailleur t) {
        CompoundTag m = t.memoire;
        if (m.getBoolean("revolte")) return revolter(t);
        if (t.mode != Travailleur.Mode.TRAVAIL && t.mode != Travailleur.Mode.GREVE) return false; // pas sous les yeux du chef qu'il suit
        for (String k : List.of("vol_ou", "sabotage_ou")) {
            if (!m.contains(k)) continue;
            BlockPos p = BlockPos.of(m.getLong(k));
            m.remove(k);
            Cerveau.lancer(t, k.equals("vol_ou") ? new Voler(p, false) : new Saboter(p));
            return true;
        }
        return false;
    }

    // --- Le vol ---------------------------------------------------------------------------------------------------

    /**
     * Où voler : le dépôt de son chef, ou un coffre ou un tonneau à moins de 16 blocs, à qui que ce soit (cahier 5.5).
     * Sans recherche bloc à bloc : seulement les blocs-entités des chunks voisins, et seulement le jour où il vole.
     */
    static BlockPos cibleVol(Travailleur t) {
        Bureau.Entite b = Bureau.entite(t);
        boolean depot = b != null && !cases(b.depot).isEmpty();
        List<BlockPos> autres = new ArrayList<>();
        if (Reglages.actuel.moral_vol_autres && t.level() instanceof ServerLevel l) {
            ChunkPos c = t.chunkPosition();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    LevelChunk ch = l.getChunkSource().getChunkNow(c.x + dx, c.z + dz);
                    if (ch == null) continue;
                    for (BlockEntity be : ch.getBlockEntities().values()) {
                        Container x = contenant(be);
                        if (x != null && !be.getBlockPos().equals(t.bureau) && be.getBlockPos().distSqr(t.blockPosition()) < 16 * 16 && !cases(x).isEmpty())
                            autres.add(be.getBlockPos());
                    }
                }
        }
        if (depot && (autres.isEmpty() || t.getRandom().nextBoolean())) return t.bureau;
        return autres.isEmpty() ? null : autres.get(t.getRandom().nextInt(autres.size()));
    }

    /** Ce qu'on peut piller : un dépôt de poste, un coffre ou un tonneau (pas un coffre de donjon jamais ouvert). */
    static Container contenant(BlockEntity be) {
        if (be instanceof Bureau.Entite b) return b.depot;
        if (be instanceof RandomizableContainerBlockEntity r && r.getLootTable() != null) return null;
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity ? (Container) be : null;
    }

    /** Les cases où il ose prendre quelque chose (rien de précieux, rien qui ne s'empile : {@link Moral#precieux}). */
    static List<Integer> cases(Container c) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && Reglages.piece(s) < 0
                    && !Moral.precieux(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getMaxStackSize() > 1)) l.add(i);
        }
        return l;
    }

    /** Il prend au plus {@code n} objets d'un même tas, au hasard, et les cache ; compté dans ses vols du jour. */
    static ItemStack voler(Travailleur t, Container c, int n) {
        List<Integer> l = cases(c);
        if (l.isEmpty() || n <= 0) return ItemStack.EMPTY;
        int i = l.get(t.getRandom().nextInt(l.size()));
        ItemStack s = c.removeItem(i, Moral.prise(c.getItem(i).getCount(), n));
        if (s.isEmpty()) return s;
        c.setChanged();
        cacher(t, s.copy());
        CompoundTag m = t.memoire;
        if (m.getLong("vols_jour") != t.jour()) { m.putLong("vols_jour", t.jour()); m.putInt("vols", 0); }
        m.putInt("vols", m.getInt("vols") + s.getCount());
        return s;
    }

    static int resteDuJour(Travailleur t) {
        int vols = t.memoire.getLong("vols_jour") == t.jour() ? t.memoire.getInt("vols") : 0;
        return Math.max(0, Reglages.actuel.moral_vol_max - vols);
    }

    /** Dans sa cachette (pas son sac : il ne le range pas au dépôt). Rendu à sa mort avec ses affaires, emporté s'il s'en va. */
    static void cacher(Travailleur t, ItemStack s) {
        ListTag l = t.memoire.getList("butin", Tag.TAG_COMPOUND);
        l.add(s.save(t.registryAccess()));
        t.memoire.put("butin", l);
    }

    /** Son butin caché, retiré de sa cachette (à sa mort). */
    static List<ItemStack> sortirButin(Travailleur t) {
        List<ItemStack> r = new ArrayList<>();
        for (Tag x : t.memoire.getList("butin", Tag.TAG_COMPOUND)) ItemStack.parse(t.registryAccess(), x).ifPresent(r::add);
        t.memoire.remove("butin");
        return r;
    }

    static int butin(Travailleur t) {
        int n = 0;
        for (Tag x : t.memoire.getList("butin", Tag.TAG_COMPOUND)) n += ((CompoundTag) x).getInt("count");
        return n;
    }

    /** Il va au coffre (ou au dépôt), y prend discrètement un peu de quelque chose, et marmonne ; un collègue peut le voir. */
    static final class Voler extends Actions.Action {
        final BlockPos ou;
        final boolean fuite;

        Voler(BlockPos ou, boolean fuite) { super("voler"); this.ou = ou; this.fuite = fuite; }

        Actions.Etat tick(Travailleur t) {
            Vec3 v = Vec3.atBottomCenterOf(ou);
            if (t.position().distanceToSqr(v) > 3.2 * 3.2) {
                if (coince() || age > 20 * 90) return echec("inaccessible");
                marcher(t, v, 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(ou));
            BlockEntity be = t.level().getBlockEntity(ou);
            Container c = be == null ? null : contenant(be);
            ItemStack pris = c == null ? ItemStack.EMPTY : voler(t, c, fuite ? Moral.VOL_COUP : resteDuJour(t));
            if (pris.isEmpty()) return echec("rien");
            t.swing(InteractionHand.MAIN_HAND);
            String nom = Consignes.nom(pris), objet = "un peu " + Souvenirs.de(nom) + nom;
            if (!fuite) Besoins.parler(t, "vol", null, objet); // il marmonne : qui passe tout près l'entend
            denoncer(t, "prendre " + objet + (ou.equals(t.bureau) ? " au dépôt" : " dans un coffre"));
            return Actions.Etat.OK;
        }
    }

    /** Un collègue du même chef, pas au plus bas lui-même, l'a vu faire (à moins de 16 blocs, en vue) : une fois sur deux, il le dit au chef. */
    static void denoncer(Travailleur t, String quoi) {
        if (t.chef == null) return;
        for (Travailleur x : t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(16),
                x -> x != t && t.chef.equals(x.chef) && x.mode != Travailleur.Mode.CHOMEUR && x.moral >= Reglages.actuel.moral_seuil_demission))
            if (x.hasLineOfSight(t) && x.getRandom().nextBoolean()) { Courrier.ecrire(x, "denonce", t.prenom, quoi); return; }
    }

    // --- Le sabotage ----------------------------------------------------------------------------------------------

    /**
     * Ce qu'il abîme : une torche ou une lanterne près du poste (jamais une clôture : les bêtes d'un enclos s'enfuiraient).
     * Un seul bloc, qui tombe à terre : il suffit de le reposer. Cherché seulement le jour où il sabote (17 × 6 × 17 blocs
     * autour du poste), si toute la zone est chargée, et jamais chez un voisin ({@link Fermier#aLui}).
     */
    static BlockPos cibleSabotage(Travailleur t) {
        if (t.bureau == null || !(t.level() instanceof ServerLevel l)) return null;
        BlockPos a = t.bureau.offset(-8, -2, -8), z = t.bureau.offset(8, 3, 8);
        for (int cx = a.getX() >> 4; cx <= z.getX() >> 4; cx++)
            for (int cz = a.getZ() >> 4; cz <= z.getZ() >> 4; cz++) if (l.getChunkSource().getChunkNow(cx, cz) == null) return null;
        List<BlockPos> c = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(a, z)) if (sabotable(l.getBlockState(p)) != null) c.add(p.immutable());
        java.util.Collections.shuffle(c, new java.util.Random(t.getRandom().nextLong()));
        for (BlockPos p : c) if (Fermier.aLui(t, p)) return p; // le coût de aLui, seulement pour les quelques torches trouvées
        return null;
    }

    /** {ce qu'il avoue, ce qu'un collègue a vu}, ou null. */
    static String[] sabotable(BlockState st) {
        if (st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH))
            return new String[]{"la torche", "arracher une torche près du poste"};
        if (st.is(Blocks.LANTERN) || st.is(Blocks.SOUL_LANTERN)) return new String[]{"la lanterne", "décrocher une lanterne près du poste"};
        return null;
    }

    static final class Saboter extends Actions.Action {
        final BlockPos ou;

        Saboter(BlockPos ou) { super("saboter"); this.ou = ou; }

        Actions.Etat tick(Travailleur t) {
            String[] quoi = sabotable(t.level().getBlockState(ou));
            if (quoi == null) return echec("rien");
            if (!Actions.aPortee(t, ou)) {
                if (coince() || age > 20 * 90) return echec("inaccessible");
                marcher(t, Vec3.atCenterOf(ou), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(Vec3.atCenterOf(ou));
            t.swing(InteractionHand.MAIN_HAND);
            t.level().destroyBlock(ou, true, t); // l'objet tombe à terre (révolte : cahier 11.2)
            t.memoire.putString("sabotage", quoi[0]);
            denoncer(t, quoi[1]);
            return Actions.Etat.OK;
        }
    }

    /** Au chef qui demande comment il va : parfois il avoue son sabotage (une fois), ou se trahit s'il cache un butin. */
    static String aveu(Travailleur t) {
        CompoundTag m = t.memoire;
        String s = null;
        if (m.contains("sabotage") && t.getRandom().nextBoolean()) {
            s = Vie.tirer("sabotage_avoue", t.temperament, valeurs(t, m.getString("sabotage")), t.getRandom()::nextDouble);
            m.remove("sabotage");
        } else if (!m.getList("butin", Tag.TAG_COMPOUND).isEmpty() && t.getRandom().nextInt(3) == 0)
            s = Vie.tirer("butin_cache", t.temperament, valeurs(t, null), t.getRandom()::nextDouble);
        return s == null ? "" : s;
    }

    // --- La fuite -------------------------------------------------------------------------------------------------

    /**
     * Décidé à partir en douce (au lieu de démissionner), après avoir rendu au dépôt tout ce qu'il avait sur lui
     * ({@link #demissionner}) : un petit butin pris au dépôt (au plus {@link Moral#VOL_COUP} objets sans valeur), puis la
     * route, loin du poste ({@link Moral#FUITE_LOIN} blocs, en marchant), une lettre d'adieu, et il redevient chercheur
     * d'emploi avec ce seul butin caché. Vrai s'il s'en occupe.
     */
    static boolean fuir(Travailleur t) {
        CompoundTag m = t.memoire;
        int etape = m.getInt("fuite_etape");
        m.putInt("fuite_etape", etape + 1);
        Bureau.Entite b = Bureau.entite(t);
        if (etape == 0 && b != null && !cases(b.depot).isEmpty()) { Cerveau.lancer(t, new Voler(t.bureau, true)); return true; }
        Vec3 poste = t.bureau == null ? null : Vec3.atBottomCenterOf(t.bureau);
        boolean loin = poste == null || t.position().distanceToSqr(poste) >= (Moral.FUITE_LOIN - 8) * (Moral.FUITE_LOIN - 8);
        if (!loin && etape <= ESSAIS) {
            if (!m.getBoolean("fuite_dit")) { m.putBoolean("fuite_dit", true); Besoins.dire(t, "fuite_nuit", null); }
            Vec3 cap = t.position().subtract(poste).multiply(1, 0, 1);
            if (cap.lengthSqr() < 1) cap = new Vec3(t.getRandom().nextDouble() - 0.5, 0, t.getRandom().nextDouble() - 0.5);
            Cerveau.lancer(t, new Actions.Aller(poste.add(cap.normalize().scale(Moral.FUITE_LOIN)), 4, true));
            return true;
        }
        Courrier.ecrire(t, "fuite", cause(t).isEmpty() ? "le cœur n'y est plus" : cause(t));
        Courrier.deposer(t); // encore près du poste : la lettre y reste (licencier l'oublierait) ; loin, un corbeau la porte déjà
        t.licencier();
        return true;
    }

    // --- La révolte -----------------------------------------------------------------------------------------------

    /**
     * En révolte : la caisse couvre enfin ce qu'on lui doit, il va toucher sa paie (ce qui l'arrête) ; la faim, la nuit ou
     * l'épuisement passent avant (faux : le cerveau s'en occupe comme d'habitude) ; sinon il frappe son chef s'il est à
     * portée (lui seul, jamais un autre), ou l'attend, les bras croisés.
     */
    static boolean revolter(Travailleur t) {
        if (calmer(t)) return false;
        if (paieEnCaisse(t)) { Cerveau.lancer(t, new Actions.ToucherPaie()); return true; }
        if (t.faim <= 8 || t.faim <= 14 && t.compter(Actions.Filtre.de("nourriture")) > 0) return false;
        if (!t.enService() || t.fatigue >= 100 || t.memoire.getBoolean("epuise")) return false;
        ServerPlayer chef = t.chefJoueur();
        if (chef == null || t.distanceTo(chef) > FRAPPE) { Cerveau.attendre(t, 5); return true; }
        Besoins.parler(t, "revolte", null);
        Cerveau.lancer(t, new Actions.Attaquer(chef).portee(PRESENT));
        return true;
    }

    /**
     * La révolte cesse : moral remonté, ce qu'on lui devait payé, chef parti loin (ou absent), ou le réglage coupé. Ses
     * avertissements repartent à zéro ; le chef encore là, il le dit. Vrai si elle a cessé (ou n'existait pas).
     */
    static boolean calmer(Travailleur t) {
        CompoundTag m = t.memoire;
        if (!m.getBoolean("revolte")) return true;
        // Les pièces sont dans la caisse : il lâche son chef pour aller se payer (revolter, au prochain passage du cerveau).
        if (paieEnCaisse(t) && t.action instanceof Actions.Attaquer a && a.cible.getUUID().equals(t.chef)) t.finirAction("paie");
        ServerPlayer chef = t.chefJoueur();
        boolean la = chef != null && chef.isAlive() && !chef.isSpectator() && chef.level() == t.level() && t.distanceTo(chef) < LOIN;
        Reglages r = Reglages.actuel;
        if (r.moral_revolte && !Moral.finRevolte(t.moral, r.moral_seuil_lettre, m.getInt("revolte_impayes"), t.impayes, la)) return false;
        m.remove("revolte");
        m.remove("revolte_impayes");
        m.remove("menaces"); // une nouvelle révolte exigera de nouveaux avertissements
        if (la) Besoins.dire(t, "revolte_fin", null);
        if (t.action instanceof Actions.Attaquer a && a.cible.getUUID().equals(t.chef)) t.finirAction("apaise");
        return true;
    }

    /** On lui doit quelque chose, et la caisse du poste le couvre. */
    static boolean paieEnCaisse(Travailleur t) {
        long du = t.du();
        Bureau.Entite b = du > 0 ? Bureau.entite(t) : null;
        return b != null && Reglages.valeur(b.caisse) >= du;
    }
}
