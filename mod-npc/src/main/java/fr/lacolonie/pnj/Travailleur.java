package fr.lacolonie.pnj;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
    /** Sa tenue, dessinée chez les joueurs quand il n'a pas de pseudo de skin ({@link Tenues}) : son métier, ou « chomeur ». */
    public static final EntityDataAccessor<String> TENUE = SynchedEntityData.defineId(Travailleur.class, EntityDataSerializers.STRING);
    /** Sous son nom, au-dessus de sa tête : « Mineur · KOLRAX_MC », ou « Cherche du travail » ({@link Insignes#role}). */
    public static final EntityDataAccessor<String> ROLE = SynchedEntityData.defineId(Travailleur.class, EntityDataSerializers.STRING);
    /** Son icône d'état au-dessus de la tête (faim, attente, grève… {@link Insignes#etat}) ; vide : aucune. */
    public static final EntityDataAccessor<String> ETAT = SynchedEntityData.defineId(Travailleur.class, EntityDataSerializers.STRING);

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
    /** La dernière recherche de lit n'a rien trouvé (lot 5 : il dit qu'il n'a pas de lit). */
    boolean litIntrouvable;
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
    /** Le facteur de son salaire, fixé à l'embauche selon le nombre d'ouvriers qu'avait déjà son chef ({@link Cout}) ; 1 pour les anciens. */
    public double surcout = 1;
    /** Ses compétences propres (lot 13, {@link Talents}) : un ou deux talents tirés à son arrivée ; aucun pour les ouvriers d'avant. */
    public List<String> talents = List.of();
    /**
     * Son âge en années (lot 13, {@link Famille}) ; -1 : pas encore tiré. {@code ageFin} : l'âge où il s'éteindra ;
     * {@code ageLe} : le temps du jeu de son dernier anniversaire compté (0 : pas encore). Sauvegardés « Age », « AgeFin », « AgeLe ».
     */
    public double age = -1, ageFin;
    long ageLe;
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
        /** « Tous les jours » : une fois livrée, elle attend le jour suivant (jourFait : le jour du jeu où elle l'a été, -1 sinon). */
        public boolean quotidienne;
        public long jourFait = -1;

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
            if (quotidienne) { t.putBoolean("quotidienne", true); t.putLong("jourFait", jourFait); }
            return t;
        }

        static Mission lire(CompoundTag t) {
            Mission m = new Mission(t.getString("id"), t.getString("mode"), t.getInt("nombre"));
            m.livre = t.getInt("livre");
            m.quotidienne = t.getBoolean("quotidienne");
            m.jourFait = t.contains("jourFait") ? t.getLong("jourFait") : -1;
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
            if (m.quantite() && !m.quotidienne && m.livre >= m.nombre) {
                it.remove();
                memoire.putLong("fini_le", level().getGameTime()); // « travail fini » (Remarques)
                Mission suivante = missionActive();
                Courrier.ecrire(this, suivante != null ? "mission_finie_suite" : "mission_finie", m.nombre + " " + c.nom.toLowerCase(),
                        suivante == null ? "" : Metier.nomMission(metier, suivante));
            }
        }
    }
    // --- Ordres longs (cahier 8.3) -------------------------------------------------------------------

    /** Ses règles (« s'il pleut, rentre », « quand tu as fini, va aider Raoul »…), 8 au plus, sauvegardées. */
    public final List<OrdresLongs.Regle> regles = new ArrayList<>();
    /** Vrai le temps d'appliquer une règle (ce n'est pas un ordre du chef). */
    boolean parRegle;
    /** Le dernier ordre du chef (temps du jeu, non sauvegardé) : une règle qui s'arrête ne défait pas un ordre donné depuis. */
    long ordreChef;

    /** Toutes les secondes : les missions quotidiennes (livrées : elles attendent demain), puis ses règles. */
    void tickRegles() {
        long jour = level().getDayTime() / 24000;
        for (Mission m : missions) {
            if (!m.quotidienne) continue;
            if (m.livre >= m.nombre && m.jourFait < 0) {
                m.jourFait = jour;
                Metier.Commande c = Metier.catalogue(metier, m.id);
                if (c != null) Courrier.ecrire(this, "mission_jour_finie", m.nombre + " " + c.nom.toLowerCase());
            } else if (m.jourFait >= 0 && jour != m.jourFait) { m.livre = 0; m.jourFait = -1; }
        }
        if (regles.isEmpty() || chef == null || mode == Mode.CHOMEUR) return;
        long maintenant = level().getGameTime();
        int heure = (int) ((level().getDayTime() + 6000) % 24000 / 1000);
        java.util.List<LivingEntity> autour = null;
        // Un ordre du chef donné après la règle passe avant elle : il le suit, il attend où on le lui a dit, il tient une consigne
        // (garde, escorte…), il est en grève. La règle ne se déclenche pas (« quand tu as fini » attend qu'il soit libre).
        boolean bloque = mode == Mode.SUIVRE || mode == Mode.GREVE || mode == Mode.ATTENDRE && regles.stream().noneMatch(x -> x.mene)
                || consigne != null && consigne.tenue();
        for (OrdresLongs.Regle r : List.copyOf(regles)) {
            LivingEntity vu = null;
            boolean vrai;
            if (r.quand.equals("FINI")) vrai = missionActive() == null;
            else if (r.voit()) {
                if (autour == null) autour = level().getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(16), x -> x != this && x.isAlive());
                for (LivingEntity x : autour) if (vu(r.vu(), x) && hasLineOfSight(x)) { vu = x; break; }
                vrai = vu != null;
            } else vrai = OrdresLongs.vrai(r.quand, heure, level().isRaining(), level().isThundering());
            if (vrai && !r.actif) {
                // Bloqué, il prévient quand même (« si tu vois un pillard, préviens-moi » pendant sa garde), sans rien faire d'autre.
                if (bloque && !(r.voit() && r.previens())) { if (r.quand.equals("FINI")) continue; }
                else if (!r.voit() || maintenant - r.dernierAvis >= 2400) declencher(r, vu, maintenant, bloque); // un avis toutes les 2 minutes au plus
                if (r.quand.equals("FINI")) regles.remove(r); // « quand tu as fini » ne sert qu'une fois
            } else if (!vrai && r.actif && r.mene) reprendre(r);
            r.actif = vrai;
        }
    }

    /** Ce qu'il guette : un zombie, un monstre, un étranger (un joueur qui n'est pas son chef)… */
    boolean vu(String type, LivingEntity e) {
        return switch (type) {
            case "zombie" -> e instanceof net.minecraft.world.entity.monster.Zombie;
            case "squelette" -> e instanceof net.minecraft.world.entity.monster.AbstractSkeleton;
            case "creeper" -> e instanceof net.minecraft.world.entity.monster.Creeper;
            case "araignee" -> e instanceof net.minecraft.world.entity.monster.Spider;
            case "sorciere" -> e instanceof net.minecraft.world.entity.monster.Witch;
            case "enderman" -> e instanceof net.minecraft.world.entity.monster.EnderMan;
            case "pillard" -> e instanceof net.minecraft.world.entity.monster.AbstractIllager;
            case "monstre" -> e instanceof net.minecraft.world.entity.monster.Enemy;
            case "etranger" -> e instanceof Player p && !p.getUUID().equals(chef) && !p.isSpectator();
            default -> false;
        };
    }

    /** La règle se déclenche : il prévient son chef, ou fait ce qu'elle dit (comme un ordre reçu par lettre). */
    void declencher(OrdresLongs.Regle r, LivingEntity vu, long maintenant) { declencher(r, vu, maintenant, false); }

    void declencher(OrdresLongs.Regle r, LivingEntity vu, long maintenant, boolean bloque) {
        r.depuis = maintenant;
        Ordres.Lecture x = Ordres.lire(r.suite, metier);
        x.regles.clear();
        x.annulerRegles = false;
        x.ajouter = true; // une règle n'efface jamais ses autres missions
        if (r.voit()) {
            r.dernierAvis = maintenant;
            if (r.previens()) {
                String nom = OrdresLongs.NOMS_VUS.getOrDefault(r.vu(), "un monstre");
                String dit = Messages.corps(this, "vu", nom, Messages.ou(this));
                dire(dit);
                if (level() instanceof ServerLevel sl) Courrier.avis(sl.getServer(), chef, "vu", false, prenom + " a vu " + nom, prenom + " : « " + dit + " »");
                x.faire.removeIf(f -> f.id().equals("message"));
                if (bloque || x.intentions.isEmpty() && x.missions.isEmpty() && x.faire.isEmpty()) return;
            }
        }
        if (x.intentions.isEmpty() && x.missions.isEmpty() && x.faire.isEmpty() && metier.equals(OrdresLongs.metierDuVerbe(r.suite)))
            x.intentions.add(Ordres.Intention.TRAVAIL); // « le matin tu mines »
        // « S'il pleut, rentre » : il reste à l'abri au poste tant que ça dure.
        if ((r.quand.equals("PLUIE") || r.quand.equals("ORAGE")) && x.intentions.contains(Ordres.Intention.RENTRER) && !x.intentions.contains(Ordres.Intention.TRAVAIL))
            x.intentions.add(Ordres.Intention.ATTENDRE);
        String dit;
        parRegle = true;
        try { dit = appliquer(x, null); } finally { parRegle = false; }
        r.mene = r.finit() && (x.intentions.contains(Ordres.Intention.RENTRER) || x.intentions.contains(Ordres.Intention.ATTENDRE));
        if (dit != null && !dit.isBlank()) dire(dit);
    }

    /** La condition a cessé (la pluie, la nuit) : s'il attend à cause d'elle et que son chef n'a rien dit depuis, il reprend le travail. */
    void reprendre(OrdresLongs.Regle r) {
        r.mene = false;
        if (ordreChef > r.depuis || mode == Mode.GREVE) return;
        if (mode == Mode.ATTENDRE || memoire.getBoolean("rappel") || memoire.getBoolean("attendre_apres")) {
            memoire.remove("rappel");
            memoire.remove("attendre_apres");
            mode = Mode.TRAVAIL;
            finirAction("ordre");
            majNom();
            dire(OrdresLongs.fin(r) + " : je retourne au travail.");
        }
    }

    /** Ce qu'il fera quand la règle se déclenchera, à la première personne (« je rentre au poste »). */
    String quoi(OrdresLongs.Regle r) {
        if (r.voit() && r.previens()) return "je te préviens";
        Ordres.Lecture x = Ordres.lire(r.suite, metier);
        List<String> q = new ArrayList<>();
        if (x.intentions.contains(Ordres.Intention.RENTRER)) q.add((r.quand.equals("PLUIE") || r.quand.equals("ORAGE")) ? "je rentre au poste à l'abri" : "je rentre au poste");
        else if (x.intentions.contains(Ordres.Intention.ATTENDRE)) q.add("j'attends");
        if (!x.missions.isEmpty()) q.add("je m'occupe de " + String.join(" et ", x.missions.stream().map(m -> Metier.nomMission(metier, new Mission(m.id(), m.mode(), m.nombre()))).toList()));
        else if (x.intentions.contains(Ordres.Intention.TRAVAIL) || x.intentions.isEmpty() && x.faire.isEmpty()) q.add("je travaille");
        if (!x.faire.isEmpty()) q.add("je fais ce que tu m'as dit (« " + r.suite + " »)");
        return String.join(", et ", q);
    }

    /** Profondeur imposée (tests) ; Integer.MIN_VALUE = celle du catalogue. */
    public int profondeurForcee = Integer.MIN_VALUE;
    public int fatigue;
    int fatigueCompteur;
    /** Ticks de travail depuis sa dernière pause ou son dernier repos (lot 5, loisirs ; non sauvegardé). */
    int travailSansPause;
    /** Ses dernières paroles en bulle (20 au plus, non sauvegardées) : lues par les tests. */
    final java.util.ArrayDeque<String> paroles = new java.util.ArrayDeque<>();
    public long prochainePaie, arrivee;
    public int impayes;
    public String besoin = "";
    /** Le moral, de 0 à 100 ({@link Obeissance}) : content, il obéit toujours ; au plus bas, il marchande ou refuse. */
    public int moral = Obeissance.DEPART;
    /** Les primes promises au chef qui a accepté son marché : payées avec sa prochaine paie (par la caisse du poste). */
    public long prime;
    /** L'ordre qu'il a refusé ou marchandé, gardé 60 s (en mémoire seulement) : le chef peut insister ou accepter le marché. */
    Ordres.Lecture enAttente;
    long attenteFin;
    boolean attenteMarche;
    /** Avec {@link #enAttente} : la commande qu'il a proposé d'aller porter à l'ouvrier du bon métier ({@link Relais}), en attendant le « oui ». */
    Relais.Commande relais;
    /** Vrai quand sa réponse au dernier ordre vient de son obéissance (refus, marché, râle) : l'IA ne parle pas à sa place. */
    boolean reponseImposee;
    /** Sa mémoire longue : promesses, insultes, compliments, cadeaux, coups, ordres longs, marchés (NBT « Souvenirs »). */
    final Souvenirs souvenirs = new Souvenirs();
    /** Ce qu'il répond tout de suite à ce qui le marque dans la phrase du chef (le temps d'appliquer un ordre). */
    String reaction = "";
    /** La réaction dit tout (récit, phrase courte) : pas de « je n'ai pas compris » en plus. */
    boolean reactionSuffit;
    // Corps
    public int faim = 20;
    int faimCompteur, soinCompteur;
    public boolean aTerre, mortDefinitive;
    int aTerreTicks;
    /**
     * Le coup en cours ({@link #hurt}) : sa vie juste avant (-1 hors d'un coup) et sa source. La gravité se mesure à la vie
     * vraiment perdue (armure comprise), aussi dans {@link #tomberATerre} s'il le met à terre.
     */
    float vieAvantCoup = -1;
    Gravite.Source sourceCoup = Gravite.Source.SANS;
    public final SimpleContainer inventaire = new SimpleContainer(27) {
        @Override
        public boolean stillValid(Player p) { return isAlive() && p.distanceToSqr(Travailleur.this) < 64; }
    };
    // Cerveau
    public Actions.Action action;
    /** Le temps qu'il passe coincé, pour le ramener au poste en dernier recours (non sauvegardé). */
    final Coince coince = new Coince();
    /** Ses sons : le délai entre deux d'un même genre ({@link Sons}). */
    final Cadence sons = new Cadence();
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
    /** Conversations entre ouvriers ({@link Causeries}) : prochain essai, et fin du dernier combat, fuite ou péril (temps du jeu). */
    long prochaineCauserie, finDanger;
    /** Le moment (temps du jeu) de sa prochaine remarque spontanée ({@link Remarques}) ; 0 : pas encore tiré. Non sauvegardé. */
    long prochaineRemarque;

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
    /** Les monstres qu'il a fuis il y a peu : id → {distance à la fuite, jusqu'à quand il s'en souvient} (Defense.dejaFui). */
    final java.util.Map<Integer, double[]> fuis = new java.util.HashMap<>();
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
        b.define(TENUE, Tenues.CHOMEUR);
        b.define(ROLE, "");
        b.define(ETAT, "");
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
        talents = Talents.tirer(r::nextDouble);
        Famille.naitreAdulte(this, 18, 50);
        arrivee = System.currentTimeMillis();
        appliquerNiveau();
        majNom();
    }

    void appliquerNiveau() {
        Famille.appliquer(this); // la vie selon le niveau, les talents et l'âge ; la taille selon l'âge
        getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(1 + 0.5 * (niveau - 1));
        setHealth(getMaxHealth());
    }

    /** Vitesse de travail : 0,8 à ★, 1,6 à ★★★★★, réduite par la fatigue ou une paie trop basse. */
    public double facteurTravail() {
        return (0.6 + 0.2 * niveau) * Talents.vitesse(talents) * facteurPaie() * (fatigue >= 90 ? 0.5 : fatigue >= 70 ? 0.75 : 1)
                * (Famille.actives() ? Ages.vitesse(age) : 1); // apprenti à demi, vieux un peu moins (lot 13)
    }

    /** Ce qu'il demande pour son niveau, et selon la taille de la colonie à son embauche. */
    public long salaireDemande() {
        long base = Apprentissage.salaireDeBase(this);
        if (Famille.actives()) base = Math.round(base * Ages.paie(age)); // l'apprenti, la moitié ; l'enfant, rien (lot 13)
        return Cout.salaire(base, surcout);
    }

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
        int h = heure();
        if (debut == fin) return true; // même heure de début et de fin : toute la journée
        return debut < fin ? h >= debut && h < fin : h >= debut || h < fin;
    }

    /** L'heure du jeu (0 à 23 ; le tick 0 du jour est 6 h du matin). */
    int heure() { return (int) ((level().getDayTime() + 6000) % 24000 / 1000); }

    /** Vrai s'il travaille de nuit (la plupart de ses heures de service la nuit : lot 13, {@link Nuit}). */
    public boolean deNuit() { return Nuit.deNuit(debut, fin); }

    /** Le modificateur de vie max des nuits d'affilée (lot 13). */
    static final ResourceLocation NUITS = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "nuits");

    /**
     * Chaque heure du jeu (depuis {@link #tickCorps}) : les heures travaillées la nuit ; chaque jour à midi, une nuit de plus
     * ou un jour libre ({@link Nuit#compter}), puis la vie max d'après les nuits d'affilée.
     */
    void compterNuits(boolean heureDeNuitTravaillee) {
        if (heureDeNuitTravaillee) memoire.putInt("nuit_heures", memoire.getInt("nuit_heures") + 1);
        long jour = Math.floorDiv(level().getDayTime() + 6000 - 12000, 24000); // le jour change à midi, après la nuit
        if (!memoire.contains("nuit_jour")) memoire.putLong("nuit_jour", jour);
        else if (memoire.getLong("nuit_jour") != jour) {
            memoire.putLong("nuit_jour", jour);
            Nuit.Compte c = Nuit.compter(new Nuit.Compte(memoire.getInt("nuits"), memoire.getInt("nuits_libres")), memoire.getInt("nuit_heures"),
                    Reglages.actuel.nuit_jours_de_repos);
            memoire.putInt("nuits", c.nuits());
            memoire.putInt("nuits_libres", c.libres());
            memoire.remove("nuit_heures");
        }
        santeNuits();
    }

    /** La vie max réduite par les nuits d'affilée (−2, puis −4), rendue quand le compte retombe. */
    void santeNuits() {
        var a = getAttribute(Attributes.MAX_HEALTH);
        if (a == null) return;
        double voulu = -Nuit.vieEnMoins(memoire.getInt("nuits"), Reglages.actuel.nuit_nuits_avant_malus);
        AttributeModifier m = a.getModifier(NUITS);
        if (m != null && m.amount() == voulu) return;
        if (m != null) a.removeModifier(NUITS);
        if (voulu != 0) a.addTransientModifier(new AttributeModifier(NUITS, voulu, AttributeModifier.Operation.ADD_VALUE));
        if (getHealth() > getMaxHealth()) setHealth(getMaxHealth());
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
        if (action instanceof Deuil.Enterrement) return "À un enterrement";
        return switch (mode) {
            case CHOMEUR -> "Cherche du travail";
            case GREVE -> "En grève";
            case SUIVRE -> "Te suit";
            case ATTENDRE -> "Attend";
            case TRAVAIL -> bureau == null ? "Sans poste" : expedition ? "En expédition"
                    : action instanceof Actions.Reposer || !enService() ? (deNuit() && !Nuit.heureDeNuit(heure()) ? "Dort le jour (travaille de nuit)" : "Se repose")
                    : action instanceof Besoins.Flaner ? "En pause"
                    : action instanceof Blessures.Accourir ? "Auprès d'un blessé"
                    : action instanceof Guerisseur.Soin ? "Soigne un blessé"
                    : action instanceof Actions.Attendre && Blessures.gravite(this).auRepos() ? "Blessé, au repos"
                    : missions.isEmpty() && !metier.equals("bucheron") && !metier.equals("guerrier") && !metier.equals("guerisseur") && !metier.equals("cuisinier") && !metier.equals("transporteur") && !metier.equals("macon") ? "Sans mission"
                    : !missions.isEmpty() && missionActive() == null ? "Mission remplie" : "Au travail"; // vert à l'écran : « de nuit » est dit dans son portrait (fiche)
        };
    }

    /** Son lit : le lit le plus proche du bureau (8 blocs au plus), qu'il garde pour lui. Null s'il n'en a pas. */
    public BlockPos monLit() {
        if (bureau == null) return lit = null;
        boolean perdu = false;
        if (lit != null) {
            if (!level().isLoaded(lit)) return lit; // trop loin pour vérifier : on le croit sur parole
            if (estUnLit(lit) && presDuBureau(lit)) return lit;
            lit = null; // cassé, ou le bureau a changé
            perdu = true;
            prochaineRechercheLit = 0; // il en cherche un autre tout de suite
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
        lit = meilleur;
        litIntrouvable = lit == null;
        if (perdu && lit == null) Besoins.litPerdu(this); // son lit n'est plus là, et pas d'autre : il le dit, et ça lui pèse
        return lit;
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

    public String tenue() { return entityData.get(TENUE); }

    public String role() { return entityData.get(ROLE); }

    public String etat() { return entityData.get(ETAT); }

    /** Côté client : vu par le joueur (rien de plein entre leurs yeux), revu toutes les 10 ticks ({@link Insignes#arevoir}, {@code Client.Rendu}). */
    long vueLe = Long.MIN_VALUE;
    boolean vue;
    /** Côté client : son prénom, tiré du nom du jeu tant que celui-ci ne change pas (pas de découpe à chaque image). */
    Component nomLu;
    String prenomLu = "", roleLu = "";
    int largeurPrenom, largeurRole;

    /**
     * Ce qu'on lit au-dessus de sa tête ({@link Insignes}) : son rôle et son état. Toutes les secondes et à chaque
     * changement de nom ; le jeu n'envoie aux joueurs que ce qui a changé.
     */
    void majTete() {
        entityData.set(ROLE, Insignes.role(Famille.nomMetier(this), chefNom, mode != Mode.CHOMEUR));
        boolean peur = action instanceof Actions.Fuir || action instanceof Actions.Echapper; // en péril, survivre() lance Echapper
        boolean repos = isSleeping() || memoire.getBoolean("epuise") || fatigue >= 90;
        entityData.set(ETAT, Insignes.etat(new Insignes.Corps(aTerre, getHealth(), peur, mode == Mode.GREVE, faim,
                !besoin.isEmpty(), impayes, moral, repos)));
    }

    /** Son nom affiché et sa tenue, à rappeler à chaque changement de mode ou de métier. */
    public void majNom() {
        entityData.set(TENUE, Tenues.tenue(metier, mode != Mode.CHOMEUR));
        String suffixe = switch (mode) {
            case CHOMEUR -> " · cherche du travail";
            case GREVE -> " · en grève";
            default -> " (" + Famille.nomMetier(this) + ")" + (chefNom.isEmpty() ? "" : " · " + chefNom); // cahier 6.6 : tout le monde voit son chef
        };
        if (aTerre) suffixe = " · à terre";
        setCustomName(Component.literal(prenom + suffixe));
        majTete();
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
        if ((tickCount + getId()) % 20 == 3) majTete();
        if (aTerre) { tickATerre(); mesure.mesurer(Charge.Partie.CORPS, h); return; }
        tickCorps();
        Sons.sommeil(this);
        if (tickCount % 20 == 0) { tickCourrier(); surveillerCoince(); if (isRemoved()) return; } // ramené dans une autre dimension
        if ((tickCount + getId()) % 20 == 7 && mode != Mode.CHOMEUR) tickRegles();
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
        if ((tickCount + getId()) % 1200 == 600 && chef != null) { Famille.veiller(this); if (isDeadOrDying() || isRemoved()) return; } // l'âge, une fois par minute (lot 13)
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
            if (e != Actions.Etat.EN_COURS && action == a) {
                finirAction(e == Actions.Etat.OK ? "ok" : a.raison.isEmpty() ? "echec" : a.raison);
                if (a.ensuite != null && action == null) Cerveau.lancer(this, a.ensuite);
            }
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
        // Des ordres longs (« s'il pleut, rentre ») : l'interpréteur seul les comprend, sans dépenser l'IA.
        Ordres.Lecture seul = Ordres.lire(l.texte, metier);
        if (!seul.regles.isEmpty() || seul.annulerRegles) { lireLettreSansIA(l); return; }
        if (IA.conversationDisponible() && level().getServer() != null) {
            var serveur = level().getServer();
            IA.converser(this, l.texte, false).whenComplete((d, err) -> serveur.execute(() -> {
                if (!isAlive()) return;
                if (d == null || d.reponse().isEmpty()) { lireLettreSansIA(l); return; }
                Ordres.Lecture li = d.lecture();
                li.quotidien |= seul.quotidien;
                Relais.reprendre(li, seul); // pas son métier : il fait passer la demande (Relais)
                GardeChef.reprendre(li, seul); // ceux qui frappent le chef : l'IA ne connaît pas ces ordres
                GardeDepot.reprendre(li, seul); // le dépôt du poste non plus
                Guerre.reprendre(li, seul); // ni la guerre
                if (seul.boutique != null) li.boutique = seul.boutique; // ni la boutique
                if (seul.intentions.contains(Ordres.Intention.LIENS)) li.intentions.add(Ordres.Intention.LIENS); // ses amis : c'est le mod qui répond
                if (li.texte.isBlank()) li.texte = l.texte;
                String dit = appliquer(li, null);
                String reponse = reponseImposee ? dit : d.reponse(); // un refus ou un marché : c'est lui qui répond, pas l'IA
                IA.noter(this, "Lettre du chef", l.texte);
                IA.noter(this, "Ta réponse", reponse);
                Courrier.ecrireTexte(this, "Réponse à ta lettre", reponse, false);
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
        if (("bloque".equals(r) || "inaccessible".equals(r) || "bureau_inaccessible".equals(r)) && !(action instanceof Actions.Casser k && k.chantier))
            coince.echec(level().getGameTime());
        else if ("ok".equals(r) && action != null && action.aMarche()
                && !(action instanceof Actions.SuivreChef || action instanceof Actions.Fuir || action instanceof Actions.Reposer))
            coince.progres(getX(), getY(), getZ());
        if ("ok".equals(r) && (action instanceof Actions.Casser || action instanceof Actions.Abattre || action instanceof Actions.Poser
                || action instanceof Actions.Attaquer || action != null && action.travail)) { coince.travail(); Apprentissage.travail(this); }
        if (action instanceof Actions.Attaquer || action instanceof Actions.Fuir || action instanceof Actions.Echapper) finDanger = level().getGameTime();
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
        Courrier.avis(ici.getServer(), chef, "ramene", false, "Ouvrier ramené au poste", prenom + " était coincé en " + avant.toShortString() + " depuis "
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
        if (faimCompteur >= Talents.seuilFaim(6 * 60 * 20 * 2, talents)) {
            faimCompteur = 0;
            faim = Math.max(0, faim - 1);
            if (faim <= 6) changerMoral(Obeissance.FAIM); // la faim forte mine le moral, lentement
        }
        if (++soinCompteur >= Nuit.soin(memoire.getInt("nuits"), Reglages.actuel.nuit_nuits_avant_malus)) { // les nuits d'affilée ralentissent la guérison
            soinCompteur = 0;
            if (faim >= 15 && getHealth() < getMaxHealth()) heal(1);
            else if (faim == 0 && getHealth() > 1) hurt(damageSources().starve(), 1);
        }
        // Le temps de travail depuis sa dernière pause (Besoins.pause) ; le repos le remet à zéro.
        if (action instanceof Actions.Reposer || isSleeping()) travailSansPause = 0;
        else if (mode == Mode.TRAVAIL && action != null && !(action instanceof Actions.Attendre) && !(action instanceof Besoins.Flaner)
                && !(action instanceof Causeries.Bavarder) && !(action instanceof Actions.LireLettres) && !(action instanceof Actions.ToucherPaie)
                && !(action instanceof Actions.Fuir)) travailSansPause++;
        if (++fatigueCompteur >= 1000) { // une heure du jeu
            fatigueCompteur = 0;
            // Une expédition fatigue : on y travaille plus dur, et on récupère mal en dormant dehors.
            boolean dehors = expedition && sortieDepuis >= 0;
            // De nuit (lot 13) : le travail de nuit fatigue plus, et le sommeil de jour d'un travailleur de nuit répare moins.
            Reglages rg = Reglages.actuel;
            boolean laNuit = Nuit.heureDeNuit(heure()), travaille = false;
            if (action instanceof Actions.Reposer || mode == Mode.ATTENDRE || mode == Mode.GREVE)
                fatigue -= Nuit.repos(isSleeping() ? 12 : dehors ? 5 : 8, !laNuit && deNuit(), rg.nuit_sommeil_jour);
            else if (mode == Mode.TRAVAIL && action != null) {
                // Seul un vrai travail compte pour la nuit : pas l'attente au poste, une pause, ni le retour au poste (le chasseur de nuit).
                travaille = !(action instanceof Actions.Attendre) && !(action instanceof Besoins.Flaner) && !(action instanceof Causeries.Bavarder)
                        && !(action instanceof Actions.LireLettres) && !(action instanceof Actions.ToucherPaie)
                        && !(action instanceof Actions.Aller a && bureau != null && a.cible.distanceToSqr(Vec3.atBottomCenterOf(bureau)) < 25);
                fatigue += Talents.fatigue(Nuit.fatigue(dehors ? 7 : 5, laNuit && travaille, rg.nuit_fatigue), talents);
            }
            else fatigue -= 2;
            fatigue = Math.clamp(fatigue, 0, 100);
            compterNuits(travaille && laNuit);
            moral = Moral.repos(moral, memoire.getInt("bilan")); // avec le temps, le moral revient doucement vers 60 (pas après un mauvais bilan)
            if (action instanceof Actions.Reposer && moral < Obeissance.REPOS) changerMoral(1); // le repos le fait remonter
        }
        if (tickCount % 10 == 0) ramasserAutour();
        if (tickCount % 20 == 0) { Consignes.verifierVitesse(this); Blessures.lenteur(this); Humeur.lenteur(this); }
        // Il nage comme un joueur : allongé, quand il court dans l'eau profonde.
        boolean nage = isInWater() && isSprinting() && !isPassenger() && !isSleeping() && level().getFluidState(blockPosition().below()).is(net.minecraft.tags.FluidTags.WATER);
        if (nage && getPose() == Pose.STANDING) setPose(Pose.SWIMMING);
        else if (!nage && getPose() == Pose.SWIMMING) setPose(Pose.STANDING);
        if (tickCount % 100 == 0) equiperArmure();
        if (tickCount % 20 == 0) {
            if (bureau == null || chezLui(blockPosition())) {
                if (expedition && sortieDepuis >= 0) { Cerveau.ecrire(this, "retour", Cerveau.UN_JOUR, "retour_expedition"); expedition = false; }
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
            changerMoral(Obeissance.RETARD);
            Courrier.reputer(this, Recrutement.IMPAYE);
            prochainePaie = System.currentTimeMillis() + periodePaie();
            if (impayes >= Reglages.actuel.paies_impayees_avant_greve) commencerGreve();
            else {
                Courrier.ecrire(this, "paie_retard");
                Besoins.parler(this, impayes >= 2 ? "impaye" : "paie_retard", null); // « des jours sans paie » seulement à la 2e paie manquée
            }
        }
    }

    static long periodePaie() { return Reglages.actuel.minutes_entre_paies * 60_000L; }

    /** Vrai quand la paie est due (le PNJ va alors la chercher au bureau). */
    public boolean paieDue() { return chef != null && mode != Mode.CHOMEUR && System.currentTimeMillis() >= prochainePaie && !Famille.enfant(this); }

    /** Une journée entière est passée sans qu'il ait pu toucher sa paie. */
    boolean paieEnRetard() { return Monnaie.paieEnRetard(System.currentTimeMillis(), prochainePaie, periodePaie()) && action == null; }

    /** Montant dû : les paies impayées, plus la paie du jour (et les primes promises) si elle est due. */
    public long du() { return salaireVerse() * (impayes + (paieDue() ? 1 : 0)) + (paieDue() ? prime : 0); }

    void changerMoral(int delta) { moral = Obeissance.moral(moral, delta); }

    void commencerGreve() {
        Courrier.reputer(this, Recrutement.GREVE);
        mode = Mode.GREVE;
        finirAction("greve");
        majNom();
        Courrier.ecrire(this, "greve", Reglages.texte(du()));
    }

    /** Appelé quand il a touché tout ce qu'on lui devait. */
    public void paye(long montant, boolean paieDuJour) {
        impayes = 0;
        memoire.putLong("paie_le", jour()); // « paie_recue » des conversations
        changerMoral(Obeissance.PAIE);
        Sons.paie(this);
        if (paieDuJour) { prochainePaie = System.currentTimeMillis() + periodePaie(); prime = 0; if (level() instanceof ServerLevel sl) Courrier.de(sl.getServer()).reputerPaie(chef, jour()); }
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
        if (aTerreTicks % 20 == 0) Sons.plainte(this);
    }

    void tomberATerre() {
        // Le temps à terre avant la mort dépend de la gravité : 3 min (légère), 5 min (grave), 8 min (très grave).
        Gravite coup = vieAvantCoup < 0 ? Gravite.AUCUNE : Gravite.du(vieAvantCoup - Math.max(0, getHealth()), getMaxHealth(), sourceCoup);
        vieAvantCoup = -1;
        Gravite g = Gravite.aTerre(Blessures.gravite(this), coup);
        Blessures.noter(this, g);
        aTerre = true;
        aTerreTicks = g.ticksATerre();
        sons.retarder(Cadence.Genre.PLAINTE, level().getGameTime(), 100); // le cri de la chute d'abord, la plainte ensuite
        setHealth(1);
        setPose(Pose.SLEEPING);
        finirAction("a_terre");
        getNavigation().stop();
        for (Mob m : level().getEntitiesOfClass(Mob.class, getBoundingBox().inflate(32), m -> m.getTarget() == this)) m.setTarget(null);
        majNom();
        Courrier.ecrire(this, "a_terre", blockPosition().toShortString());
        Besoins.dire(this, "a_terre", null);
        Blessures.accourir(this); // les ouvriers de son chef, tout près, viennent autour de lui
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
        Besoins.dire(this, "releve", null);
    }

    // Les sons du corps : ceux d'un joueur (lot 8, cahier 10.7 ; le réglage « sons » rend ceux d'une créature).
    @Override
    protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource src) { return Sons.actifs() ? src.type().effects().sound() : super.getHurtSound(src); }

    @Override
    protected net.minecraft.sounds.SoundEvent getDeathSound() { return Sons.actifs() ? net.minecraft.sounds.SoundEvents.PLAYER_DEATH : super.getDeathSound(); }

    @Override
    public Fallsounds getFallSounds() {
        return Sons.actifs() ? new Fallsounds(net.minecraft.sounds.SoundEvents.PLAYER_SMALL_FALL, net.minecraft.sounds.SoundEvents.PLAYER_BIG_FALL) : super.getFallSounds();
    }

    @Override
    protected net.minecraft.sounds.SoundEvent getSwimSound() { return Sons.actifs() ? net.minecraft.sounds.SoundEvents.PLAYER_SWIM : super.getSwimSound(); }

    @Override
    protected net.minecraft.sounds.SoundEvent getSwimSplashSound() { return Sons.actifs() ? net.minecraft.sounds.SoundEvents.PLAYER_SPLASH : super.getSwimSplashSound(); }

    @Override
    protected net.minecraft.sounds.SoundEvent getSwimHighSpeedSplashSound() {
        return Sons.actifs() ? net.minecraft.sounds.SoundEvents.PLAYER_SPLASH_HIGH_SPEED : super.getSwimHighSpeedSplashSound();
    }

    @Override
    public boolean hurt(DamageSource src, float amount) {
        if (aTerre && !level().isClientSide) {
            // À terre : seuls un joueur (qui l'achève) ou la mort absolue (vide, /kill) peuvent l'atteindre.
            if (!(src.getEntity() instanceof Player) && !src.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
            mortDefinitive = true;
            if (dAutrui(src.getEntity(), chef)) memoire.putBoolean("tombe_autrui", true); // achevé par un autre joueur
        } else if (!level().isClientSide) memoire.putBoolean("tombe_autrui", dAutrui(src.getEntity(), chef)); // le dernier coup reçu debout
        // La gravité du coup (vie vraiment perdue, source) : notée s'il reste debout, aggravée s'il tombe à terre (tomberATerre).
        boolean debout = !aTerre && !level().isClientSide;
        if (debout) { vieAvantCoup = getHealth(); sourceCoup = Blessures.source(src); }
        float avant = vieAvantCoup;
        boolean touche = super.hurt(src, amount);
        if (touche && debout && !aTerre) Blessures.blesser(this, Gravite.du(avant - getHealth(), getMaxHealth(), sourceCoup));
        vieAvantCoup = -1;
        // Frappé par son chef : il s'en souvient (le moral baisse, et il le dira s'il refuse un ordre).
        if (touche && !level().isClientSide && src.getEntity() instanceof Player p && p.getUUID().equals(chef)) {
            Humeur.offense(this, Obeissance.COUP_CHEF); // des excuses pourront en effacer la moitié
            memoire.putLong("coup_chef", level().getGameTime());
            souvenirs.noter(Souvenirs.Genre.COUP, jour(), IA.heure(this), "un coup");
            if (!aTerre && !muet() && Cerveau.parfois(this, "coup_dit", 10)) Besoins.dire(this, "coup", null); // selon son caractère
        }
        return touche;
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
            // Lot 13 : une mort bête ou au travail se sait et coûte au chef ; pas celle d'un ouvrier tué par un autre joueur ou ses hommes (guerre).
            // Mort de vieillesse (lot 13) : ce n'est la faute de personne.
            boolean vieux = memoire.getBoolean("vieillesse");
            if (!vieux && !memoire.getBoolean("tombe_autrui") && !dAutrui(src.getEntity(), chef)) Courrier.reputer(this, Recrutement.MORT);
            Humeur.deuil(this); // ses compagnons du même chef en deuil
            Deuil.mort(this, tombe != null ? tombe : blockPosition()); // sa tombe, et la lettre au chef absent
            Courrier.avis(sl.getServer(), chef, "deces", true, "Avis de décès : " + prenom, prenom + " (" + Metier.nom(metier).toLowerCase() + ", " + "★".repeat(niveau)
                    + ") est mort" + (vieux ? " de vieillesse, à " + Ages.ansTexte(age) + "," : "") + " en " + blockPosition().toShortString() + ", "
                    + Messages.date(level()).toLowerCase() + ".\n\n" + lieuAffaires);
        }
    }

    /** Le coup vient d'un autre que son chef et ses gens : un autre joueur, ou l'ouvrier d'un autre chef (un ennemi en guerre). */
    static boolean dAutrui(net.minecraft.world.entity.Entity e, UUID chef) {
        if (e instanceof Player p) return !p.getUUID().equals(chef);
        return e instanceof Travailleur t && t.chef != null && !t.chef.equals(chef);
    }

    /** Le coffre de ses affaires, posé à sa mort : sa tombe ({@link Deuil}). */
    BlockPos tombe;

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
        tout.addAll(Humeur.sortirButin(this)); // ce qu'il avait volé et caché
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
            tombe = pos;
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
        if (memoire.getBoolean("demission")) return; // il rend tout au dépôt (Humeur.demissionner) : il ne se rhabille pas
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

    /**
     * Il mange ce qui va le mieux : l'aliment qui dépasse le moins sa faim (20 − faim), le plus nourrissant à égalité. Jamais un
     * aliment à effet néfaste (chair putréfiée, œil d'araignée…) ni un objet rare (pomme dorée) : on ne gaspille pas.
     */
    public boolean manger() {
        int meilleur = -1, ecart = Integer.MAX_VALUE, nourrit = 0;
        boolean change = false; // un aliment qu'il n'a pas mangé à ses 3 derniers repas passe avant (Moral : varier les repas)
        List<String> derniers = Moral.lire(memoire.getString("repas"));
        derniers = derniers.subList(Math.max(0, derniers.size() - 3), derniers.size());
        for (int i = 0; i < inventaire.getContainerSize(); i++) {
            ItemStack s = inventaire.getItem(i);
            FoodProperties fp = s.getFoodProperties(this);
            if (fp == null || fp.nutrition() <= 0 || s.getRarity() != net.minecraft.world.item.Rarity.COMMON
                    || fp.effects().stream().anyMatch(e -> e.effect().getEffect().value().getCategory() == net.minecraft.world.effect.MobEffectCategory.HARMFUL)) continue;
            int trop = Math.max(0, fp.nutrition() - (20 - faim));
            boolean neuf = !derniers.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
            if (neuf && !change || neuf == change && (trop < ecart || trop == ecart && fp.nutrition() > nourrit)) { meilleur = i; ecart = trop; nourrit = fp.nutrition(); change = neuf; }
        }
        if (meilleur < 0) return false;
        ItemStack s = inventaire.getItem(meilleur);
        FoodProperties fp = s.getFoodProperties(this);
        if (faim <= 8) changerMoral(Obeissance.MANGER); // manger enfin, ça remonte le moral
        Humeur.repas(this, s); // ses derniers repas : toujours le même, ça lasse (Moral)
        faim = Math.min(20, faim + fp.nutrition());
        eat(level(), s, fp);
        Sons.rot(this);
        inventaire.setChanged();
        return true;
    }

    // --- Paroles ---------------------------------------------------------------------------------

    /** Il parle : une bulle au-dessus de sa tête pour ceux qui sont près de lui (et la fenêtre de conversation). Jamais dans le chat. */
    public void dire(String texte) {
        if (!(level() instanceof ServerLevel) || muet() || texte == null || texte.isBlank()) return;
        paroles.addLast(texte);
        if (paroles.size() > 20) paroles.removeFirst();
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
            surcout = surcoutPour(sp); // le prix annoncé à celui qui l'aborde ; refait à l'embauche
            getLookControl().setLookAt(player);
            sePresenter(sp);
            PacketDistributor.sendToPlayer(sp, new Reseau.Fiche(getId(), fiche(true)));
            return InteractionResult.CONSUME;
        }
        if (!player.getUUID().equals(chef)) {
            // Il tient la boutique de son chef : un client peut lui parler (« qu'est-ce que tu vends ? », « je t'achète 2 pains »).
            if (Boutiquier.tient(this)) {
                getLookControl().setLookAt(player);
                Boutiquier.accueillir(this, sp);
                PacketDistributor.sendToPlayer(sp, new Reseau.Conversation(getId(), fiche(false)));
                return InteractionResult.CONSUME;
            }
            // Un autre joueur (lot 14) : il le salue et la fenêtre de conversation s'ouvre ; ce qu'il écrit va à Etranger.
            if (!player.isShiftKeyDown()) Etranger.aborder(this, sp);
            else signaler(sp, prenom + " travaille pour " + chefNom + ".");
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && !enMain.isEmpty()) {
            // De quoi travailler ou manger : il remercie. Autre chose (un diamant…) : il s'en souvient et demande quoi en faire.
            boolean utile = enMain.getFoodProperties(this) != null || enMain.isDamageableItem() || enMain.getItem() instanceof net.minecraft.world.item.BlockItem;
            ItemStack reste = inventaire.addItem(enMain.copy());
            int pris = enMain.getCount() - reste.getCount();
            if (pris == 0) { dire("Mon sac est plein, chef."); return InteractionResult.CONSUME; } // rien de pris : il le garde en main
            int piece = Reglages.piece(enMain);
            String objet = piece >= 0 ? Reglages.texte((long) pris * Reglages.actuel.monnaie.get(piece).valeur) : Souvenirs.nombre(pris, Consignes.nom(enMain));
            if (!utile) souvenirs.noter(Souvenirs.Genre.CADEAU, jour(), IA.heure(this), objet);
            enMain.setCount(reste.getCount());
            // Un présent : il remercie selon son caractère ({objet} seulement s'il y en a plusieurs : « 3 diamants », sans article à deviner).
            dire(utile ? Messages.corps(this, "dit_merci_objet") : selon("cadeau", piece >= 0 || pris > 1 ? objet : null, "souv_cadeau"));
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
        t.putString("attitude", (Famille.portrait(this) + " " + attitude() + " " + Talents.portrait(talents) + (deNuit() ? " Il travaille de nuit." : "")
                + (Nuit.vieEnMoins(memoire.getInt("nuits"), Reglages.actuel.nuit_nuits_avant_malus) > 0 ? " Il a les traits tirés." : "")).trim());
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

    /**
     * À l'entretien (lot 13) : il dit ce qu'il sait faire de mieux et ce qu'on raconte du chef ({@link Recrutement#palier}),
     * au plus une fois toutes les 30 secondes.
     */
    void sePresenter(ServerPlayer p) {
        long t = level().getGameTime();
        if (memoire.contains("presente") && t - memoire.getLong("presente") < 600) return;
        memoire.putLong("presente", t);
        String talent = talents.isEmpty() ? null : Besoins.texte(this, "talent_" + talents.get(0), null, null);
        String renom = Besoins.texte(this, Recrutement.palier(Courrier.de(p.getServer()).reputation(p.getUUID())), null, null);
        dire(talent == null ? renom : renom == null ? talent : talent + " " + renom);
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
    void parler(ServerPlayer p, String texte) {
        if (!p.getUUID().equals(chef) && Boutiquier.client(this, p, texte)) return; // un client à sa boutique (Boutiquier)
        Dialogue.parler(p, texte, this, false);
    }

    /**
     * Applique ce qu'il a compris (de vive voix si {@code enFace} n'est pas nul, sinon par lettre) et renvoie sa réponse.
     * Une lettre ne peut pas le faire suivre, attendre sur place, montrer son sac ni le renvoyer : il faut être là.
     */
    String appliquer(Ordres.Lecture l, ServerPlayer enFace) {
        if (action instanceof Causeries.Bavarder) finirAction("ordre"); // son chef lui parle : il laisse la conversation
        Mode avant = mode;
        Actions.Action actionAvant = action;
        reponseImposee = false;
        reaction = parRegle ? "" : seSouvenir(l);
        String reponse = obeir(l, enFace);
        if (!reaction.isEmpty()) reponse = reponse.isEmpty() ? reaction : reaction + " " + reponse;
        // « Tu te souviens… ? » : c'est sa mémoire qui répond, l'IA n'invente pas de promesse à sa place.
        if (!parRegle && (Souvenirs.question(l.texte) || l.intentions.contains(Ordres.Intention.LIENS))) reponseImposee = true; // ses amis aussi : l'IA n'en invente pas
        reaction = "";
        // Un ordre qui change ce qu'il fait (pas une question, pas une lettre de nouvelles) : le compte « coincé » repart de zéro.
        if (mode != avant || action != actionAvant) coince.progres(getX(), getY(), getZ());
        return reponse;
    }

    /** Le jour de jeu (il change à minuit), pour dater ses souvenirs. */
    long jour() { return (level().getDayTime() + 6000) / 24000; }

    /**
     * Ce que la phrase du chef lui laisse en mémoire ({@link Souvenirs}) et ce qu'il en dit tout de suite : vexé par une
     * insulte (moral en baisse), content d'un compliment ou d'un cadeau (moral en hausse, une fois par jour), il retient une
     * promesse. « Tu te souviens… ? » : il raconte.
     */
    private String seSouvenir(Ordres.Lecture l) {
        if (chef == null || mode == Mode.CHOMEUR || l.texte.isBlank() && l.regles.isEmpty()) return "";
        long j = jour();
        int h = IA.heure(this);
        reactionSuffit = false;
        if (Souvenirs.question(l.texte)) { reactionSuffit = true; return souvenirs.raconter(l.texte, j, h); }
        String famille = Famille.question(this, l.texte); // son âge, ses parents, ses enfants (lot 13)
        if (famille != null) { reactionSuffit = reponseImposee = true; return famille; }
        // Les ordres longs : leur phrase est retirée de l.texte (« s'il pleut, rentre au poste » ne laisse rien) ; chacun est noté.
        for (OrdresLongs.Regle x : l.regles) souvenirs.noter(Souvenirs.Genre.ORDRE, j, h, Souvenirs.CONSIGNE + OrdresLongs.quand(x) + ", " + quoi(x));
        if (!l.texte.isBlank() && (!l.missions.isEmpty() || l.jours > 0 || l.quotidien)) souvenirs.noter(Souvenirs.Genre.ORDRE, j, h, l.texte);
        List<String> r = new ArrayList<>();
        // Une phrase courte (« Tu es un bon à rien ! », « Bravo, Raoul ! ») est toute comprise ; plus longue, il dit aussi ce qu'il n'a pas compris.
        reactionSuffit = l.texte.strip().split("\\s+").length <= 8;
        // Des excuses (avant l'insulte de la même phrase, qui reste), et « tu as gardé un diamant ? » : le jeu seul sait la
        // vérité, l'IA ne répond pas à sa place.
        if (Ordres.excuses(l.texte)) {
            String x = Humeur.excuses(this);
            // Rien à pardonner : une réponse neutre, seulement si la phrase ne demandait rien d'autre (« Pardon, viens ici »).
            if (x == null && l.missions.isEmpty() && l.faire.isEmpty() && l.intentions.stream().allMatch(i -> i == Ordres.Intention.EXCUSES || i == Ordres.Intention.SALUT))
                x = Messages.corps(this, "souv_excuses_rien");
            if (x != null) { r.add(x); reponseImposee = reactionSuffit = true; }
        }
        if (Ordres.diamantCache(l.texte)) {
            // La lecture de l'IA aussi : rien à faire dans la question elle-même, seulement dans le reste de la phrase.
            Ordres.Lecture seul = Ordres.lire(l.texte, metier);
            l.missions.clear(); l.missions.addAll(seul.missions);
            l.horsMetier.clear(); l.horsMetier.putAll(seul.horsMetier);
            l.pourAutres.clear(); l.pourAutres.putAll(seul.pourAutres);
            l.faire.clear(); l.faire.addAll(seul.faire);
            if (!seul.intentions.contains(Ordres.Intention.SAC)) l.intentions.remove(Ordres.Intention.SAC);
            l.intentions.add(Ordres.Intention.DIAMANT);
            r.add(Humeur.questionDiamant(this));
            reponseImposee = reactionSuffit = true;
        }
        for (Souvenirs.Genre g : Souvenirs.lire(l.texte)) {
            if (g == Souvenirs.Genre.CADEAU && souvenirs.deja(g, j)) { /* l'objet tendu tout à l'heure est déjà noté */ }
            else souvenirs.noter(g, j, h, g == Souvenirs.Genre.CADEAU ? Souvenirs.PRESENT : l.texte);
            switch (g) {
                case INSULTE -> { Humeur.offense(this, Obeissance.INSULTE); r.add(selon("insulte", null, "souv_insulte")); }
                case COMPLIMENT -> { if (souvenirs.bonus(j)) changerMoral(Obeissance.COMPLIMENT); r.add(Messages.corps(this, "souv_compliment")); }
                case CADEAU -> { if (souvenirs.bonus(j)) changerMoral(Obeissance.COMPLIMENT); r.add(selon("cadeau", null, "souv_cadeau_content")); }
                case PROMESSE -> r.add(Messages.corps(this, "souv_promesse"));
                default -> {}
            }
        }
        return String.join(" ", r);
    }

    /** Sa réplique de vie.txt selon son caractère (complétée par caractere_<cle> de remarques.txt), sinon le message {@code repli}. */
    String selon(String cle, String objet, String repli) {
        String s = Besoins.texte(this, cle, null, objet);
        return s != null ? s : Messages.corps(this, repli);
    }

    /**
     * Le seul point où passent les ordres du chef (de vive voix, par l'IA ou par lettre) : obéit-il ? (cahier 8.4 et 8.5,
     * {@link Obeissance}). Un ordre qui le tuerait, il le refuse toujours. Selon son moral et son caractère, il obéit,
     * râle, marchande (le chef accepte : prime à la prochaine paie) ou refuse (le chef insiste : il obéit en râlant, sauf
     * au plus bas). Les règles qui se déclenchent ont déjà été acceptées ; un gréviste garde sa grève.
     */
    private String obeir(Ordres.Lecture l, ServerPlayer enFace) {
        long maintenant = level().getGameTime();
        if (mode == Mode.CHOMEUR || chef == null || l.intentions.contains(Ordres.Intention.RENVOYER) || memoire.getLong("renvoi") > maintenant)
            return appliquerOrdre(l, enFace);
        String danger = Obeissance.mortel(l.texte);
        if (danger != null) {
            reponseImposee = true;
            enAttente = null;
            return Messages.corps(this, "mortel_" + danger);
        }
        if (parRegle) return appliquerOrdre(l, enFace);
        // Un enfant ne travaille pas ; « apprends le métier de bûcheron » à un enfant ou un apprenti (Famille, lot 13).
        String petit = Famille.ordre(this, l);
        if (petit != null) {
            reponseImposee = true;
            return petit;
        }
        // « On va enterrer Bertrand » : ses compagnons proches vont à sa tombe ; nul ne refuse un dernier adieu (Deuil).
        if (l.intentions.contains(Ordres.Intention.ENTERRER)) {
            reponseImposee = true;
            return Deuil.ordonner(this, l.texte);
        }
        // « Oui » à sa proposition d'enterrement, ou « non, arrête » en pleine cérémonie (Deuil).
        String deuil = Deuil.repondre(this, l);
        if (deuil != null) {
            reponseImposee = true;
            return deuil;
        }
        // L'impossible (voler, aller sur la lune, casser la roche du fond du monde…) : il en rit, sans rien faire (cahier 14.9).
        String impossible = Impossible.demande(l.texte, Ordres.lire(l.texte, metier)); // seulement si rien n'y est faisable
        String blague = impossible == null ? null : Besoins.texte(this, "impossible_" + impossible, null, null);
        if (blague != null) {
            reponseImposee = true;
            return blague;
        }
        Ordres.Lecture avant = enAttente != null && maintenant <= attenteFin ? enAttente : null;
        enAttente = null;
        Relais.Commande rel = relais;
        relais = null;
        // « Je vais prévenir Bertrand ? » : oui, il y va ; non, il reprend ; une question entre-temps, la proposition reste.
        if (avant != null && rel != null) {
            Ordres.Lecture seul = Ordres.lire(l.texte, metier);
            if (Obeissance.sansOrdre(seul) && Relais.accepte(l.texte)) { reponseImposee = true; return prevenir(rel); }
            if (Obeissance.sansOrdre(seul) && Relais.refuse(l.texte)) { reponseImposee = true; return Messages.corps(this, "relais_non"); }
            if (Obeissance.question(seul)) { enAttente = avant; relais = rel; }
            avant = null;
        }
        if (avant != null) {
            // Une phrase qui porte son propre ordre (« oui, mais d'abord rentre au poste », « fais le guet ») est un nouvel ordre.
            // Sur les mots du chef : avec l'IA, l est sa lecture, qui reprend souvent l'ordre en suspens.
            boolean seule = Obeissance.sansOrdre(Ordres.lire(l.texte, metier));
            boolean accepte = attenteMarche && seule && Obeissance.accepte(l.texte);
            boolean insiste = !accepte && (seule && Obeissance.insiste(l.texte) || Ordres.normaliser(l.texte).equals(Ordres.normaliser(avant.texte)));
            if (accepte) {
                reponseImposee = true;
                changerMoral(Obeissance.MARCHE);
                prime += Obeissance.prime(salaireVerse());
                souvenirs.noter(Souvenirs.Genre.MARCHE, jour(), IA.heure(this), Reglages.texte(Obeissance.prime(salaireVerse())) + " de plus pour « " + avant.texte + " »");
                return Messages.corps(this, "marche") + " " + appliquerOrdre(avant, enFace);
            }
            if (seule && Obeissance.abandonne(l.texte)) { reponseImposee = true; return Messages.corps(this, "abandon"); }
            if (attenteMarche && seule && !insiste && l.intentions.contains(Ordres.Intention.NON)) {
                reponseImposee = true;
                if (temperament.equals("colerique") || temperament.equals("fourbe")) { Sons.grogne(this); return Messages.corps(this, "pas_de_marche"); }
                changerMoral(Obeissance.FORCE);
                return Messages.corps(this, "sans_marche") + " " + appliquerOrdre(avant, enFace);
            }
            if (insiste && mode != Mode.GREVE) {
                Obeissance.Ordre o = Obeissance.peine(avant, metier, !enService());
                Obeissance.Verdict premier = Obeissance.premier(moral, temperament, o);
                if (premier == Obeissance.Verdict.OBEIT) return appliquerOrdre(avant, enFace); // son moral est remonté entre-temps
                if (premier == Obeissance.Verdict.RALE) { Sons.grogne(this); return Messages.corps(this, "rale") + " " + appliquerOrdre(avant, enFace); }
                reponseImposee = true;
                Sons.grogne(this); // il refuse encore, ou obéit de force
                if (Obeissance.decider(moral, temperament, o, true) == Obeissance.Verdict.REFUSE)
                    return Messages.corps(this, "refus_ferme", Messages.remede(raisonDuRefus(maintenant)));
                changerMoral(Obeissance.FORCE);
                return Messages.corps(this, "force") + " " + appliquerOrdre(avant, enFace);
            }
        }
        Obeissance.Ordre ordre = Obeissance.peine(l, metier, !enService());
        if (avant != null && Obeissance.question(Ordres.lire(l.texte, metier))) enAttente = avant; // une simple question entre-temps : l'ordre reste en suspens
        if (mode == Mode.GREVE) return appliquerOrdre(l, enFace);
        Obeissance.Verdict v = Obeissance.decider(moral, temperament, ordre, false);
        // Un marché (une prime à la paie) ne se conclut qu'avec le chef : à un autre, il refuse (Etranger).
        if (v == Obeissance.Verdict.NEGOCIE && enFace != null && !enFace.getUUID().equals(chef)) v = Obeissance.Verdict.REFUSE;
        switch (v) {
            case RALE -> { Sons.grogne(this); return Messages.corps(this, "rale") + " " + appliquerOrdre(l, enFace); } // avec l'IA, elle râle à sa façon (elle voit son moral)
            case NEGOCIE, REFUSE -> {
                Sons.grogne(this);
                reponseImposee = true;
                enAttente = enFace == null || enFace.getUUID().equals(chef) ? l : null; // l'ordre refusé d'un autre ne reste pas en suspens
                attenteFin = maintenant + 1200;
                attenteMarche = v == Obeissance.Verdict.NEGOCIE;
                return attenteMarche ? Messages.corps(this, "negocie", Reglages.texte(Obeissance.prime(salaireVerse())))
                        : Messages.corps(this, "refus_" + raisonDuRefus(maintenant));
            }
            default -> { return appliquerOrdre(l, enFace); }
        }
    }

    /**
     * Le chef lui demande ce que son métier ne fait pas (« va me chercher 64 fers », au bûcheron) : il nomme l'ouvrier du
     * bon métier chez le même chef, le plus proche ({@link Relais}), et propose d'aller le prévenir ; la réponse est attendue
     * 60 s comme un marché ({@link #enAttente}, {@link #relais}). Par lettre, il fait passer la demande tout de suite.
     * Personne de ce métier chez le chef : il le dit. Un collègue qui a entendu la même phrase ({@code l.avec}) s'en charge
     * lui-même : rien à dire ; un collègue qui a déjà cette mission : il le dit, sans la lui faire recommencer. Ni à terre ni
     * en grève : pas candidat. Une seule recherche des ouvriers, à l'ordre (pas à chaque tick).
     */
    private String proposerRelais(Ordres.Lecture l, String met, Ordres.Mission m, ServerPlayer enFace) {
        List<String> f = Relais.fournisseurs(met, m.id(), metier);
        List<Travailleur> autres = level().getServer() == null ? List.of() : ColoniePnj.tous(level().getServer()).stream()
                .filter(o -> o != this && chef.equals(o.chef) && o.mode != Mode.CHOMEUR && !Famille.enfant(o) && f.contains(o.metier) && Metier.catalogue(o.metier, m.id()) != null).toList();
        if (autres.stream().anyMatch(o -> l.avec.contains(o.getUUID()))) return "";
        for (Travailleur o : autres)
            if (o.missions.stream().anyMatch(x -> x.id.equals(m.id()) && !Metier.remplie(o, x))) return Messages.corps(this, "relais_deja", Relais.qui(o.prenom, Metier.nom(o.metier)));
        autres = autres.stream().filter(o -> o.mode != Mode.GREVE && !o.aTerre).toList();
        int i = Relais.choisir(f, autres.stream().map(o -> new Relais.Candidat(o.metier, o.level() == level() ? distanceToSqr(o) : Double.MAX_VALUE)).toList());
        String objet = Relais.objet(Metier.nomCommande(met, m.id()));
        if (i < 0) {
            // Personne à portée : un ouvrier de ce métier peut être inscrit à l'effectif, mais loin, hors des terres chargées.
            var effectif = Courrier.de(level().getServer()).effectifs.get(chef);
            if (effectif != null) for (var x : effectif.entrySet())
                if (!x.getKey().equals(getUUID()) && x.getValue().mort.isEmpty() && f.contains(x.getValue().metier))
                    return Messages.corps(this, "relais_absent", Relais.qui(x.getValue().nom, Metier.nom(x.getValue().metier)));
            return Messages.corps(this, "relais_personne", Relais.personne(met), Metier.nom(met).toLowerCase(), objet);
        }
        Travailleur o = autres.get(i);
        Relais.Commande c = new Relais.Commande(o.getUUID(), o.prenom, o.metier, m, l.ajouter, l.quotidien, chef);
        String qui = Relais.qui(o.prenom, Metier.nom(o.metier));
        if (enFace == null) { transmettre(c, false); reponseImposee = true; return Messages.corps(this, "relais_lettre", qui); }
        reponseImposee = true;
        enAttente = l;
        attenteFin = level().getGameTime() + 1200;
        attenteMarche = false;
        relais = c;
        return Messages.corps(this, "relais_propose", objet, qui);
    }

    /** Le chef a dit oui : il va prévenir l'ouvrier à pied s'il est à moins de 48 blocs ({@link Actions.Prevenir}), sinon il lui fait porter le mot. */
    private String prevenir(Relais.Commande c) {
        Travailleur o = level().getServer() == null ? null : Courrier.trouver(level().getServer(), c.cible());
        if (o == null) return Messages.corps(this, "relais_perdu", c.prenom());
        if (o.level() == level() && distanceToSqr(o) <= 48 * 48 && mode != Mode.GREVE) {
            Cerveau.lancer(this, new Actions.Prevenir(c));
            return Messages.corps(this, "relais_oui", c.prenom());
        }
        transmettre(c, false);
        return Messages.corps(this, "relais_loin", c.prenom());
    }

    /**
     * La commande du chef remise à l'ouvrier du bon métier : il la reçoit comme si le chef la lui avait donnée par écrit
     * (son obéissance, ses missions), la commande seule (pas la phrase du chef, qui pouvait viser le messager).
     * {@code surPlace} : le messager est à côté de lui et le lui dit. Le collègue refuse ou marchande : le chef le sait
     * (de vive voix s'il est là, sinon par une lettre), et le collègue garde la commande en suspens un jour, le temps
     * d'une réponse par lettre. Le messager renvoyé entre-temps : la commande part quand même, pour le chef d'origine.
     */
    void transmettre(Relais.Commande c, boolean surPlace) {
        Travailleur o = level().getServer() == null ? null : Courrier.trouver(level().getServer(), c.cible());
        if (o == null || o.chef == null || !o.chef.equals(c.chef())) { if (chef != null) dire(Messages.corps(this, "relais_perdu", c.prenom())); return; }
        String quoi = Metier.nomMission(o.metier, new Mission(c.mission().id(), c.mission().mode(), c.mission().nombre()));
        Ordres.Lecture x = new Ordres.Lecture();
        x.texte = "De la part du chef : " + quoi + ".";
        x.missions.add(c.mission());
        x.ajouter = c.ajouter();
        x.quotidien = c.quotidien();
        if (surPlace) { o.ecouter(this, 60); dire(Messages.corps(this, "relais_message", o.prenom, quoi)); }
        String r = o.appliquer(x, null);
        if (surPlace) o.getLookControl().setLookAt(this);
        if (o.missions.stream().anyMatch(y -> y.id.equals(c.mission().id()))) { o.dire(Messages.corps(o, "relais_recu", prenom)); return; }
        // Il refuse ou marchande : le chef doit le savoir.
        o.dire(r);
        if (o.enAttente == x) o.attenteFin = Math.max(o.attenteFin, level().getGameTime() + 24000);
        String rapport = Messages.corps(this, "relais_refuse", o.prenom, quoi, r);
        Courrier.ecrireTexte(chef != null && chef.equals(c.chef()) ? this : o, "Ta commande : " + quoi, rapport, false);
    }

    /** Pourquoi il refuse : sa paie en retard, sa faim, sa fatigue, les coups de son chef (ce jour-ci), ou le cœur n'y est plus. */
    String raisonDuRefus(long maintenant) {
        return Obeissance.raison(impayes, faim, fatigue, memoire.contains("coup_chef") && maintenant - memoire.getLong("coup_chef") < 24000);
    }

    private String appliquerOrdre(Ordres.Lecture l, ServerPlayer enFace) {
        List<String> r = new ArrayList<>();
        boolean duChef = enFace == null || enFace.getUUID().equals(chef); // un autre joueur (Etranger) : ni renvoi, ni formation, ni armée
        String finFormation = duChef ? Apprentissage.interrompre(this, l, enFace) : null; // tout ordre du chef met fin à sa formation, le prix rendu (lot 13)
        if (finFormation != null) r.add(finFormation);
        long maintenant = level().getGameTime();
        boolean confirmation = memoire.getLong("renvoi") > maintenant;
        if (enFace != null && duChef && confirmation && l.intentions.contains(Ordres.Intention.OUI)) {
            memoire.remove("renvoi");
            String adieu = Messages.corps(this, "dit_renvoye");
            Courrier.reputer(this, Recrutement.RENVOI);
            licencier();
            return adieu;
        }
        if (duChef && confirmation && l.intentions.contains(Ordres.Intention.NON)) { memoire.remove("renvoi"); return Messages.corps(this, "dit_garde"); }
        if (l.intentions.contains(Ordres.Intention.RENVOYER) && Famille.enfant(this)) { // on ne renvoie pas un enfant (lot 13)
            String s = Besoins.texte(this, "enfant_renvoi", null, null);
            return s != null ? s : "Me renvoyer ? Mais je suis un enfant, chef ! Ma famille est ici.";
        }
        if (l.intentions.contains(Ordres.Intention.RENVOYER)) {
            if (enFace == null) return "Si tu veux te passer de moi, dis-le-moi en face, chef.";
            memoire.putLong("renvoi", maintenant + 600);
            return Messages.corps(this, "dit_confirmer_renvoi");
        }
        if (mode == Mode.CHOMEUR || chef == null) return "";
        boolean auTravail = false;
        if (!parRegle && (!l.missions.isEmpty() || !l.faire.isEmpty() || l.intentions.stream().anyMatch(i -> java.util.EnumSet.of(Ordres.Intention.SUIVRE,
                Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE, Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER).contains(i))))
            ordreChef = maintenant;
        // Un ordre de base ou une mission met fin à ce qu'on lui avait demandé (« au travail » après « garde à vous »).
        if (!l.missions.isEmpty() || l.intentions.stream().anyMatch(i -> java.util.EnumSet.of(Ordres.Intention.SUIVRE, Ordres.Intention.ARRETER_SUIVRE, Ordres.Intention.ATTENDRE, Ordres.Intention.TRAVAIL, Ordres.Intention.RENTRER).contains(i))) Consignes.annuler(this);

        // Les missions.
        if (!l.missions.isEmpty()) {
            if (!l.ajouter) missions.clear();
            for (Ordres.Mission m : l.missions) {
                // Une règle qui redonne une mission déjà en cours ne la remet pas à zéro.
                if (parRegle && missions.stream().anyMatch(x -> x.id.equals(m.id()) && !Metier.remplie(this, x))) continue;
                missions.removeIf(x -> x.id.equals(m.id()));
                Mission nm = new Mission(m.id(), m.mode(), m.nombre());
                nm.quotidienne = l.quotidien && nm.quantite();
                missions.add(nm);
            }
            auTravail = true;
            r.add(Messages.corps(this, "dit_missions", String.join(", puis ", missions.stream().map(m -> Metier.nomMission(metier, m)).toList())));
        }
        // La guerre entre colonies (Guerre, Armee) : déclaration, paix, levée, retraite ; « rentre » ramène aussi l'armée.
        if (!parRegle && l.guerre != null) { Armee.ordre(this, l.guerre, r, l.rang == 0); reponseImposee = true; }
        // « Repliez-vous », « rentrez » : en guerre, c'est aussi la retraite de l'armée (la phrase reste à la consigne « repli »).
        else if (!parRegle && duChef && l.rang == 0 && Guerre.repli(l.texte) && !Armee.ennemis(this).isEmpty()) {
            Guerre.Lu g = new Guerre.Lu();
            g.retraite = true;
            Armee.ordre(this, g, r, true);
        }
        if (duChef && l.intentions.contains(Ordres.Intention.RENTRER)) Armee.finir(this);
        // La boutique (Boutique, Boutiquier) : l'ouvrir, la fermer, ses prix ; c'est le mod qui répond.
        if (!parRegle && l.boutique != null) { Boutiquier.ordre(this, l.boutique, r); reponseImposee = true; }
        // Pas de son métier (« va me chercher du fer », au bûcheron) : il propose d'aller prévenir celui dont c'est le travail.
        String relaye = null;
        if (!parRegle && chef != null && mode != Mode.GREVE) for (var e : l.pourAutres.entrySet()) {
            String dit = proposerRelais(l, e.getKey(), e.getValue(), enFace); // vide : le collègue l'a entendu lui-même
            if (!dit.isEmpty()) r.add(dit);
            relaye = e.getKey();
            break;
        }
        String dejaDit = relaye;
        l.horsMetier.forEach((met, id) -> { if (!met.equals(dejaDit)) r.add(Messages.corps(this, "dit_hors_metier", Metier.nom(metier).toLowerCase(), "« " + Metier.nomCommande(met, id) + " »")); });

        // Les ordres longs : « oublie tes ordres », puis les nouvelles règles (pas celles d'une règle qui se déclenche).
        if (l.annulerRegles && !parRegle) {
            regles.clear();
            memoire.remove("garde_chef");
            memoire.remove("epargnes");
            memoire.remove("garde_depot");
            r.add(Messages.corps(this, "dit_oublie_regles"));
        }
        if (!parRegle) for (OrdresLongs.Regle x : l.regles) {
            x.actif = x.quand.equals("FINI") && missionActive() == null; // « quand tu as fini » : seulement après une mission en cours
            OrdresLongs.ajouter(regles, x);
            r.add(Messages.corps(this, "dit_regle", OrdresLongs.quand(x), quoi(x)));
        }

        // Ceux qui frappent le chef (GardeChef) : riposter, s'interposer seulement, ne jamais toucher à tel joueur.
        // Il redit clairement ce qu'il a compris : c'est le mod qui répond, pas l'IA. Un homme qui ne porte pas les armes ne promet pas de frapper.
        if (!parRegle && l.posture != null) {
            memoire.putString("garde_chef", l.posture.name());
            boolean arme = Famille.arme(this); // un combattant, pas un mineur (lot 13)
            String cle = l.posture != GardeChef.Posture.ATTAQUE ? "chef_ordre_tranquille" : arme ? "chef_ordre_attaque" : "chef_ordre_attaque_civil";
            String dit = Besoins.texte(this, cle, null, null);
            r.add(dit != null ? dit : l.posture != GardeChef.Posture.ATTAQUE ? "Compris : si on te frappe, je m'interposerai, sans frapper."
                    : arme ? "Compris : je frapperai quiconque te frappe." : "Je ne porte pas les armes, chef : je crierai, mais je ne frapperai pas.");
            reponseImposee = true;
        }
        if (!parRegle && !l.epargner.isEmpty()) {
            reponseImposee = true;
            memoire.putString("epargnes", GardeChef.ajouter(memoire.getString("epargnes"), l.epargner));
            String noms = String.join(" ni à ", l.epargner);
            String dit = Besoins.texte(this, "chef_ordre_epargne", noms, null);
            r.add(dit != null ? dit : "Entendu, chef : je ne ferai jamais rien à " + noms + ".");
        }
        // Le dépôt du poste (GardeDepot, cahier 6.4) : sa garde, comme celle du chef ; c'est le mod qui répond.
        // « Reste tranquille », « ne frappe personne » la calment aussi, sans un mot de plus.
        if (!parRegle && l.depot == null && memoire.contains("garde_depot"))
            memoire.putString("garde_depot", GardeDepot.garde(GardeChef.posture(memoire.getString("garde_depot")), l).name());
        if (!parRegle && l.depot != null) {
            memoire.putString("garde_depot", l.depot.name());
            boolean arme = Famille.arme(this); // un combattant, pas un mineur (lot 13)
            String cle = l.depot != GardeChef.Posture.ATTAQUE ? "depot_ordre_tranquille" : arme ? "depot_ordre_attaque" : "depot_ordre_attaque_civil";
            String dit = Besoins.texte(this, cle, null, null);
            r.add(dit != null ? dit : l.depot != GardeChef.Posture.ATTAQUE ? "Compris : qui se sert au dépôt me trouvera devant lui, sans coup donné."
                    : arme ? "Compris : qui pille le dépôt goûtera de mon fer." : "Je ne porte pas les armes, chef : je crierai, mais je ne frapperai pas.");
            reponseImposee = true;
        }
        // Qui peut se servir au dépôt : dit à un seul, il le répète aux autres (retenu pour toute la colonie, dans le Courrier).
        if (!parRegle && chef != null && (!l.permis.isEmpty() || !l.retires.isEmpty()) && level() instanceof ServerLevel sl) {
            Courrier.de(sl.getServer()).permettre(chef, l.permis, l.retires);
            reponseImposee = true;
            if (!l.permis.isEmpty()) {
                String noms = String.join(" et ", l.permis), dit = Besoins.texte(this, "depot_ordre_permis", noms, null);
                r.add(dit != null ? dit : "Entendu, chef : " + noms + " pourra se servir au dépôt. Je le dirai aux autres.");
            }
            if (!l.retires.isEmpty()) {
                String noms = String.join(" ni ", l.retires), dit = Besoins.texte(this, "depot_ordre_retire", noms, null);
                r.add(dit != null ? dit : "Entendu, chef : plus de dépôt pour " + noms + ". Je préviendrai les autres.");
            }
        }
        // Qui d'autre peut le commander (Commandement, cahier 6.2) : dit à un seul, toute la colonie le sait (dans le Courrier).
        if (!parRegle && chef != null && (!l.commandants.isEmpty() || !l.destitues.isEmpty()) && level() instanceof ServerLevel sl) {
            Courrier.de(sl.getServer()).autoriser(chef, l.commandants, l.destitues);
            reponseImposee = true;
            if (!l.commandants.isEmpty()) {
                String noms = String.join(" et ", l.commandants), dit = Besoins.texte(this, "commande_permis", noms, null);
                r.add(dit != null ? dit : "Entendu, chef : j'obéirai aussi à " + noms + ". Je le dirai aux autres.");
            }
            if (!l.destitues.isEmpty()) {
                String noms = String.join(" ni à ", l.destitues), dit = Besoins.texte(this, "commande_retire", noms, null);
                r.add(dit != null ? dit : "Entendu, chef : je n'obéirai plus à " + noms + ". Je préviendrai les autres.");
            }
        }
        // « Arrête ! » pendant qu'il frappe un homme sur son ordre : il baisse son arme.
        if (!parRegle && enFace != null && action instanceof Actions.Attaquer a && a.cible instanceof Player && a.cible != enFace && GardeChef.cesse(l)) {
            finirAction("ordre");
            reponseImposee = true;
            String dit = Besoins.texte(this, "chef_cesse", null, null);
            r.add(dit != null ? dit : "Bien, chef. Je le laisse.");
        }

        // Horaires et expéditions.
        if (l.debut >= 0 || l.fin >= 0 || l.equipe >= 0) {
            // « Travaille de nuit » : ce qui n'est pas dit vient des réglages ; « travaille de jour » : 6 h - 18 h s'il était de nuit (lot 13, Nuit).
            int[] h = Nuit.horaires(l.equipe, l.debut, l.fin, debut, fin, Reglages.actuel.nuit_debut, Reglages.actuel.nuit_fin);
            debut = h[0];
            fin = h[1];
            r.add(debut == fin ? Messages.corps(this, "dit_toute_journee")
                    : l.equipe == 1 ? Messages.corps(this, "dit_nuit", debut, fin)
                    : l.equipe == 0 ? Messages.corps(this, "dit_jour", debut, fin)
                    : Messages.corps(this, "dit_horaires", debut, fin));
        }
        if (l.jours > 0) {
            joursExpedition = Math.clamp(l.jours, 1, 30);
            r.add(Messages.corps(this, "dit_jours", joursExpedition));
        }

        // Les déplacements. « Au travail », « suis-moi » : il ne rentre plus au poste qu'on lui avait demandé.
        if (!parRegle && !l.intentions.contains(Ordres.Intention.RENTRER) && (l.intentions.contains(Ordres.Intention.TRAVAIL)
                || l.intentions.contains(Ordres.Intention.SUIVRE) || l.intentions.contains(Ordres.Intention.ARRETER_SUIVRE))) {
            memoire.remove("rappel");
            memoire.remove("attendre_apres");
        }
        boolean arreter = l.intentions.contains(Ordres.Intention.ARRETER_SUIVRE);
        if (arreter || l.intentions.contains(Ordres.Intention.TRAVAIL) && l.missions.isEmpty()) {
            auTravail = true;
            r.add(bureau == null ? Messages.corps(this, "sans_poste") : Messages.corps(this, arreter ? "dit_arrete_suivre" : "dit_travail"));
        }
        if (l.intentions.contains(Ordres.Intention.RENTRER)) {
            if (bureau == null) r.add(Messages.corps(this, "sans_poste"));
            else if (mode != Mode.GREVE) {
                memoire.putBoolean("rappel", true);
                if (l.intentions.contains(Ordres.Intention.ATTENDRE)) memoire.putBoolean("attendre_apres", true);
                auTravail = true;
                r.add(Messages.corps(this, "dit_rentrer"));
            }
        } else if (l.intentions.contains(Ordres.Intention.ATTENDRE)) {
            if (enFace == null && !parRegle) r.add("Pour que je t'attende quelque part, viens me le dire, chef.");
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
            // Une règle qui se déclenche (« quand tu as fini, va aider Raoul ») : comme un ordre de son chef, s'il est dans le monde.
            ServerPlayer c = chefJoueur();
            String dit = Consignes.ordonner(this, f, enFace != null || !parRegle ? enFace
                    : c != null && c.level() == level() && c.distanceToSqr(this) < 48 * 48 ? c : null, l.texte, l.rang, l.total);
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
        if (l.intentions.contains(Ordres.Intention.LIENS)) r.add(Relations.reponse(this, Ordres.dit(Ordres.normaliser(l.texte), "couple", "moitie", "amoureux", "amoureuse", "coeur")));
        if (r.isEmpty() && l.intentions.contains(Ordres.Intention.SALUT)) r.add(Messages.corps(this, "dit_salut"));
        if (l.intentions.contains(Ordres.Intention.MERCI) && reaction.isEmpty()) r.add(Messages.corps(this, "dit_merci"));
        majNom();
        if (r.isEmpty() && !l.intentions.contains(Ordres.Intention.SAC) && l.faire.isEmpty() && relaye == null)
            return enFace == null || !reaction.isEmpty() && reactionSuffit ? "" : Messages.corps(this, "dit_pas_compris");
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
            case "batir" -> "Je bâtis, pierre après pierre.";
            case "approvisionner" -> "Je prends de quoi bâtir au dépôt.";
            case "ramasser" -> "Je ramasse ce qui traîne.";
            default -> "Je m'occupe.";
        };
        return geste + mission;
    }

    /** « Où en es-tu ? » */
    String rapport() {
        String ms = missions.isEmpty() ? "Je n'ai pas de mission." : "Mes missions : " + String.join(" ; ", missions.stream().map(m -> Metier.avancementTexte(this, m)).toList()) + ".";
        String rg = regles.isEmpty() ? "" : " Tes consignes : " + String.join(" ; ", regles.stream().map(x -> OrdresLongs.quand(x) + ", " + quoi(x)).toList()) + ".";
        return "Je suis " + Messages.ou(this) + ". " + activite() + " " + ms + rg;
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
        if (!enService()) return deNuit() ? "Je travaille de nuit, de " + debut + " h à " + fin + " h : le jour, je dors."
                : "Ma journée est finie : je travaille de " + debut + " h à " + fin + " h.";
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
        Apprentissage.annuler(this, chefJoueur()); // sa formation s'arrête, le prix rendu à l'ancien chef s'il est en ligne
        Macon.oublier(this); // son chantier était celui de ce chef-là
        Transporteur.oublier(this); // ses trajets étaient ceux de ce chef-là ; ce qu'il portait va à son ancien dépôt (avant d'oublier le poste)
        if (chef != null && level() instanceof ServerLevel sl) Courrier.de(sl.getServer()).retirer(chef, getUUID());
        chef = null;
        chefNom = "";
        mode = Mode.CHOMEUR;
        prime = 0;
        besoin = ""; // sinon un renvoyé garderait l'icône d'attente ou de paie
        impayes = 0;
        enAttente = null;
        lit = null;
        bureau = null;
        missions.clear();
        regles.clear();
        memoire.remove("garde_chef"); // « attaque quiconque me frappe » : l'ordre de ce chef-là, pas du suivant (GardeChef)
        memoire.remove("epargnes");
        memoire.remove("achat"); // « qu'as-tu acheté ? » : la réponse est pour ce chef-là
        memoire.remove("garde_depot");
        memoire.remove("boutique"); // sa boutique et ses prix : ceux de ce chef-là (Boutiquier)
        souvenirs.liste.clear(); // ses souvenirs parlent de ce chef-là
        lettresADeposer.clear();
        lettresALire.clear();
        lettresAuCalme.clear();
        Pecheur.oublier(this); // son coin de pêche était près du poste de ce chef-là
        Humeur.oublier(this); // son moral auprès de ce chef-là (lettres, démission, rancune) ; il repart à 70
        memoire.remove("champs"); // les champs du fermier aussi
        memoire.remove("plants_laisses");
        Fermier.TOURS.remove(this);
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
        t.talents = List.of(); // pas de talent au hasard : les tests et le banc restent comparables
        t.appliquerNiveau();
        t.mode = Mode.TRAVAIL;
        t.chef = chef;
        ServerPlayer j = chef == null ? null : l.getServer().getPlayerList().getPlayer(chef);
        if (j != null) t.chefNom = j.getName().getString(); // son nom au-dessus de la tête de l'ouvrier
        t.prochainePaie = Monnaie.SANS_PAIE;
        t.debut = 0;
        t.fin = 0; // de 0 h à 0 h : en service toute la journée
        t.majNom();
        if (l.getBlockEntity(bureau) instanceof Bureau.Entite b) b.proprietaire = chef;
        t.bureau = bureau;
        if (!outil.isEmpty()) t.inventaire.addItem(outil);
        l.addFreshEntity(t);
        return t;
    }

    void embaucher(ServerPlayer p) {
        if (mode != Mode.CHOMEUR) return;
        if (Famille.enfant(this)) { signaler(p, "Un enfant ne s'embauche pas."); return; } // lot 13
        if (Reglages.actuel.metiers_inactifs.contains(metier) || !Metier.existe(metier)) {
            signaler(p, "Ce métier n'est plus proposé sur le serveur.");
            return;
        }
        int nb = ColoniePnj.pnjDe(p.getServer(), p.getUUID()).size(), payes = payes(p); // avant qu'il ne soit des leurs
        if (nb >= Reglages.actuel.pnj_max_par_joueur) {
            signaler(p, "Tu as déjà " + nb + " PNJ : c'est le maximum (" + Reglages.actuel.pnj_max_par_joueur + ").");
            return;
        }
        long prime = Reglages.prime(niveau);
        if (!Reglages.payer(p.getInventory(), prime, s -> { p.getInventory().add(s); return s; }, s -> p.drop(s, false))) {
            signaler(p, "Il te faut " + Reglages.texte(prime) + " dans ton inventaire pour la prime d'embauche.");
            return;
        }
        entrerAuService(p, prime, -1);
    }

    /**
     * Il entre au service de {@code p} (embauche, ou débauche par {@link Etranger}) : {@code prime} déjà versée ;
     * {@code salaireFixe} : le salaire promis (-1 : celui qu'il demande).
     */
    void entrerAuService(ServerPlayer p, long prime, long salaireFixe) {
        int nb = ColoniePnj.pnjDe(p.getServer(), p.getUUID()).size();
        // Rien de l'ancien chef ne le suit (un ouvrier renvoyé avant que le renvoi ne l'efface).
        Transporteur.oublier(this);
        Macon.oublier(this);
        memoire.remove("champs");
        memoire.remove("plants_laisses");
        Fermier.TOURS.remove(this);
        chef = p.getUUID();
        chefNom = p.getName().getString();
        mode = Mode.SUIVRE;
        coince.progres(getX(), getY(), getZ());
        impayes = 0;
        this.prime = 0; // (« prime = 0 » remettait la prime d'embauche à zéro, et le message disait « pour rien »)
        salaire = salaireFixe; // un nouveau marché : ni le salaire fixé par l'ancien chef…
        int renom = Courrier.de(p.getServer()).reputation(p.getUUID());
        surcout = surcout(payes, renom); // …ni son ancien rang (cahier 9.2), et selon ce qu'on dit du chef (lot 13)
        memoire.remove("etoiles_gagnees"); // ses étoiles d'avant sont les siennes : il en demande le prix (Apprentissage)
        enAttente = null;
        prochainePaie = System.currentTimeMillis() + periodePaie();
        majNom();
        if (level() instanceof ServerLevel sl) Courrier.de(sl.getServer()).inscrire(this);
        Remarques.embauche(this); // ses nouveaux collègues en parleront
        boolean poste = level() instanceof ServerLevel sl2 && Courrier.de(sl2.getServer()).poste(chef) != null;
        // Une débauche (salaire promis) : Etranger le dit à sa façon.
        if (salaireFixe < 0) dire("Marché conclu, chef ! Je te suis." + (poste ? " Dis-moi « au travail » et je rejoins ton poste." : " Pose ton poste de travail (un bureau) pour me donner du travail."));
        if (Recrutement.exigence(renom) > 1) Besoins.dire(this, "embauche_reputation", null); // on dit qu'il paie mal : il prend ses sûretés
        else if (surcout > 1) Besoins.dire(this, "embauche_surcout", null); // « Une grande colonie, ça se paie, chef. »
        signaler(p, "Tu as embauché " + prenom + " (" + Metier.nom(metier) + ", " + "★".repeat(niveau) + ") pour " + Reglages.texte(prime)
                + ". Salaire : " + Reglages.texte(salaireVerse()) + ", dans la caisse de ton poste.");
    }

    /** Le facteur de salaire qu'il demanderait à ce chef, selon les ouvriers que celui-ci a déjà (cahier 9.2). */
    static double surcoutPour(ServerPlayer p) {
        return surcout(payes(p), Courrier.de(p.getServer()).reputation(p.getUUID()));
    }

    /** Ses ouvriers payés : les enfants ne comptent pas dans le surcoût (lot 13), mais bien dans le plafond. */
    static int payes(ServerPlayer p) { return (int) ColoniePnj.pnjDe(p.getServer(), p.getUUID()).stream().filter(x -> !Famille.enfant(x)).count(); }

    /** Le facteur de salaire chez un chef qui a déjà {@code nb} ouvriers et telle réputation ({@link Cout}, {@link Recrutement#exigence}). */
    static double surcout(int nb, int reputation) { return Math.min(Cout.FACTEUR_MAX, Reglages.surcout(nb + 1) * Recrutement.exigence(reputation)); }

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
        t.putDouble("Surcout", surcout);
        t.putString("Talents", Talents.texte(talents));
        t.putDouble("Age", age);
        t.putDouble("AgeFin", ageFin);
        t.putLong("AgeLe", ageLe);
        ListTag ms = new ListTag();
        for (Mission m : missions) ms.add(m.sauver());
        t.put("Missions", ms);
        ListTag rg = new ListTag();
        for (OrdresLongs.Regle r : regles) {
            CompoundTag x = new CompoundTag();
            x.putString("quand", r.quand);
            x.putString("suite", r.suite);
            x.putBoolean("actif", r.actif);
            x.putBoolean("mene", r.mene);
            rg.add(x);
        }
        t.put("Regles", rg);
        t.putInt("Fatigue", fatigue);
        t.putLong("ProchainePaie", prochainePaie);
        t.putLong("Arrivee", arrivee);
        t.putInt("Impayes", impayes);
        t.putInt("Moral", moral);
        t.putLong("Prime", prime);
        t.putString("Besoin", besoin);
        t.putInt("Faim", faim);
        t.putBoolean("ATerre", aTerre);
        t.putInt("ATerreTicks", aTerreTicks);
        t.put("Inventaire", inventaire.createTag(registryAccess()));
        t.put("Etat", memoire);
        ListTag sv = new ListTag();
        souvenirs.enTextes().forEach(x -> sv.add(StringTag.valueOf(x)));
        t.put("Souvenirs", sv);
        t.putLong("SouvenirsBonus", souvenirs.jourBonus);
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
        surcout = t.contains("Surcout") ? Math.clamp(t.getDouble("Surcout"), 1, Cout.FACTEUR_MAX) : 1; // avant le lot 11 : ×1
        talents = Talents.lire(t.getString("Talents")); // avant le lot 13 : aucun
        missions.clear();
        if (t.contains("Missions")) for (Tag x : t.getList("Missions", Tag.TAG_COMPOUND)) missions.add(Mission.lire((CompoundTag) x));
        else if (!t.getString("Commande").isEmpty()) { // avant la 0.5.0 : une seule commande
            Mission m = new Mission(t.getString("Commande"), t.contains("CommandeMode") ? t.getString("CommandeMode") : "stock",
                    t.contains("CommandeNombre") ? t.getInt("CommandeNombre") : 64);
            m.livre = t.getInt("CommandeLivre");
            missions.add(m);
        }
        regles.clear();
        for (Tag x : t.getList("Regles", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) x;
            OrdresLongs.Regle r = new OrdresLongs.Regle(c.getString("quand"), c.getString("suite"));
            r.actif = c.getBoolean("actif");
            r.mene = c.getBoolean("mene");
            OrdresLongs.ajouter(regles, r);
        }
        fatigue = t.getInt("Fatigue");
        prochainePaie = t.getLong("ProchainePaie");
        arrivee = t.getLong("Arrivee");
        impayes = t.getInt("Impayes");
        moral = t.contains("Moral") ? Math.clamp(t.getInt("Moral"), 0, 100) : Obeissance.DEPART;
        prime = Math.max(0, t.getLong("Prime"));
        besoin = t.getString("Besoin");
        faim = t.contains("Faim") ? t.getInt("Faim") : 20;
        aTerre = t.getBoolean("ATerre");
        aTerreTicks = t.getInt("ATerreTicks");
        if (aTerre) setPose(Pose.SLEEPING);
        inventaire.fromTag(t.getList("Inventaire", Tag.TAG_COMPOUND), registryAccess());
        memoire = t.getCompound("Etat"); // l'ancienne mémoire des scripts (« Memoire ») n'est pas reprise
        ListTag sv = t.getList("Souvenirs", Tag.TAG_STRING);
        List<String> lignes = new ArrayList<>();
        for (int i = 0; i < sv.size(); i++) lignes.add(sv.getString(i));
        souvenirs.lireTextes(lignes);
        souvenirs.jourBonus = t.contains("SouvenirsBonus") ? t.getLong("SouvenirsBonus") : -1;
        consigne = t.contains("Consigne") ? Consignes.Consigne.lire(t.getCompound("Consigne")) : null;
        consigneFond = t.contains("ConsigneFond") ? Consignes.Consigne.lire(t.getCompound("ConsigneFond")) : null;
        lettresADeposer.clear();
        lettresALire.clear();
        lettresAuCalme.clear(); // elles sont dans « LettresALire »
        for (Tag x : t.getList("LettresADeposer", Tag.TAG_COMPOUND)) lettresADeposer.add(Courrier.Lettre.lire((CompoundTag) x));
        for (Tag x : t.getList("LettresALire", Tag.TAG_COMPOUND)) lettresALire.add(Courrier.Lettre.lire((CompoundTag) x));
        if (t.contains("Age") && t.getDouble("Age") >= 0) { age = t.getDouble("Age"); ageFin = t.getDouble("AgeFin"); ageLe = t.getLong("AgeLe"); }
        else Famille.naitreAdulte(this, 20, 45); // avant le lot 13 : un âge d'homme fait
        Famille.appliquer(this);
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
