package fr.lacolonie.pnj;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
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
import net.minecraft.world.phys.Vec3;
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
    /** Le geste qu'il fait (garde-à-vous, salut, danse…), dessiné chez les joueurs ({@link Poses}) ; vide : aucun. */
    public static final EntityDataAccessor<String> GESTE = SynchedEntityData.defineId(Travailleur.class, EntityDataSerializers.STRING);

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
    static final int RAYON_LIT = 16; // un poste commun : les lits de tous ses ouvriers autour
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
    /**
     * Ses missions, dans l'ordre : il remplit la première qui ne l'est pas. Une quantité livrée est retirée ; un stock
     * reste (il reprend quand il baisse).
     */
    public final List<Mission> missions = new ArrayList<>();

    /** Une mission : un identifiant du catalogue de son métier, « stock » ou « quantite », un nombre, et ce qu'il a déjà livré. */
    public static final class Mission {
        public final String id, mode;
        public final int nombre;
        public int livre;

        public Mission(String id, String mode, int nombre) {
            this.id = id;
            this.mode = mode.equals("quantite") ? "quantite" : "stock";
            this.nombre = Math.clamp(nombre, 1, 100_000);
        }

        public boolean quantite() { return mode.equals("quantite"); }

        CompoundTag sauver() {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putString("mode", mode);
            t.putInt("nombre", nombre);
            t.putInt("livre", livre);
            return t;
        }

        static Mission lire(CompoundTag t) {
            Mission m = new Mission(t.getString("id"), t.getString("mode"), t.getInt("nombre"));
            m.livre = t.getInt("livre");
            return m;
        }
    }

    /** Une seule mission, qui remplace les autres (tests, et ancienne façon de faire). */
    public void mission(String id, String mode, int nombre) {
        missions.clear();
        if (id != null && !id.isEmpty()) missions.add(new Mission(id, mode, nombre));
    }

    /** La mission sur laquelle il travaille : la première qui n'est pas remplie, ou null. */
    public Mission missionActive() {
        for (Mission m : missions) if (!Metier.remplie(this, m)) return m;
        return null;
    }

    /** Retire les quantités livrées et les missions qui n'existent plus dans son métier ; prévient son chef. */
    void nettoyerMissions() {
        for (var it = missions.iterator(); it.hasNext(); ) {
            Mission m = it.next();
            Metier.Commande c = Metier.catalogue(metier, m.id);
            if (c == null) { it.remove(); continue; }
            if (m.quantite() && m.livre >= m.nombre) {
                it.remove();
                Mission suivante = missionActive();
                Courrier.ecrire(this, suivante != null ? "mission_finie_suite" : "mission_finie", m.nombre + " " + c.nom.toLowerCase(),
                        suivante == null ? "" : Metier.nomMission(metier, suivante));
            }
        }
    }
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
    /** Le temps qu'il passe coincé, pour le ramener au poste en dernier recours (non sauvegardé). */
    final Coince coince = new Coince();
    public String resultat = "";
    /** Sa mémoire de travail (où en est sa mine, les coins qu'il connaît…), sauvegardée avec lui. */
    public CompoundTag memoire = new CompoundTag();
    /** Où en est son trajet (pour le panneau) : chemin direct, étape, sort de sous terre, creuse, attend le terrain, coincé. */
    public String diag = "";
    /** Les lettres qu'il porte au poste (il les y pose en passant) et celles de son chef qu'il doit y lire. */
    public final List<Courrier.Lettre> lettresADeposer = new ArrayList<>(), lettresALire = new ArrayList<>();
    /** Une lettre arrivée par corbeau, qu'il est en train de lire (il s'arrête un instant). */
    Courrier.Lettre lettreEnMain;
    /** Les lettres arrivées par corbeau pendant un danger (combat, fuite, monstre tout près) : il les lira au calme. */
    final List<Courrier.Lettre> lettresAuCalme = new ArrayList<>();
    /** Depuis quand (temps du monde) des lettres attendent le calme : au bout de 60 s sans coup ni combat, il les lit. */
    long auCalmeDepuis;
    /** Il attend la réponse de l'IA (il « réfléchit ») : on ne lui en redemande pas une autre en même temps. */
    boolean reflechit;
    /**
     * Ce que le chef lui a demandé en plus de son travail (une action du répertoire : un geste, une posture, une tâche),
     * et la posture qu'il tenait avant un petit geste (il y revient ensuite). Voir {@link Consignes}.
     */
    Consignes.Consigne consigne, consigneFond;
    int lectureTicks;
    /** Hélé par un messager : il s'arrête et l'attend, sans lâcher ce qu'il faisait ({@link #ecouter}). */
    int ecouteTicks;
    net.minecraft.world.entity.Entity ecoutePar;
    /** Où ses affaires ont été mises à sa mort (pour l'avis de décès). */
    String lieuAffaires = "";
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
        // Un point visé juste au-dessus ou au-dessous de lui : Minecraft le ferait tourner sur lui-même (en toupie).
        moveControl = new net.minecraft.world.entity.ai.control.MoveControl(this) {
            @Override
            public void tick() {
                if (operation == Operation.MOVE_TO && Math.abs(wantedX - mob.getX()) < 0.15 && Math.abs(wantedZ - mob.getZ()) < 0.15) {
                    if (wantedY > mob.getY() + 0.6 && mob.onGround()) mob.getJumpControl().jump();
                    operation = Operation.WAIT;
                    mob.setZza(0);
                }
                super.tick();
            }
        };
    }

    /** Les cibles qu'il n'a pas pu atteindre (identifiant → moment jusqu'auquel il les laisse), pour ne pas s'acharner. */
    final java.util.Map<Integer, Long> inaccessibles = new java.util.HashMap<>();
    /**
     * Les blocs qu'il n'a pas pu atteindre (arbre perché, minerai derrière un mur : position → moment du monde jusqu'auquel
     * il les laisse, 30 s). Non sauvegardé. Sans ça, il les choisissait de nouveau aussitôt, et chaque essai coûte des
     * recherches de chemin qui échouent (les plus chères).
     */
    final java.util.Map<Long, Long> blocsInaccessibles = new java.util.HashMap<>();

    void blocInaccessible(BlockPos p) {
        long maintenant = level().getGameTime();
        if (blocsInaccessibles.size() >= 64) blocsInaccessibles.values().removeIf(fin -> fin <= maintenant);
        blocsInaccessibles.put(p.asLong(), maintenant + 600);
    }

    boolean blocInaccessible(long p) {
        Long fin = blocsInaccessibles.get(p);
        if (fin == null) return false;
        if (fin > level().getGameTime()) return true;
        blocsInaccessibles.remove(p);
        return false;
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
        b.define(GESTE, "");
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
        goalSelector.addGoal(9, new RandomLookAroundGoal(this) { // pas en marchant : il regarderait partout, l'air de ne pas savoir où il va
            @Override
            public boolean canUse() { return getNavigation().isDone() && !isPassenger() && super.canUse(); }
        });
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

    /** L'objet que produit sa mission en cours. */
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
            case TRAVAIL -> bureau == null ? "Sans poste" : expedition ? "En expédition"
                    : action instanceof Actions.Reposer || !enService() ? "Se repose"
                    : missions.isEmpty() && !metier.equals("bucheron") && !metier.equals("guerrier") ? "Sans mission"
                    : !missions.isEmpty() && missionActive() == null ? "Mission remplie" : "Au travail";
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

    public String geste() { return entityData.get(GESTE); }

    public void setGeste(String g) { if (!geste().equals(g)) entityData.set(GESTE, g == null ? "" : g); }

    /** On lui a demandé de se taire (plus de bulles ni de voix, jusqu'à l'heure dite). */
    public boolean muet() { return memoire.getLong("muet") > level().getGameTime(); }

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

    /** Le tick entier, mesuré pour le banc de charge ({@link Charge}). */
    @Override
    public void tick() {
        if (level().isClientSide) { super.tick(); return; }
        long debut = System.nanoTime();
        super.tick();
        Charge.ICI.pnj(System.nanoTime() - debut);
    }

    /** D'où vient la prochaine recherche de chemin (posé par {@link Itineraire} le temps de l'appel), pour le banc de charge. */
    Charge.Origine origineChemin = Charge.Origine.AUTRE;
    /** Portée réduite de la prochaine recherche (posée par {@link Itineraire} le temps de l'appel) ; 0 : la portée normale (FOLLOW_RANGE). */
    float porteeChemin;
    /** Les points d'étape qu'il n'a pas pu atteindre depuis là où il était (voir {@link Itineraire}) : clé → fin de l'oubli (temps du monde). */
    final java.util.Map<Long, Long> etapesRatees = new java.util.HashMap<>();
    /** Ses recherches de chemin (depuis son apparition, non sauvegardé), et le plus qu'il en a fait sur un même tick. */
    int recherches, recherchesPireTick;
    private int recherchesCeTick;
    private long tickRecherches = -1;

    /** Une vraie recherche de chemin vient d'être faite (pas un chemin déjà en cours rendu tel quel). */
    private void compterRecherche(Path c, long nanos) {
        long tick = level().getGameTime();
        if (tick != tickRecherches) { tickRecherches = tick; recherchesCeTick = 0; }
        recherches++;
        recherchesPireTick = Math.max(recherchesPireTick, ++recherchesCeTick);
        Charge.ICI.recherche(origineChemin, c == null || !c.canReach(), recherchesCeTick, nanos);
    }

    /** Le calcul des chemins, mesuré et compté pour le banc de charge (tous les appels passent par ici). */
    @Override
    protected PathNavigation createNavigation(Level lvl) {
        return new GroundPathNavigation(this, lvl) {
            @Override
            protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int max) {
                super.createPathFinder(max); // pose l'évaluateur de la marche (portes comprises)
                return new net.minecraft.world.level.pathfinder.PathFinder(nodeEvaluator, max) {
                    @Override
                    public Path findPath(net.minecraft.world.level.PathNavigationRegion r, Mob m, java.util.Set<BlockPos> cibles, float portee, int precision, float profondeur) {
                        long debut = System.nanoTime();
                        Path c = super.findPath(r, m, cibles, portee, precision, profondeur);
                        if (!level().isClientSide) compterRecherche(c, System.nanoTime() - debut);
                        return c;
                    }
                };
            }

            @Override
            protected Path createPath(java.util.Set<BlockPos> cibles, int decalage, boolean auDessus, int precision, float portee) {
                if (level.isClientSide) return super.createPath(cibles, decalage, auDessus, precision, portee);
                if (porteeChemin > 0) portee = Math.min(portee, porteeChemin); // une recherche courte (étapes) : région et exploration réduites
                long debut = System.nanoTime();
                try { return super.createPath(cibles, decalage, auDessus, precision, portee); }
                finally { Charge.ICI.mesurer(Charge.Partie.CHEMINS, debut); }
            }
        };
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        Charge mesure = Charge.ICI;
        long h = System.nanoTime();
        if (tickCount % 20 == 0) { forcerChunk(); h = mesure.mesurer(Charge.Partie.CHUNKS, h); }
        if (aTerre) { tickATerre(); mesure.mesurer(Charge.Partie.CORPS, h); return; }
        tickCorps();
        if (tickCount % 20 == 0) { tickCourrier(); surveillerCoince(); if (isRemoved()) return; } // ramené dans une autre dimension
        if ((tickCount + getId()) % 5 == 0 && mode != Mode.CHOMEUR) survivre();
        h = mesure.mesurer(Charge.Partie.CORPS, h);
        if (lettreEnMain != null && vientDEtreFrappe()) { garder(lettreEnMain, true); lettreEnMain = null; } // frappé : il la lira plus tard
        if (lettreEnMain != null) {
            getNavigation().stop();
            getLookControl().setLookAt(getX(), getEyeY() - 1, getZ());
            if (--lectureTicks <= 0) { Courrier.Lettre l = lettreEnMain; lettreEnMain = null; lireLettre(l); }
            return;
        }
        if (ecouteTicks > 0 && vientDEtreFrappe()) { ecouteTicks = 0; ecoutePar = null; } // frappé : il cesse d'écouter pour riposter ou fuir
        if (ecouteTicks > 0) { // un messager le hèle : il l'attend sur place, puis reprend son action là où il l'a laissée
            ecouteTicks--;
            getNavigation().stop();
            if (ecoutePar != null && ecoutePar.isAlive()) getLookControl().setLookAt(ecoutePar);
            if (ecouteTicks == 0) ecoutePar = null;
            return;
        }
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
            h = mesure.mesurer(Charge.Partie.ACTION, h);
        }
        if (pauseCerveau > 0) pauseCerveau--;
        else if ((tickCount + getId()) % 10 == 0) { Cerveau.penser(this); mesure.mesurer(Charge.Partie.CERVEAU, h); }
    }

    /**
     * Toutes les secondes : son poste de travail (le poste de son chef, où qu'il soit), son inscription à l'effectif,
     * la lettre qu'il tient à la main, et celles qu'il ne peut plus porter à pied (il est sorti de l'espace de vie).
     */
    void tickCourrier() {
        if (chef == null || !(level() instanceof ServerLevel sl)) return;
        Courrier c = Courrier.de(sl.getServer());
        if (tickCount % 200 == 0) c.inscrire(this);
        Courrier.Poste p = c.poste(chef);
        if (p != null && p.monde() == level().dimension() && !p.pos().equals(bureau)) { bureau = p.pos(); lit = null; }
        // Le danger passé, il lit ; un monstre reste en vue (une ferme à monstres, le Nether) : au bout de 60 s sans coup ni combat, aussi.
        if (!lettresAuCalme.isEmpty() && lettreEnMain == null && (!enDanger() || !menace() && level().getGameTime() - auCalmeDepuis >= 1200))
            ouvrir(lettresAuCalme.remove(0));
        if (!lettresADeposer.isEmpty() && bureau != null && distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(bureau)) < 16) {
            swing(InteractionHand.OFF_HAND); // au poste, quoi qu'il fasse : il y pose ses lettres
            Courrier.deposer(this);
        }
        if (!lettresADeposer.isEmpty() && (bureau == null || !chezLui(blockPosition()))) {
            for (Courrier.Lettre l : lettresADeposer) c.envoyerCorbeau(level(), blockPosition(), chef, l, true);
            lettresADeposer.clear();
        }
        // Une lettre à la main (dans la main gauche) quand il en porte ou en lit une.
        boolean lettre = !lettresADeposer.isEmpty() || lettreEnMain != null || action instanceof Actions.LireLettres;
        boolean tient = getOffhandItem().is(Items.PAPER);
        if (lettre && !tient && getOffhandItem().isEmpty()) setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.PAPER));
        else if (!lettre && tient) setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    /**
     * Un messager le hèle (« Odon, attends ! ») : il s'arrête {@code ticks} ticks pour l'écouter. Sans ça, quand il
     * marchait, le messager qui va aussi vite que lui ne le rattrapait jamais. Pas au combat, ni en fuite (même pour une
     * consigne du chef), ni à terre, ni tout juste frappé, ni avec un monstre à quelques pas : il resterait sans défense.
     */
    void ecouter(net.minecraft.world.entity.Entity qui, int ticks) {
        if (enDanger()) return;
        ecouteTicks = Math.max(ecouteTicks, ticks);
        ecoutePar = qui;
    }

    /** Jusqu'à quand (tickCount) ne pas chercher de refuge : il n'y en avait pas. */
    int sansRefugeJusqua;

    /** Dans la lave ou le feu, enseveli dans un bloc (il étouffe), ou à bout d'air sous l'eau. */
    boolean enPeril() {
        if (isInLava() || isInWall()) return true;
        if (level().getBlockState(blockPosition()).is(net.minecraft.tags.BlockTags.FIRE)) return true;
        return getAirSupply() < 100 && isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
    }

    /**
     * Le réflexe de survie, pour tout métier, tous les 5 ticks (quelques lectures de blocs) : en péril ({@link #enPeril}),
     * ou en feu avec de l'eau tout près, il lâche ce qu'il fait (une lettre, une écoute, son action) pour {@link Actions.Echapper}.
     * À bout d'air sans place au sec à 4 blocs, il continue sa route : la lâcher sous l'eau le noierait.
     */
    void survivre() {
        if (action instanceof Actions.Echapper e && (!e.eau || !enPeril()) || isPassenger()) return; // en route vers l'eau, il tombe dans la lave : il en sort d'abord
        // Juste en feu au milieu d'un combat (frappé, se bat, fuit) : pas d'aller-retour entre la fuite et l'eau.
        boolean peril = enPeril(), feu = !peril && isOnFire() && !isInWaterRainOrBubble()
                && !vientDEtreFrappe() && !enCombat(action) && !(action instanceof Consignes.Faire f && enCombat(f.sous));
        if (!peril && !feu) return;
        boolean grave = isInLava() || isInWall() || level().getBlockState(blockPosition()).is(net.minecraft.tags.BlockTags.FIRE);
        // Pas de refuge à portée tout à l'heure : il ne refait pas la recherche avant une seconde (sauf danger grave).
        if (!grave && tickCount < sansRefugeJusqua) return;
        Actions.Echapper e = new Actions.Echapper(feu);
        boolean refuge = e.choisir(this);
        if (!refuge) sansRefugeJusqua = tickCount + 20;
        if (!refuge && (feu || !grave)) return;
        if (lettreEnMain != null) { garder(lettreEnMain, true); lettreEnMain = null; }
        ecouteTicks = 0;
        ecoutePar = null;
        if (action != null) finirAction("danger");
        Cerveau.lancer(this, e);
    }

    /**
     * À terre, tout juste frappé, au combat ou en fuite (même pour une consigne du chef), ou en péril (lave, feu, enseveli,
     * sans air) : ni écoute d'un messager, ni lecture de lettre.
     */
    boolean menace() {
        return aTerre || vientDEtreFrappe() || enCombat(action) || action instanceof Consignes.Faire f && enCombat(f.sous)
                || action instanceof Actions.Echapper || enPeril();
    }

    /** Menacé, ou un monstre à 8 blocs qu'il voit ou qui le vise (pas celui qu'un mur sépare de lui). */
    boolean enDanger() {
        return menace() || !level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(8),
                e -> Monde.hostile(e) && (e instanceof Mob m && m.getTarget() == this || hasLineOfSight(e))).isEmpty();
    }

    static boolean enCombat(Actions.Action a) { return a instanceof Actions.Attaquer || a instanceof Actions.Fuir; }

    /** Une créature (monstre, joueur, son chef…) l'a frappé il y a moins de 10 secondes. */
    /** Le coup (moment de getLastHurtByMobTimestamp) qui a déjà interrompu son action : une seule interruption par coup. */
    int coupVu = Integer.MIN_VALUE;

    boolean vientDEtreFrappe() { return getLastHurtByMob() != null && Monde.recent(this, getLastHurtByMobTimestamp()); }

    /**
     * Un corbeau lui apporte une lettre de son chef : il s'arrête pour la lire. Pas au milieu d'un danger (il se battait
     * ou fuyait, et restait figé 3 s) ni d'une autre lecture : il la garde et la lit au calme ({@link #tickCourrier}).
     */
    void recevoirLettre(Courrier.Lettre l) {
        if (lettreEnMain != null || enDanger()) garder(l, false);
        else ouvrir(l);
    }

    /** Il la garde pour plus tard (en tête de la pile si on l'a interrompu en pleine lecture). */
    void garder(Courrier.Lettre l, boolean enTete) {
        if (lettresAuCalme.isEmpty()) auCalmeDepuis = level().getGameTime();
        lettresAuCalme.add(enTete ? 0 : lettresAuCalme.size(), l);
    }

    /** Il s'arrête 3 s pour lire la lettre. */
    void ouvrir(Courrier.Lettre l) {
        lettreEnMain = l;
        lectureTicks = 60;
        finirAction("lettre");
        setItemSlot(EquipmentSlot.OFFHAND, getOffhandItem().isEmpty() ? new ItemStack(Items.PAPER) : getOffhandItem());
    }

    /**
     * Il lit une lettre de son chef : il exécute ce qu'il en comprend et répond, avec de ses nouvelles. Avec l'IA,
     * c'est elle qui comprend et qui écrit la réponse ; sans elle (ou si elle ne répond pas), l'interpréteur.
     */
    void lireLettre(Courrier.Lettre l) {
        if (IA.conversationDisponible() && level().getServer() != null) {
            var serveur = level().getServer();
            IA.converser(this, l.texte, false).whenComplete((d, err) -> serveur.execute(() -> {
                if (!isAlive()) return;
                if (d == null || d.reponse().isEmpty()) { lireLettreSansIA(l); return; }
                appliquer(d.lecture(), null);
                IA.noter(this, "Lettre du chef", l.texte);
                IA.noter(this, "Ta réponse", d.reponse());
                Courrier.ecrireTexte(this, "Réponse à ta lettre", d.reponse(), false);
            }));
            return;
        }
        lireLettreSansIA(l);
    }

    void lireLettreSansIA(Courrier.Lettre l) {
        String compris = appliquer(Ordres.lire(l.texte, metier), null);
        Courrier.ecrire(this, "nouvelles", compris.isEmpty() ? "" : compris + " ", Messages.ou(this), activite(), Messages.etat(this));
    }

    void finirAction(String r) {
        // Arrivé où il allait : un vrai progrès. Échoué à y aller : le temps « coincé » compte, même à l'arrêt (Coince).
        if ("bloque".equals(r) || "inaccessible".equals(r) || "bureau_inaccessible".equals(r)) coince.echec(level().getGameTime());
        else if ("ok".equals(r) && action != null && action.aMarche()
                && !(action instanceof Actions.SuivreChef || action instanceof Actions.Fuir || action instanceof Actions.Reposer))
            coince.progres(getX(), getY(), getZ());
        if ("ok".equals(r) && (action instanceof Actions.Casser || action instanceof Actions.Abattre || action instanceof Actions.Poser
                || action instanceof Actions.Attaquer || action != null && action.travail)) coince.travail();
        if (action != null) action.fin(this);
        action = null;
        resultat = r;
    }

    /**
     * Toutes les secondes : le temps qu'il passe coincé ({@link Coince}). Ce temps ne compte que s'il pourrait être ramené :
     * il a un poste, il travaille (ou fait grève), sans danger, ni bateau, ni repos, ni sommeil, ne suit pas une créature
     * qui bouge, et il est à plus de 6 blocs du poste ; sinon le compte repart de zéro. Au bout de 5 minutes, il est ramené.
     */
    void surveillerCoince() {
        coince.mode(mode == Mode.ATTENDRE);
        Actions.Action a = action instanceof Consignes.Faire f && f.sous != null ? f.sous : action;
        if (bureau == null || chef == null || mode != Mode.TRAVAIL && mode != Mode.GREVE || menace() || isPassenger() || isSleeping()
                || a instanceof Actions.Reposer || a != null && a.mobile || distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 36) {
            coince.progres(getX(), getY(), getZ());
            return;
        }
        if (coince.relever(getX(), getY(), getZ(), 20, level().getGameTime(), action == null || action instanceof Actions.Attendre)) ramenerAuPoste();
    }

    /**
     * Le dernier recours (cahier 11.3, exception accordée le 11/10, la seule téléportation permise) : coincé depuis
     * longtemps, il est ramené à côté de son poste, dans sa dimension (celle du poste s'il est ailleurs). Il le dit, et le
     * poste en avertit son chef. Pas de place sûre au poste : il réessaie une minute plus tard (une lettre, une fois). Ramené
     * deux fois sans avoir travaillé entre-temps : il attend les ordres au poste au lieu de repartir se perdre.
     */
    void ramenerAuPoste() {
        if (!(level() instanceof ServerLevel ici)) return;
        Courrier.Poste poste = Courrier.de(ici.getServer()).poste(chef);
        boolean ailleurs = poste != null && poste.monde() != ici.dimension();
        ServerLevel l = ailleurs ? ici.getServer().getLevel(poste.monde()) : ici;
        BlockPos b = ailleurs ? poste.pos() : bureau;
        if (l == null || b == null) return;
        l.getChunk(b.getX() >> 4, b.getZ() >> 4); // sans chunk chargé, tout y paraît vide
        BlockPos q = Monde.placeSure(l, b);
        BlockPos avant = blockPosition();
        long maintenant = level().getGameTime();
        if (q == null) {
            coince.reporter(maintenant);
            if (!coince.sansPlaceEcrit) { coince.sansPlaceEcrit = true; Courrier.ecrire(this, "sans_place", avant.toShortString()); }
            return;
        }
        int minutes = Math.max(1, coince.ticks / 1200);
        coince.ramene(maintenant);
        if (isSleeping()) stopSleeping();
        finirAction("ramene");
        getNavigation().stop();
        trace.clear();
        etapesRatees.clear();
        Vec3 v = Vec3.atBottomCenterOf(q);
        Entity e = this;
        if (ailleurs) {
            libererChunk(); // la copie dans l'autre monde garde les siens
            e = changeDimension(new net.minecraft.world.level.portal.DimensionTransition(l, v, Vec3.ZERO, getYRot(), getXRot(),
                    net.minecraft.world.level.portal.DimensionTransition.DO_NOTHING));
        } else teleportTo(v.x, v.y, v.z);
        if (!(e instanceof Travailleur t)) return;
        t.setDeltaMovement(Vec3.ZERO);
        t.resetFallDistance();
        t.forcerChunk();
        t.dire("Me voilà de retour au poste : j'étais coincé là-bas.");
        Courrier.avis(ici.getServer(), chef, "Ouvrier ramené au poste", prenom + " était coincé en " + avant.toShortString() + " depuis "
                + minutes + (minutes > 1 ? " minutes" : " minute") + " : je l'ai ramené au poste.");
        Cerveau.journal("[" + prenom + "] coincé en " + avant.toShortString() + " depuis " + minutes + " min : ramené au poste en " + q.toShortString());
        if (t == this && coince.retours >= Coince.RETOURS_MAX) {
            if (mode != Mode.GREVE) { // un gréviste reste en grève, au poste
                mode = Mode.ATTENDRE;
                coince.attenteAuto = true;
                majNom();
                Courrier.ecrire(this, "ramene_attend", avant.toShortString());
            }
        }
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
        if (tickCount % 20 == 0) Consignes.verifierVitesse(this);
        // Il nage comme un joueur : allongé, quand il court dans l'eau profonde.
        boolean nage = isInWater() && isSprinting() && !isPassenger() && !isSleeping() && level().getFluidState(blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER);
        if (nage && getPose() == Pose.STANDING) setPose(Pose.SWIMMING);
        else if (!nage && getPose() == Pose.SWIMMING) setPose(Pose.STANDING);
        if (tickCount % 100 == 0) equiperArmure();
        if (tickCount % 20 == 0) {
            if (bureau == null || chezLui(blockPosition())) {
                if (expedition && sortieDepuis >= 0) { Courrier.ecrire(this, "retour_expedition"); expedition = false; }
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
            else Courrier.ecrire(this, "paie_retard");
        }
    }

    static long periodePaie() { return Reglages.actuel.minutes_entre_paies * 60_000L; }

    /** Vrai quand la paie est due (le PNJ va alors la chercher au bureau). */
    public boolean paieDue() { return chef != null && mode != Mode.CHOMEUR && System.currentTimeMillis() >= prochainePaie; }

    /** Une journée entière est passée sans qu'il ait pu toucher sa paie. */
    boolean paieEnRetard() { return Monnaie.paieEnRetard(System.currentTimeMillis(), prochainePaie, periodePaie()) && action == null; }

    /** Montant dû : les paies impayées, plus la paie du jour si elle est due. */
    public long du() { return salaireVerse() * (impayes + (paieDue() ? 1 : 0)); }

    void commencerGreve() {
        mode = Mode.GREVE;
        finirAction("greve");
        majNom();
        Courrier.ecrire(this, "greve", Reglages.texte(du()));
    }

    /** Appelé quand il a touché tout ce qu'on lui devait. */
    public void paye(long montant, boolean paieDuJour) {
        impayes = 0;
        if (paieDuJour) prochainePaie = System.currentTimeMillis() + periodePaie();
        if (mode == Mode.GREVE) {
            mode = bureau != null ? Mode.TRAVAIL : Mode.SUIVRE;
            majNom();
            Courrier.ecrire(this, "reprise_greve", Reglages.texte(montant));
        }
    }

    void tickATerre() {
        getNavigation().stop();
        if (--aTerreTicks <= 0) {
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
        Courrier.ecrire(this, "a_terre", blockPosition().toShortString());
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
        super.die(src);
        if (!level().isClientSide && chef != null && level() instanceof ServerLevel sl) {
            Courrier.de(sl.getServer()).marquerMort(this, Messages.date(level()));
            Courrier.avis(sl.getServer(), chef, "Avis de décès : " + prenom, prenom + " (" + Metier.nom(metier).toLowerCase() + ", " + "★".repeat(niveau)
                    + ") est mort en " + blockPosition().toShortString() + ", " + Messages.date(level()).toLowerCase() + ".\n\n" + lieuAffaires);
        }
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
        tout.removeIf(st -> st.is(Items.PAPER) && st.getCount() == 1); // la lettre qu'il tenait
        if (tout.isEmpty()) { lieuAffaires = "Il n'avait rien sur lui."; return; }
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
            lieuAffaires = "Ses affaires sont dans le coffre « Affaires de " + prenom + " », en " + pos.toShortString() + ".";
        } else {
            tout.forEach(this::spawnAtLocation);
            lieuAffaires = "Ses affaires sont tombées par terre en " + blockPosition().toShortString() + ".";
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
        if (memoire.getLong("sans_ramasser") > level().getGameTime()) return;
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

    /** Il parle : une bulle au-dessus de sa tête pour ceux qui sont près de lui (et la fenêtre de conversation). Jamais dans le chat. */
    public void dire(String texte) {
        if (!(level() instanceof ServerLevel) || muet() || texte == null || texte.isBlank()) return;
        for (Player p : level().players())
            if (p.distanceToSqr(this) < 24 * 24 && p instanceof ServerPlayer sp && sp.connection != null && sp.connection.hasChannel(Reseau.Parole.TYPE))
                PacketDistributor.sendToPlayer(sp, new Reseau.Parole(getId(), texte));
    }

    public ServerPlayer chefJoueur() {
        return chef == null || level().getServer() == null ? null : level().getServer().getPlayerList().getPlayer(chef);
    }

    /** Une ligne discrète au-dessus de la barre d'objets du joueur (pas le chat). */
    static void signaler(Player p, String texte) { p.displayClientMessage(Component.literal(texte).withStyle(ChatFormatting.GOLD), true); }

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
            } else signaler(sp, prenom + " est à terre : prends à manger ou une potion de soin en main, puis clic droit sur lui.");
            return InteractionResult.CONSUME;
        }
        if (mode == Mode.CHOMEUR) {
            PacketDistributor.sendToPlayer(sp, new Reseau.Fiche(getId(), fiche(true)));
            return InteractionResult.CONSUME;
        }
        if (!player.getUUID().equals(chef)) {
            signaler(sp, prenom + " travaille pour " + chefNom + ".");
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && !enMain.isEmpty()) {
            ranger(enMain.copy());
            enMain.setCount(0);
            dire(Messages.corps(this, "dit_merci_objet"));
            return InteractionResult.CONSUME;
        }
        getLookControl().setLookAt(player);
        PacketDistributor.sendToPlayer(sp, new Reseau.Conversation(getId(), fiche(false)));
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
        t.putString("statut", statut());
        t.putString("ou", Messages.ou(this));
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

    /** L'entretien d'embauche (le seul écran à boutons qui reste). */
    void commande(ServerPlayer p, String ordre) {
        if (aTerre || p.distanceToSqr(this) > 64) return;
        if (ordre.equals("embaucher")) embaucher(p);
    }

    /**
     * Son chef lui écrit dans la fenêtre de conversation. C'est {@link Dialogue} qui trouve à qui il parle vraiment
     * (« Raoul, viens » s'adresse à Raoul, « les gars » à tous ceux qui l'entendent) et qui fait répondre chacun.
     */
    void parler(ServerPlayer p, String texte) { Dialogue.parler(p, texte, this, false); }

    /**
     * Applique ce qu'il a compris (de vive voix si {@code enFace} n'est pas nul, sinon par lettre) et renvoie sa réponse.
     * Une lettre ne peut pas le faire suivre, attendre sur place, montrer son sac ni le renvoyer : il faut être là.
     */
    String appliquer(Ordres.Lecture l, ServerPlayer enFace) {
        Mode avant = mode;
        Actions.Action actionAvant = action;
        String reponse = appliquerOrdre(l, enFace);
        // Un ordre qui change ce qu'il fait (pas une question, pas une lettre de nouvelles) : le compte « coincé » repart de zéro.
        if (mode != avant || action != actionAvant) coince.progres(getX(), getY(), getZ());
        return reponse;
    }

    private String appliquerOrdre(Ordres.Lecture l, ServerPlayer enFace) {
        List<String> r = new ArrayList<>();
        long maintenant = level().getGameTime();
        boolean confirmation = memoire.getLong("renvoi") > maintenant;
        if (enFace != null && confirmation && l.intentions.contains(Ordres.Intention.OUI)) {
            memoire.remove("renvoi");
            String adieu = Messages.corps(this, "dit_renvoye");
            licencier();
            return adieu;
        }
        if (confirmation && l.intentions.contains(Ordres.Intention.NON)) { memoire.remove("renvoi"); return Messages.corps(this, "dit_garde"); }
        if (l.intentions.contains(Ordres.Intention.RENVOYER)) {
            if (enFace == null) return "Si tu veux te passer de moi, dis-le-moi en face, chef.";
            memoire.putLong("renvoi", maintenant + 600);
            return Messages.corps(this, "dit_confirmer_renvoi");
        }
        if (mode == Mode.CHOMEUR || chef == null) return "";
        boolean auTravail = false;
        // Un ordre de base ou une mission met fin à ce qu'on lui avait demandé (« au travail » après « garde à vous »).
        if (!l.missions.isEmpty() || l.intentions.stream().anyMatch(i -> java.util.EnumSet.of(Ordres.Intention.SUIVRE, Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE, Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER).contains(i))) Consignes.annuler(this);

        // Les missions.
        if (!l.missions.isEmpty()) {
            if (!l.ajouter) missions.clear();
            for (Ordres.Mission m : l.missions) {
                missions.removeIf(x -> x.id.equals(m.id()));
                missions.add(new Mission(m.id(), m.mode(), m.nombre()));
            }
            auTravail = true;
            r.add(Messages.corps(this, "dit_missions", String.join(", puis ", missions.stream().map(m -> Metier.nomMission(metier, m)).toList())));
        }
        l.horsMetier.forEach((met, id) -> r.add(Messages.corps(this, "dit_hors_metier", Metier.nom(metier).toLowerCase(), "« " + Metier.nomCommande(met, id) + " »")));

        // Horaires et expéditions.
        if (l.debut >= 0 || l.fin >= 0) {
            if (l.debut >= 0) debut = l.debut;
            if (l.fin >= 0) fin = l.fin;
            r.add(debut == fin ? Messages.corps(this, "dit_toute_journee") : Messages.corps(this, "dit_horaires", debut, fin));
        }
        if (l.jours > 0) {
            joursExpedition = Math.clamp(l.jours, 1, 30);
            r.add(Messages.corps(this, "dit_jours", joursExpedition));
        }

        // Les déplacements.
        boolean arreter = l.intentions.contains(Ordres.Intention.ARRETER_SUIVRE);
        if (arreter || l.intentions.contains(Ordres.Intention.TRAVAIL) && l.missions.isEmpty()) {
            auTravail = true;
            r.add(bureau == null ? Messages.corps(this, "sans_poste") : Messages.corps(this, arreter ? "dit_arrete_suivre" : "dit_travail"));
        }
        if (l.intentions.contains(Ordres.Intention.RENTRER)) {
            if (bureau == null) r.add(Messages.corps(this, "sans_poste"));
            else {
                memoire.putBoolean("rappel", true);
                if (l.intentions.contains(Ordres.Intention.ATTENDRE)) memoire.putBoolean("attendre_apres", true);
                auTravail = true;
                r.add(Messages.corps(this, "dit_rentrer"));
            }
        } else if (l.intentions.contains(Ordres.Intention.ATTENDRE)) {
            if (enFace == null) r.add("Pour que je t'attende quelque part, viens me le dire, chef.");
            else { mode = Mode.ATTENDRE; auTravail = false; finirAction("ordre"); r.add(Messages.corps(this, "dit_attendre")); }
        }
        if (l.intentions.contains(Ordres.Intention.SUIVRE)) {
            if (enFace == null) r.add("Je ne peux pas te suivre d'aussi loin, chef : viens me chercher, ou dis-moi de rentrer.");
            else { mode = Mode.SUIVRE; auTravail = false; finirAction("ordre"); r.add(Messages.corps(this, "dit_suivre")); }
        }
        if (auTravail && mode != Mode.GREVE) {
            mode = Mode.TRAVAIL;
            finirAction("ordre");
        }

        // Les actions du répertoire (gestes, postures, déplacements, tâches, paroles…).
        for (Ordres.Faire f : l.faire) {
            String dit = Consignes.ordonner(this, f, enFace, l.texte, l.rang, l.total);
            if (!dit.isEmpty()) r.add(dit);
        }

        // Son sac.
        if (l.intentions.contains(Ordres.Intention.SAC)) {
            if (enFace == null) r.add("Je te montrerai mon sac à mon retour.");
            else enFace.openMenu(new SimpleMenuProvider((id, inv, pl) -> ChestMenu.threeRows(id, inv, inventaire), Component.literal("Sac de " + prenom)));
        }

        // Les questions.
        if (l.intentions.contains(Ordres.Intention.ETAT)) r.add(Messages.etat(this));
        if (l.intentions.contains(Ordres.Intention.AVANCEMENT)) r.add(rapport());
        if (l.intentions.contains(Ordres.Intention.POURQUOI)) r.add(pourquoi());
        if (l.intentions.contains(Ordres.Intention.AIDE)) r.add(Messages.corps(this, "dit_aide"));
        if (r.isEmpty() && l.intentions.contains(Ordres.Intention.SALUT)) r.add(Messages.corps(this, "dit_salut"));
        if (l.intentions.contains(Ordres.Intention.MERCI)) r.add(Messages.corps(this, "dit_merci"));
        majNom();
        if (r.isEmpty() && !l.intentions.contains(Ordres.Intention.SAC) && l.faire.isEmpty()) return enFace == null ? "" : Messages.corps(this, "dit_pas_compris");
        return String.join(" ", r);
    }

    /** Ce qu'il est en train de faire, en une phrase. */
    String activite() {
        if (aTerre) return "Je suis à terre.";
        if (mode == Mode.GREVE) return "Je suis en grève.";
        if (mode == Mode.SUIVRE) return "Je te suis.";
        if (mode == Mode.ATTENDRE) return "J'attends là où tu m'as dit d'attendre.";
        if (consigne != null && consigne.entree() != null) return "Je fais ce que tu m'as demandé : " + consigne.entree().nom() + ".";
        Mission m = missionActive();
        String mission = m == null ? "" : " Je travaille à : " + Metier.avancementTexte(this, m) + ".";
        String geste = action == null ? "Je réfléchis à la suite." : switch (action.nom) {
            case "aller" -> expedition ? "Je suis en route, en expédition." : "Je marche vers mon prochain chantier.";
            case "casser" -> "Je mine.";
            case "abattre" -> "J'abats un arbre.";
            case "se_reposer" -> isSleeping() ? "Je dors." : sortieDepuis >= 0 ? "Je campe pour la nuit." : "Je me repose.";
            case "demander" -> "J'attends qu'on m'apporte ce qu'il me faut.";
            case "deposer" -> "Je range ce que j'ai rapporté au dépôt.";
            case "toucher_paie" -> "Je vais chercher ma paie.";
            case "attaquer" -> "Je me bats !";
            case "fuir" -> "Je fuis un danger !";
            case "poser" -> "Je pose des blocs.";
            case "ramasser" -> "Je ramasse ce qui traîne.";
            default -> "Je m'occupe.";
        };
        return geste + mission;
    }

    /** « Où en es-tu ? » */
    String rapport() {
        String ms = missions.isEmpty() ? "Je n'ai pas de mission." : "Mes missions : " + String.join(" ; ", missions.stream().map(m -> Metier.avancementTexte(this, m)).toList()) + ".";
        return "Je suis " + Messages.ou(this) + ". " + activite() + " " + ms;
    }

    /** « Pourquoi tu ne travailles pas ? » */
    String pourquoi() {
        if (aTerre) return "Je suis à terre, chef : il me faut à manger ou une potion de soin, vite.";
        if (mode == Mode.GREVE) return "Je suis en grève : il me faut " + Reglages.texte(du()) + " dans la caisse du poste.";
        if (mode == Mode.SUIVRE) return "Je te suis, comme tu me l'as demandé. Dis-moi « au travail » et j'y retourne.";
        if (mode == Mode.ATTENDRE) return "J'attends, comme tu me l'as demandé. Dis-moi « au travail » et j'y retourne.";
        if (bureau == null) return Messages.corps(this, "sans_poste");
        if (!besoin.isEmpty()) return besoin;
        if (faim <= 8) return "J'ai trop faim pour travailler.";
        if (memoire.getBoolean("epuise")) return "Je suis épuisé : je me repose avant de reprendre.";
        if (!enService()) return "Ma journée est finie : je travaille de " + debut + " h à " + fin + " h.";
        if (missions.isEmpty()) return metier.equals("bucheron") ? "Sans mission, je coupe n'importe quel bois." : Messages.corps(this, "sans_mission");
        Mission m = missionActive();
        if (m == null) {
            Mission premiere = missions.get(0);
            Metier.Commande c = Metier.catalogue(metier, premiere.id);
            return "Mes missions sont remplies : " + (c == null ? "" : "le stock de " + c.nom.toLowerCase() + " est plein (" + Math.max(0, Metier.avancement(this, premiere)) + "/" + premiere.nombre + "). ")
                    + "Je reprends dès qu'il baisse, ou donne-moi une autre mission.";
        }
        return "Mais je travaille, chef ! " + activite();
    }

    /** Renvoyé : il redevient chercheur d'emploi et quitte l'effectif. */
    void licencier() {
        if (chef != null && level() instanceof ServerLevel sl) Courrier.de(sl.getServer()).retirer(chef, getUUID());
        chef = null;
        chefNom = "";
        mode = Mode.CHOMEUR;
        lit = null;
        bureau = null;
        missions.clear();
        lettresADeposer.clear();
        lettresALire.clear();
        lettresAuCalme.clear();
        arrivee = System.currentTimeMillis();
        finirAction("licencie");
        coince.progres(getX(), getY(), getZ());
        libererChunk();
        majNom();
    }

    /**
     * Un ouvrier ★★★★★ déjà embauché par {@code chef}, en service toute la journée et sans paie, rattaché à un bureau
     * posé en {@code bureau} (tests de jeu et banc de charge).
     */
    static Travailleur auTravail(ServerLevel l, String metier, BlockPos bureau, net.minecraft.world.phys.Vec3 p, UUID chef, ItemStack outil) {
        if (!l.getBlockState(bureau).is(ColoniePnj.BUREAU.get())) l.setBlockAndUpdate(bureau, ColoniePnj.BUREAU.get().defaultBlockState());
        Travailleur t = ColoniePnj.TRAVAILLEUR.get().create(l);
        t.moveTo(p.x, p.y, p.z, -90, 0);
        t.tirerAuSort(metier, 5);
        t.mode = Mode.TRAVAIL;
        t.chef = chef;
        t.prochainePaie = Monnaie.SANS_PAIE;
        t.debut = 0;
        t.fin = 0; // de 0 h à 0 h : en service toute la journée
        if (l.getBlockEntity(bureau) instanceof Bureau.Entite b) b.proprietaire = chef;
        t.bureau = bureau;
        if (!outil.isEmpty()) t.inventaire.addItem(outil);
        l.addFreshEntity(t);
        return t;
    }

    void embaucher(ServerPlayer p) {
        if (mode != Mode.CHOMEUR) return;
        if (Reglages.actuel.metiers_inactifs.contains(metier) || !Metier.existe(metier)) {
            signaler(p, "Ce métier n'est plus proposé sur le serveur.");
            return;
        }
        long nb = ColoniePnj.pnjDe(p.getServer(), p.getUUID()).size();
        if (nb >= Reglages.actuel.pnj_max_par_joueur) {
            signaler(p, "Tu as déjà " + nb + " PNJ : c'est le maximum (" + Reglages.actuel.pnj_max_par_joueur + ").");
            return;
        }
        long prime = Reglages.prime(niveau);
        if (!Reglages.payer(p.getInventory(), prime, s -> { p.getInventory().add(s); return s; }, s -> p.drop(s, false))) {
            signaler(p, "Il te faut " + Reglages.texte(prime) + " dans ton inventaire pour la prime d'embauche.");
            return;
        }
        chef = p.getUUID();
        chefNom = p.getName().getString();
        mode = Mode.SUIVRE;
        coince.progres(getX(), getY(), getZ());
        impayes = 0;
        prochainePaie = System.currentTimeMillis() + periodePaie();
        majNom();
        if (level() instanceof ServerLevel sl) Courrier.de(sl.getServer()).inscrire(this);
        boolean poste = level() instanceof ServerLevel sl2 && Courrier.de(sl2.getServer()).poste(chef) != null;
        dire("Marché conclu, chef ! Je te suis." + (poste ? " Dis-moi « au travail » et je rejoins ton poste." : " Pose ton poste de travail (un bureau) pour me donner du travail."));
        signaler(p, "Tu as embauché " + prenom + " (" + Metier.nom(metier) + ", " + "★".repeat(niveau) + ") pour " + Reglages.texte(prime)
                + ". Salaire : " + Reglages.texte(Reglages.salaire(niveau)) + ", dans la caisse de ton poste.");
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
        ListTag ms = new ListTag();
        for (Mission m : missions) ms.add(m.sauver());
        t.put("Missions", ms);
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
        if (consigne != null) t.put("Consigne", consigne.sauver());
        if (consigneFond != null) t.put("ConsigneFond", consigneFond.sauver());
        ListTag ad = new ListTag(), al = new ListTag();
        for (Courrier.Lettre l : lettresADeposer) ad.add(l.sauver());
        for (Courrier.Lettre l : lettresALire) al.add(l.sauver());
        for (int i = lettresAuCalme.size() - 1; i >= 0; i--) al.add(0, lettresAuCalme.get(i).sauver()); // lues au poste après un redémarrage
        if (lettreEnMain != null) al.add(0, lettreEnMain.sauver());
        t.put("LettresADeposer", ad);
        t.put("LettresALire", al);
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
        missions.clear();
        if (t.contains("Missions")) for (Tag x : t.getList("Missions", Tag.TAG_COMPOUND)) missions.add(Mission.lire((CompoundTag) x));
        else if (!t.getString("Commande").isEmpty()) { // avant la 0.5.0 : une seule commande
            Mission m = new Mission(t.getString("Commande"), t.contains("CommandeMode") ? t.getString("CommandeMode") : "stock",
                    t.contains("CommandeNombre") ? t.getInt("CommandeNombre") : 64);
            m.livre = t.getInt("CommandeLivre");
            missions.add(m);
        }
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
        consigne = t.contains("Consigne") ? Consignes.Consigne.lire(t.getCompound("Consigne")) : null;
        consigneFond = t.contains("ConsigneFond") ? Consignes.Consigne.lire(t.getCompound("ConsigneFond")) : null;
        lettresADeposer.clear();
        lettresALire.clear();
        lettresAuCalme.clear(); // elles sont dans « LettresALire »
        for (Tag x : t.getList("LettresADeposer", Tag.TAG_COMPOUND)) lettresADeposer.add(Courrier.Lettre.lire((CompoundTag) x));
        for (Tag x : t.getList("LettresALire", Tag.TAG_COMPOUND)) lettresALire.add(Courrier.Lettre.lire((CompoundTag) x));
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
