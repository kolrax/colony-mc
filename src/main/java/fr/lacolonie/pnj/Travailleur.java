package fr.lacolonie.pnj;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Un PNJ travailleur : un corps de joueur piloté par le cerveau commun et son métier (codés dans le mod). */
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
    /** Son bureau (un PNJ par bureau) : sans bureau, il ne travaille pas. Autour, son espace de vie. */
    public BlockPos bureau;
    public static final int ESPACE_DE_VIE = 50;
    /**
     * Il travaille où il veut, tant qu'il rentre à temps dans son espace de vie : le soir s'il n'a pas de lit,
     * au bout de {@link #joursExpedition} jours s'il en a un (son lit : à 8 blocs du bureau au plus).
     */
    public BlockPos lit;
    static final int RAYON_LIT = 8;
    BlockPos litRefuse;
    int prochaineRechercheLit;
    public int joursExpedition = 3;
    /** Moment (temps du jeu, en ticks) où il est sorti de son espace de vie ; -1 s'il y est. */
    public long sortieDepuis = -1;
    /** Parti en expédition (il l'a décidé lui-même : ce qu'il cherche est trop loin pour une sortie à la journée). */
    public boolean expedition;
    /** Le chemin de l'aller, un point tous les 20 blocs environ : pour rentrer par un chemin qu'il sait praticable. */
    public final List<BlockPos> trace = new ArrayList<>();
    /** Ce qu'il parcourt en une heure du jeu, compté large (il marche à 4 blocs par seconde sur le plat). */
    static final double BLOCS_PAR_HEURE = 120;
    /** Heures de travail, en heures du jeu (0 à 24). */
    public int debut = 6, fin = 18;
    /** Salaire fixé par le chef ; -1 = ce qu'il demande. */
    public long salaire = -1;
    /** Commande en cours : identifiant (catalogue du métier), « stock » ou « quantite », nombre, et ce qui est déjà livré. */
    public String commande = "", commandeMode = "stock";
    public int commandeNombre = 64, commandeLivre;
    /** Profondeur imposée (tests) ; Integer.MIN_VALUE = celle du catalogue. */
    public int profondeurForcee = Integer.MIN_VALUE;
    public int fatigue;
    int fatigueCompteur;
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
    /** Sa mémoire de travail (où en est sa mine, les coins qu'il connaît…), sauvegardée avec lui. */
    public CompoundTag memoire = new CompoundTag();
    /** Où en est son trajet (pour le panneau) : chemin direct, étape, sort de sous terre, creuse, attend le terrain, coincé. */
    public String diag = "";
    int pauseCerveau;
    ChunkPos chunkForce;

    public Travailleur(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(true);
        // Il nage comme un joueur : l'eau n'est pas un mur, il la traverse quand c'est plus court.
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 2.0f);
        setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER_BORDER, 0.0f);
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
        goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.AvoidEntityGoal<>(this, net.minecraft.world.entity.monster.Monster.class, 10, 0.9, 1.25) {
            @Override
            public boolean canUse() { return mode == Mode.CHOMEUR && !aTerre && super.canUse(); }
        });
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

    /** Vitesse de travail : 0,8 à ★, 1,6 à ★★★★★, réduite par la fatigue ou une paie trop basse. */
    public double facteurTravail() { return (0.6 + 0.2 * niveau) * facteurPaie() * (fatigue >= 90 ? 0.5 : fatigue >= 70 ? 0.75 : 1); }

    /** Ce qu'il demande pour son niveau. */
    public long salaireDemande() { return Reglages.salaire(niveau); }

    /** Ce qu'on lui verse vraiment (fixé dans le bureau). */
    public long salaireVerse() { return salaire < 0 ? salaireDemande() : salaire; }

    /** Payé moitié moins, il travaille à 75 % ; payé plus, jusqu'à 115 %. */
    public double facteurPaie() {
        long d = salaireDemande();
        if (d <= 0) return 1;
        double r = (double) salaireVerse() / d;
        return Math.clamp(0.5 + 0.5 * r, 0.5, 1.15);
    }

    /** Vrai pendant ses heures de travail (heures du jeu). */
    public boolean enService() {
        int h = (int) ((level().getDayTime() + 6000) % 24000 / 1000); // 0 tick = 6 h du matin
        if (debut == fin) return true; // même heure de début et de fin : toute la journée
        return debut < fin ? h >= debut && h < fin : h >= debut || h < fin;
    }

    /** L'objet que produit sa commande en cours. */
    public Actions.Filtre objetCommande() { return Metier.objetCommande(this); }

    // --- Espace de vie, lit, sorties et expéditions -------------------------------------------------

    /** Vrai si ce point est dans son espace de vie (50 blocs autour du bureau, à l'horizontale). */
    public boolean chezLui(BlockPos p) {
        if (bureau == null) return false;
        double dx = p.getX() - bureau.getX(), dz = p.getZ() - bureau.getZ();
        return dx * dx + dz * dz <= ESPACE_DE_VIE * ESPACE_DE_VIE;
    }

    /** Heures du jeu qu'il lui faut pour marcher de a à b. */
    static double heuresDeMarche(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return (Math.sqrt(dx * dx + dz * dz) + 1.5 * Math.abs(a.getY() - b.getY())) / BLOCS_PAR_HEURE;
    }

    /** Heures du jeu avant la fin de son service (0 s'il n'est pas en service, 24 s'il travaille toute la journée). */
    double heuresAvantFinService() {
        if (!enService()) return 0;
        if (debut == fin) return 24;
        double reste = fin - ((level().getDayTime() + 6000) % 24000) / 1000.0;
        return reste > 0 ? reste : reste + 24;
    }

    /** Heures du jeu avant lesquelles il doit être rentré : la fin de sa journée sans lit (ou l'épuisement), la fin de l'expédition avec un lit. */
    public double heuresRestantes() {
        if (bureau == null) return 0;
        if (expedition)
            return joursExpedition * 24.0 - (sortieDepuis < 0 ? 0 : (level().getGameTime() - sortieDepuis) / 1000.0);
        return Math.min(heuresAvantFinService(), (100 - fatigue) / 5.0); // la fatigue monte de 5 par heure de travail
    }

    /** Vrai s'il doit prendre le chemin du retour maintenant pour rentrer à temps. */
    public boolean doitRentrer() {
        return bureau != null && !chezLui(blockPosition()) && heuresDeMarche(blockPosition(), bureau) + 0.5 >= heuresRestantes();
    }

    /** Vrai s'il peut aller là, y travailler un peu, et rentrer à temps. Toujours vrai dans son espace de vie. */
    public boolean peutAller(BlockPos p) {
        if (bureau == null) return false;
        return chezLui(p) || heuresDeMarche(blockPosition(), p) + heuresDeMarche(p, bureau) + 1 <= heuresRestantes();
    }

    /** Jusqu'où il pourra aller au plus loin (une journée de marche aller-retour sans lit, l'expédition entière avec). */
    public double porteeMax() {
        return Math.max(ESPACE_DE_VIE, (Math.max(joursExpedition * 24.0, heuresJourneeComplete()) - 1) * BLOCS_PAR_HEURE / 2);
    }

    /** Les heures d'une journée de travail entière (la fatigue l'arrête au bout de 20 heures). */
    public double heuresJourneeComplete() { return Math.min(debut == fin ? 24 : Math.floorMod(fin - debut, 24), 20); }

    public double distanceAuBureau() {
        if (bureau == null) return 0;
        double dx = getX() - bureau.getX(), dz = getZ() - bureau.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ce qu'on voit au bureau : à terre, en grève, en expédition, se repose, au travail… */
    public String statut() {
        if (aTerre) return "À terre";
        return switch (mode) {
            case CHOMEUR -> "Cherche du travail";
            case GREVE -> "En grève";
            case SUIVRE -> "Te suit";
            case ATTENDRE -> "Attend";
            case TRAVAIL -> bureau == null ? "Sans bureau" : expedition ? "En expédition"
                    : action instanceof Actions.Reposer || !enService() ? "Se repose" : "Au travail";
        };
    }

    /** Son lit : le lit le plus proche du bureau (8 blocs au plus), qu'il garde pour lui. Null s'il n'en a pas. */
    public BlockPos monLit() {
        if (bureau == null) return lit = null;
        if (lit != null) {
            if (!level().isLoaded(lit)) return lit; // trop loin pour vérifier : on le croit sur parole
            if (estUnLit(lit) && presDuBureau(lit)) return lit;
            lit = null; // cassé, ou le bureau a changé
        }
        if (tickCount < prochaineRechercheLit || level().getServer() == null || !level().isLoaded(bureau)) return null;
        prochaineRechercheLit = tickCount + 200;
        java.util.Set<BlockPos> pris = new java.util.HashSet<>();
        for (Travailleur x : ColoniePnj.tous(level().getServer())) if (x != this && x.lit != null) pris.add(x.lit);
        for (ServerPlayer p : level().getServer().getPlayerList().getPlayers()) // le lit où un joueur réapparaît est à lui
            if (p.getRespawnPosition() != null && p.getRespawnDimension() == level().dimension()) pris.add(p.getRespawnPosition());
        BlockPos meilleur = null;
        for (BlockPos q : BlockPos.betweenClosed(bureau.offset(-RAYON_LIT, -4, -RAYON_LIT), bureau.offset(RAYON_LIT, 4, RAYON_LIT)))
            if (level().isLoaded(q) && estUnLit(q) && !pris.contains(q) && !q.equals(litRefuse)
                    && (meilleur == null || q.distSqr(bureau) < meilleur.distSqr(bureau))) meilleur = q.immutable();
        return lit = meilleur;
    }

    boolean presDuBureau(BlockPos p) {
        return Math.abs(p.getX() - bureau.getX()) <= RAYON_LIT && Math.abs(p.getY() - bureau.getY()) <= 4 && Math.abs(p.getZ() - bureau.getZ()) <= RAYON_LIT;
    }

    boolean estUnLit(BlockPos p) {
        BlockState st = level().getBlockState(p);
        return st.getBlock() instanceof BedBlock && st.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    /** Un joueur clique sur un lit où dort un PNJ : le PNJ se lève et le lui laisse (les joueurs passent avant). */
    static void cederLit(Level lvl, BlockPos pos) {
        BlockState st = lvl.getBlockState(pos);
        if (!(st.getBlock() instanceof BedBlock)) return;
        BlockPos tete = st.getValue(BedBlock.PART) == BedPart.HEAD ? pos : pos.relative(st.getValue(BedBlock.FACING));
        for (Travailleur t : lvl.getEntitiesOfClass(Travailleur.class, new AABB(tete).inflate(3), x -> tete.equals(x.getSleepingPos().orElse(null)))) {
            t.stopSleeping();
            t.finirAction("ordre");
            t.lit = null;
            t.litRefuse = tete;
            t.dire("Oh, pardon ! C'est ton lit : je t'en laisse.");
        }
    }

    /** Depuis quand il est parti de son espace de vie (« parti depuis 2 h », « parti depuis 1,5 j »), ou rien s'il y est. */
    public String sortieTexte() {
        if (bureau == null || sortieDepuis < 0) return "";
        double h = (level().getGameTime() - sortieDepuis) / 1000.0;
        return "parti depuis " + (h < 1 ? "moins d'1 h" : h < 24 ? (int) h + " h" : String.valueOf(Math.round(h / 2.4) / 10.0).replace('.', ',').replace(",0", "") + " j");
    }

    public String skin() { return entityData.get(SKIN); }
    public void setSkin(String pseudo) { entityData.set(SKIN, pseudo == null ? "" : pseudo.trim()); }

    public void majNom() {
        String suffixe = switch (mode) {
            case CHOMEUR -> " · cherche du travail";
            case GREVE -> " · en grève";
            default -> " (" + Metier.nom(metier) + ")";
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
        else if ((tickCount + getId()) % 10 == 0) Cerveau.penser(this);
    }

    void finirAction(String r) {
        if (action != null) action.fin(this);
        action = null;
        resultat = r;
    }

    void tickCorps() {
        // Faim : un point toutes les 6 minutes au travail, deux fois moins vite sinon.
        faimCompteur += (mode == Mode.TRAVAIL && action != null) ? (isSprinting() ? 3 : 2) : 1; // courir creuse l'appétit
        if (faimCompteur >= 6 * 60 * 20 * 2) { faimCompteur = 0; faim = Math.max(0, faim - 1); }
        if (++soinCompteur >= 200) {
            soinCompteur = 0;
            if (faim >= 15 && getHealth() < getMaxHealth()) heal(1);
            else if (faim == 0 && getHealth() > 1) hurt(damageSources().starve(), 1);
        }
        if (++fatigueCompteur >= 1000) { // une heure du jeu
            fatigueCompteur = 0;
            // Une expédition fatigue : on y travaille plus dur, et on récupère mal en dormant dehors.
            boolean dehors = expedition && sortieDepuis >= 0;
            if (action instanceof Actions.Reposer || mode == Mode.ATTENDRE || mode == Mode.GREVE) fatigue -= isSleeping() ? 12 : dehors ? 5 : 8;
            else if (mode == Mode.TRAVAIL && action != null) fatigue += dehors ? 7 : 5;
            else fatigue -= 2;
            fatigue = Math.clamp(fatigue, 0, 100);
        }
        if (tickCount % 10 == 0) ramasserAutour();
        if (tickCount % 100 == 0) equiperArmure();
        if (tickCount % 20 == 0) {
            if (bureau == null || chezLui(blockPosition())) {
                if (expedition && sortieDepuis >= 0) { direAuChef("Me voilà de retour d'expédition, chef."); expedition = false; }
                if (expedition && !(action instanceof Actions.Aller)) expedition = false; // décidée, mais il n'est pas parti
                sortieDepuis = -1;
                trace.clear();
            } else {
                if (sortieDepuis < 0) sortieDepuis = level().getGameTime();
                // La trace de l'aller : un point tous les 20 blocs, là où il a pu marcher, seulement en s'éloignant de chez lui
                // (au retour, il la suit à l'envers : un point noté en revenant serait derrière lui).
                // Elle commence en sortant de l'espace de vie (pas n'importe où : il ne la suivrait pas pour rien).
                boolean depart = trace.isEmpty() && blockPosition().distSqr(bureau) <= (ESPACE_DE_VIE + 24) * (ESPACE_DE_VIE + 24);
                if ((onGround() || isInWater()) && (depart || !trace.isEmpty() && trace.get(trace.size() - 1).distSqr(blockPosition()) >= 400
                        && blockPosition().distSqr(bureau) > trace.get(trace.size() - 1).distSqr(bureau))) {
                    trace.add(blockPosition());
                    if (trace.size() > 300) for (int i = trace.size() - 2; i > 0; i -= 2) trace.remove(i); // un point sur deux au-delà
                }
            }
        }
        // Parti loin, il n'a pas pu passer à la caisse : il touchera sa paie à son retour.
        if (mode != Mode.CHOMEUR && mode != Mode.GREVE && chef != null && sortieDepuis < 0 && paieEnRetard()) {
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
    public long du() { return salaireVerse() * (impayes + (paieDue() ? 1 : 0)); }

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
            mode = bureau != null ? Mode.TRAVAIL : Mode.SUIVRE;
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
        direAuChef("Je suis à terre en " + blockPosition().toShortString() + " ! Il me reste 2 minutes : clic droit sur moi (ou tout près) avec à manger ou une potion de soin.");
        dire("Je suis touché… aidez-moi !");
    }

    /**
     * Clic droit avec à manger ou une potion de soin à moins de 3 blocs d'un PNJ à terre, même sans le viser : on le relève.
     * (Allongé, sa zone cliquable ne fait que 0,2 bloc : Minecraft l'impose aux dormeurs, impossible à viser.)
     */
    static boolean releverAutour(Player p) {
        if (p.level().isClientSide || !soigne(p.getMainHandItem())) return false;
        Travailleur t = p.level().getEntitiesOfClass(Travailleur.class, p.getBoundingBox().inflate(3), x -> x.aTerre && x.isAlive())
                .stream().min(java.util.Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
        if (t == null) return false;
        p.getMainHandItem().consume(1, p);
        t.relever(p);
        return true;
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
        if (!level().isClientSide && chef != null) direAuChef("§c" + prenom + " est mort.");
        if (!level().isClientSide) Bureau.marquerMort(this);
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
        if (tout.isEmpty()) { direAuChef("Il n'avait rien sur lui : pas de coffre."); return; }
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
            direAuChef("Ses affaires sont dans le coffre « Affaires de " + prenom + " », en " + pos.toShortString() + ".");
        } else {
            tout.forEach(this::spawnAtLocation);
            direAuChef("Ses affaires sont tombées par terre en " + blockPosition().toShortString() + ".");
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() || reason == RemovalReason.CHANGED_DIMENSION) libererChunk();
        super.remove(reason);
    }

    /**
     * Garde actifs les 3 x 3 chunks autour de lui, même sans joueur. Un seul ne suffit pas : Minecraft fige
     * toute créature qui passe dans un chunk voisin inactif, et il ne pourrait plus déplacer ses chunks.
     */
    void forcerChunk() {
        if (!(level() instanceof ServerLevel sl)) return;
        if (chef == null) { libererChunk(); return; }
        ChunkPos c = chunkPosition();
        if (c.equals(chunkForce)) return;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ColoniePnj.TICKETS.forceChunk(sl, this, c.x + dx, c.z + dz, true, true);
        LongSet avant = ColoniePnj.CHUNKS_D_AVANT.remove(getUUID()); // ceux qu'il gardait avant le redémarrage du serveur
        if (avant != null) avant.forEach((long k) -> lacher(sl, new ChunkPos(k), c));
        if (chunkForce != null)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) lacher(sl, new ChunkPos(chunkForce.x + dx, chunkForce.z + dz), c);
        chunkForce = c;
    }

    /** Ne garde plus ce chunk, s'il n'est pas autour de {@code centre}. */
    void lacher(ServerLevel sl, ChunkPos k, ChunkPos centre) {
        if (Math.abs(k.x - centre.x) > 1 || Math.abs(k.z - centre.z) > 1) ColoniePnj.TICKETS.forceChunk(sl, this, k.x, k.z, false, true);
    }

    void libererChunk() {
        if (chunkForce != null && level() instanceof ServerLevel sl)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) ColoniePnj.TICKETS.forceChunk(sl, this, chunkForce.x + dx, chunkForce.z + dz, false, true);
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
            } else sp.sendSystemMessage(Component.literal(prenom + " est à terre : prends à manger ou une potion de soin en main, puis clic droit sur lui."));
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
        t.putString("metier", Metier.nom(metier));
        t.putString("description", Metier.description(metier));
        t.putInt("niveau", niveau);
        t.putString("salaire", Reglages.texte(entretien ? salaireDemande() : salaireVerse()));
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
                finirAction("ordre");
                dire(bureau == null ? "Il me faut un bureau pour travailler : pose-en un et rattache-moi dans son écran."
                        : "Je retourne au travail, chef. Je sais où chercher.");
            }
            case "inventaire" -> p.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, inventaire),
                    Component.literal("Sac de " + prenom)));
            case "licencier" -> {
                dire("Bon… je m'en vais chercher du travail ailleurs.");
                chef = null;
                chefNom = "";
                mode = Mode.CHOMEUR;
                lit = null;
                Bureau.liberer(this);
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
        if (Reglages.actuel.metiers_inactifs.contains(metier) || !Metier.existe(metier)) {
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
        dire("Marché conclu, chef ! Je te suis. Rattache-moi à un bureau (clic droit dessus) pour me donner du travail.");
        p.sendSystemMessage(Component.literal("Tu as embauché " + prenom + " (" + Metier.nom(metier) + ", " + "★".repeat(niveau)
                + ") pour " + Reglages.texte(prime) + ". Salaire : " + Reglages.texte(Reglages.salaire(niveau)) + ", à déposer dans la caisse de son bureau. Pose un bureau et rattache-le pour lui donner du travail.").withStyle(ChatFormatting.GREEN));
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
        if (bureau != null) t.put("Bureau", NbtUtils.writeBlockPos(bureau));
        if (lit != null) t.put("Lit", NbtUtils.writeBlockPos(lit));
        t.putInt("JoursExpedition", joursExpedition);
        t.putLong("SortieDepuis", sortieDepuis);
        t.putBoolean("Expedition", expedition);
        t.putLongArray("Trace", trace.stream().mapToLong(BlockPos::asLong).toArray());
        t.putInt("Debut", debut);
        t.putInt("Fin", fin);
        t.putLong("Salaire", salaire);
        t.putString("Commande", commande);
        t.putString("CommandeMode", commandeMode);
        t.putInt("CommandeNombre", commandeNombre);
        t.putInt("CommandeLivre", commandeLivre);
        t.putInt("Fatigue", fatigue);
        t.putLong("ProchainePaie", prochainePaie);
        t.putLong("Arrivee", arrivee);
        t.putInt("Impayes", impayes);
        t.putString("Besoin", besoin);
        t.putInt("Faim", faim);
        t.putBoolean("ATerre", aTerre);
        t.putInt("ATerreTicks", aTerreTicks);
        t.put("Inventaire", inventaire.createTag(registryAccess()));
        t.put("Etat", memoire);
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
        bureau = NbtUtils.readBlockPos(t, "Bureau").orElse(null);
        lit = NbtUtils.readBlockPos(t, "Lit").orElse(null);
        joursExpedition = t.contains("JoursExpedition") ? Math.clamp(t.getInt("JoursExpedition"), 1, 30) : 3;
        sortieDepuis = t.contains("SortieDepuis") ? t.getLong("SortieDepuis") : -1;
        expedition = t.getBoolean("Expedition");
        trace.clear();
        for (long l : t.getLongArray("Trace")) trace.add(BlockPos.of(l));
        debut = t.contains("Debut") ? t.getInt("Debut") : 6;
        fin = t.contains("Fin") ? t.getInt("Fin") : 18;
        salaire = t.contains("Salaire") ? t.getLong("Salaire") : -1;
        commande = t.getString("Commande");
        commandeMode = t.contains("CommandeMode") ? t.getString("CommandeMode") : "stock";
        commandeNombre = t.contains("CommandeNombre") ? t.getInt("CommandeNombre") : 64;
        commandeLivre = t.getInt("CommandeLivre");
        fatigue = t.getInt("Fatigue");
        prochainePaie = t.getLong("ProchainePaie");
        arrivee = t.getLong("Arrivee");
        impayes = t.getInt("Impayes");
        besoin = t.getString("Besoin");
        faim = t.contains("Faim") ? t.getInt("Faim") : 20;
        aTerre = t.getBoolean("ATerre");
        aTerreTicks = t.getInt("ATerreTicks");
        if (aTerre) setPose(Pose.SLEEPING);
        inventaire.fromTag(t.getList("Inventaire", Tag.TAG_COMPOUND), registryAccess());
        memoire = t.getCompound("Etat"); // l'ancienne mémoire des scripts (« Memoire ») n'est pas reprise
        majNom();
    }

    /**
     * Comme un joueur, il saute d'un petit à-pic (6 blocs, quelques points de vie) plutôt que de rester bloqué au bord ;
     * affaibli (affamé, blessé), il ne prend plus ce risque (3 blocs, sans dégâts).
     */
    @Override
    public int getMaxFallDistance() { return getHealth() > 10 ? 6 : 3; }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    protected boolean shouldDespawnInPeaceful() { return false; }

    @Override
    public boolean canBeLeashed() { return false; }

    public boolean estDe(LivingEntity e) { return e instanceof Player p && p.getUUID().equals(chef); }
}
