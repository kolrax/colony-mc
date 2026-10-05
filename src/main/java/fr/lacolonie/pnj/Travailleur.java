package fr.lacolonie.pnj;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.luaj.vm2.LuaTable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Un PNJ travailleur : un corps de joueur piloté par les scripts Lua de son métier. */
public class Travailleur extends PathfinderMob {
    public static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(Travailleur.class, EntityDataSerializers.STRING);

    public enum Mode {
        CHOMEUR("chomeur"), SUIVRE("suivre"), ATTENDRE("attendre"), TRAVAIL("travail"), GREVE("greve");
        public final String lua;
        Mode(String lua) { this.lua = lua; }
    }

    static final String[] PRENOMS = {"Gauthier", "Aliénor", "Thibaut", "Isabeau", "Enguerrand", "Mahaut", "Baudouin", "Ysolde",
            "Godefroy", "Blanche", "Perceval", "Héloïse", "Arnaud", "Clémence", "Bertrand", "Agnès", "Renaud", "Jehanne",
            "Hugues", "Ermengarde", "Aymeric", "Béatrix", "Foulques", "Pétronille", "Raoul", "Sibylle", "Guillaume", "Mélisende",
            "Tancrède", "Adélaïde", "Lancelin", "Guenièvre", "Odon", "Oriane", "Thierry", "Bérengère", "Amaury", "Philippa"};
    static final String[] TEMPERAMENTS = {"calme", "peureux", "fourbe", "colerique"};

    // Identité
    public String prenom = "Gauthier", metier = "mineur", temperament = "calme";
    public int niveau = 1;
    // Emploi
    public UUID chef;
    public String chefNom = "";
    public Mode mode = Mode.CHOMEUR;
    public BlockPos lieu;
    public Direction direction = Direction.NORTH;
    public long prochainePaie, arrivee;
    public int impayes;
    public String besoin = "";
    // Corps
    public int faim = 20;
    int faimCompteur, soinCompteur;
    public boolean aTerre, mortDefinitive;
    int aTerreTicks;
    public final SimpleContainer inventaire = new SimpleContainer(27) {
        @Override
        public boolean stillValid(Player p) { return isAlive() && p.distanceToSqr(Travailleur.this) < 64; }
    };
    // Cerveau
    public Actions.Action action;
    public String resultat = "";
    LuaTable api;
    CompoundTag memoireSauvee = new CompoundTag();
    int pauseCerveau;
    ChunkPos chunkForce;

