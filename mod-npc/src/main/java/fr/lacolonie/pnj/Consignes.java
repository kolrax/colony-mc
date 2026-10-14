package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Les actions du répertoire ({@link Repertoire}), jouées pour de vrai. Le chef en demande une ({@link #ordonner}) : elle
 * devient la consigne de l'ouvrier, qu'il tient jusqu'au bout (un geste, une tâche) ou jusqu'à nouvel ordre (une
 * posture, une garde), avant tout le reste de son travail. Le cerveau la lance ({@link #executer}) ; une attaque ou
 * une grosse faim l'interrompent, puis il la reprend.
 * <p>
 * Les règles du serveur valent ici aussi : il ne frappe que des monstres (jamais un joueur, un villageois ou un
 * animal), ne casse que des blocs naturels (rien de construit), ne se téléporte jamais, et ne donne que ce qu'il porte.
 */
final class Consignes {
    private Consignes() {}

    /**
     * Une action demandée. {@code fin} : -1 jusqu'à nouvel ordre, 0 le temps de la faire, sinon l'heure du jeu (en ticks)
     * où elle s'arrête. {@code lieu} : l'endroit visé (là où regardait le chef, une place dans le rang…) ;
     * {@code entite} : la personne ou la créature visée ; {@code texte} : un message à porter, une direction.
     */
    static final class Consigne {
        final String id, cible, texte;
        final int nombre, rang, total;
        final long fin;
        final BlockPos lieu;
        final UUID entite;

        Consigne(String id, String cible, String texte, int nombre, int rang, int total, long fin, BlockPos lieu, UUID entite) {
            this.id = id;
            this.cible = cible == null ? "" : cible;
            this.texte = texte == null ? "" : texte;
            this.nombre = nombre;
            this.rang = rang;
            this.total = total;
            this.fin = fin;
            this.lieu = lieu;
            this.entite = entite;
        }

        Repertoire.Entree entree() { return Repertoire.PAR_ID.get(id); }

        boolean tenue() { return fin < 0; }

        CompoundTag sauver() {
            CompoundTag t = new CompoundTag();
            t.putString("id", id);
            t.putString("cible", cible);
            t.putString("texte", texte);
            t.putInt("nombre", nombre);
            t.putInt("rang", rang);
            t.putInt("total", total);
            t.putLong("fin", fin);
            if (lieu != null) t.put("lieu", NbtUtils.writeBlockPos(lieu));
            if (entite != null) t.putUUID("entite", entite);
            return t;
        }

        static Consigne lire(CompoundTag t) {
            if (!t.contains("id") || !Repertoire.PAR_ID.containsKey(t.getString("id"))) return null;
            return new Consigne(t.getString("id"), t.getString("cible"), t.getString("texte"), t.getInt("nombre"), t.getInt("rang"), t.getInt("total"),
                    t.getLong("fin"), NbtUtils.readBlockPos(t, "lieu").orElse(null), t.hasUUID("entite") ? t.getUUID("entite") : null);
        }
    }

    /** Les chantiers creusés selon un plan ({@link Fouille}) : salle ou cave, carrière, tunnel. */
    static final Set<String> FOUILLES = Set.of("tunnel", "salle", "carriere");
    /** Les chantiers du bûcheron sur une surface dite (cahier 3.6) : forêt en rangs, terrain à défricher. */
    static final Set<String> TERRAINS = Set.of("foret", "defricher_zone");
    /** Au plus tant de liquides bouchés pour un chantier ; au-delà, il s'arrête (un lac, une rivière). */
    static final int BOUCHONS = 24;

    /** Tenir un endroit (une porte, un pont) : il ne s'en écarte pas de plus de tant de blocs pour frapper. */
    static final double TENIR = 6;
    /** …sauf pour un tireur ou un monstre qui l'a blessé : jusqu'à 16 blocs. */
    static final double TENIR_TIREUR = 16;

    static final String[] ACQUIS = {"À tes ordres, chef.", "Bien, chef.", "Tout de suite, chef.", "D'accord, chef.", "C'est parti !", "Compte sur moi."};

    // --- Recevoir un ordre ------------------------------------------------------------------------------------

    /**
     * Le chef lui demande l'action {@code f} (de vive voix si {@code chef} n'est pas nul). Renvoie ce qu'il répond sans IA
     * (avec l'IA, c'est elle qui parle). {@code phrase} : ce que le chef a dit (on y cherche qui ou quoi est visé si l'IA
     * ne l'a pas dit) ; {@code rang} sur {@code total} : sa place dans le groupe à qui l'ordre est donné (les formations).
     */
    static String ordonner(Travailleur t, Ordres.Faire f, ServerPlayer chef, String phrase, int rang, int total) {
        Repertoire.Entree e = Repertoire.PAR_ID.get(f.id());
        if (e == null) return "";
        if (!e.pour(t.metier))
            return "Ça, ce n'est pas mon métier, chef : demande à " + String.join(" ou à ", e.metiers().stream().map(m -> "un " + Metier.nom(m).toLowerCase()).toList()) + ".";
        long maintenant = t.level().getGameTime();
        if (f.fin()) {
            Consigne c = t.consigne;
            if (c != null && (c.id.equals(e.id()) || memeGeste(c.entree(), e))) { annuler(t); return "D'accord, j'arrête."; }
            return "";
        }
        // Aller voir quelqu'un de nommé (« va aider Raoul », par lettre ou par une règle) se fait sans le chef.
        boolean versQuelquUn = e.comportement().equals("aller") && e.arg().equals("cible") && e.visePersonne();
        if (chef == null && !versQuelquUn) return "Pour ça, il faut que tu sois là, chef.";
        boolean parole = e.comportement().equals("parler") || e.comportement().equals("info");
        if (t.mode == Travailleur.Mode.GREVE && !parole) return "Je suis en grève, chef. Paie-moi d'abord.";

        // Ce qui se fait tout de suite.
        switch (e.comportement()) {
            case "annuler" -> {
                annuler(t);
                if (t.isSleeping()) t.stopSleeping();
                return reponse(t, e, "Bien, chef.");
            }
            case "silence" -> {
                t.memoire.putLong("muet", maintenant + Math.max(1, e.duree()) * 20L);
                return "";
            }
            case "parole" -> {
                t.memoire.remove("muet");
                return reponse(t, e, "Merci, chef !");
            }
            case "vitesse" -> {
                vitesse(t, e.arg(), maintenant);
                return reponse(t, e, "Bien, chef.");
            }
            case "combat" -> {
                // Le parcours de sa ronde : l'endroit que regarde le chef, ou tout effacer.
                if (e.arg().equals("ronde_point")) { BlockPos vu = regardBloc(chef, 64); return Guerrier.retenirPoint(t, vu == null ? null : vu.above()); }
                if (e.arg().equals("ronde_effacer")) return Guerrier.effacerRonde(t);
            }
            default -> {}
        }

        // Ce qu'il vise : là où regarde le chef, une personne, un objet.
        BlockPos lieu = null;
        UUID entite = null;
        String texte = "";
        String quoi = f.cible().isBlank() ? phrase : f.cible();
        String a = e.arg();
        boolean regardLa = a.equals("la") || e.opt("regard", "").equals("la")
                || Set.of("miner_la", "tunnel", "trou", "salle", "carriere", "torche_la", "abattre_la", "planter_la", "foret", "defricher_zone", "champ", "oublier_champ").contains(a) || e.comportement().equals("combat") && Set.of("charge", "garder_ici").contains(a);
        if (regardLa) {
            BlockPos vu = regardBloc(chef, 48);
            if (vu == null && !a.equals("garder_ici")) return "Où ça, chef ? Regarde l'endroit en me le disant.";
            lieu = vu;
        }
        if (e.visePersonne()) {
            Entity p = personne(t, chef, quoi);
            if (p == null) return "Qui ça, chef ?";
            entite = p.getUUID();
        }
        int n = f.nombre() > 0 ? f.nombre() : nombre(e);
        switch (e.comportement()) {
            case "combat" -> {
                switch (a) {
                    case "cible" -> {
                        LivingEntity m = monstreVise(t, chef);
                        if (m == null) return "Je ne vois aucun monstre, chef.";
                        entite = m.getUUID();
                    }
                    case "garder_ici" -> {
                        if (lieu == null || lieu.distSqr(t.blockPosition()) > 24 * 24) lieu = t.blockPosition();
                        else if (t.level().getBlockState(lieu).is(BlockTags.DOORS) || t.level().getBlockState(lieu).is(BlockTags.FENCE_GATES)) {
                            // Une porte : il se poste devant, du côté du chef (pas dans l'embrasure).
                            if (t.level().getBlockState(lieu.below()).is(BlockTags.DOORS)) lieu = lieu.below();
                            lieu = debout(t.level(), lieu.relative(Direction.getNearest(chef.getX() - lieu.getX() - 0.5, 0, chef.getZ() - lieu.getZ() - 0.5)));
                        } else lieu = lieu.above();
                    }
                    case "proteger_chef", "patrouiller", "repli" -> entite = chef.getUUID();
                    default -> {}
                }
            }
            case "travail" -> {
                String refus = verifierTravail(t, a, lieu);
                if (refus == null && a.equals("soigner")) refus = Guerisseur.refus(t, entite); // « soigne Jean » : un ouvrier de la colonie, blessé
                if (refus != null) return refus;
                if (FOUILLES.contains(a)) {
                    // Le plan dit (mesures, direction) ; de face, il creuse vers l'intérieur de la paroi, au niveau des pieds du chef.
                    Fouille.Plan p = Fouille.lire(a, phrase, f.nombre() > 0 ? f.nombre() : nombre(e));
                    Direction face = chef.pick(48, 1.0f, false) instanceof BlockHitResult b ? b.getDirection() : Direction.UP;
                    boolean bas = p.descend() || a.equals("salle") && face == Direction.UP;
                    Direction d = !p.direction().isEmpty() ? Direction.byName(p.direction()) : face.getAxis().isHorizontal() ? face.getOpposite() : chef.getDirection();
                    if (!bas && lieu.getY() > chef.getBlockY() && lieu.getY() - chef.getBlockY() <= 2) lieu = lieu.atY(chef.getBlockY());
                    texte = p.vers(d.getName(), bas).texte();
                    // Une salle ou une carrière : tout le chantier près de son poste.
                    if (!a.equals("tunnel") && t.bureau != null && !(t.chezLui(lieu) && t.chezLui(lieu.relative(d, p.longueur() - 1))))
                        return "C'est trop loin de mon poste, chef : je creuse près de chez nous.";
                }
                if (TERRAINS.contains(a) || a.equals("champ")) {
                    // Le sol sous l'endroit regardé (le chef peut viser un tronc, des feuilles, l'herbe).
                    for (int i = 0; i < 40 && (t.level().getBlockState(lieu).is(BlockTags.LOGS) || t.level().getBlockState(lieu).is(BlockTags.LEAVES)
                            || t.level().getBlockState(lieu).canBeReplaced()); i++) lieu = lieu.below();
                    Fouille.Plan p = Fouille.lire(a.equals("defricher_zone") ? "defriche" : a, phrase, 0).vers(chef.getDirection().getName(), false);
                    String essence = Fouille.essence(phrase);
                    // Un chêne noir ne pousse que d'un carré de 4 pousses : la forêt se plante alors par carrés.
                    boolean carre = Fouille.geants(phrase) || essence.equals("chene_noir");
                    texte = a.equals("foret") ? p.texte() + "|" + Bucheron.pousseDe(essence.isEmpty() && carre ? "chene_noir" : essence) + "|" + (carre ? "carre" : "un") : a.equals("champ") ? p.texte() + "|" + Cultures.dite(phrase).id() : p.texte();
                    Direction d = chef.getDirection();
                    // Tout le terrain près de son poste.
                    if (t.bureau != null)
                        for (int[] k : new int[][]{{-1, -1}, {-1, 1}, {1, -1}, {1, 1}})
                            if (!t.chezLui(lieu.relative(d, k[0] * p.longueur() / 2).relative(d.getClockWise(), k[1] * p.largeur() / 2)))
                                return "C'est trop loin de mon poste, chef : je travaille près de chez nous.";
                }
                if (a.equals("abattre_betes")) {
                    // L'espèce dite (« abats deux vaches ») et combien (une, sans nombre) ; sans espèce : les bêtes en trop.
                    Elevage.Espece esp = Elevage.dite(phrase);
                    texte = esp == null ? "" : esp.id();
                    int q = Fouille.quantite(phrase);
                    n = q > 0 ? Math.min(q, 64) : 1;
                }
                if (a.equals("chasser")) {
                    // Le gibier dit (« chasse des lapins ») et combien (un tour de chasse, sans nombre).
                    Chasse.Espece esp = Chasse.dite(phrase);
                    texte = esp == null ? "" : esp.id();
                    int q = Fouille.quantite(phrase);
                    n = q > 0 ? Math.min(q, 16) : Chasse.PAR_TOUR;
                }
                if (a.equals("planches") || a.equals("charbon")) {
                    // Le bois dit (« des planches de bouleau »), sinon n'importe quelle bûche (qui brûle, pour le charbon) ; la quantité dite.
                    String essence = Fouille.essence(phrase);
                    texte = Metier.catalogue("bucheron", essence) instanceof Bucheron.Bois b ? b.bois.toString() : a.equals("charbon") ? "#minecraft:logs_that_burn" : "#minecraft:logs";
                    int q = Fouille.quantite(phrase);
                    if (q > 0) n = Math.min(q, a.equals("planches") ? 640 : 64);
                }
            }
            case "aller" -> {
                if (a.equals("chef")) entite = chef.getUUID();
                else if (!a.equals("cible")) {
                    BlockPos p = lieu(t, chef, a, lieu);
                    if (p == null && entite == null) return introuvable(a);
                    // Cahier 8.5 : un ordre qui le tuerait (lave, feu, vide), il refuse. Pas pour aller près d'un bloc (« va au feu de camp »).
                    if (p != null && !BLOCS_LIEUX.containsKey(a) && t.level().isLoaded(p) && Monde.mortel(t.level(), p))
                        return "Non, chef : là-bas, c'est la mort assurée. Je n'irai pas.";
                    lieu = p;
                }
            }
            case "formation" -> lieu = place(chef, a, rang, total);
            case "info" -> {
                Fait fait = fait(t, a, chef, quoi);
                lieu = fait.ou;
                texte = fait.texte;
            }
            case "message" -> texte = message(phrase, f.cible());
            case "donner", "jeter", "tenir", "prendre" -> {
                String filtre = filtre(a, quoi, t);
                if (filtre == null) return "Quoi donc, chef ?";
                texte = filtre;
                if (!a.equals("tout") && !e.comportement().equals("prendre") && t.compter(Actions.Filtre.de(filtre)) == 0
                        && !(e.comportement().equals("tenir") && Actions.Filtre.de(filtre).objet(t.getOffhandItem())))
                    return "Je n'en ai pas sur moi, chef.";
                if (e.comportement().equals("donner") && e.drapeau("a") && entite == null) return "À qui, chef ?";
            }
            case "parler" -> texte = parole(t, a, chef);
            case "manger" -> {
                if (!t.manger()) return "Je n'ai rien à manger sur moi, chef.";
            }
            default -> {}
        }

        long fin = e.tenue() ? -1 : e.duree() > 0 ? maintenant + e.duree() * 20L : 0;
        if (e.comportement().equals("parler")) fin = maintenant + Math.clamp(40 + texte.length() * 2L, 60, 240);
        if (e.comportement().equals("info")) fin = maintenant + 60;
        if (e.comportement().equals("manger")) fin = maintenant + 50;
        Consigne c = new Consigne(e.id(), f.cible(), texte, n, rang, total, fin, lieu, entite);
        // Un petit geste pendant une posture (« salue », au garde-à-vous) : il y revient ensuite.
        boolean court = !c.tenue() && Set.of("geste", "son", "parler", "info", "regarder", "musique", "manger").contains(e.comportement());
        Consigne avant = t.consigne;
        t.consigneFond = court && avant != null && avant.tenue() ? avant : null;
        t.consigne = c;
        if (!(t.action instanceof Actions.Attaquer)) t.finirAction("ordre");
        if (t.isSleeping() && !e.comportement().equals("dormir")) t.stopSleeping();
        return switch (e.comportement()) {
            case "info", "parler" -> texte;
            default -> reponse(t, e, ACQUIS[t.getRandom().nextInt(ACQUIS.length)]);
        };
    }

    /** « Arrête de danser » pendant la gigue : même geste, autre formule. */
    static boolean memeGeste(Repertoire.Entree a, Repertoire.Entree b) {
        if (a == null) return false;
        if (a.comportement().equals("musique") && b.comportement().equals("musique")) return true;
        String ga = a.comportement().equals("geste") ? a.arg() : a.opt("geste", ""), gb = b.comportement().equals("geste") ? b.arg() : b.opt("geste", "");
        return !ga.isEmpty() && ga.equals(gb);
    }

    static int nombre(Repertoire.Entree e) {
        try { return Integer.parseInt(e.opt("n", e.opt("fois", "0"))); } catch (NumberFormatException x) { return 0; }
    }

    static String reponse(Travailleur t, Repertoire.Entree e, String defaut) {
        List<String> r = e.reponses();
        return r.isEmpty() ? defaut : r.get(t.getRandom().nextInt(r.size()));
    }

    /** Fin de toute consigne (« repos », un ordre de base, une nouvelle mission). */
    static void annuler(Travailleur t) {
        t.consigne = null;
        t.consigneFond = null;
        if (t.action instanceof Faire) t.finirAction("ordre");
        t.setGeste("");
    }

    /** La consigne {@code c} est faite : il revient à la posture qu'il tenait avant, sinon à son travail. */
    static void fini(Travailleur t, Consigne c) {
        if (t.consigne != c) return;
        t.consigne = t.consigneFond;
        t.consigneFond = null;
        t.setGeste("");
    }

    /** Appelé par le cerveau quand il n'a rien en cours : lance sa consigne, s'il en a une. */
    static boolean executer(Travailleur t) {
        Consigne c = t.consigne;
        if (c == null) return false;
        Repertoire.Entree e = c.entree();
        if (e == null || c.fin > 0 && t.level().getGameTime() >= c.fin) {
            fini(t, c);
            return t.consigne != null && executer(t);
        }
        if (t.mode == Travailleur.Mode.GREVE && !e.comportement().equals("parler") && !e.comportement().equals("info")) { annuler(t); return false; }
        Cerveau.lancer(t, new Faire(c, e));
        return true;
    }

    /** Une attaque ou une grosse faim passent avant (il reprendra ensuite) ; un combat demandé, lui, continue. */
    static boolean interrompre(Travailleur t, Faire f) {
        if (t.faim <= 6) return true;
        if (f.e.comportement().equals("combat")) return false;
        LivingEntity a = t.getLastHurtByMob();
        return a != null && a.isAlive() && !t.estDe(a) && Monde.recent(t, t.getLastHurtByMobTimestamp());
    }

    // --- Viser ------------------------------------------------------------------------------------------------

    /** Le bloc que regarde le chef (null s'il regarde le ciel). */
    static BlockPos regardBloc(ServerPlayer chef, double portee) {
        HitResult h = chef.pick(portee, 1.0f, false);
        return h instanceof BlockHitResult b && h.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
    }

    /** La créature ou la personne que regarde le chef. */
    static Entity regardEntite(ServerPlayer chef, double portee, Predicate<Entity> filtre) {
        Vec3 oeil = chef.getEyePosition(), bout = oeil.add(chef.getLookAngle().scale(portee));
        BlockPos b = regardBloc(chef, portee);
        double max = b == null ? portee * portee : oeil.distanceToSqr(Vec3.atCenterOf(b)) + 1;
        EntityHitResult r = ProjectileUtil.getEntityHitResult(chef, oeil, bout, chef.getBoundingBox().expandTowards(chef.getLookAngle().scale(portee)).inflate(1),
                x -> x != chef && x.isAlive() && filtre.test(x), max);
        return r == null ? null : r.getEntity();
    }

    /**
     * La personne visée : un ouvrier du chef ou un joueur nommé dans le texte (prénom approché, comme pour
     * l'adressage), sinon celui que regarde le chef. Jamais l'ouvrier lui-même.
     */
    static Entity personne(Travailleur t, ServerPlayer chef, String texte) {
        List<String> mots = Ordres.jetons(texte).stream().filter(w -> !Ordres.ponctuation(w)).toList();
        List<Entity> gens = new ArrayList<>();
        if (t.level() instanceof ServerLevel sl) {
            gens.addAll(sl.getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(96), x -> x != t && x.isAlive() && t.chef != null && t.chef.equals(x.chef)));
            for (ServerPlayer p : sl.players()) if (!(p instanceof FakePlayer)) gens.add(p);
        }
        gens.sort(Comparator.comparingDouble(t::distanceToSqr));
        for (Entity g : gens) {
            String nom = g instanceof Travailleur x ? x.prenom : g.getName().getString();
            List<String> n = Ordres.jetons(nom).stream().filter(w -> !Ordres.ponctuation(w)).toList();
            if (n.isEmpty()) continue;
            if (g == chef && mots.stream().noneMatch(w -> w.equals(n.get(0)))) continue; // « suis-moi » n'est pas d'ici
            for (String w : mots) if (Ordres.proche(w, n.get(0))) return g;
        }
        Entity vu = chef == null ? null : regardEntite(chef, 32, x -> (x instanceof Travailleur w && w != t) || x instanceof Player);
        if (vu != null) return vu;
        // « Suis-le », « protège-la » : le seul autre ouvrier du chef dans les parages.
        List<Entity> proches = gens.stream().filter(g -> g instanceof Travailleur && g.distanceToSqr(t) < 24 * 24).toList();
        return proches.size() == 1 ? proches.get(0) : null;
    }

    /** Le monstre que regarde le chef, sinon le plus proche de l'endroit qu'il regarde, sinon le plus proche de l'ouvrier. */
    static LivingEntity monstreVise(Travailleur t, ServerPlayer chef) {
        Entity vu = regardEntite(chef, 48, x -> x instanceof LivingEntity l && Monde.hostile(l));
        if (vu instanceof LivingEntity l) return l;
        BlockPos b = regardBloc(chef, 48);
        Vec3 centre = b == null ? t.position() : Vec3.atCenterOf(b);
        return t.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(12), Monde::hostile).stream()
                .min(Comparator.comparingDouble(x -> x.distanceToSqr(centre))).orElse(null);
    }

    static Entity entite(Travailleur t, UUID id) {
        if (id == null || !(t.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(id);
        return e != null && e.isAlive() && e.level() == t.level() ? e : null;
    }

    // --- Les lieux ----------------------------------------------------------------------------------------

    static final Map<String, String> BLOCS_LIEUX = Map.of("porte", "#minecraft:doors", "coffre", "minecraft:chest|minecraft:barrel|minecraft:trapped_chest",
            "four", "minecraft:furnace|minecraft:blast_furnace|minecraft:smoker", "etabli", "minecraft:crafting_table", "cloche", "minecraft:bell",
            "feu", "#minecraft:campfires|minecraft:fire|minecraft:lava_cauldron");

    /** Où aller pour ce lieu (null : introuvable ; une créature visée est cherchée ensuite, au départ). */
    static BlockPos lieu(Travailleur t, ServerPlayer chef, String lieu, BlockPos vu) {
        var l = t.level();
        Vec3 av = Vec3.directionFromRotation(0, chef.getYRot()), droite = new Vec3(-av.z, 0, av.x);
        BlockPos pc = chef.blockPosition();
        return switch (lieu) {
            case "la" -> vu == null ? null : debout(l, vu.above());
            case "derriere" -> debout(l, BlockPos.containing(chef.position().subtract(av.scale(2))));
            case "devant" -> debout(l, BlockPos.containing(chef.position().add(av.scale(2.5))));
            case "gauche" -> debout(l, BlockPos.containing(chef.position().subtract(droite.scale(2))));
            case "droite" -> debout(l, BlockPos.containing(chef.position().add(droite.scale(2))));
            case "loin" -> debout(l, BlockPos.containing(chef.position().add(t.position().subtract(chef.position()).multiply(1, 0, 1).normalize().scale(12))));
            case "recule" -> debout(l, BlockPos.containing(t.position().add(t.position().subtract(chef.position()).multiply(1, 0, 1).normalize().scale(4))));
            case "avance" -> debout(l, BlockPos.containing(t.position().add(Vec3.directionFromRotation(0, t.getYRot()).scale(4))));
            case "poste" -> t.bureau;
            case "lit" -> t.monLit();
            case "haut" -> haut(t);
            case "abri", "ombre" -> chercherPlace(t, 20, p -> !l.canSeeSky(p) && l.getBrightness(LightLayer.SKY, p) < 15);
            case "lumiere" -> chercherPlace(t, 16, p -> l.getBrightness(LightLayer.BLOCK, p) >= 10 || l.canSeeSky(p) && l.isDay());
            case "dehors" -> {
                int y = Monde.surface(l, t.getBlockX(), t.getBlockZ());
                yield l.canSeeSky(t.blockPosition()) ? null : y == Integer.MIN_VALUE ? null : new BlockPos(t.getBlockX(), y, t.getBlockZ());
            }
            case "eau" -> Monde.chercherBloc(t, Actions.Filtre.de("minecraft:water"), 24, t.blockPosition(), true, false);
            case "arbre" -> Actions.chercherArbre((ServerLevel) l, t.blockPosition(), 24);
            default -> BLOCS_LIEUX.containsKey(lieu) ? Monde.chercherBloc(t, Actions.Filtre.de(BLOCS_LIEUX.get(lieu)), 16, t.blockPosition(), false, false)
                    : ANIMAUX.containsKey(lieu) || lieu.equals("cible") ? null : pc;
        };
    }

    static String introuvable(String lieu) {
        return switch (lieu) {
            case "poste" -> "Tu n'as pas de poste de travail, chef.";
            case "lit" -> "Je n'ai pas de lit, chef.";
            case "dehors" -> "On est déjà dehors, chef.";
            case "la" -> "Où ça, chef ? Regarde l'endroit en me le disant.";
            default -> "Je n'en vois pas par ici, chef.";
        };
    }

    /** Une place où se tenir debout près de p (à la même hauteur, un peu au-dessus ou au-dessous). */
    static BlockPos debout(net.minecraft.world.level.Level l, BlockPos p) {
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) if (Monde.debout(l, p.above(dy))) return p.above(dy);
        return p;
    }

    /** La place la plus proche (rayon r) où il peut se tenir et qui vérifie {@code ok}. */
    static BlockPos chercherPlace(Travailleur t, int r, Predicate<BlockPos> ok) {
        var l = t.level();
        BlockPos o = t.blockPosition(), meilleur = null;
        double dMin = Double.MAX_VALUE;
        for (BlockPos q : BlockPos.betweenClosed(o.offset(-r, -4, -r), o.offset(r, 4, r))) {
            double d = q.distSqr(o);
            if (d >= dMin || !l.isLoaded(q) || !Monde.debout(l, q) || !ok.test(q)) continue;
            meilleur = q.immutable();
            dMin = d;
        }
        return meilleur;
    }

    /** Le point le plus haut des environs (où l'on voit loin). */
    static BlockPos haut(Travailleur t) {
        var l = t.level();
        BlockPos meilleur = null;
        for (int dx = -16; dx <= 16; dx += 2)
            for (int dz = -16; dz <= 16; dz += 2) {
                int x = t.getBlockX() + dx, z = t.getBlockZ() + dz, y = Monde.surface(l, x, z);
                if (y == Integer.MIN_VALUE || !l.getBlockState(new BlockPos(x, y - 1, z)).getFluidState().isEmpty()) continue;
                if (meilleur == null || y > meilleur.getY()) meilleur = new BlockPos(x, y, z);
            }
        return meilleur;
    }

    static final Map<String, Class<? extends Entity>> ANIMAUX = new LinkedHashMap<>();

    static {
        ANIMAUX.put("chien", net.minecraft.world.entity.animal.Wolf.class);
        ANIMAUX.put("chat", net.minecraft.world.entity.animal.Cat.class);
        ANIMAUX.put("cheval", net.minecraft.world.entity.animal.horse.AbstractHorse.class);
        ANIMAUX.put("vache", net.minecraft.world.entity.animal.Cow.class);
        ANIMAUX.put("cochon", net.minecraft.world.entity.animal.Pig.class);
        ANIMAUX.put("mouton", net.minecraft.world.entity.animal.Sheep.class);
        ANIMAUX.put("poule", net.minecraft.world.entity.animal.Chicken.class);
        ANIMAUX.put("villageois", net.minecraft.world.entity.npc.Villager.class);
        ANIMAUX.put("golem", net.minecraft.world.entity.animal.IronGolem.class);
        ANIMAUX.put("lapin", net.minecraft.world.entity.animal.Rabbit.class);
        ANIMAUX.put("renard", net.minecraft.world.entity.animal.Fox.class);
        ANIMAUX.put("perroquet", net.minecraft.world.entity.animal.Parrot.class);
        ANIMAUX.put("chevre", net.minecraft.world.entity.animal.goat.Goat.class);
        ANIMAUX.put("abeille", net.minecraft.world.entity.animal.Bee.class);
    }

    static Entity animal(Travailleur t, String nom) {
        Class<? extends Entity> c = ANIMAUX.get(nom);
        if (c == null) return null;
        return t.level().getEntitiesOfClass(c, t.getBoundingBox().inflate(32), Entity::isAlive).stream().min(Comparator.comparingDouble(t::distanceToSqr)).orElse(null);
    }

    /** Sa place dans une formation autour du chef (rang face à lui, file derrière lui, cercle autour de lui, ligne derrière lui). */
    static BlockPos place(ServerPlayer chef, String forme, int rang, int total) {
        Vec3 av = Vec3.directionFromRotation(0, chef.getYRot()), droite = new Vec3(-av.z, 0, av.x), c = chef.position();
        double i = rang - (Math.max(1, total) - 1) / 2.0;
        Vec3 p = switch (forme) {
            case "file" -> c.subtract(av.scale(2 + 1.6 * rang));
            case "cercle" -> {
                double a = 2 * Math.PI * rang / Math.max(1, total);
                yield c.add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
            }
            case "derriere" -> c.subtract(av.scale(2.5)).add(droite.scale(1.6 * i));
            default -> c.add(av.scale(3.5)).add(droite.scale(1.6 * i));
        };
        return debout(chef.level(), BlockPos.containing(p));
    }

    // --- Les objets (en français) ------------------------------------------------------------------------------

    /** Des mots pour les objets courants → le filtre qui les reconnaît dans un sac. */
    static final Map<String, String> MOTS_OBJETS = new LinkedHashMap<>();
    /** Le nom français des objets courants (pour dire ce qu'il porte ; les autres gardent leur nom du jeu). */
    static final Map<String, String> NOMS = new LinkedHashMap<>();

    static void mot(String filtre, String... mots) { for (String m : mots) MOTS_OBJETS.put(m, filtre); }

    static {
        mot("nourriture", "nourriture", "manger", "bouffe", "repas", "provisions", "vivres", "a manger");
        mot("minecraft:bread", "pain", "pains", "miche");
        mot("minecraft:apple|minecraft:golden_apple", "pomme", "pommes");
        mot("minecraft:carrot|minecraft:golden_carrot", "carotte", "carottes");
        mot("minecraft:potato|minecraft:baked_potato", "patate", "patates", "pomme de terre", "pommes de terre");
        mot("minecraft:beef|minecraft:cooked_beef|minecraft:porkchop|minecraft:cooked_porkchop|minecraft:chicken|minecraft:cooked_chicken|minecraft:mutton|minecraft:cooked_mutton|minecraft:rabbit|minecraft:cooked_rabbit",
                "viande", "viandes", "steak", "steaks", "boeuf", "porc", "jambon", "poulet", "gigot");
        mot("#minecraft:fishes", "poisson", "poissons", "saumon", "morue");
        mot("minecraft:cookie", "biscuit", "biscuits", "cookie", "cookies");
        mot("minecraft:cake|minecraft:pumpkin_pie", "gateau", "tarte");
        mot("minecraft:mushroom_stew|minecraft:beetroot_soup|minecraft:rabbit_stew|minecraft:suspicious_stew", "soupe", "ragout");
        mot("minecraft:coal|minecraft:charcoal", "charbon", "charbons", "houille");
        mot("minecraft:iron_ingot|minecraft:raw_iron|minecraft:iron_ore|minecraft:deepslate_iron_ore", "fer", "fers");
        mot("minecraft:gold_ingot|minecraft:raw_gold|minecraft:gold_nugget|minecraft:gold_ore", "or");
        mot("minecraft:copper_ingot|minecraft:raw_copper|minecraft:copper_ore", "cuivre");
        mot("minecraft:diamond", "diamant", "diamants");
        mot("minecraft:emerald", "emeraude", "emeraudes");
        mot("minecraft:redstone", "redstone");
        mot("minecraft:lapis_lazuli", "lapis");
        mot("minecraft:cobblestone|minecraft:stone|minecraft:cobbled_deepslate|minecraft:andesite|minecraft:granite|minecraft:diorite", "pierre", "pierres", "caillou", "cailloux", "roche");
        mot("minecraft:dirt", "terre");
        mot("minecraft:sand", "sable");
        mot("minecraft:gravel", "gravier");
        mot("#minecraft:logs", "bois", "buche", "buches", "rondin", "rondins", "tronc", "troncs");
        mot("#minecraft:planks", "planche", "planches");
        mot("minecraft:stick", "baton", "batons");
        mot("minecraft:torch", "torche", "torches");
        mot("#minecraft:pickaxes", "pioche", "pioches");
        mot("#minecraft:axes", "hache", "haches");
        mot("#minecraft:swords", "epee", "epees", "arme");
        mot("#minecraft:shovels", "pelle", "pelles");
        mot("minecraft:bow|minecraft:crossbow", "arc", "arbalete");
        mot("#minecraft:arrows", "fleche", "fleches");
        mot("minecraft:shield", "bouclier");
        mot("minecraft:bucket|minecraft:water_bucket|minecraft:milk_bucket", "seau", "seaux", "lait");
        mot("#minecraft:saplings", "pousse", "pousses", "jeune arbre", "graine d arbre");
        mot("minecraft:wheat_seeds", "graine", "graines");
        mot("minecraft:wheat", "ble");
        mot("#minecraft:wool", "laine");
        mot("minecraft:leather", "cuir");
        mot("minecraft:bone", "os");
        mot("minecraft:string", "ficelle", "fil");
        mot("minecraft:feather", "plume", "plumes");
        mot("minecraft:egg", "oeuf", "oeufs");
        mot("minecraft:book|minecraft:paper", "livre", "papier");
        mot("#minecraft:boats", "bateau", "barque");
        mot("minecraft:flint", "silex");
        mot("minecraft:rotten_flesh", "chair", "chair putrefiee");

        for (String[] n : new String[][]{{"bread", "pain"}, {"apple", "pomme"}, {"golden_apple", "pomme dorée"}, {"carrot", "carotte"},
                {"potato", "pomme de terre"}, {"baked_potato", "pomme de terre cuite"}, {"beef", "bœuf cru"}, {"cooked_beef", "steak"},
                {"porkchop", "porc cru"}, {"cooked_porkchop", "côtelette"}, {"chicken", "poulet cru"}, {"cooked_chicken", "poulet rôti"},
                {"mutton", "mouton cru"}, {"cooked_mutton", "gigot"}, {"cod", "morue"}, {"salmon", "saumon"}, {"cookie", "biscuit"},
                {"coal", "charbon"}, {"charcoal", "charbon de bois"}, {"iron_ingot", "lingot de fer"}, {"raw_iron", "fer brut"},
                {"gold_ingot", "lingot d'or"}, {"raw_gold", "or brut"}, {"copper_ingot", "lingot de cuivre"}, {"raw_copper", "cuivre brut"},
                {"diamond", "diamant"}, {"emerald", "émeraude"}, {"redstone", "redstone"}, {"lapis_lazuli", "lapis"}, {"cobblestone", "pierre"},
                {"cobbled_deepslate", "ardoise des abîmes"}, {"stone", "roche"}, {"andesite", "andésite"}, {"granite", "granite"},
                {"diorite", "diorite"}, {"dirt", "terre"}, {"sand", "sable"}, {"gravel", "gravier"}, {"flint", "silex"}, {"stick", "bâton"},
                {"torch", "torche"}, {"wooden_pickaxe", "pioche en bois"}, {"stone_pickaxe", "pioche en pierre"}, {"iron_pickaxe", "pioche en fer"},
                {"diamond_pickaxe", "pioche en diamant"}, {"wooden_axe", "hache en bois"}, {"stone_axe", "hache en pierre"}, {"iron_axe", "hache en fer"},
                {"diamond_axe", "hache en diamant"}, {"wooden_sword", "épée en bois"}, {"stone_sword", "épée en pierre"}, {"iron_sword", "épée en fer"},
                {"diamond_sword", "épée en diamant"}, {"shield", "bouclier"}, {"bow", "arc"}, {"arrow", "flèche"}, {"oak_log", "bûche de chêne"},
                {"birch_log", "bûche de bouleau"}, {"spruce_log", "bûche de sapin"}, {"jungle_log", "bûche d'acajou"}, {"acacia_log", "bûche d'acacia"},
                {"dark_oak_log", "bûche de chêne noir"}, {"cherry_log", "bûche de cerisier"}, {"mangrove_log", "bûche de palétuvier"},
                {"oak_sapling", "pousse de chêne"}, {"birch_sapling", "pousse de bouleau"}, {"spruce_sapling", "pousse de sapin"},
                {"rotten_flesh", "chair putréfiée"}, {"bone", "os"}, {"string", "ficelle"}, {"paper", "papier"}, {"wheat_seeds", "graines"},
                {"oak_boat", "barque"}, {"leather", "cuir"}, {"feather", "plume"}, {"egg", "œuf"}, {"wheat", "blé"}})
            NOMS.put("minecraft:" + n[0], n[1]);
    }

    /** Le filtre de l'objet demandé : l'argument du répertoire (nourriture, arme…), sinon le mot dit (« ton pain »). Null : on ne sait pas quoi. */
    static String filtre(String arg, String texte, Travailleur t) {
        switch (arg) {
            case "nourriture" -> { return "nourriture"; }
            case "tout" -> { return "tout"; }
            case "torche" -> { return "minecraft:torch"; }
            case "bouclier" -> { return "minecraft:shield"; }
            case "arme" -> { return "#minecraft:swords|#minecraft:axes"; }
            case "outil" -> {
                return switch (t.metier) {
                    case "mineur" -> "#minecraft:pickaxes";
                    case "bucheron" -> "#minecraft:axes";
                    default -> "#minecraft:swords|#minecraft:axes";
                };
            }
            default -> {}
        }
        if (texte.contains(":") && !texte.contains(" ")) return texte.strip();
        String n = Ordres.normaliser(texte);
        String meilleur = null;
        int longueur = 0;
        for (var e : MOTS_OBJETS.entrySet())
            if (n.contains(" " + e.getKey() + " ") && e.getKey().length() > longueur) { meilleur = e.getValue(); longueur = e.getKey().length(); }
        return meilleur;
    }

    static String nom(ItemStack s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        return NOMS.getOrDefault(id, s.getHoverName().getString().toLowerCase());
    }

    /** « 12 pains, 3 torches et une pioche en fer ». */
    static String inventaire(Travailleur t) {
        Map<String, Integer> l = new LinkedHashMap<>();
        if (!t.getMainHandItem().isEmpty()) l.merge(nom(t.getMainHandItem()), t.getMainHandItem().getCount(), Integer::sum);
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            ItemStack s = t.inventaire.getItem(i);
            if (!s.isEmpty()) l.merge(nom(s), s.getCount(), Integer::sum);
        }
        if (l.isEmpty()) return "rien";
        List<String> r = new ArrayList<>();
        l.forEach((n, k) -> r.add(k == 1 ? "un(e) " + n : k + " × " + n));
        return r.size() == 1 ? r.get(0) : String.join(", ", r.subList(0, r.size() - 1)) + " et " + r.get(r.size() - 1);
    }

    // --- Ce qu'il sait ---------------------------------------------------------------------------------------

    /** Un fait, et l'endroit qu'il montre du doigt en le disant (null : rien à montrer). */
    record Fait(String texte, BlockPos ou) {}

    static String lune(int phase) {
        return new String[]{"C'est la pleine lune.", "La lune décroît.", "C'est le dernier quartier.", "Il ne reste qu'un fin croissant de lune.",
                "C'est la nouvelle lune : nuit noire.", "Un fin croissant de lune apparaît.", "C'est le premier quartier.", "La lune grossit, bientôt pleine."}[Math.floorMod(phase, 8)];
    }

    static String distance(BlockPos de, BlockPos a) {
        int d = (int) Math.sqrt(de.distSqr(new BlockPos(a.getX(), de.getY(), a.getZ())));
        return d < 3 ? "juste ici" : "à " + d + " blocs " + Messages.direction(de, a);
    }

    static final Map<String, String> BIOMES = Map.ofEntries(Map.entry("plains", "une plaine"), Map.entry("forest", "une forêt"),
            Map.entry("birch_forest", "une forêt de bouleaux"), Map.entry("dark_forest", "une forêt sombre"), Map.entry("taiga", "une taïga"),
            Map.entry("snowy_plains", "une plaine enneigée"), Map.entry("desert", "un désert"), Map.entry("savanna", "une savane"),
            Map.entry("jungle", "une jungle"), Map.entry("swamp", "un marais"), Map.entry("mangrove_swamp", "une mangrove"), Map.entry("river", "une rivière"),
            Map.entry("ocean", "l'océan"), Map.entry("beach", "une plage"), Map.entry("meadow", "une prairie"), Map.entry("cherry_grove", "une cerisaie"),
            Map.entry("windswept_hills", "des collines venteuses"), Map.entry("stony_peaks", "des pics rocheux"), Map.entry("badlands", "des terres arides"),
            Map.entry("mushroom_fields", "des champs de champignons"), Map.entry("lush_caves", "une grotte luxuriante"), Map.entry("dripstone_caves", "une grotte de stalactites"),
            Map.entry("deep_dark", "les abysses"), Map.entry("sunflower_plains", "une plaine de tournesols"), Map.entry("old_growth_birch_forest", "une vieille forêt de bouleaux"),
            Map.entry("snowy_taiga", "une taïga enneigée"), Map.entry("flower_forest", "une forêt fleurie"), Map.entry("grove", "un bosquet enneigé"));

    /** Ce qu'il répond à une question sur ce qu'il sait ou voit (calculé ici : l'IA le reçoit aussi pour s'en servir). */
    static Fait fait(Travailleur t, String sujet, ServerPlayer chef, String texte) {
        var l = t.level();
        BlockPos ici = t.blockPosition();
        return switch (sujet) {
            case "heure" -> new Fait("Il est " + IA.heure(t) + " h.", null);
            case "meteo" -> new Fait(l.isThundering() ? "Il y a de l'orage : mieux vaut rester à l'abri." : l.isRaining()
                    ? (l.getBiome(ici).value().coldEnoughToSnow(ici) ? "Il neige." : "Il pleut.") : l.isDay() ? "Il fait beau." : "La nuit est claire.", null);
            case "jour" -> new Fait("Nous sommes au jour " + (l.getDayTime() / 24000 + 1) + ", et il fait " + (l.isDay() ? "jour." : "nuit."), null);
            case "lune" -> new Fait(lune(l.getMoonPhase()), ici.above(40));
            case "poste" -> t.bureau == null ? new Fait("Tu n'as pas encore de poste de travail, chef.", null)
                    : new Fait("Le poste est " + distance(ici, t.bureau) + ".", t.bureau);
            case "lit" -> {
                BlockPos lit = t.monLit();
                yield lit == null ? new Fait("Je n'ai pas de lit : il m'en faudrait un près du poste.", null) : new Fait("Mon lit est " + distance(ici, lit) + ".", lit);
            }
            case "cible" -> {
                Entity p = personne(t, chef, texte);
                if (p == null) yield new Fait(Ordres.normaliser(texte).contains(" autres ") || Ordres.normaliser(texte).contains(" ouvriers ")
                        ? ouvriers(t).texte : "Je ne sais pas de qui tu parles, chef.", null);
                String nom = p instanceof Travailleur w ? w.prenom : p.getName().getString();
                String fait = p instanceof Travailleur w ? " " + w.activite() : "";
                yield new Fait(nom + " est " + distance(ici, p.blockPosition()) + "." + fait, p.blockPosition());
            }
            case "position" -> new Fait("Nous sommes en " + ici.getX() + " " + ici.getY() + " " + ici.getZ()
                    + (t.bureau == null ? "." : ", " + Messages.ou(t).replace("au poste", "au poste même") + "."), null);
            case "nord" -> new Fait("Le nord est par là.", ici.north(30));
            case "altitude" -> {
                int s = Monde.surface(l, ici.getX(), ici.getZ());
                yield new Fait("Nous sommes à la hauteur " + ici.getY() + (s != Integer.MIN_VALUE && s - ici.getY() > 4 ? ", à " + (s - ici.getY()) + " blocs sous la surface." : ici.getY() > 100 ? ", bien en hauteur." : "."), null);
            }
            case "biome" -> {
                String k = l.getBiome(ici).unwrapKey().map(x -> x.location().getPath()).orElse("");
                yield new Fait("Nous sommes dans " + BIOMES.getOrDefault(k, "un endroit qu'on appelle « " + k.replace('_', ' ') + " »") + ".", null);
            }
            case "monstres" -> {
                List<LivingEntity> m = l.getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(24), Monde::hostile);
                m.sort(Comparator.comparingDouble(t::distanceToSqr));
                yield m.isEmpty() ? new Fait("Aucun monstre en vue, chef. La voie est libre.", null)
                        : new Fait((m.size() == 1 ? "Je vois un monstre : " : "Je vois " + m.size() + " monstres, le plus proche : ") + m.get(0).getName().getString().toLowerCase()
                        + ", " + distance(ici, m.get(0).blockPosition()) + ".", m.get(0).blockPosition());
            }
            case "animaux" -> {
                Map<String, Integer> n = new LinkedHashMap<>();
                for (var e : ANIMAUX.entrySet())
                    if (!e.getKey().equals("villageois") && !e.getKey().equals("golem"))
                        n.put(e.getKey(), l.getEntitiesOfClass(e.getValue(), t.getBoundingBox().inflate(24), Entity::isAlive).size());
                n.values().removeIf(k -> k == 0);
                List<String> r = new ArrayList<>();
                n.forEach((k, v) -> r.add(v + " " + k + (v > 1 && !k.endsWith("s") && !k.endsWith("x") ? "s" : "")));
                yield new Fait(r.isEmpty() ? "Pas un animal en vue." : "Je vois " + String.join(", ", r) + ".", null);
            }
            case "joueurs" -> {
                List<String> r = new ArrayList<>();
                for (Player p : l.players())
                    if (p != chef && !(p instanceof FakePlayer) && p.distanceToSqr(t) < 64 * 64 && !p.isSpectator())
                        r.add(p.getName().getString() + " (" + (int) p.distanceTo(t) + " blocs)");
                yield new Fait(r.isEmpty() ? "Personne d'autre que nous dans les parages." : "Il y a " + String.join(", ", r) + ".", null);
            }
            case "lumiere" -> {
                int b = l.getBrightness(LightLayer.BLOCK, ici), c = l.getMaxLocalRawBrightness(ici);
                yield new Fait(b == 0 && c < 8 ? "Il fait sombre ici : des monstres peuvent apparaître. Il faudrait des torches."
                        : b == 0 ? "Il fait clair, mais rien n'éclaire ici une fois la nuit tombée." : "C'est assez éclairé, pas de monstre qui apparaîtra ici.", null);
            }
            case "eau" -> {
                BlockPos e = Monde.chercherBloc(t, Actions.Filtre.de("minecraft:water"), 24, ici, true, false);
                yield e == null ? new Fait("Je ne vois pas d'eau par ici.", null) : new Fait("Il y a de l'eau " + distance(ici, e) + ".", e);
            }
            case "sac" -> new Fait("Sur moi, j'ai " + inventaire(t) + ".", null);
            case "sante" -> new Fait("Santé " + (int) Math.ceil(t.getHealth()) + " sur " + (int) t.getMaxHealth() + ", faim " + t.faim + " sur 20, fatigue "
                    + t.fatigue + " sur 100. " + Messages.etat(t), null);
            case "outil" -> {
                ItemStack o = t.getMainHandItem();
                if (o.isEmpty() || !o.isDamageableItem()) yield new Fait("Je n'ai pas d'outil en main.", null);
                int reste = 100 - 100 * o.getDamageValue() / Math.max(1, o.getMaxDamage());
                yield new Fait("Ma " + nom(o) + " est usée " + (reste > 70 ? "un peu" : reste > 30 ? "à moitié" : "presque jusqu'au bout") + " (" + reste + " % de vie).", null);
            }
            case "armure" -> {
                List<String> r = new ArrayList<>();
                for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
                    if (!t.getItemBySlot(s).isEmpty()) r.add(nom(t.getItemBySlot(s)));
                yield new Fait(r.isEmpty() ? "Je n'ai aucune armure, chef. Un plastron ne serait pas de refus." : "Je porte : " + String.join(", ", r) + ".", null);
            }
            case "salaire" -> new Fait("Je touche " + Reglages.texte(t.salaireVerse()) + " par paie"
                    + (t.impayes > 0 ? ", et tu me dois " + t.impayes + " paie(s)." : ", prochaine paie dans " + Math.max(0, (t.prochainePaie - System.currentTimeMillis()) / 60_000) + " minutes."), null);
            case "anciennete" -> {
                long h = Math.max(0, (System.currentTimeMillis() - t.arrivee) / 3_600_000);
                yield new Fait("Je travaille pour toi depuis " + (h < 1 ? "moins d'une heure" : h < 48 ? h + " heures" : h / 24 + " jours") + ", chef.", null);
            }
            case "caractere" -> new Fait("On me dit " + IA.TEMPERAMENTS.getOrDefault(t.temperament, "ordinaire") + ".", null);
            case "niveau" -> new Fait("J'ai " + t.niveau + " étoile" + (t.niveau > 1 ? "s" : "") + " sur 5 comme " + Metier.nom(t.metier).toLowerCase() + ".", null);
            case "distance" -> new Fait(chef == null ? "Je ne te vois pas, chef." : "Je suis à " + (int) t.distanceTo(chef) + " blocs de toi.", chef == null ? null : chef.blockPosition());
            case "ouvriers" -> ouvriers(t);
            case "minerai" -> {
                BlockPos m = Monde.chercherBloc(t, Actions.Filtre.de("#c:ores"), 12, ici, false, false);
                yield m == null ? new Fait("Je ne vois pas de minerai d'ici, chef.", null)
                        : new Fait("Il y a du " + minerai(l.getBlockState(m)) + " " + distance(ici, m) + (m.getY() < ici.getY() - 2 ? ", plus bas" : m.getY() > ici.getY() + 2 ? ", plus haut" : "") + ".", m);
            }
            case "arbre" -> {
                BlockPos a = Actions.chercherArbre((ServerLevel) l, ici, 32);
                yield a == null ? new Fait("Pas d'arbre à moins de 32 blocs, chef.", null) : new Fait("Il y a un arbre " + distance(ici, a) + ".", a);
            }
            default -> new Fait("Je ne saurais pas te dire, chef.", null);
        };
    }

    static Fait ouvriers(Travailleur t) {
        List<Travailleur> l = t.level().getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(128), x -> x != t && x.isAlive() && t.chef != null && t.chef.equals(x.chef));
        if (l.isEmpty()) return new Fait("Je ne vois aucun de mes collègues dans les parages.", null);
        l.sort(Comparator.comparingDouble(t::distanceToSqr));
        List<String> r = new ArrayList<>();
        for (Travailleur x : l.subList(0, Math.min(4, l.size()))) r.add(x.prenom + " (" + Metier.nom(x.metier).toLowerCase() + ") " + distance(t.blockPosition(), x.blockPosition()));
        return new Fait("Dans les parages : " + String.join(", ", r) + ".", l.get(0).blockPosition());
    }

    static String minerai(BlockState s) {
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        for (String[] m : new String[][]{{"diamond", "diamant"}, {"iron", "fer"}, {"gold", "or"}, {"copper", "cuivre"}, {"coal", "charbon"},
                {"redstone", "redstone"}, {"lapis", "lapis"}, {"emerald", "émeraude"}, {"zinc", "zinc"}})
            if (id.contains(m[0])) return m[1];
        return id.replace('_', ' ');
    }

    /** Les faits utiles à l'IA pour cette phrase (les questions du répertoire qui pourraient la concerner), en une ligne. */
    static String faits(Travailleur t, ServerPlayer chef, String phrase, List<Repertoire.Entree> candidats) {
        List<String> r = new ArrayList<>();
        for (Repertoire.Entree e : candidats)
            if (e.comportement().equals("info") && e.pour(t.metier) && r.size() < 3) {
                String f = fait(t, e.arg(), chef, phrase).texte;
                if (!r.contains(f)) r.add(f);
            }
        if (candidats.stream().anyMatch(e -> Set.of("donner", "jeter", "tenir").contains(e.comportement()))) r.add("Sur lui : " + inventaire(t) + ".");
        return String.join(" ", r);
    }

    // --- Paroles ----------------------------------------------------------------------------------------------

    /** Ce qu'il dit sans IA pour un genre (blague, dicton…), ou calculé (se présenter, compter…). */
    static String parole(Travailleur t, String genre, ServerPlayer chef) {
        String s = switch (genre) {
            case "presentation" -> "Je m'appelle " + t.prenom + ", " + Metier.nom(t.metier).toLowerCase() + " (" + t.niveau + " étoile" + (t.niveau > 1 ? "s" : "")
                    + "), à ton service, chef. On me dit " + IA.TEMPERAMENTS.getOrDefault(t.temperament, "ordinaire").split(",")[0] + ".";
            case "rapport" -> t.rapport();
            case "compter" -> "Un, deux, trois, quatre, cinq, six, sept, huit, neuf… dix !";
            case "alphabet" -> "A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y… et Z !";
            default -> Repertoire.parole(genre, new java.util.Random(t.getRandom().nextLong()));
        };
        return s.replace("{prenom}", t.prenom).replace("{metier}", Metier.nom(t.metier).toLowerCase()).replace("{chef}", t.chefNom.isEmpty() ? "chef" : t.chefNom);
    }

    /** « Dis à Raoul que le repas est prêt » → « le repas est prêt » (ce qu'il ira lui dire de la part du chef). */
    static String message(String phrase, String cible) {
        String p = phrase.strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)\\b(?:qu'|que |de |d')(.+)$").matcher(p);
        String s = m.find() ? m.group(1).strip() : "";
        if (s.isEmpty()) return "le chef te demande.";
        return s.replaceAll("[.!]+$", "") + ".";
    }

    // --- Vitesse ----------------------------------------------------------------------------------------------

    static final ResourceLocation VITESSE = ResourceLocation.fromNamespaceAndPath(ColoniePnj.MODID, "ordre_vitesse");

    static void vitesse(Travailleur t, String v, long maintenant) {
        var a = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        a.removeModifier(VITESSE);
        t.memoire.remove("vitesse_fin");
        if (v.equals("normal")) return;
        a.addOrUpdateTransientModifier(new AttributeModifier(VITESSE, v.equals("rapide") ? 0.3 : -0.45, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        t.memoire.putLong("vitesse_fin", maintenant + 3 * 60 * 20);
    }

    /** Une vitesse demandée ne dure que trois minutes (appelé toutes les secondes ; aussi au rechargement). */
    static void verifierVitesse(Travailleur t) {
        var a = t.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) return;
        long fin = t.memoire.getLong("vitesse_fin");
        if (fin > 0 && t.level().getGameTime() >= fin) { a.removeModifier(VITESSE); t.memoire.remove("vitesse_fin"); }
        else if (fin == 0 && a.getModifier(VITESSE) != null) a.removeModifier(VITESSE);
    }

    // --- Travail : ce qu'il accepte de casser ou de poser ----------------------------------------------------------

    /** Un bloc naturel qu'il peut casser (pas une construction, pas un coffre, pas la roche du fond). */
    static boolean cassable(net.minecraft.world.level.Level l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.getDestroySpeed(l, p) < 0 || l.getBlockEntity(p) != null || Monde.construit(l, p)) return false;
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(BlockTags.LOGS)
                || s.is(BlockTags.LEAVES) || s.is(Blocks.CLAY) || s.is(BlockTags.SNOW) || s.is(Blocks.ICE) || s.is(BlockTags.TERRACOTTA)
                || s.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.MOSSY_COBBLESTONE)
                || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(Blocks.MAGMA_BLOCK);
    }

    static String verifierTravail(Travailleur t, String mode, BlockPos vu) {
        var l = t.level();
        switch (mode) {
            case "miner_la", "trou" -> {
                if (!cassable(l, vu)) return "Je ne casse pas ça, chef : ce n'est pas de la roche ou de la terre naturelle.";
                if (vu.distSqr(t.blockPosition()) > 32 * 32) return "C'est trop loin, chef.";
            }
            case "tunnel", "salle", "carriere" -> { if (vu.distSqr(t.blockPosition()) > 24 * 24) return "C'est trop loin pour commencer ce chantier, chef."; }
            case "abattre_la" -> {
                if (!l.getBlockState(vu).is(BlockTags.LOGS) && !l.getBlockState(vu).is(BlockTags.LEAVES)) return "Ce n'est pas un arbre, ça, chef.";
                if (pied(l, vu) == null) return "Ce bois-là n'est pas un arbre, chef : on dirait une construction.";
            }
            case "planter_la", "torche_la" -> {
                if (vu.distSqr(t.blockPosition()) > 24 * 24) return "C'est trop loin, chef.";
                if (!l.getBlockState(vu.above()).canBeReplaced()) return "Il n'y a pas de place, là.";
                String f = mode.equals("torche_la") ? "minecraft:torch" : "#minecraft:saplings";
                if (t.compter(Actions.Filtre.de(f)) == 0) return mode.equals("torche_la") ? "Je n'ai pas de torche sur moi, chef." : "Je n'ai pas de pousse sur moi, chef.";
            }
            case "planter" -> { if (t.compter(Actions.Filtre.de("#minecraft:saplings")) == 0) return "Je n'ai pas de pousses à planter, chef."; }
            case "foret", "defricher_zone" -> { if (vu.distSqr(t.blockPosition()) > 48 * 48) return "C'est trop loin, chef."; }
            case "nourrir", "tondre", "abattre_betes", "chasser" -> { if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef."; }
            case "champ", "recolter", "pain", "oublier_champ" -> {
                if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef.";
                if (mode.equals("champ") && vu.distSqr(t.blockPosition()) > 48 * 48) return "C'est trop loin, chef.";
            }
            case "planches", "charbon" -> {
                if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef.";
                if (mode.equals("charbon") && l.isLoaded(t.bureau)) {
                    BlockPos f = Actions.Charbon.fourneau(l, t.bureau);
                    if (f == null) return "Il me faut un fourneau près du poste pour faire du charbon de bois, chef.";
                    if (l.getBlockEntity(f) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity fb && !Actions.Charbon.vide(fb))
                        return "Le fourneau est occupé, chef.";
                }
            }
            case "soigner", "soigner_blesses" -> { if (t.bureau == null) return "Il me faut un poste de travail pour ça, chef."; }
            case "torches" -> {
                if (t.compter(Actions.Filtre.de("minecraft:torch")) == 0 && Bureau.entite(t) == null) return "Je n'ai pas de torches, chef.";
            }
            default -> {}
        }
        return null;
    }

    /** Le pied de l'arbre dont fait partie ce bois (null si ce n'est pas un arbre naturel). */
    static BlockPos pied(net.minecraft.world.level.Level l, BlockPos b) {
        BlockPos p = b;
        if (l.getBlockState(p).is(BlockTags.LEAVES)) {
            for (int dy = 0; dy < 6 && !l.getBlockState(p).is(BlockTags.LOGS); dy++) p = p.below();
            if (!l.getBlockState(p).is(BlockTags.LOGS)) return null;
        }
        while (l.getBlockState(p.below()).is(BlockTags.LOGS)) p = p.below();
        return l instanceof ServerLevel sl && Actions.arbre(sl, p) != null ? p : null;
    }

    // --- Sons, musique, particules -------------------------------------------------------------------------------

    static void jouer(Travailleur t, String son, float hauteur) {
        var l = t.level();
        double x = t.getX(), y = t.getY() + 1.5, z = t.getZ();
        Holder<SoundEvent> h = switch (son) {
            case "talons" -> SoundEvents.ARMOR_EQUIP_IRON;
            case "sifflet" -> SoundEvents.NOTE_BLOCK_FLUTE;
            case "compter" -> SoundEvents.NOTE_BLOCK_HAT;
            case "cor" -> SoundEvents.GOAT_HORN_SOUND_VARIANTS.get(0);
            default -> null;
        };
        if (h != null) { l.playSound(null, x, y, z, h, SoundSource.NEUTRAL, 1.0f, son.equals("sifflet") ? 2.0f : hauteur); return; }
        SoundEvent s = switch (son) {
            case "cochon" -> SoundEvents.PIG_AMBIENT;
            case "vache" -> SoundEvents.COW_AMBIENT;
            case "mouton" -> SoundEvents.SHEEP_AMBIENT;
            case "poule" -> SoundEvents.CHICKEN_AMBIENT;
            case "chien" -> SoundEvents.WOLF_AMBIENT;
            case "loup" -> SoundEvents.WOLF_HOWL;
            case "chat" -> SoundEvents.CAT_AMBIENT;
            case "cheval" -> SoundEvents.HORSE_AMBIENT;
            case "ane" -> SoundEvents.DONKEY_AMBIENT;
            case "chevre" -> SoundEvents.GOAT_AMBIENT;
            case "perroquet" -> SoundEvents.PARROT_AMBIENT;
            case "renard" -> SoundEvents.FOX_AMBIENT;
            case "grenouille" -> SoundEvents.FROG_AMBIENT;
            case "abeille" -> SoundEvents.BEE_POLLINATE;
            case "villageois" -> SoundEvents.VILLAGER_AMBIENT;
            case "zombie" -> SoundEvents.ZOMBIE_AMBIENT;
            case "squelette" -> SoundEvents.SKELETON_AMBIENT;
            case "creeper" -> SoundEvents.CREEPER_PRIMED;
            case "enderman" -> SoundEvents.ENDERMAN_AMBIENT;
            case "araignee" -> SoundEvents.SPIDER_AMBIENT;
            case "ghast" -> SoundEvents.GHAST_AMBIENT;
            case "ours" -> SoundEvents.POLAR_BEAR_WARNING;
            case "chauve_souris" -> SoundEvents.BAT_AMBIENT;
            case "dauphin" -> SoundEvents.DOLPHIN_AMBIENT;
            case "panda" -> SoundEvents.PANDA_SNEEZE;
            case "tortue" -> SoundEvents.TURTLE_AMBIENT_LAND;
            case "slime" -> SoundEvents.SLIME_JUMP;
            case "rot" -> SoundEvents.PLAYER_BURP;
            case "cri" -> SoundEvents.PILLAGER_CELEBRATE;
            case "applaudir" -> SoundEvents.WOODEN_BUTTON_CLICK_ON;
            case "hourra" -> SoundEvents.VILLAGER_CELEBRATE;
            case "rire" -> SoundEvents.WITCH_CELEBRATE;
            case "grogner" -> SoundEvents.VINDICATOR_AMBIENT;
            case "soupir" -> SoundEvents.VILLAGER_NO;
            case "pas" -> SoundEvents.STONE_STEP;
            case "frappe" -> SoundEvents.PLAYER_ATTACK_STRONG;
            case "epee" -> SoundEvents.PLAYER_ATTACK_SWEEP;
            case "bouclier" -> SoundEvents.SHIELD_BLOCK;
            case "scie" -> SoundEvents.UI_STONECUTTER_TAKE_RESULT;
            default -> null;
        };
        // Une voix d'homme pour imiter : un peu plus grave que l'animal (sauf le creeper, qui doit faire peur pour de vrai).
        if (s != null) l.playSound(null, x, y, z, s, SoundSource.NEUTRAL, 1.0f, son.equals("creeper") ? 1.0f : hauteur);
    }

    static ParticleOptions particule(String p) {
        return switch (p) {
            case "coeur" -> ParticleTypes.HEART;
            case "note" -> ParticleTypes.NOTE;
            case "colere" -> ParticleTypes.ANGRY_VILLAGER;
            case "content" -> ParticleTypes.HAPPY_VILLAGER;
            case "sueur", "eclaboussure" -> ParticleTypes.SPLASH;
            case "larmes" -> ParticleTypes.FALLING_WATER;
            case "magie" -> ParticleTypes.ENCHANT;
            case "fumee" -> ParticleTypes.SMOKE;
            default -> null;
        };
    }

    static void particules(Travailleur t, String p, int n) {
        ParticleOptions o = particule(p);
        if (o != null && t.level() instanceof ServerLevel sl)
            sl.sendParticles(o, t.getX(), t.getY() + (p.equals("larmes") ? 1.5 : 2.1), t.getZ(), n, 0.3, 0.2, 0.3, p.equals("note") ? 1 : 0.02);
    }

    static Holder<SoundEvent> instrument(String i) {
        return switch (i) {
            case "flute" -> SoundEvents.NOTE_BLOCK_FLUTE;
            case "harpe" -> SoundEvents.NOTE_BLOCK_HARP;
            case "guitare" -> SoundEvents.NOTE_BLOCK_GUITAR;
            case "tambour" -> SoundEvents.NOTE_BLOCK_BASEDRUM;
            case "caisse" -> SoundEvents.NOTE_BLOCK_SNARE;
            case "baguettes" -> SoundEvents.NOTE_BLOCK_HAT;
            case "cloche" -> SoundEvents.NOTE_BLOCK_BELL;
            case "carillon" -> SoundEvents.NOTE_BLOCK_CHIME;
            case "xylophone" -> SoundEvents.NOTE_BLOCK_XYLOPHONE;
            case "vibraphone" -> SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE;
            case "cloche_vache" -> SoundEvents.NOTE_BLOCK_COW_BELL;
            case "didgeridoo" -> SoundEvents.NOTE_BLOCK_DIDGERIDOO;
            case "basse" -> SoundEvents.NOTE_BLOCK_BASS;
            case "banjo" -> SoundEvents.NOTE_BLOCK_BANJO;
            case "bit" -> SoundEvents.NOTE_BLOCK_BIT;
            default -> SoundEvents.NOTE_BLOCK_PLING;
        };
    }

    /** Des airs anciens (domaine public) et deux danses, en notes de bloc musical (0 = fa dièse grave, -1 = silence). */
    static final int[][] AIRS = {
            {1, 1, 1, 3, 5, -1, 3, -1, 1, 5, 3, 3, 1, -1, -1, -1},              // Au clair de la lune
            {1, 3, 5, 1, 1, 3, 5, 1, 5, 6, 8, -1, 5, 6, 8, -1},                 // Frère Jacques
            {8, 6, 5, 3, 5, 6, 8, -1, 10, 8, 6, 5, 3, 5, 1, -1},                // une gigue
            {13, 13, 15, 13, 10, -1, 8, 10, 13, 13, 15, 17, 15, -1, 13, -1},    // une ronde
            {6, 8, 10, 6, 6, 8, 10, 6, 10, 11, 13, -1, 10, 11, 13, -1}};        // Frère Jacques, plus haut
    static final String[] INSTRUMENTS_HASARD = {"flute", "harpe", "guitare", "banjo", "cloche", "xylophone"};

    static String gesteInstrument(String i) {
        return switch (i) {
            case "flute", "didgeridoo" -> "jouer_flute";
            case "tambour", "caisse", "baguettes", "cloche", "carillon", "xylophone", "vibraphone", "cloche_vache" -> "tambour";
            default -> "jouer_luth";
        };
    }

    // --- L'action qui joue la consigne -----------------------------------------------------------------------------

    /** Un message à porter : 2 minutes au plus pour rattraper l'ouvrier ; il le hèle 7 s au plus (l'autre reste figé 8 s au plus). */
    static final int MESSAGE_MAX = 20 * 120, HELER_MAX = 140;

    static final class Faire extends Actions.Action {
        final Consigne c;
        final Repertoire.Entree e;
        Actions.Action sous;
        String raisonSous = "";
        Vec3 but;
        Entity vise;
        int etape, vides;
        /** Ticks pendant lesquels le messager a hélé l'ouvrier visé (il ne le fige pas plus de {@link #HELER_MAX}). */
        int hele;
        boolean arrive;
        final List<BlockPos> chantier = new java.util.LinkedList<>();
        /** Chantier selon un plan : où poser une torche, liquides bouchés, blocs laissés (pas à nous), sac vidé, deuxième passe. */
        final java.util.Set<BlockPos> torches = new java.util.HashSet<>();
        /** D'où creuser chaque bloc du plan (le sol de la tranche d'avant, la couche au-dessus) : un bloc dans la paroi ne s'atteint pas en marchant vers lui. */
        final Map<BlockPos, BlockPos> debout = new java.util.HashMap<>();
        BlockPos approche, aCote;
        int bouchons, laisses;
        /** Forêt : pousses prévues et plantées ; planches et charbon : le travail au poste. */
        int voulu, plantees;
        /** Défrichage : rangs déjà relevés, plantes trouvées (après les arbres), le terrain (élargi) où ramasser à la fin. */
        int releves;
        final List<BlockPos> plantesZone = new ArrayList<>();
        AABB zone;
        boolean releve;
        Actions.Action atelier;
        boolean vide, repasse, descend, minerai;
        String instrument;
        int[] air;

        Faire(Consigne c, Repertoire.Entree e) { super("faire"); this.c = c; this.e = e; }

        @Override
        void fin(Travailleur t) {
            super.fin(t);
            if (sous != null) sous.fin(t);
            sous = null;
            t.setGeste("");
        }

        /** Fait avancer l'action déléguée (marcher, casser, abattre…) ; null tant qu'elle est en cours. */
        Actions.Etat deleguer(Travailleur t, Actions.Action a) {
            if (sous == null) sous = a;
            Actions.Etat r = sous.tick(t);
            sous.age++;
            if (r == Actions.Etat.EN_COURS) return null;
            raisonSous = sous.raison;
            sous.fin(t);
            sous = null;
            return r;
        }

        Actions.Etat ok(Travailleur t) { fini(t, c); return Actions.Etat.OK; }

        Actions.Etat rate(Travailleur t, String dit) {
            if (dit != null && !dit.isEmpty()) Voix.dire(t, dit, "inquiet");
            fini(t, c);
            return echec(raisonSous.isEmpty() ? "consigne" : raisonSous);
        }

        @Override
        Actions.Etat tick(Travailleur t) {
            if (c.fin > 0 && t.level().getGameTime() >= c.fin) return ok(t);
            if (age == 0 && !e.opt("objet", "").isEmpty()) tenir(t, e.opt("objet", ""));
            return switch (e.comportement()) {
                case "geste" -> geste(t, e.arg(), e.opt("regard", "chef"));
                case "regarder" -> geste(t, e.opt("geste", ""), e.arg());
                case "arret" -> { t.getNavigation().stop(); yield Actions.Etat.EN_COURS; }
                case "parler", "info" -> {
                    String g = e.opt("geste", e.comportement().equals("info") && c.lieu != null ? "pointer" : "parler");
                    if (c.lieu != null && e.comportement().equals("info")) { t.getNavigation().stop(); t.getLookControl().setLookAt(Vec3.atCenterOf(c.lieu)); t.setGeste(g); effets(t); yield Actions.Etat.EN_COURS; }
                    yield geste(t, g, e.opt("regard", "chef"));
                }
                case "son" -> son(t);
                case "musique" -> musique(t);
                case "manger" -> geste(t, "manger", "chef");
                case "aller", "formation" -> aller(t);
                case "suivre" -> suivre(t);
                case "donner" -> donner(t);
                case "jeter" -> jeter(t);
                case "prendre" -> {
                    Actions.Etat r = deleguer(t, new Actions.Prendre(Actions.Filtre.de(c.texte), Math.max(1, c.nombre)));
                    yield r == null ? Actions.Etat.EN_COURS : r == Actions.Etat.OK ? ok(t) : rate(t, "Il n'y en a pas au dépôt, chef.");
                }
                case "deposer" -> {
                    Metier m = Metier.de(t.metier);
                    Actions.Etat r = deleguer(t, new Actions.Deposer(m == null ? List.of() : m.garder));
                    yield r == null ? Actions.Etat.EN_COURS : r == Actions.Etat.OK ? ok(t) : rate(t, raisonSous.equals("depot_plein") ? "Le dépôt est plein, chef." : "Je n'arrive pas au poste, chef.");
                }
                case "tenir" -> {
                    if (age == 0 && !tenir(t, c.texte)) yield rate(t, "Je n'en ai pas sur moi, chef.");
                    yield geste(t, "montrer", "chef");
                }
                case "ranger" -> {
                    ItemStack m = t.getMainHandItem();
                    if (!m.isEmpty()) { t.ranger(m.copy()); t.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); }
                    yield ok(t);
                }
                case "dormir" -> dormir(t);
                case "utiliser" -> utiliser(t);
                case "combat" -> combat(t);
                case "travail" -> travail(t);
                case "message" -> message(t);
                default -> ok(t);
            };
        }

        // Gestes : sur place, le regard posé où il faut, avec ses sons et ses particules.
        Actions.Etat geste(Travailleur t, String g, String regard) {
            t.getNavigation().stop();
            regarder(t, regard);
            t.setGeste(g.isEmpty() ? "" : g + (e.drapeau("tourne") ? "+tourne" : ""));
            effets(t);
            return Actions.Etat.EN_COURS;
        }

        void effets(Travailleur t) {
            String son = e.opt("son", "");
            int rythme = Integer.parseInt(e.opt("rythme", "20"));
            if (!son.isEmpty() && (rythme == 0 ? age == 2 : age % Math.max(4, rythme) == 2)) jouer(t, son, 0.9f + t.getRandom().nextFloat() * 0.2f);
            String p = e.opt("part", "");
            if (!p.isEmpty() && age % 10 == 0) particules(t, p, p.equals("note") ? 1 : 3);
            if (e.drapeau("saut") && t.onGround() && age % 14 == 0) t.getJumpControl().jump();
        }

        void regarder(Travailleur t, String regard) {
            ServerPlayer chef = t.chefJoueur();
            Vec3 o = t.getEyePosition(), av = Vec3.directionFromRotation(0, t.yBodyRot);
            switch (regard) {
                case "chef" -> { if (chef != null && chef.level() == t.level() && chef.distanceToSqr(t) < 48 * 48) t.getLookControl().setLookAt(chef); }
                case "devant" -> {
                    if (age == 0 && chef != null && chef.level() == t.level()) { // il se tourne vers le chef, puis regarde droit devant lui
                        float yaw = (float) (Mth.atan2(chef.getZ() - t.getZ(), chef.getX() - t.getX()) * Mth.RAD_TO_DEG) - 90;
                        t.setYRot(yaw); t.setYBodyRot(yaw); t.setYHeadRot(yaw);
                        av = Vec3.directionFromRotation(0, yaw);
                    }
                    t.getLookControl().setLookAt(o.add(av.scale(10)));
                }
                case "ciel" -> t.getLookControl().setLookAt(o.add(av.scale(2)).add(0, 10, 0));
                case "sol" -> t.getLookControl().setLookAt(o.add(av.scale(2)).add(0, -2.5, 0));
                case "la" -> { if (c.lieu != null) t.getLookControl().setLookAt(Vec3.atCenterOf(c.lieu)); }
                case "cible" -> { Entity x = vise != null ? vise : (vise = entite(t, c.entite)); if (x != null) t.getLookControl().setLookAt(x); }
                case "autour" -> {
                    double a = age * 0.05;
                    t.getLookControl().setLookAt(o.add(Math.cos(a) * 10, 0, Math.sin(a) * 10));
                }
                case "dos" -> {
                    if (chef != null && chef.level() == t.level()) t.getLookControl().setLookAt(o.add(o.subtract(chef.getEyePosition()).multiply(1, 0, 1).normalize().scale(8)));
                }
                default -> {}
            }
        }

        Actions.Etat son(Travailleur t) {
            int fois = Math.max(1, c.nombre);
            String g = e.opt("geste", "");
            if (!g.isEmpty()) t.setGeste(g);
            t.getNavigation().stop();
            regarder(t, e.opt("regard", "chef"));
            if (age % 25 == 0 && age / 25 < fois) jouer(t, e.arg(), 0.85f + t.getRandom().nextFloat() * 0.2f);
            String p = e.opt("part", "");
            if (!p.isEmpty() && age % 10 == 0) particules(t, p, 2);
            if (e.drapeau("saut") && t.onGround() && age % 14 == 0) t.getJumpControl().jump();
            return age >= fois * 25 + 10 ? ok(t) : Actions.Etat.EN_COURS;
        }

        Actions.Etat musique(Travailleur t) {
            if (instrument == null) {
                instrument = e.arg().equals("hasard") ? INSTRUMENTS_HASARD[Math.floorMod(t.getUUID().hashCode(), INSTRUMENTS_HASARD.length)] : e.arg();
                air = AIRS[t.getRandom().nextInt(AIRS.length)];
            }
            t.getNavigation().stop();
            regarder(t, "chef");
            t.setGeste(gesteInstrument(instrument));
            if (age % 5 == 0) {
                int note = air[(age / 5) % air.length];
                boolean percussion = Set.of("tambour", "caisse", "baguettes").contains(instrument);
                if (percussion) {
                    String frappe = (age / 5) % 4 == 0 ? "tambour" : (age / 5) % 2 == 0 ? "caisse" : "baguettes";
                    t.level().playSound(null, t.getX(), t.getY() + 1.2, t.getZ(), instrument(frappe), SoundSource.RECORDS, 1.0f, 1.0f);
                } else if (note >= 0) {
                    float hauteur = (float) Math.pow(2, (note - 12) / 12.0);
                    t.level().playSound(null, t.getX(), t.getY() + 1.2, t.getZ(), instrument(instrument), SoundSource.RECORDS, 1.0f, hauteur);
                    if (t.level() instanceof ServerLevel sl) sl.sendParticles(ParticleTypes.NOTE, t.getX(), t.getY() + 2.2, t.getZ(), 0, note / 24.0, 0, 0, 1);
                }
            }
            return Actions.Etat.EN_COURS;
        }

        // Aller quelque part (et y rester, ou enchaîner une autre action).
        Actions.Etat aller(Travailleur t) {
            if (!arrive) {
                if (vise == null && c.entite != null) vise = entite(t, c.entite);
                if (vise == null && c.lieu == null && ANIMAUX.containsKey(e.arg())) vise = animal(t, e.arg());
                if (vise == null && c.lieu == null) return rate(t, c.entite != null ? "Je ne le vois plus, chef." : "Je n'en vois pas par ici, chef.");
                Vec3 cible = vise != null ? vise.position() : Vec3.atBottomCenterOf(c.lieu);
                double portee = e.comportement().equals("formation") ? 0.9 : vise != null ? 2.2 : e.arg().equals("la") ? 1.2 : 2.0;
                if (e.drapeau("vite")) t.setSprinting(true);
                if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(cible) > 9) { sous.fin(t); sous = null; }
                Actions.Etat r = deleguer(t, new Actions.Aller(cible, portee));
                if (r == null) return Actions.Etat.EN_COURS;
                t.setSprinting(false);
                if (r != Actions.Etat.OK) return rate(t, "Je n'arrive pas à y aller, chef.");
                arrive = true;
                String puis = e.opt("puis", "");
                if (!puis.isEmpty()) {
                    Repertoire.Entree p = Repertoire.PAR_ID.get(puis);
                    if (p != null && t.consigne == c) {
                        long fin = p.tenue() ? -1 : p.duree() > 0 ? t.level().getGameTime() + p.duree() * 20L : 0;
                        if (p.comportement().equals("parler")) { String s = parole(t, p.arg(), t.chefJoueur()); Voix.dire(t, s, "joyeux"); fin = t.level().getGameTime() + 100; }
                        t.consigne = new Consigne(p.id(), c.cible, c.texte, nombre(p), c.rang, c.total, fin, c.lieu, vise != null ? vise.getUUID() : c.entite);
                        return Actions.Etat.OK;
                    }
                }
                if (!c.tenue()) return ok(t);
            }
            // Arrivé : il tient la place, face au chef (ou dans la direction du rang).
            t.getNavigation().stop();
            String regard = e.comportement().equals("formation") && Set.of("file", "derriere").contains(e.arg()) ? "devant" : e.opt("regard", "chef");
            if (e.comportement().equals("formation") && Vec3.atBottomCenterOf(c.lieu).distanceToSqr(t.position()) > 4) { arrive = false; return Actions.Etat.EN_COURS; }
            regarder(t, regard);
            String g = e.opt("geste", "");
            if (e.comportement().equals("formation") && g.equals("garde_a_vous") && !t.metier.equals("guerrier") && e.id().equals("haie_honneur")) g = "salut_militaire";
            t.setGeste(g);
            return Actions.Etat.EN_COURS;
        }

        Actions.Etat suivre(Travailleur t) {
            if (vise == null || !vise.isAlive() || vise.level() != t.level()) vise = entite(t, c.entite);
            if (vise == null) return rate(t, "Je l'ai perdu de vue, chef.");
            double d = t.distanceTo(vise);
            t.getLookControl().setLookAt(vise);
            if (d <= 3) { if (sous != null) { sous.fin(t); sous = null; } t.getNavigation().stop(); return Actions.Etat.EN_COURS; }
            if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(vise.position()) > 4) { sous.fin(t); sous = null; }
            t.setSprinting(d > 10);
            Actions.Etat r = deleguer(t, new Actions.Aller(vise.position(), 2.5).mobile(true));
            if (r == Actions.Etat.ECHEC && raisonSous.equals("bloque")) return rate(t, "Je n'arrive pas à le suivre, chef.");
            return Actions.Etat.EN_COURS;
        }

        Actions.Etat donner(Travailleur t) {
            if (vise == null) vise = e.drapeau("a") ? entite(t, c.entite) : t.chefJoueur();
            if (vise == null || vise.level() != t.level()) return rate(t, "Il n'est plus là.");
            if (t.distanceTo(vise) > 2.5) {
                if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(vise.position()) > 4) { sous.fin(t); sous = null; }
                Actions.Etat r = deleguer(t, new Actions.Aller(vise.position(), 2.2).mobile(true));
                if (r == Actions.Etat.ECHEC) return rate(t, "Je n'arrive pas jusqu'à toi, chef.");
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(vise);
            if (age < 5) return Actions.Etat.EN_COURS;
            Actions.Filtre f = Actions.Filtre.de(c.texte);
            int reste = c.nombre > 0 ? c.nombre : Integer.MAX_VALUE;
            List<ItemStack> donnes = new ArrayList<>();
            for (int i = 0; i < t.inventaire.getContainerSize() && reste > 0; i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (s.isEmpty() || !f.objet(s)) continue;
                ItemStack pris = s.split(Math.min(reste, s.getCount()));
                reste -= pris.getCount();
                donnes.add(pris);
            }
            if (donnes.isEmpty() && f.objet(t.getMainHandItem()) && !c.texte.equals("tout")) donnes.add(t.getMainHandItem().split(Math.min(reste, t.getMainHandItem().getCount())));
            t.inventaire.setChanged();
            if (donnes.isEmpty()) return rate(t, "Finalement, je n'en ai plus, chef.");
            t.swing(InteractionHand.MAIN_HAND);
            for (ItemStack s : donnes) {
                if (vise instanceof Player p) { if (!p.getInventory().add(s)) p.drop(s, false); }
                else if (vise instanceof Travailleur x) x.ranger(s);
            }
            t.level().playSound(null, vise.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.2f);
            return ok(t);
        }

        Actions.Etat jeter(Travailleur t) {
            Actions.Filtre f = Actions.Filtre.de(c.texte);
            Vec3 av = Vec3.directionFromRotation(0, t.getYRot());
            int jetes = 0;
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (s.isEmpty() || !f.objet(s)) continue;
                var ie = new net.minecraft.world.entity.item.ItemEntity(t.level(), t.getX() + av.x, t.getY() + 1, t.getZ() + av.z, s.copy());
                ie.setDeltaMovement(av.scale(0.25).add(0, 0.15, 0));
                ie.setPickUpDelay(40);
                t.level().addFreshEntity(ie);
                t.inventaire.setItem(i, ItemStack.EMPTY);
                jetes++;
            }
            // Il ne ramasse pas tout de suite ce qu'on lui a fait jeter.
            t.memoire.putLong("sans_ramasser", t.level().getGameTime() + 1200);
            t.swing(InteractionHand.MAIN_HAND);
            return jetes > 0 ? ok(t) : rate(t, "Je n'ai rien de tel sur moi, chef.");
        }

        Actions.Etat dormir(Travailleur t) {
            boolean jour = t.level().isDay() && (t.level().getDayTime() % 24000) < 11000;
            if (age > 1200 && jour && t.fatigue < 30) { if (t.isSleeping()) t.stopSleeping(); return ok(t); } // réveillé par le jour, reposé
            if (t.monLit() != null) {
                Actions.Etat r = deleguer(t, sous instanceof Actions.Reposer ? sous : new Actions.Reposer());
                if (r != null) sous = null; // il refait le point toutes les 30 s, et se recouche
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.setGeste("dormir");
            return Actions.Etat.EN_COURS;
        }

        static final Map<String, String> BLOCS = Map.of("porte", "#minecraft:wooden_doors", "cloche", "minecraft:bell", "levier", "minecraft:lever",
                "bouton", "#minecraft:buttons", "trappe", "#minecraft:wooden_trapdoors", "portillon", "#minecraft:fence_gates", "note", "minecraft:note_block");

        Actions.Etat utiliser(Travailleur t) {
            if (but == null) {
                BlockPos b = Monde.chercherBloc(t, Actions.Filtre.de(BLOCS.get(e.arg())), 16, t.blockPosition(), false, false);
                if (b == null) return rate(t, "Je n'en vois pas par ici, chef.");
                but = Vec3.atCenterOf(b);
            }
            BlockPos b = BlockPos.containing(but);
            if (!Actions.aPortee(t, b) || t.distanceToSqr(but) > 9) {
                Actions.Etat r = deleguer(t, new Actions.Aller(but, 2.0));
                if (r == Actions.Etat.ECHEC) return rate(t, "Je n'arrive pas jusque-là, chef.");
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(but);
            if (age < 8) return Actions.Etat.EN_COURS;
            ServerLevel sl = (ServerLevel) t.level();
            BlockState s = sl.getBlockState(b);
            t.swing(InteractionHand.MAIN_HAND);
            if (s.getBlock() instanceof BellBlock bell) bell.attemptToRing(sl, b, Direction.getNearest(t.getX() - but.x, 0, t.getZ() - but.z));
            else {
                FakePlayer fp = FakePlayerFactory.get(sl, Actions.PROFIL);
                fp.moveTo(t.getX(), t.getY(), t.getZ(), t.getYRot(), t.getXRot());
                Direction face = Direction.getNearest(t.getX() - but.x, t.getEyeY() - but.y, t.getZ() - but.z);
                s.useWithoutItem(sl, fp, new BlockHitResult(but.relative(face, 0.5), face, b, false));
            }
            return ok(t);
        }

        // Combat : seulement des monstres (règle du serveur), jamais un joueur, un villageois ou un animal.
        Actions.Etat combat(Travailleur t) {
            Guerrier.armer(t);
            ServerPlayer chef = t.chefJoueur();
            switch (e.arg()) {
                case "cible" -> {
                    if (vise == null) vise = entite(t, c.entite);
                    if (!(vise instanceof LivingEntity m) || !m.isAlive()) return ok(t);
                    Actions.Etat r = deleguer(t, sous instanceof Actions.Attaquer ? sous : new Actions.Attaquer(m));
                    if (r == null) return Actions.Etat.EN_COURS;
                    if (!m.isAlive()) Voix.dire(t, "Il ne bougera plus, chef !", "fier");
                    return r == Actions.Etat.OK ? ok(t) : rate(t, "Je n'arrive pas à l'atteindre, chef.");
                }
                case "nettoyer" -> {
                    if (sous == null) {
                        List<LivingEntity> l = Monde.cibles(t, Math.max(8, c.nombre));
                        if (l.isEmpty()) {
                            if (++vides > 60) { Voix.dire(t, "La zone est sûre, chef.", "fier"); return ok(t); }
                            if (age % 40 == 0) regarder(t, "autour");
                            return Actions.Etat.EN_COURS;
                        }
                        vides = 0;
                        sous = new Actions.Attaquer(l.get(0));
                    }
                    deleguer(t, sous);
                    return age > 20 * 300 ? ok(t) : Actions.Etat.EN_COURS;
                }
                case "garder_ici", "proteger", "proteger_chef", "patrouiller" -> {
                    Entity protege = e.arg().equals("garder_ici") ? null : vise != null && vise.isAlive() ? vise : (vise = entite(t, c.entite));
                    if (!e.arg().equals("garder_ici") && protege == null) return rate(t, "Je ne le vois plus, chef.");
                    Vec3 centre = protege != null ? protege.position() : Vec3.atBottomCenterOf(c.lieu);
                    if (sous instanceof Actions.Attaquer) { if (deleguer(t, sous) != null) sous = null; return Actions.Etat.EN_COURS; }
                    LivingEntity m = menace(t, centre, protege);
                    // Il tient une porte ou un pont : il n'en bouge que pour un monstre tout proche ; plus loin, il le tire à l'arc s'il peut.
                    // Un tireur (squelette…) ou un monstre qui vient de le blesser : il ne reste pas planté sous ses coups, il va
                    // le chercher, sans s'écarter de plus de 16 blocs.
                    boolean tenir = e.arg().equals("garder_ici");
                    LivingEntity agresseur = t.getLastHurtByMob();
                    if (tenir && agresseur != null && Monde.hostile(agresseur) && Monde.recent(t, t.getLastHurtByMobTimestamp())) m = agresseur;
                    boolean dangereux = m != null && (m == agresseur || m instanceof net.minecraft.world.entity.monster.RangedAttackMob);
                    if (m != null && tenir && !dangereux && m.distanceToSqr(centre) > TENIR * TENIR && !(Guerrier.peutTirer(t) && t.getSensing().hasLineOfSight(m))) {
                        t.getLookControl().setLookAt(m);
                        m = null;
                    }
                    if (m != null) {
                        if (sous != null) sous.fin(t);
                        sous = tenir ? new Actions.Attaquer(m).tenir(centre, dangereux ? TENIR_TIREUR : TENIR) : new Actions.Attaquer(m);
                        return Actions.Etat.EN_COURS;
                    }
                    Vec3 place = centre;
                    double portee = protege != null ? 3 : 1.5;
                    if (e.arg().equals("patrouiller")) {
                        double a = (etape % 8) * Math.PI / 4;
                        place = centre.add(Math.cos(a) * 6, 0, Math.sin(a) * 6);
                        portee = 1.5;
                    }
                    if (t.position().distanceToSqr(place) > portee * portee) {
                        if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(place) > 4) { sous.fin(t); sous = null; }
                        Actions.Etat r = deleguer(t, new Actions.Aller(place, portee).mobile(protege != null));
                        if (r == Actions.Etat.OK && e.arg().equals("patrouiller")) etape++;
                        if (r == Actions.Etat.ECHEC && e.arg().equals("patrouiller")) etape++;
                        t.setGeste("");
                        return Actions.Etat.EN_COURS;
                    }
                    if (e.arg().equals("patrouiller")) { etape++; return Actions.Etat.EN_COURS; }
                    t.getNavigation().stop();
                    regarder(t, "autour");
                    t.setGeste(e.arg().equals("garder_ici") ? "en_garde" : "");
                    return Actions.Etat.EN_COURS;
                }
                case "charge" -> {
                    t.setSprinting(true);
                    t.setGeste("en_garde");
                    List<LivingEntity> l = Monde.cibles(t, 6);
                    if (!l.isEmpty() && !(sous instanceof Actions.Attaquer)) { if (sous != null) sous.fin(t); sous = new Actions.Attaquer(l.get(0)); }
                    if (sous instanceof Actions.Attaquer) { if (deleguer(t, sous) != null) sous = null; return Actions.Etat.EN_COURS; }
                    if (c.lieu == null) return ok(t);
                    Actions.Etat r = deleguer(t, new Actions.Aller(Vec3.atBottomCenterOf(c.lieu.above()), 2));
                    return r == null ? Actions.Etat.EN_COURS : ok(t);
                }
                case "repli" -> {
                    if (chef == null || chef.level() != t.level()) return ok(t);
                    t.setSprinting(true);
                    if (t.distanceTo(chef) <= 3) return ok(t);
                    if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(chef.position()) > 4) { sous.fin(t); sous = null; }
                    Actions.Etat r = deleguer(t, new Actions.Aller(chef.position(), 2.5).mobile(true));
                    return r == Actions.Etat.ECHEC ? rate(t, "") : Actions.Etat.EN_COURS;
                }
                default -> { return ok(t); }
            }
        }

        /** Un monstre qui menace l'endroit gardé ou la personne protégée (ou qui vient de l'attaquer). */
        LivingEntity menace(Travailleur t, Vec3 centre, Entity protege) {
            if (protege instanceof LivingEntity p) {
                LivingEntity a = p.getLastHurtByMob();
                if (a != null && a != t && Monde.hostile(a) && Monde.recent(p, p.getLastHurtByMobTimestamp())) return a; // un monstre : pas une bête, ni un golem
            }
            return t.level().getEntitiesOfClass(LivingEntity.class, new AABB(centre, centre).inflate(12), x -> Monde.hostile(x) && Math.abs(x.getY() - centre.y) < 6
                            && !t.inaccessibles.containsKey(x.getId())).stream().min(Comparator.comparingDouble(x -> x.distanceToSqr(centre))).orElse(null);
        }

        // Travail : casser, creuser, éclairer, abattre, planter, ramasser.
        Actions.Etat travail(Travailleur t) {
            var l = t.level();
            String m = e.arg();
            if (age == 0) {
                switch (m) {
                    case "miner_la", "tunnel", "trou", "salle", "carriere" -> { if (!t.equiper(Actions.Filtre.de("#minecraft:pickaxes"))) t.equiper(Actions.Filtre.de("#minecraft:shovels")); }
                    case "abattre_la", "abattre_autour", "defricher_zone" -> t.equiper(Actions.Filtre.de("#minecraft:axes"));
                    default -> {}
                }
                chantier.addAll(chantier(t));
            }
            if (sous != null) {
                Actions.Etat r = deleguer(t, sous);
                if (r == null) return Actions.Etat.EN_COURS;
                if (r == Actions.Etat.ECHEC && m.equals("foret") && etape == 2) plantees--; // une pousse pas posée
                if (r == Actions.Etat.ECHEC && Set.of("inaccessible", "pas_un_arbre").contains(raisonSous) && chantier.isEmpty())
                    return rate(t, "Je n'arrive pas à l'atteindre, chef.");
            }
            switch (m) {
                case "defricher_zone" -> { if (!releve && !(releve = relever(t, 4))) return Actions.Etat.EN_COURS; }
                case "ramasser" -> {
                    if (etape++ > 0) return ok(t);
                    sous = new Actions.Ramasser(Math.max(4, c.nombre), Actions.Filtre.de("tout"));
                    return Actions.Etat.EN_COURS;
                }
                case "abattre_autour" -> {
                    if (etape >= Math.max(1, c.nombre)) return ok(t);
                    BlockPos a = Actions.chercherArbre((ServerLevel) l, t.blockPosition(), 20, null, t);
                    if (a == null) { Voix.dire(t, etape == 0 ? "Je ne vois pas d'arbre par ici, chef." : "Plus d'arbre par ici, chef.", "calme"); return ok(t); }
                    etape++;
                    sous = new Actions.Abattre(a);
                    return Actions.Etat.EN_COURS;
                }
                case "foret" -> {
                    String[] x = c.texte.split("\\|");
                    Actions.Filtre pousse = Actions.Filtre.de(x.length > 1 ? x[1] : "#minecraft:saplings");
                    boolean carre = x.length > 2 && x[2].equals("carre");
                    if (etape == 0) {
                        etape = 1;
                        voulu = chantier.size();
                        if (voulu == 0) return rate(t, "Il n'y a pas de terre libre pour planter là, chef.");
                        // Il prend au dépôt les pousses qui lui manquent.
                        int manque = voulu - t.compter(pousse);
                        if (manque > 0 && Bureau.entite(t) != null) { sous = new Actions.Prendre(pousse, manque); return Actions.Etat.EN_COURS; }
                    }
                    if (etape == 1) {
                        etape = 2;
                        int a = t.compter(pousse), garde = carre ? a / 4 * 4 : a;
                        if (garde == 0) return rate(t, carre ? "Il me faut 4 pousses de ce bois par arbre, chef, et je n'en ai pas assez, ni au dépôt."
                                : "Je n'ai pas de pousses de ce bois, chef, ni sur moi ni au dépôt.");
                        while (chantier.size() > garde) chantier.remove(chantier.size() - 1);
                    }
                }
                case "soigner", "soigner_blesses" -> {
                    // Le guérisseur : son action dit elle-même ce qu'elle a fait.
                    if (etape++ == 0) { sous = Guerisseur.consigne(t, m, c); return Actions.Etat.EN_COURS; }
                    return raisonSous.isEmpty() ? ok(t) : rate(t, null);
                }
                case "champ", "recolter", "pain", "oublier_champ" -> {
                    // Le fermier : son action dit elle-même ce qu'elle a fait.
                    if (etape++ == 0) { sous = Fermier.consigne(t, m, c); return Actions.Etat.EN_COURS; }
                    return raisonSous.isEmpty() ? ok(t) : rate(t, null);
                }
                case "nourrir", "tondre", "abattre_betes" -> {
                    // L'éleveur : sa tournée des enclos dit elle-même ce qu'elle a fait.
                    if (etape++ == 0) { sous = Eleveur.consigne(t, m, c); return Actions.Etat.EN_COURS; }
                    return raisonSous.isEmpty() ? ok(t) : rate(t, null);
                }
                case "chasser" -> {
                    // Le chasseur : son tour de chasse dit lui-même ce qu'il a fait.
                    if (etape++ == 0) { sous = Chasseur.consigne(t, c); return Actions.Etat.EN_COURS; }
                    return raisonSous.isEmpty() ? ok(t) : rate(t, null);
                }
                case "planches", "charbon" -> {
                    if (etape++ == 0) {
                        atelier = m.equals("planches") ? new Actions.Planches(Actions.Filtre.de(c.texte), Math.max(1, c.nombre))
                                : new Actions.Charbon(Actions.Filtre.de(c.texte), Math.max(1, c.nombre));
                        sous = atelier;
                        return Actions.Etat.EN_COURS;
                    }
                    if (atelier instanceof Actions.Planches p) {
                        if (p.faites == 0) return rate(t, "Je n'ai plus de bûches, chef, ni sur moi ni au dépôt.");
                        Voix.dire(t, p.faites + " planches au dépôt, chef." + ("depot_plein".equals(raisonSous) ? " Le dépôt est plein : j'ai gardé le reste sur moi." : ""), "calme");
                        return ok(t);
                    }
                    if (atelier instanceof Actions.Charbon ch && ch.recu > 0) {
                        Voix.dire(t, ch.recu + " charbon" + (ch.recu > 1 ? "s" : "") + " de bois au dépôt, chef.", "calme");
                        return ok(t);
                    }
                    return rate(t, switch (raisonSous) {
                        case "pas_de_fourneau" -> "Il me faut un fourneau près du poste pour faire du charbon de bois, chef.";
                        case "pas_de_bois" -> "Je n'ai pas de bûches à cuire, chef, ni au dépôt.";
                        case "four_occupe" -> "Le fourneau est occupé, chef.";
                        case "pas_de_combustible" -> "Il me faut de quoi chauffer le fourneau, chef : du charbon, ou plus de bûches.";
                        default -> "Le feu n'a rien donné, chef.";
                    });
                }
                case "torches" -> {
                    if (t.compter(Actions.Filtre.de("minecraft:torch")) == 0) {
                        if (etape == 0 && Bureau.entite(t) != null) { etape = 1; sous = new Actions.Prendre(Actions.Filtre.de("minecraft:torch"), 16); return Actions.Etat.EN_COURS; }
                        return chantier.size() < Math.max(1, c.nombre) ? rate(t, "Je n'ai plus de torches, chef.") : ok(t);
                    }
                    etape = Math.max(etape, 1);
                    if (chantier.isEmpty()) { chantier.addAll(sombres(t, Math.max(1, c.nombre))); if (chantier.isEmpty()) { Voix.dire(t, "C'est déjà bien éclairé ici, chef.", "calme"); return ok(t); } etape = 2; }
                    break;
                }
                default -> {}
            }
            // Le chantier, bloc par bloc.
            while (!chantier.isEmpty()) {
                BlockPos p = chantier.remove(0);
                if (FOUILLES.contains(m)) {
                    String arret = fouiller(t, p);
                    if (arret != null) { Voix.dire(t, arret, "inquiet"); return ok(t); }
                    if (sous != null) return Actions.Etat.EN_COURS;
                    continue;
                }
                switch (m) {
                    case "miner_la", "tunnel", "trou", "defricher" -> {
                        if (l.getBlockState(p).isAir()) continue;
                        if (m.equals("defricher") ? !l.getBlockState(p).canBeReplaced() : !cassable(l, p)) continue;
                        if (lave(l, p)) { Voix.dire(t, "De la lave, chef ! Je m'arrête là.", "inquiet"); return ok(t); }
                        sous = new Actions.Casser(p);
                    }
                    case "abattre_la" -> sous = new Actions.Abattre(p);
                    case "torches", "torche_la" -> {
                        if (!l.getBlockState(p).canBeReplaced()) continue;
                        sous = new Actions.Poser(p, Actions.Filtre.de("minecraft:torch"));
                    }
                    case "planter", "planter_la", "foret" -> {
                        if (!l.getBlockState(p).canBeReplaced() || !l.getBlockState(p.below()).is(BlockTags.DIRT)) continue;
                        String[] x = c.texte.split("\\|");
                        sous = new Actions.Poser(p, Actions.Filtre.de(m.equals("foret") && x.length > 1 ? x[1] : "#minecraft:saplings"));
                        plantees++;
                    }
                    case "defricher_zone" -> {
                        BlockState s = l.getBlockState(p);
                        List<BlockPos> bois = s.is(BlockTags.LOGS) ? Actions.arbre((ServerLevel) l, p) : null;
                        if (bois == null && !plante(l, p, s)) continue;
                        // La place qu'il faut au sac (le bois de l'arbre, plus pousses et bâtons) : sinon au dépôt d'abord ;
                        // sans poste, ou le dépôt plein, il s'arrête et le dit (rien ne tombe par terre).
                        int place = bois != null ? (bois.size() + 63) / 64 + 2 : 1;
                        if (Monde.libres(t) < place) {
                            if (t.bureau == null) { Voix.dire(t, "Mon sac est plein, chef : je m'arrête là.", "inquiet"); return ok(t); }
                            if (vide) { Voix.dire(t, "Mon sac est plein, et le dépôt aussi, chef : je m'arrête là.", "inquiet"); return ok(t); }
                            vide = true;
                            chantier.add(0, p);
                            Metier mt = Metier.de(t.metier);
                            sous = new Actions.Deposer(mt == null ? List.of() : mt.garder);
                        } else {
                            vide = false;
                            sous = bois != null ? new Actions.Abattre(p) : new Actions.Casser(p);
                        }
                    }
                    default -> {}
                }
                if (sous != null) return Actions.Etat.EN_COURS;
            }
            if (m.equals("torches") && etape < 2) return Actions.Etat.EN_COURS;
            if (m.equals("defricher_zone") && etape == 0) { etape = 1; sous = new Actions.Ramasser(Fouille.TERRAIN_MAX, Actions.Filtre.de("tout"), zone); return Actions.Etat.EN_COURS; }
            if (m.equals("defricher_zone")) Voix.dire(t, "Le terrain est dégagé, chef : on peut y bâtir.", "calme");
            if (m.equals("foret")) Voix.dire(t, "La forêt est plantée, chef." + (plantees < voulu ? " Il me manquait des pousses : j'en ai mis " + plantees + " sur " + voulu + "." : ""), "calme");
            // Fini : une deuxième passe pour ce qui est tombé (gravier) ou n'a pas pu être pris du premier coup.
            if (FOUILLES.contains(m) && !repasse) { repasse = true; laisses = 0; chantier.addAll(chantier(t)); return Actions.Etat.EN_COURS; }
            if (FOUILLES.contains(m)) Voix.dire(t, (m.equals("tunnel") ? "Le tunnel est percé, chef." : m.equals("salle") ? "C'est creusé, chef." : "La carrière est vidée, chef.")
                    + (laisses > 0 ? " J'ai laissé quelques blocs : pas à nous, ou trop risqués." : ""), "calme");
            return ok(t);
        }

        /**
         * Un bloc d'un chantier selon un plan, avec les sécurités du mineur : jamais un bloc construit ni un contenant
         * ({@link #cassable}), ni une structure ou les terres d'un voisin ({@link Mineur#interdit}) ; un liquide contre le bloc
         * est d'abord bouché (pierre ou terre de son sac) ; sac plein, il va le vider au dépôt ; le gravier au-dessus, c'est
         * {@link Actions.Casser} qui s'en garde. Met dans {@code sous} ce qu'il faut faire (rien : bloc suivant). Renvoie
         * ce qu'il dit s'il doit s'arrêter, sinon null.
         */
        String fouiller(Travailleur t, BlockPos p) {
            var l = t.level();
            BlockState st = l.getBlockState(p);
            if (st.isAir()) {
                if (torches.remove(p) && l.getMaxLocalRawBrightness(p) < 8 && t.compter(Actions.Filtre.de("minecraft:torch")) > 0)
                    sous = new Actions.Poser(p, Actions.Filtre.de("minecraft:torch"));
                return null;
            }
            if (!cassable(l, p)) return null;
            if (Mineur.interdit(t, p, true)) { laisses++; return null; }
            // Un liquide contre le bloc : comme le mineur (Mineur.creuserCase), il bouche la lave, et l'eau sous terre loin de
            // toute construction (jamais un canal ni un puits) ; sinon, ou sans de quoi boucher, il laisse ce bloc.
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                String quoi = Monde.liquide(l, n);
                if (quoi == null) continue;
                boolean bouchable = l.getBlockState(n).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                        && ("lave".equals(quoi) || "eau".equals(quoi) && !l.canSeeSky(n.above()) && !Monde.construitAutour(l, n));
                if (!bouchable || Itineraire.caseDeChantier(t, true) < 0 || ++bouchons > BOUCHONS) { laisses++; return null; }
                chantier.add(0, p);
                sous = Actions.aPortee(t, n) ? new Actions.Boucher(n) : new Actions.Aller(Vec3.atCenterOf(p), 2);
                return null;
            }
            // Le bon outil : un minerai qu'il ne peut pas récolter (diamant à la pioche de pierre) serait perdu ; il le laisse.
            if (st.requiresCorrectToolForDrops() && !t.getMainHandItem().isCorrectToolForDrops(st)) {
                for (int i = 0; i < t.inventaire.getContainerSize(); i++)
                    if (t.inventaire.getItem(i).isCorrectToolForDrops(st)) { t.equiper(Actions.Filtre.de(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(t.inventaire.getItem(i).getItem()).toString())); break; }
                if (!t.getMainHandItem().isCorrectToolForDrops(st)) {
                    if (t.compter(Actions.Filtre.de(Mineur.PIOCHE)) == 0 && !t.getMainHandItem().is(net.minecraft.tags.ItemTags.PICKAXES))
                        return "Il me faut une pioche pour creuser ça, chef.";
                    if (!minerai) { minerai = true; Voix.dire(t, "Ce minerai demande une meilleure pioche, chef : je le laisse en place.", "calme"); }
                    laisses++;
                    return null;
                }
            }
            // Sac plein : au dépôt, même loin (Deposer y va), puis il reprend ; sans poste, ou dépôt plein, il s'arrête.
            if (Monde.libres(t) == 0) {
                if (t.bureau == null) return "Mon sac est plein, chef : je m'arrête là.";
                if (vide) return "Mon sac est plein, et le dépôt aussi, chef.";
                vide = true;
                Metier mt = Metier.de(t.metier);
                chantier.add(0, p);
                sous = new Actions.Deposer(mt == null ? List.of() : mt.garder);
                return null;
            }
            vide = false;
            // En descendant : il ne casse jamais le bloc où il se tient si le dessous est vide (une grotte) ; il le casse depuis
            // une place à côté, sur un appui solide qui n'est pas ce bloc (pas depuis le dessus) ; s'il n'y en a pas, il le laisse.
            if (descend && Monde.chute(l, p) > 0) {
                boolean dessus = t.getBoundingBox().inflate(0.05, 0, 0.05).intersects(p.getX(), p.getY() + 0.5, p.getZ(), p.getX() + 1, p.getY() + 3, p.getZ() + 1);
                if (dessus || !Actions.aPortee(t, p)) {
                    if (p.equals(aCote)) { laisses++; return null; }
                    aCote = p;
                    BlockPos place = null;
                    // À deux blocs : arrivé « à peu près » (Aller s'arrête à 0,8 bloc), son corps n'est plus au-dessus.
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        BlockPos q = p.above().relative(d, 2);
                        if (Monde.debout(l, q) && Monde.solide(l, q.below()) && !Monde.mortel(l, q)) { place = q; break; }
                    }
                    if (place == null) { laisses++; return null; }
                    chantier.add(0, p);
                    sous = new Actions.Aller(Vec3.atBottomCenterOf(place), 0.8);
                    return null;
                }
            }
            if (!Actions.aPortee(t, p) && !p.equals(approche) && debout.containsKey(p)) {
                approche = p;
                chantier.add(0, p);
                sous = new Actions.Aller(Vec3.atBottomCenterOf(debout.get(p)), 1.5);
                return null;
            }
            sous = new Actions.Casser(p);
            return null;
        }

        /** Les blocs à travailler, dans l'ordre. */
        List<BlockPos> chantier(Travailleur t) {
            var l = t.level();
            List<BlockPos> r = new ArrayList<>();
            BlockPos v = c.lieu;
            switch (e.arg()) {
                case "miner_la" -> r.add(v);
                case "trou" -> { for (int i = 0; i < Math.max(1, c.nombre); i++) r.add(v.below(i)); }
                case "tunnel", "salle", "carriere" -> {
                    // Le plan gardé dans la consigne ; une consigne d'avant (la direction seule) : le tunnel de toujours.
                    Fouille.Plan p = Fouille.Plan.de(c.texte);
                    if (p == null) p = new Fouille.Plan(Math.max(1, c.nombre), 1, 2, c.texte, false);
                    Direction d = Direction.byName(p.direction());
                    if (d == null || d.getAxis().isVertical()) d = Direction.fromYRot(t.getYRot());
                    descend = p.descend();
                    for (int[] b : Fouille.blocs(p)) {
                        BlockPos q = v.relative(d, b[0]).relative(d.getClockWise(), b[1]).above(b[2]);
                        r.add(q);
                        if (b[3] == 1) torches.add(q);
                        else debout.put(q, p.descend() ? q.above() : v.relative(d, b[0] - 1).relative(d.getClockWise(), b[1]));
                    }
                }
                case "abattre_la" -> { BlockPos p = pied(l, v); if (p != null) r.add(p); }
                case "torche_la", "planter_la" -> r.add(v.above());
                case "planter" -> {
                    for (BlockPos q : BlockPos.betweenClosed(t.blockPosition().offset(-6, -2, -6), t.blockPosition().offset(6, 2, 6))) {
                        if (r.size() >= Math.max(1, c.nombre)) break;
                        if ((q.getX() + q.getZ()) % 3 != 0 || !l.getBlockState(q).isAir() || !l.getBlockState(q.below()).is(BlockTags.DIRT) || !l.canSeeSky(q)) continue;
                        if (r.stream().anyMatch(x -> x.distSqr(q) < 9)) continue;
                        r.add(q.immutable());
                    }
                }
                case "foret" -> {
                    String[] x = c.texte.split("\\|");
                    Fouille.Plan p = Fouille.Plan.de(x[0]);
                    if (p == null) break;
                    Direction d = Direction.byName(p.direction());
                    if (d == null || d.getAxis().isVertical()) d = Direction.NORTH;
                    // Un carré de 4 pousses n'est gardé que si les 4 places sont bonnes, au même niveau.
                    Map<Integer, List<BlockPos>> groupes = new LinkedHashMap<>();
                    for (int[] b : Fouille.rangs(p, x.length > 2 && x[2].equals("carre")))
                        groupes.computeIfAbsent(b[2], k -> new ArrayList<>()).add(terre(t, v.relative(d, b[0]).relative(d.getClockWise(), b[1]), v.getY()));
                    for (List<BlockPos> g : groupes.values())
                        if (g.stream().allMatch(q -> q != null && q.getY() == g.get(0).getY())) r.addAll(g);
                }
                case "defricher" -> {
                    int ray = Math.max(2, c.nombre);
                    for (BlockPos q : BlockPos.betweenClosed(t.blockPosition().offset(-ray, -2, -ray), t.blockPosition().offset(ray, 2, ray))) {
                        BlockState s = l.getBlockState(q);
                        if (s.is(BlockTags.FLOWERS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN) || s.is(Blocks.LARGE_FERN) || s.is(Blocks.DEAD_BUSH))
                            r.add(q.immutable());
                    }
                    r.sort(Comparator.comparingDouble(q -> q.distSqr(t.blockPosition())));
                }
                default -> {}
            }
            return r;
        }

        /**
         * Défrichage : relève quelques rangs du terrain (un relevé de 32 x 32 d'un coup ferait un pic) ; les arbres naturels
         * vont dans {@code chantier}, herbes, fleurs et buissons dans {@code plantesZone}. Vrai quand tout est relevé.
         */
        boolean relever(Travailleur t, int rangs) {
            var l = t.level();
            BlockPos v = c.lieu;
            Fouille.Plan p = Fouille.Plan.de(c.texte);
            if (p == null) return true;
            Direction d = Direction.byName(p.direction());
            if (d == null || d.getAxis().isVertical()) d = Direction.NORTH;
            int f0 = -(p.longueur() - 1) / 2, c0 = -(p.largeur() - 1) / 2;
            if (zone == null) {
                BlockPos a = v.relative(d, f0).relative(d.getClockWise(), c0), b = v.relative(d, f0 + p.longueur() - 1).relative(d.getClockWise(), c0 + p.largeur() - 1);
                zone = new AABB(Math.min(a.getX(), b.getX()) - 3, v.getY() - 3, Math.min(a.getZ(), b.getZ()) - 3, Math.max(a.getX(), b.getX()) + 4, v.getY() + 12, Math.max(a.getZ(), b.getZ()) + 4);
            }
            // Rang par rang en serpentin : les arbres naturels d'abord, puis herbes, fleurs et buissons.
            for (int fin = Math.min(p.longueur(), releves + rangs); releves < fin; releves++)
                for (int j = 0; j < p.largeur(); j++) {
                    int i = releves;
                    BlockPos col = v.relative(d, f0 + i).relative(d.getClockWise(), c0 + (i % 2 == 0 ? j : p.largeur() - 1 - j));
                    if (!l.isLoaded(col)) continue;
                    for (int y = v.getY() - 3; y <= v.getY() + 4; y++) {
                        BlockPos q = col.atY(y);
                        BlockState s = l.getBlockState(q);
                        if (s.is(BlockTags.LOGS)) {
                            if (l.getBlockState(q.below()).is(BlockTags.DIRT) && Actions.arbre((ServerLevel) l, q) != null && !Mineur.interdit(t, q, true)) chantier.add(q);
                        } else if (plante(l, q, s)) plantesZone.add(q);
                    }
                }
            if (releves < p.longueur()) return false;
            chantier.addAll(plantesZone);
            plantesZone.clear();
            return true;
        }

        /**
         * Où planter une pousse dans cette colonne, près de la hauteur y0 : la place libre (air, herbe) sur de la terre ou de
         * l'herbe, au ciel ouvert, près de son poste, jamais sur un bloc construit ; null s'il n'y en a pas.
         */
        static BlockPos terre(Travailleur t, BlockPos col, int y0) {
            var l = t.level();
            if (!l.isLoaded(col)) return null;
            for (int y = y0 + 4; y >= y0 - 3; y--) {
                BlockPos q = col.atY(y);
                BlockState s = l.getBlockState(q), sous = l.getBlockState(q.below());
                if (!s.canBeReplaced() || !s.getFluidState().isEmpty() || sous.canBeReplaced()) continue;
                return sous.is(BlockTags.DIRT) && !Monde.construit(l, q.below()) && (t.bureau == null || t.chezLui(q)) && l.canSeeSky(q) ? q : null;
            }
            return null;
        }

        /** Herbe, fleur, fougère, buisson : ce qu'on arrache pour défricher (jamais une feuille posée, une pousse plantée, un contenant). */
        static boolean plante(net.minecraft.world.level.Level l, BlockPos q, BlockState s) {
            if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock || s.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock
                    || !s.getFluidState().isEmpty() || l.getBlockEntity(q) != null || Monde.construit(l, q)) return false;
            return s.is(BlockTags.FLOWERS) || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.AZALEA) || s.is(Blocks.DEAD_BUSH);
        }

        /** Des endroits sombres à éclairer autour de lui (un sol solide, de la place, aucune lumière), espacés de 5 blocs. */
        List<BlockPos> sombres(Travailleur t, int n) {
            var l = t.level();
            List<BlockPos> r = new ArrayList<>();
            List<BlockPos> tous = new ArrayList<>();
            for (BlockPos q : BlockPos.betweenClosed(t.blockPosition().offset(-8, -3, -8), t.blockPosition().offset(8, 3, 8)))
                if (l.getBlockState(q).isAir() && Monde.solide(l, q.below()) && l.getBrightness(LightLayer.BLOCK, q) < 6) tous.add(q.immutable());
            tous.sort(Comparator.comparingDouble(q -> q.distSqr(t.blockPosition())));
            for (BlockPos q : tous) {
                if (r.size() >= n) break;
                if (r.stream().noneMatch(x -> x.distSqr(q) < 25)) r.add(q);
            }
            return r;
        }

        static boolean lave(net.minecraft.world.level.Level l, BlockPos p) {
            for (Direction d : Direction.values()) if (l.getFluidState(p.relative(d)).is(net.minecraft.tags.FluidTags.LAVA)) return true;
            return false;
        }

        Actions.Etat message(Travailleur t) {
            if (vise == null) vise = entite(t, c.entite);
            if (vise == null) return rate(t, "Je ne le trouve pas, chef.");
            // Il ne court pas après lui sans fin : au bout de 2 minutes, il renonce et le dit (pas s'il a commencé à le lui dire).
            if (age > MESSAGE_MAX && etape == 0) {
                raisonSous = "trop_long";
                return rate(t, "Je n'ai pas pu le rattraper, chef. Mieux vaut lui écrire.");
            }
            // À portée de voix, il le hèle : l'autre l'attend sur place, et l'écoute jusqu'à la fin (s'il marchait, il ne le rattrapait jamais).
            // Seulement un ouvrier du même chef : sinon, on pourrait figer à volonté les ouvriers d'un autre. Celui-là, il va jusqu'à lui.
            // Quelques secondes au plus : s'il ne le rejoint pas (passage fermé), l'autre ne reste pas planté là.
            if (vise instanceof Travailleur w && t.chef != null && t.chef.equals(w.chef) && hele < HELER_MAX && t.distanceTo(w) <= 16 && t.hasLineOfSight(w)) {
                hele++;
                w.ecouter(t, 20);
            }
            if (t.distanceTo(vise) > 3) {
                if (sous instanceof Actions.Aller a && a.cible.distanceToSqr(vise.position()) > 4) { sous.fin(t); sous = null; }
                Actions.Etat r = deleguer(t, new Actions.Aller(vise.position(), 2.5).mobile(true));
                if (r == Actions.Etat.ECHEC) return rate(t, "Je n'arrive pas jusqu'à lui, chef.");
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            t.getLookControl().setLookAt(vise);
            if (etape++ == 0) {
                String nom = vise instanceof Travailleur w ? w.prenom : vise.getName().getString();
                Voix.dire(t, nom + ", le chef te fait dire : " + c.texte, "calme");
                if (vise instanceof Travailleur w) {
                    IA.noter(w, t.prenom + " (de la part du chef)", c.texte);
                    w.getLookControl().setLookAt(t);
                }
                t.setGeste("parler");
            }
            return etape > 60 ? ok(t) : Actions.Etat.EN_COURS;
        }
    }

    /** Prend en main ce qu'il doit montrer (un bouclier va dans l'autre main). Faux s'il n'en a pas. */
    static boolean tenir(Travailleur t, String quoi) {
        String f = switch (quoi) {
            case "arme" -> "#minecraft:swords|#minecraft:axes";
            case "bouclier" -> "minecraft:shield";
            case "torche" -> "minecraft:torch";
            case "outil" -> filtre("outil", "", t);
            default -> quoi;
        };
        if (f == null || f.isEmpty()) return false;
        Actions.Filtre filtre = Actions.Filtre.de(f);
        if (quoi.equals("bouclier") || f.equals("minecraft:shield")) {
            if (t.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)) return true;
            for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
                ItemStack s = t.inventaire.getItem(i);
                if (!filtre.objet(s)) continue;
                ItemStack avant = t.getOffhandItem();
                t.setItemSlot(EquipmentSlot.OFFHAND, s);
                t.inventaire.setItem(i, avant.is(net.minecraft.world.item.Items.PAPER) ? ItemStack.EMPTY : avant);
                return true;
            }
            return false;
        }
        return t.equiper(filtre);
    }
}