    public Travailleur(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(true);
        setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder attributs() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 1)
                .add(Attributes.FOLLOW_RANGE, 64);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        super.defineSynchedData(b);
        b.define(SKIN, "");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6) {
            @Override
            public boolean canUse() { return mode == Mode.CHOMEUR && !aTerre && super.canUse(); }
        });
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    /** Tire un nouveau chercheur d'emploi au hasard. */
    public void tirerAuSort(String metierChoisi, int niveauChoisi) {
        var r = getRandom();
        prenom = PRENOMS[r.nextInt(PRENOMS.length)];
        temperament = TEMPERAMENTS[r.nextInt(TEMPERAMENTS.length)];
        metier = metierChoisi;
        if (niveauChoisi > 0) niveau = Math.clamp(niveauChoisi, 1, 5);
        else {
            int x = r.nextInt(100); // 40 % ★, 30 % ★★, 18 % ★★★, 9 % ★★★★, 3 % ★★★★★
            niveau = x < 40 ? 1 : x < 70 ? 2 : x < 88 ? 3 : x < 97 ? 4 : 5;
        }
        arrivee = System.currentTimeMillis();
        appliquerNiveau();
        majNom();
    }

    void appliquerNiveau() {
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(20 + 2 * (niveau - 1));
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1 + 0.5 * (niveau - 1));
        setHealth(getMaxHealth());
    }

    /** Vitesse de travail : 0,8 à ★, 1,6 à ★★★★★. */
    public double facteurTravail() { return 0.6 + 0.2 * niveau; }

    public String skin() { return entityData.get(SKIN); }
    public void setSkin(String pseudo) { entityData.set(SKIN, pseudo == null ? "" : pseudo.trim()); }

    public void majNom() {
        String suffixe = switch (mode) {
            case CHOMEUR -> " · cherche du travail";
            case GREVE -> " · en grève";
            default -> " (" + Lua.nomMetier(metier) + ")";
        };
        if (aTerre) suffixe = " · à terre";
        setCustomName(Component.literal(prenom + suffixe));
    }

    // --- Tick serveur ---------------------------------------------------------------------------

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (tickCount % 20 == 0) forcerChunk();
        if (aTerre) { tickATerre(); return; }
        tickCorps();
        if (mode == Mode.CHOMEUR) {
            long duree = Reglages.actuel.chercheurs.duree_minutes * 60_000L;
            if (System.currentTimeMillis() > arrivee + duree && level().getNearestPlayer(this, 16) == null) discard();
            return;
        }
        // Copie locale : une action peut elle-même remplacer ou effacer l'action en cours (ex. la paie qui déclenche la grève).
        Actions.Action a = action;
        if (a != null) {
            Actions.Etat e;
            try { e = a.tick(this); }
            catch (RuntimeException ex) { ColoniePnj.LOG.error("Action {} de {} en erreur", a.nom, prenom, ex); e = Actions.Etat.ECHEC; a.raison = "erreur"; }
            a.age++;
            if (e != Actions.Etat.EN_COURS && action == a) finirAction(e == Actions.Etat.OK ? "ok" : a.raison.isEmpty() ? "echec" : a.raison);
        }
        if (pauseCerveau > 0) pauseCerveau--;
        else if ((tickCount + getId()) % 10 == 0) Lua.cerveau(this);
    }

    void finirAction(String r) {
        if (action != null) action.fin(this);
        action = null;
        resultat = r;
    }

    void tickCorps() {
        // Faim : un point toutes les 6 minutes au travail, deux fois moins vite sinon.
        faimCompteur += (mode == Mode.TRAVAIL && action != null) ? 2 : 1;
        if (faimCompteur >= 6 * 60 * 20 * 2) { faimCompteur = 0; faim = Math.max(0, faim - 1); }
        if (++soinCompteur >= 200) {
            soinCompteur = 0;
            if (faim >= 15 && getHealth() < getMaxHealth()) heal(1);
            else if (faim == 0 && getHealth() > 1) hurt(damageSources().starve(), 1);
        }
        if (tickCount % 10 == 0) ramasserAutour();
        if (tickCount % 100 == 0) equiperArmure();
        if (mode != Mode.CHOMEUR && mode != Mode.GREVE && chef != null && paieEnRetard()) {
            impayes++;
            prochainePaie = System.currentTimeMillis() + periodePaie();
            if (impayes >= Reglages.actuel.paies_impayees_avant_greve) commencerGreve();
            else direAuChef("Chef, ça fait un moment que je n'ai pas été payé. N'oublie pas de mettre des pièces dans la caisse du bureau.");
        }
    }

    static long periodePaie() { return Reglages.actuel.minutes_entre_paies * 60_000L; }

    /** Vrai quand la paie est due (le PNJ va alors la chercher au bureau). */
    public boolean paieDue() { return chef != null && mode != Mode.CHOMEUR && System.currentTimeMillis() >= prochainePaie; }

    /** Une journée entière est passée sans qu'il ait pu toucher sa paie. */
    boolean paieEnRetard() { return System.currentTimeMillis() >= prochainePaie + periodePaie() / 4 && action == null; }

    /** Montant dû : les paies impayées, plus la paie du jour si elle est due. */
    public long du() { return Reglages.salaire(niveau) * (impayes + (paieDue() ? 1 : 0)); }

    void commencerGreve() {
        mode = Mode.GREVE;
        finirAction("greve");
        majNom();
        direAuChef("Je ne suis plus payé : je me mets en grève. Remplis la caisse du bureau (" + Reglages.texte(du()) + ") et je reprendrai.");
    }

    /** Appelé quand il a touché tout ce qu'on lui devait. */
    public void paye(long montant, boolean paieDuJour) {
        impayes = 0;
        if (paieDuJour) prochainePaie = System.currentTimeMillis() + periodePaie();
        if (mode == Mode.GREVE) {
            mode = lieu != null ? Mode.TRAVAIL : Mode.SUIVRE;
            majNom();
            direAuChef("Merci chef, j'ai touché mon dû (" + Reglages.texte(montant) + "). Je reprends le travail.");
        }
    }

    void tickATerre() {
        getNavigation().stop();
        if (--aTerreTicks <= 0) {
            direAuChef("…");
            mortDefinitive = true;
            kill();
            return;
        }
        if (aTerreTicks % 600 == 0) dire("À l'aide… à manger ou une potion de soin, vite !");
    }

    void tomberATerre() {
        aTerre = true;
        aTerreTicks = 2 * 60 * 20;
        setHealth(1);
        setPose(Pose.SLEEPING);
        finirAction("a_terre");
        getNavigation().stop();
        for (Mob m : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(32), m -> m.getTarget() == this)) m.setTarget(null);
        majNom();
        direAuChef("Je suis à terre ! Il me reste 2 minutes : donne-moi à manger ou une potion de soin.");
        dire("Je suis touché… aidez-moi !");
    }

    void relever(Player p) {
        aTerre = false;
        setPose(Pose.STANDING);
        setHealth(6);
        majNom();
        dire("Merci… je te dois la vie, " + p.getName().getString() + ".");
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        if (aTerre && !level().isClientSide) {
            // À terre : seuls un joueur (qui l'achève) ou la mort absolue (vide, /kill) peuvent l'atteindre.
            if (!(src.getEntity() instanceof Player) && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
            mortDefinitive = true;
        }
        return super.hurt(src, amount);
    }

    @Override
    public void die(DamageSource src) {
        if (!level().isClientSide && !mortDefinitive && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            tomberATerre();
            return;
        }
        if (!level().isClientSide && chef != null) direAuChef("§c" + prenom + " est mort. Ses affaires sont restées là où il est tombé.");
        super.die(src);
    }

    /** Mort définitive : ses affaires vont dans un coffre à son nom. */
    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource src) {
        List<ItemStack> tout = new ArrayList<>();
        for (int i = 0; i < inventaire.getContainerSize(); i++) if (!inventaire.getItem(i).isEmpty()) tout.add(inventaire.removeItemNoUpdate(i));
        for (EquipmentSlot s : EquipmentSlot.values()) {
            ItemStack st = getItemBySlot(s);
            if (!st.isEmpty()) { tout.add(st); setItemSlot(s, ItemStack.EMPTY); }
        }
        if (tout.isEmpty()) return;
        BlockPos pos = blockPosition();
        for (int i = 0; i < 3 && !level.getBlockState(pos).canBeReplaced(); i++) pos = pos.above();
        if (level.getBlockState(pos).canBeReplaced() && level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3)
                && level.getBlockEntity(pos) instanceof ChestBlockEntity coffre) {
            ItemStack nom = new ItemStack(Items.CHEST);
            nom.set(DataComponents.CUSTOM_NAME, Component.literal("Affaires de " + prenom));
            coffre.applyComponentsFromItemStack(nom);
            for (ItemStack st : tout) {
                ItemStack reste = st;
                for (int i = 0; i < coffre.getContainerSize() && !reste.isEmpty(); i++)
                    if (coffre.getItem(i).isEmpty()) { coffre.setItem(i, reste); reste = ItemStack.EMPTY; }
                if (!reste.isEmpty()) spawnAtLocation(reste);
            }
            coffre.setChanged();
        } else tout.forEach(this::spawnAtLocation);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION) libererChunk();
        super.remove(reason);
    }

    void forcerChunk() {
        if (!(level() instanceof ServerLevel sl)) return;
        if (chef == null) { libererChunk(); return; }
        ChunkPos c = chunkPosition();
        if (c.equals(chunkForce)) return;
        ColoniePnj.TICKETS.forceChunk(sl, this, c.x, c.z, true, true);
        if (chunkForce != null) ColoniePnj.TICKETS.forceChunk(sl, this, chunkForce.x, chunkForce.z, false, true);
        chunkForce = c;
    }

    void libererChunk() {
        if (chunkForce != null && level() instanceof ServerLevel sl)
            ColoniePnj.TICKETS.forceChunk(sl, this, chunkForce.x, chunkForce.z, false, true);
        chunkForce = null;
    }

    // --- Inventaire ------------------------------------------------------------------------------

    /** Range un objet dans l'inventaire ; ce qui ne rentre pas tombe par terre. */
    public void ranger(ItemStack s) {
        ItemStack reste = inventaire.addItem(s);
        if (!reste.isEmpty()) spawnAtLocation(reste);
    }

    public int compter(Actions.Filtre f) {
        int n = 0;
        for (int i = 0; i < inventaire.getContainerSize(); i++) if (f.objet(inventaire.getItem(i))) n += inventaire.getItem(i).getCount();
        if (f.objet(getMainHandItem())) n += getMainHandItem().getCount();
        return n;
    }

    /** Prend en main le premier objet qui correspond (l'objet tenu retourne dans l'inventaire). */
    public boolean equiper(Actions.Filtre f) {
        if (f.objet(getMainHandItem())) return true;
        for (int i = 0; i < inventaire.getContainerSize(); i++) {
            ItemStack s = inventaire.getItem(i);
            if (f.objet(s)) {
                ItemStack tenu = getMainHandItem();
                setItemSlot(EquipmentSlot.MAINHAND, s);
                inventaire.setItem(i, tenu);
                return true;
            }
        }
        return false;
    }

    void equiperArmure() {
        for (int i = 0; i < inventaire.getContainerSize(); i++) {
            ItemStack s = inventaire.getItem(i);
            if (!(s.getItem() instanceof ArmorItem a)) continue;
            EquipmentSlot slot = a.getEquipmentSlot();
            ItemStack porte = getItemBySlot(slot);
            if (!porte.isEmpty() && porte.getItem() instanceof ArmorItem b && b.getDefense() >= a.getDefense()) continue;
            setItemSlot(slot, s);
            inventaire.setItem(i, porte);
        }
    }

    void ramasserAutour() {
        for (ItemEntity ie : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.2, 0.5, 1.2))) {
            if (ie.hasPickUpDelay() || !ie.isAlive() || !inventaire.canAddItem(ie.getItem())) continue;
            ItemStack s = ie.getItem().copy();
            int avant = s.getCount();
            ItemStack reste = inventaire.addItem(s);
            take(ie, avant - reste.getCount());
            if (reste.isEmpty()) ie.discard(); else ie.setItem(reste);
        }
    }

    public boolean manger() {
        for (int i = 0; i < inventaire.getContainerSize(); i++) {
            ItemStack s = inventaire.getItem(i);
            FoodProperties fp = s.getFoodProperties(this);
            if (fp == null || fp.nutrition() <= 0) continue;
            faim = Math.min(20, faim + fp.nutrition());
            eat(level(), s, fp);
            inventaire.setChanged();
            return true;
        }
        return false;
    }

    // --- Paroles ---------------------------------------------------------------------------------

    public void dire(String texte) {
        Component c = Component.literal("<" + prenom + "> ").withStyle(ChatFormatting.GOLD).append(Component.literal(texte).withStyle(ChatFormatting.WHITE));
        for (Player p : level().players()) if (p.distanceToSqr(this) < 24 * 24) p.sendSystemMessage(c);
    }

    public ServerPlayer chefJoueur() {
        return chef == null || level().getServer() == null ? null : level().getServer().getPlayerList().getPlayer(chef);
    }

    /** Message privé au chef, où qu'il soit (s'il est connecté). */
    public void direAuChef(String texte) {
        ServerPlayer p = chefJoueur();
        if (p != null) p.sendSystemMessage(Component.literal("[" + prenom + "] ").withStyle(ChatFormatting.GOLD).append(Component.literal(texte).withStyle(ChatFormatting.WHITE)));
    }

    // --- Interactions ----------------------------------------------------------------------------

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level().isClientSide) return InteractionResult.SUCCESS;
        ServerPlayer sp = (ServerPlayer) player;
        ItemStack enMain = player.getMainHandItem();
        if (aTerre) {
            if (soigne(enMain)) {
                enMain.consume(1, player);
                relever(player);
            } else sp.sendSystemMessage(Component.literal(prenom + " est à terre : donne-lui à manger ou une potion de soin (clic droit avec)."));
            return InteractionResult.CONSUME;
        }
        if (mode == Mode.CHOMEUR) {
            PacketDistributor.sendToPlayer(sp, new Reseau.Fiche(getId(), fiche(true)));
            return InteractionResult.CONSUME;
        }
        if (!player.getUUID().equals(chef)) {
            sp.sendSystemMessage(Component.literal(prenom + " travaille pour " + chefNom + "."));
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && !enMain.isEmpty()) {
            ranger(enMain.copy());
            enMain.setCount(0);
            dire("Merci chef !");
            return InteractionResult.CONSUME;
        }
        PacketDistributor.sendToPlayer(sp, new Reseau.Fiche(getId(), fiche(false)));
        return InteractionResult.CONSUME;
    }

    static boolean soigne(ItemStack s) {
        if (s.getFoodProperties(null) != null) return true;
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return false;
        for (var e : pc.getAllEffects()) if (e.getEffect().value() == MobEffects.HEAL.value()) return true;
        return false;
    }

    /** Les informations montrées au joueur dans la fiche ou l'entretien. */
    CompoundTag fiche(boolean entretien) {
        CompoundTag t = new CompoundTag();
        t.putBoolean("entretien", entretien);
        t.putString("nom", prenom);
        t.putString("metier", Lua.nomMetier(metier));
        t.putString("description", Lua.descriptionMetier(metier));
        t.putInt("niveau", niveau);
        t.putString("salaire", Reglages.texte(Reglages.salaire(niveau)));
        t.putString("prime", Reglages.texte(Reglages.prime(niveau)));
        t.putString("periode", Reglages.actuel.minutes_entre_paies % 1440 == 0
                ? (Reglages.actuel.minutes_entre_paies == 1440 ? "par jour" : "tous les " + Reglages.actuel.minutes_entre_paies / 1440 + " jours")
                : "toutes les " + Reglages.actuel.minutes_entre_paies + " minutes");
        t.putString("attitude", attitude());
        t.putString("mode", switch (mode) {
            case CHOMEUR -> "Cherche du travail";
            case SUIVRE -> "Te suit";
            case ATTENDRE -> "Attend";
            case TRAVAIL -> "Au travail";
            case GREVE -> "En grève";
        });
        t.putInt("faim", faim);
        t.putInt("vie", (int) Math.ceil(getHealth()));
        t.putInt("vieMax", (int) getMaxHealth());
        t.putInt("impayes", impayes);
        t.putString("besoin", besoin);
        t.putString("action", action != null ? action.nom : "");
        return t;
    }

    String attitude() {
        String[] phrases = switch (temperament) {
            case "peureux" -> new String[]{"Il sursaute au moindre bruit.", "Il jette des coups d'œil inquiets autour de lui."};
            case "fourbe" -> new String[]{"Il a le regard fuyant.", "Il sourit un peu trop."};
            case "colerique" -> new String[]{"Il serre les poings en parlant.", "Il a la mâchoire crispée."};
            default -> new String[]{"Il a l'air posé.", "Il parle calmement."};
        };
        return phrases[Math.floorMod(getUUID().hashCode(), phrases.length)];
    }

    /** Ordres reçus depuis la fiche ou l'entretien. */
    void commande(ServerPlayer p, String ordre) {
        if (aTerre || p.distanceToSqr(this) > 64) return;
        if (ordre.equals("embaucher")) { embaucher(p); return; }
        if (!p.getUUID().equals(chef)) return;
        switch (ordre) {
            case "suivre" -> { mode = Mode.SUIVRE; finirAction("ordre"); dire("Je te suis, chef."); }
            case "attendre" -> { mode = Mode.ATTENDRE; finirAction("ordre"); dire("J'attends ici."); }
            case "travailler" -> {
                mode = Mode.TRAVAIL;
                lieu = blockPosition();
                direction = p.getDirection();
                finirAction("ordre");
                dire("Compris, je travaille ici.");
            }
            case "inventaire" -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, inventaire),
                    Component.literal("Sac de " + prenom)));
            case "licencier" -> {
                dire("Bon… je m'en vais chercher du travail ailleurs.");
                chef = null;
                chefNom = "";
                mode = Mode.CHOMEUR;
                lieu = null;
                arrivee = System.currentTimeMillis();
                finirAction("licencie");
                libererChunk();
            }
            default -> { return; }
        }
        majNom();
    }

    void embaucher(ServerPlayer p) {
        if (mode != Mode.CHOMEUR) return;
        if (Reglages.actuel.metiers_inactifs.contains(metier) || !Lua.metierExiste(metier)) {
            p.sendSystemMessage(Component.literal("Ce métier n'est plus proposé sur le serveur."));
            return;
        }
        long nb = ColoniePnj.pnjDe(p.getServer(), p.getUUID()).size();
        if (nb >= Reglages.actuel.pnj_max_par_joueur) {
            p.sendSystemMessage(Component.literal("Tu as déjà " + nb + " PNJ : c'est le maximum (" + Reglages.actuel.pnj_max_par_joueur + ")."));
            return;
        }
        long prime = Reglages.prime(niveau);
        if (!Reglages.payer(p.getInventory(), prime, s -> { p.getInventory().add(s); return s; }, s -> p.drop(s, false))) {
            p.sendSystemMessage(Component.literal("Il te faut " + Reglages.texte(prime) + " dans ton inventaire pour la prime d'embauche."));
            return;
        }
        chef = p.getUUID();
        chefNom = p.getName().getString();
        mode = Mode.SUIVRE;
        impayes = 0;
        prochainePaie = System.currentTimeMillis() + periodePaie();
        majNom();
        dire("Marché conclu, chef ! Je te suis. Dis-moi où travailler.");
        p.sendSystemMessage(Component.literal("Tu as embauché " + prenom + " (" + Lua.nomMetier(metier) + ", " + "★".repeat(niveau)
                + ") pour " + Reglages.texte(prime) + ". Salaire : " + Reglages.texte(Reglages.salaire(niveau)) + ", à déposer dans la caisse de ton bureau.").withStyle(ChatFormatting.GREEN));
    }

    // --- Sauvegarde ------------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putString("Prenom", prenom);
        t.putString("Metier", metier);
        t.putString("Temperament", temperament);
        t.putInt("Niveau", niveau);
        t.putString("Skin", skin());
        if (chef != null) t.putUUID("Chef", chef);
        t.putString("ChefNom", chefNom);
        t.putString("Mode", mode.name());
        if (lieu != null) t.put("Lieu", NbtUtils.writeBlockPos(lieu));
        t.putString("Direction", direction.getSerializedName());
        t.putLong("ProchainePaie", prochainePaie);
        t.putLong("Arrivee", arrivee);
        t.putInt("Impayes", impayes);
        t.putString("Besoin", besoin);
        t.putInt("Faim", faim);
        t.putBoolean("ATerre", aTerre);
        t.putInt("ATerreTicks", aTerreTicks);
        t.put("Inventaire", inventaire.createTag(registryAccess()));
        t.put("Memoire", api != null ? Lua.versTag((LuaTable) api.get("memoire")) : memoireSauvee);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        prenom = t.getString("Prenom");
        metier = t.getString("Metier");
        temperament = t.getString("Temperament");
        niveau = Math.clamp(t.getInt("Niveau"), 1, 5);
        setSkin(t.getString("Skin"));
        chef = t.hasUUID("Chef") ? t.getUUID("Chef") : null;
        chefNom = t.getString("ChefNom");
        try { mode = Mode.valueOf(t.getString("Mode")); } catch (IllegalArgumentException e) { mode = Mode.CHOMEUR; }
        lieu = NbtUtils.readBlockPos(t, "Lieu").orElse(null);
        Direction d = Direction.byName(t.getString("Direction"));
        direction = d != null && d.getAxis().isHorizontal() ? d : Direction.NORTH;
        prochainePaie = t.getLong("ProchainePaie");
        arrivee = t.getLong("Arrivee");
        impayes = t.getInt("Impayes");
        besoin = t.getString("Besoin");
        faim = t.contains("Faim") ? t.getInt("Faim") : 20;
        aTerre = t.getBoolean("ATerre");
        aTerreTicks = t.getInt("ATerreTicks");
        if (aTerre) setPose(Pose.SLEEPING);
        inventaire.fromTag(t.getList("Inventaire", Tag.TAG_COMPOUND), registryAccess());
        memoireSauvee = t.getCompound("Memoire");
        api = null;
        majNom();
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    @Override
    public boolean canBeLeashed() { return false; }

    public boolean estDe(LivingEntity e) { return e instanceof Player p && p.getUUID().equals(chef); }
}
