package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Le moteur de scripts : chaque métier est un fichier Lua de config/colonie_pnj/metiers/.
 * commun.lua contient le cerveau partagé (besoins, paie, ordres) ; il appelle le métier.
 * Tout tourne sur le fil du serveur. Le guide complet est dans GUIDE-SCRIPTS.md.
 */
public final class Lua {
    private Lua() {}

    static LuaTable commun;
    static final Map<String, LuaTable> METIERS = new LinkedHashMap<>();
    static final Map<String, String> ERREURS = new LinkedHashMap<>();
    static final Deque<String> JOURNAL = new ArrayDeque<>();
    /** Instructions permises par appel du cerveau (protège le serveur d'une boucle infinie). */
    static final int BUDGET = 300_000;
    static int depense;

    // --- Chargement ---------------------------------------------------------------------------------

    /**
     * Un environnement complet par script : les scripts restent isolés les uns des autres.
     * Attention : LuaJ ne compte les instructions (coupure des boucles infinies) que pour le code
     * chargé directement dans un Globals ; ne jamais charger un script dans une simple table d'environnement.
     */
    static Globals creerGlobals() {
        Globals g = new Globals();
        g.load(new BaseLib());
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new JseMathLib());
        g.load(new DebugLib());
        LoadState.install(g);
        LuaC.install(g);
        for (String interdit : new String[]{"debug", "dofile", "loadfile", "load", "require", "package", "collectgarbage", "module"})
            g.set(interdit, LuaValue.NIL);
        g.set("print", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i <= a.narg(); i++) sb.append(i > 1 ? " " : "").append(a.arg(i).tojstring());
                journal("[print] " + sb);
                return NONE;
            }
        });
        g.running.state.hookfunc = new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                depense += 1000;
                if (depense > BUDGET) throw new LuaError("script trop long (boucle infinie ?)");
                return NONE;
            }
        };
        g.running.state.hookcount = 1000;
        return g;
    }

    /** (Re)charge tous les scripts du dossier. Les PNJ prennent le nouveau comportement aussitôt. */
    public static synchronized void charger() {
        METIERS.clear();
        ERREURS.clear();
        commun = null;
        try (Stream<Path> fichiers = Files.list(Reglages.dossierMetiers())) {
            for (Path p : fichiers.filter(f -> f.toString().endsWith(".lua")).sorted().toList()) {
                String id = p.getFileName().toString().replace(".lua", "");
                try {
                    LuaTable t = executer(id, Files.readString(p, StandardCharsets.UTF_8));
                    if (id.equals("commun")) commun = t; else METIERS.put(id, t);
                } catch (LuaError | IOException e) {
                    ERREURS.put(id, message(e));
                    journal("[" + id + ".lua] ne se charge pas : " + message(e));
                }
            }
        } catch (IOException e) {
            ColoniePnj.LOG.error("Dossier des métiers illisible", e);
        }
        journal("Scripts chargés : " + METIERS.size() + " métier(s)" + (ERREURS.isEmpty() ? "" : ", " + ERREURS.size() + " en erreur"));
    }

    /** Exécute un script et vérifie qu'il renvoie bien une table (avec travailler, pour un métier). */
    static LuaTable executer(String id, String source) {
        depense = 0;
        LuaValue r = creerGlobals().load(source, id + ".lua").call();
        if (!r.istable()) throw new LuaError("le script doit finir par « return M » (une table)");
        if (!id.equals("commun") && !r.get("travailler").isfunction()) throw new LuaError("il manque la fonction M.travailler(pnj)");
        if (id.equals("commun") && !r.get("cerveau").isfunction()) throw new LuaError("il manque la fonction M.cerveau(pnj, metier)");
        return (LuaTable) r;
    }

    /** Vérifie un script sans l'installer ; renvoie le message d'erreur ou null. */
    public static synchronized String verifier(String id, String source) {
        try { executer(id, source); return null; } catch (LuaError e) { return message(e); }
    }

    static String message(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.replace("\r", "");
    }

    public static synchronized void journal(String ligne) {
        String l = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + ligne;
        JOURNAL.addFirst(l);
        while (JOURNAL.size() > 300) JOURNAL.removeLast();
        ColoniePnj.LOG.info("[scripts] {}", ligne);
    }

    public static boolean metierExiste(String id) { return METIERS.containsKey(id); }

    public static List<String> metiersActifs() {
        return METIERS.keySet().stream().filter(m -> !Reglages.actuel.metiers_inactifs.contains(m)).toList();
    }

    public static String nomMetier(String id) {
        LuaTable t = METIERS.get(id);
        return t != null && t.get("nom").isstring() ? t.get("nom").tojstring() : id;
    }

    public static String descriptionMetier(String id) {
        LuaTable t = METIERS.get(id);
        return t != null && t.get("description").isstring() ? t.get("description").tojstring() : "";
    }

    // --- Les commandes (catalogue M.commandes de chaque métier) --------------------------------------

    public record CommandeInfo(String id, String nom, boolean quantite) {}

    /** Le catalogue des commandes d'un métier, pour l'écran du bureau. */
    public static synchronized List<CommandeInfo> commandes(String metier) {
        List<CommandeInfo> l = new ArrayList<>();
        LuaTable m = METIERS.get(metier);
        if (m == null || !m.get("commandes").istable()) return l;
        LuaTable c = (LuaTable) m.get("commandes");
        for (int i = 1; i <= c.length(); i++) {
            LuaValue e = c.get(i);
            if (!e.istable() || !e.get("id").isstring()) continue;
            l.add(new CommandeInfo(e.get("id").tojstring(), e.get("nom").optjstring(e.get("id").tojstring()), !e.get("objet").isnil()));
        }
        return l;
    }

    static LuaTable entreeCommande(String metier, String id) {
        LuaTable m = METIERS.get(metier);
        if (id == null || id.isEmpty() || m == null || !m.get("commandes").istable()) return null;
        LuaTable c = (LuaTable) m.get("commandes");
        for (int i = 1; i <= c.length(); i++) if (c.get(i).istable() && id.equals(c.get(i).get("id").optjstring(""))) return (LuaTable) c.get(i);
        return null;
    }

    public static String nomCommande(String metier, String id) {
        LuaTable e = entreeCommande(metier, id);
        return e == null ? id : e.get("nom").optjstring(id);
    }

    /** L'objet produit par la commande en cours (filtre qui ne prend rien s'il n'y en a pas). */
    static Actions.Filtre objetCommande(Travailleur t) {
        LuaTable e = entreeCommande(t.metier, t.commande);
        return e != null && e.get("objet").isstring() ? Actions.Filtre.de(e.get("objet").tojstring()) : Actions.Filtre.de("");
    }

    /** Où en est la commande : le stock au dépôt (mode stock) ou ce qu'il a déjà livré (mode quantité) ; -1 si on ne sait pas. */
    static int avancement(Travailleur t) {
        if (entreeCommande(t.metier, t.commande) == null) return -1;
        if (t.commandeMode.equals("quantite")) return t.commandeLivre;
        Bureau.Entite b = Bureau.entite(t);
        return b == null ? -1 : b.compter(objetCommande(t));
    }

    static boolean commandeRemplie(Travailleur t) {
        LuaTable e = entreeCommande(t.metier, t.commande);
        if (e == null || e.get("objet").isnil()) return false;
        int a = avancement(t);
        return a >= 0 && a >= t.commandeNombre;
    }

    static String avancementTexte(Travailleur t) {
        LuaTable e = entreeCommande(t.metier, t.commande);
        if (e == null) return "Aucune commande";
        if (e.get("objet").isnil()) return nomCommande(t.metier, t.commande);
        int a = avancement(t);
        String n = (a < 0 ? "?" : String.valueOf(a)) + "/" + t.commandeNombre;
        return nomCommande(t.metier, t.commande) + " : " + (t.commandeMode.equals("quantite") ? n + " livrés" : n + " au dépôt") + (commandeRemplie(t) ? " (fait)" : "");
    }

    // --- Le cerveau ----------------------------------------------------------------------------------

    /** Appelé toutes les demi-secondes pour chaque PNJ employé. */
    static void cerveau(Travailleur t) {
        if (commun == null) return;
        LuaTable metier = METIERS.get(t.metier);
        if (metier == null) return;
        if (t.api == null) t.api = api(t);
        depense = 0;
        try {
            commun.get("cerveau").call(t.api, metier);
        } catch (LuaError | IllegalArgumentException | IllegalStateException e) {
            journal("[" + t.prenom + " / " + t.metier + "] erreur : " + message(e));
            t.pauseCerveau = 200;
        }
    }

    // --- L'API « pnj » donnée aux scripts (voir GUIDE-SCRIPTS.md) ----------------------------------------

    static LuaTable api(Travailleur t) {
        LuaTable p = new LuaTable();
        p.set("memoire", depuisTag(t.memoireSauvee));

        // Qui suis-je
        f(p, "nom", a -> v(t.prenom));
        f(p, "metier", a -> v(t.metier));
        f(p, "niveau", a -> LuaValue.valueOf(t.niveau));
        f(p, "temperament", a -> v(t.temperament));
        f(p, "mode", a -> v(t.mode.lua));
        f(p, "position", a -> pos(t.blockPosition()));
        f(p, "lieu", a -> t.centreZone() == null ? LuaValue.NIL : pos(t.centreZone()));
        f(p, "zone", a -> {
            BlockPos c = t.centreZone();
            return c == null ? LuaValue.NIL : LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(c.getX()), LuaValue.valueOf(c.getY()), LuaValue.valueOf(c.getZ()), LuaValue.valueOf(t.rayon)});
        });
        f(p, "dansZone", a -> {
            BlockPos c = t.centreZone();
            if (c == null) return LuaValue.FALSE;
            BlockPos b = bp(a, 1);
            double dx = b.getX() - c.getX(), dz = b.getZ() - c.getZ();
            return LuaValue.valueOf(dx * dx + dz * dz <= (double) t.rayon * t.rayon);
        });
        f(p, "enService", a -> LuaValue.valueOf(t.enService()));
        f(p, "horaires", a -> LuaValue.varargsOf(LuaValue.valueOf(t.debut), LuaValue.valueOf(t.fin)));
        f(p, "fatigue", a -> LuaValue.valueOf(t.fatigue));
        f(p, "commande", a -> {
            LuaTable e = entreeCommande(t.metier, t.commande);
            if (e == null) return LuaValue.NIL;
            LuaTable c = new LuaTable();
            LuaValue k = LuaValue.NIL;
            while (true) {
                Varargs n = e.next(k);
                if ((k = n.arg1()).isnil()) break;
                c.set(k, n.arg(2));
            }
            c.set("mode", t.commandeMode);
            c.set("nombre", t.commandeNombre);
            c.set("livre", t.commandeLivre);
            if (t.profondeurForcee != Integer.MIN_VALUE) c.set("profondeur", t.profondeurForcee);
            return c;
        });
        f(p, "avancement", a -> LuaValue.varargsOf(LuaValue.valueOf(avancement(t)), LuaValue.valueOf(t.commandeNombre)));
        f(p, "commandeRemplie", a -> LuaValue.valueOf(commandeRemplie(t)));
        f(p, "surface", a -> {
            int x = a.checkint(1), z = a.checkint(2);
            if (!t.level().isLoaded(new BlockPos(x, 0, z))) return LuaValue.NIL;
            return LuaValue.valueOf(t.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        });
        f(p, "seReposer", a -> lancer(t, new Actions.Reposer()));
        f(p, "solNaturel", a -> {
            BlockPos s = solNaturel(t, Math.clamp(a.optint(1, 24), 4, Math.max(4, t.rayon)));
            return s == null ? LuaValue.NIL : pos(s);
        });
        f(p, "direction", a -> LuaValue.varargsOf(LuaValue.valueOf(t.direction.getStepX()), LuaValue.valueOf(t.direction.getStepZ())));
        f(p, "vie", a -> LuaValue.varargsOf(LuaValue.valueOf(t.getHealth()), LuaValue.valueOf(t.getMaxHealth())));
        f(p, "faim", a -> LuaValue.valueOf(t.faim));
        f(p, "occupe", a -> LuaValue.valueOf(t.action != null));
        f(p, "action", a -> t.action == null ? LuaValue.NIL : v(t.action.nom));
        f(p, "resultat", a -> v(t.resultat));
        f(p, "heure", a -> LuaValue.valueOf((int) (t.level().getDayTime() % 24000)));
        f(p, "paieDue", a -> LuaValue.valueOf(t.paieDue()));
        f(p, "du", a -> LuaValue.valueOf((double) t.du()));
        f(p, "aBureau", a -> LuaValue.valueOf(t.bureau != null));
        f(p, "caisse", a -> {
            Bureau.Entite e = Bureau.entite(t);
            return LuaValue.valueOf(e == null ? -1 : (double) Reglages.valeur(e.caisse));
        });
        f(p, "depotContient", a -> {
            Bureau.Entite e = Bureau.entite(t);
            return LuaValue.valueOf(e == null ? 0 : e.compter(filtre(a, 1)));
        });

        // Le monde autour
        f(p, "bloc", a -> v(BuiltInRegistries.BLOCK.getKey(etat(t, bp(a, 1)).getBlock()).toString()));
        f(p, "est", a -> LuaValue.valueOf(filtre(a, 4).bloc(etat(t, bp(a, 1)))));
        f(p, "solide", a -> { BlockPos b = bp(a, 1); return LuaValue.valueOf(!etat(t, b).getCollisionShape(t.level(), b).isEmpty()); });
        f(p, "libre", a -> {
            BlockPos b = bp(a, 1);
            BlockState s = etat(t, b);
            return LuaValue.valueOf(s.getCollisionShape(t.level(), b).isEmpty() && s.getFluidState().isEmpty());
        });
        f(p, "liquide", a -> {
            var fl = etat(t, bp(a, 1)).getFluidState();
            return fl.isEmpty() ? LuaValue.NIL : v(fl.is(FluidTags.LAVA) ? "lave" : fl.is(FluidTags.WATER) ? "eau" : "autre");
        });
        f(p, "lumiere", a -> LuaValue.valueOf(t.level().getMaxLocalRawBrightness(bp(a, 1))));
        f(p, "chercherBloc", a -> {
            Actions.Filtre fi = filtre(a, 1);
            int r = Math.clamp(a.checkint(2), 1, 12);
            LuaValue o = a.arg(3);
            BlockPos c = centre(t, o);
            boolean expose = o.istable() && o.get("expose").toboolean();
            boolean recoltable = o.istable() && o.get("recoltable").toboolean();
            BlockPos meilleur = null;
            double dMin = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
                double d = q.distSqr(t.blockPosition());
                if (d >= dMin || !t.level().isLoaded(q)) continue;
                BlockState s = t.level().getBlockState(q);
                if (!fi.bloc(s) || expose && !expose(t, q)) continue;
                if (recoltable && s.requiresCorrectToolForDrops() && !t.getMainHandItem().isCorrectToolForDrops(s)) continue;
                meilleur = q.immutable();
                dMin = d;
            }
            return meilleur == null ? LuaValue.NIL : pos(meilleur);
        });
        f(p, "chercherArbre", a -> {
            LuaValue o = a.arg(2);
            Actions.Filtre bois = o.istable() && o.get("bois").isstring() ? Actions.Filtre.de(o.get("bois").tojstring()) : null;
            BlockPos b = Actions.chercherArbre((ServerLevel) t.level(), centre(t, o), Math.clamp(a.checkint(1), 1, 24), bois);
            return b == null ? LuaValue.NIL : pos(b);
        });
        f(p, "bureau", a -> {
            BlockPos b = Bureau.trouver(t);
            return b == null ? LuaValue.NIL : pos(b);
        });
        f(p, "distance", a -> LuaValue.valueOf(Math.sqrt(t.blockPosition().distSqr(bp(a, 1)))));
        f(p, "temps", a -> LuaValue.valueOf((double) (t.level().getGameTime() / 20)));
        f(p, "montant", a -> v(Reglages.texte((long) a.checkdouble(1))));
        f(p, "monstres", a -> liste(t, t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(Math.clamp(a.checkdouble(1), 1, 32)),
                e -> e instanceof Enemy && e.isAlive())));
        f(p, "cibles", a -> liste(t, cibles(t, Math.clamp(a.checkdouble(1), 1, 32))));
        f(p, "agresseur", a -> {
            LivingEntity e = t.getLastHurtByMob();
            return e == null || !e.isAlive() || t.tickCount - t.getLastHurtByMobTimestamp() > 200 ? LuaValue.NIL : entite(t, e);
        });
        f(p, "chef", a -> {
            if (t.chef == null) return LuaValue.NIL;
            LuaTable c = new LuaTable();
            c.set("nom", t.chefNom);
            ServerPlayer j = t.chefJoueur();
            c.set("connecte", LuaValue.valueOf(j != null));
            if (j != null && j.level() == t.level()) {
                c.set("x", j.getBlockX());
                c.set("y", j.getBlockY());
                c.set("z", j.getBlockZ());
                c.set("distance", t.distanceTo(j));
            }
            return c;
        });
        f(p, "objetsAuSol", a -> {
            Actions.Filtre fi = a.isstring(2) ? Actions.Filtre.de(a.tojstring(2)) : Actions.Filtre.de("tout");
            return LuaValue.valueOf(t.level().getEntitiesOfClass(ItemEntity.class, t.getBoundingBox().inflate(Math.clamp(a.checkdouble(1), 1, 16)),
                    ie -> fi.objet(ie.getItem())).size());
        });

        // Le sac
        f(p, "compter", a -> LuaValue.valueOf(t.compter(filtre(a, 1))));
        f(p, "a", a -> LuaValue.valueOf(t.compter(filtre(a, 1)) >= a.optint(2, 1)));
        f(p, "plein", a -> LuaValue.valueOf(libres(t) == 0));
        f(p, "libres", a -> LuaValue.valueOf(libres(t)));
        f(p, "equiper", a -> LuaValue.valueOf(t.equiper(filtre(a, 1))));
        f(p, "enMain", a -> t.getMainHandItem().isEmpty() ? LuaValue.NIL : v(BuiltInRegistries.ITEM.getKey(t.getMainHandItem().getItem()).toString()));
        f(p, "peutRecolter", a -> {
            BlockState s = etat(t, bp(a, 1));
            return LuaValue.valueOf(!s.requiresCorrectToolForDrops() || t.getMainHandItem().isCorrectToolForDrops(s));
        });
        f(p, "manger", a -> LuaValue.valueOf(t.manger()));

        // Les actions (elles durent : le script reprend la main quand pnj.occupe() redevient faux)
        f(p, "aller", a -> lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(bp(a, 1)), a.optdouble(4, 1.5))));
        f(p, "casser", a -> lancer(t, new Actions.Casser(bp(a, 1))));
        f(p, "abattre", a -> lancer(t, new Actions.Abattre(bp(a, 1))));
        f(p, "poser", a -> lancer(t, new Actions.Poser(bp(a, 1), filtre(a, 4))));
        f(p, "attaquer", a -> {
            Entity e = t.level().getEntity(a.checkint(1));
            return e instanceof LivingEntity le && le != t ? lancer(t, new Actions.Attaquer(le)) : LuaValue.FALSE;
        });
        f(p, "fuir", a -> {
            Entity e = t.level().getEntity(a.checkint(1));
            return e == null ? LuaValue.FALSE : lancer(t, new Actions.Fuir(e));
        });
        f(p, "attendre", a -> lancer(t, new Actions.Attendre((int) (Math.clamp(a.checkdouble(1), 0.05, 600) * 20))));
        f(p, "suivreChef", a -> lancer(t, new Actions.SuivreChef()));
        f(p, "deposer", a -> {
            List<Actions.Filtre> garder = new ArrayList<>();
            if (a.istable(1)) {
                LuaTable l = a.checktable(1);
                for (int i = 1; i <= l.length(); i++) garder.add(Actions.Filtre.de(l.get(i).tojstring()));
            }
            return lancer(t, new Actions.Deposer(garder));
        });
        f(p, "prendre", a -> lancer(t, new Actions.Prendre(filtre(a, 1), Math.clamp(a.optint(2, 64), 1, 27 * 64))));
        f(p, "toucherPaie", a -> lancer(t, new Actions.ToucherPaie()));
        f(p, "demander", a -> lancer(t, new Actions.Demander(a.checkjstring(1), filtre(a, 2), Math.max(1, a.optint(3, 1)))));
        f(p, "ramasser", a -> lancer(t, new Actions.Ramasser(Math.clamp(a.checkdouble(1), 1, 16),
                a.isstring(2) ? Actions.Filtre.de(a.tojstring(2)) : Actions.Filtre.de("tout"))));
        f(p, "arreter", a -> { t.finirAction("arrete"); return LuaValue.NONE; });

        // Parler
        f(p, "dire", a -> { t.dire(a.checkjstring(1)); return LuaValue.NONE; });
        f(p, "direAuChef", a -> { t.direAuChef(a.checkjstring(1)); return LuaValue.NONE; });
        f(p, "log", a -> { journal("[" + t.prenom + "] " + a.checkjstring(1)); return LuaValue.NONE; });
        return p;
    }

    /**
     * Un endroit de sa zone où creuser sans abîmer de construction : sol naturel (terre, sable, pierre…)
     * à ciel ouvert, et aucun bloc « de joueur » (planches, vitres, portes, dalles, coffres…) à 5 blocs autour.
     */
    static BlockPos solNaturel(Travailleur t, int rayon) {
        BlockPos c = t.centreZone();
        if (c == null) return null;
        var lvl = t.level();
        var r = t.getRandom();
        for (int essai = 0; essai < 200; essai++) {
            double angle = r.nextDouble() * Math.PI * 2, d = 6 + r.nextDouble() * Math.max(1, rayon - 6);
            int x = c.getX() + (int) (Math.cos(angle) * d), z = c.getZ() + (int) (Math.sin(angle) * d);
            if (!lvl.isLoaded(new BlockPos(x, c.getY(), z))) continue;
            int y = lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos sol = new BlockPos(x, y - 1, z);
            BlockState st = lvl.getBlockState(sol);
            // Herbes, fleurs, neige, barrières : ce n'est pas encore le sol, on descend jusqu'à lui.
            for (int i = 0; i < 20 && (st.canBeReplaced() && st.getFluidState().isEmpty() || st.is(net.minecraft.world.level.block.Blocks.BARRIER)); i++) {
                sol = sol.below();
                st = lvl.getBlockState(sol);
            }
            boolean naturel = st.is(net.minecraft.tags.BlockTags.DIRT) || st.is(net.minecraft.tags.BlockTags.SAND)
                    || st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || st.is(net.minecraft.world.level.block.Blocks.GRAVEL);
            if (!naturel || !st.getFluidState().isEmpty() || construitAutour(t, sol)) continue;
            return sol.above();
        }
        return null;
    }

    static boolean construitAutour(Travailleur t, BlockPos sol) {
        var lvl = t.level();
        for (BlockPos q : BlockPos.betweenClosed(sol.offset(-5, -2, -5), sol.offset(5, 4, 5))) {
            if (!lvl.isLoaded(q)) return true;
            if (lvl.getBlockEntity(q) != null) return true;
            BlockState st = lvl.getBlockState(q);
            if (st.isAir()) continue;
            if (st.is(net.minecraft.tags.BlockTags.PLANKS) || st.is(net.minecraft.tags.BlockTags.WOOL) || st.is(net.minecraft.tags.BlockTags.DOORS)
                    || st.is(net.minecraft.tags.BlockTags.SLABS) || st.is(net.minecraft.tags.BlockTags.STAIRS) || st.is(net.minecraft.tags.BlockTags.FENCES)
                    || st.is(net.minecraft.tags.BlockTags.WALLS) || st.is(net.minecraft.tags.BlockTags.BEDS) || st.is(net.neoforged.neoforge.common.Tags.Blocks.GLASS_BLOCKS)
                    || st.is(net.minecraft.tags.BlockTags.TRAPDOORS) || st.is(net.minecraft.world.level.block.Blocks.TORCH) || st.is(net.minecraft.world.level.block.Blocks.WALL_TORCH))
                return true;
        }
        return false;
    }

    // --- Petits outils ------------------------------------------------------------------------------

    static void f(LuaTable p, String nom, Function<Varargs, Varargs> corps) {
        p.set(nom, new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs a) { return corps.apply(a); }
        });
    }

    static LuaValue v(String s) { return LuaValue.valueOf(s); }

    static LuaValue lancer(Travailleur t, Actions.Action a) {
        if (t.action != null) t.action.fin(t);
        if (t.isSleeping() && !(a instanceof Actions.Reposer)) t.stopSleeping();
        if (!(a instanceof Actions.Demander) && !(a instanceof Actions.Attendre)) t.besoin = "";
        t.action = a;
        t.resultat = "";
        return LuaValue.TRUE;
    }

    static Varargs pos(BlockPos b) { return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(b.getX()), LuaValue.valueOf(b.getY()), LuaValue.valueOf(b.getZ())}); }

    static BlockPos bp(Varargs a, int i) { return new BlockPos(a.checkint(i), a.checkint(i + 1), a.checkint(i + 2)); }

    static Actions.Filtre filtre(Varargs a, int i) { return Actions.Filtre.de(a.checkjstring(i)); }

    /** Le centre d'une recherche : {x=, y=, z=} s'il est donné, sinon la position du PNJ. */
    static BlockPos centre(Travailleur t, LuaValue o) {
        if (o.istable() && o.get("x").isnumber() && o.get("y").isnumber() && o.get("z").isnumber())
            return new BlockPos(o.get("x").toint(), o.get("y").toint(), o.get("z").toint());
        return t.blockPosition();
    }

    static BlockState etat(Travailleur t, BlockPos b) {
        if (!t.level().isLoaded(b)) throw new LuaError("bloc " + b.toShortString() + " trop loin (non chargé)");
        return t.level().getBlockState(b);
    }

    static boolean expose(Travailleur t, BlockPos q) {
        for (var d : net.minecraft.core.Direction.values()) {
            BlockPos n = q.relative(d);
            if (t.level().isLoaded(n) && t.level().getBlockState(n).getCollisionShape(t.level(), n).isEmpty()) return true;
        }
        return false;
    }

    static int libres(Travailleur t) {
        int n = 0;
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) if (t.inventaire.getItem(i).isEmpty()) n++;
        return n;
    }

    /** Monstres proches, plus ceux qui s'en sont pris au PNJ ou à son chef dans les 10 dernières secondes. */
    static List<LivingEntity> cibles(Travailleur t, double rayon) {
        List<LivingEntity> l = new ArrayList<>(t.level().getEntitiesOfClass(LivingEntity.class, t.getBoundingBox().inflate(rayon),
                e -> e instanceof Enemy && e.isAlive() && !(e instanceof Travailleur)));
        LivingEntity mien = t.getLastHurtByMob();
        if (mien != null && mien.isAlive() && t.tickCount - t.getLastHurtByMobTimestamp() < 200 && !l.contains(mien) && !t.estDe(mien)) l.add(mien);
        ServerPlayer c = t.chefJoueur();
        if (c != null && c.level() == t.level() && c.distanceTo(t) < rayon * 2) {
            LivingEntity agresseur = c.getLastHurtByMob();
            if (agresseur != null && agresseur.isAlive() && agresseur != t && c.tickCount - c.getLastHurtByMobTimestamp() < 200 && !l.contains(agresseur)) l.add(agresseur);
            LivingEntity viseParChef = c.getLastHurtMob();
            if (viseParChef != null && viseParChef.isAlive() && viseParChef != t && !(viseParChef instanceof Player) && c.tickCount - c.getLastHurtMobTimestamp() < 200 && !l.contains(viseParChef)) l.add(viseParChef);
        }
        l.sort(Comparator.comparingDouble(t::distanceToSqr));
        return l;
    }

    static LuaTable liste(Travailleur t, List<? extends LivingEntity> l) {
        LuaTable r = new LuaTable();
        for (int i = 0; i < l.size(); i++) r.set(i + 1, entite(t, l.get(i)));
        return r;
    }

    static LuaTable entite(Travailleur t, LivingEntity e) {
        LuaTable x = new LuaTable();
        x.set("id", e.getId());
        x.set("nom", e.getName().getString());
        x.set("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        x.set("joueur", LuaValue.valueOf(e instanceof Player));
        x.set("x", e.getBlockX());
        x.set("y", e.getBlockY());
        x.set("z", e.getBlockZ());
        x.set("distance", t.distanceTo(e));
        return x;
    }

    // --- La mémoire du PNJ, gardée dans sa sauvegarde ------------------------------------------------

    static CompoundTag versTag(LuaTable t) { return versTag(t, 0); }

    static CompoundTag versTag(LuaTable t, int profondeur) {
        CompoundTag c = new CompoundTag();
        if (t == null || profondeur > 8) return c;
        LuaValue k = LuaValue.NIL;
        while (true) {
            Varargs n = t.next(k);
            if ((k = n.arg1()).isnil()) break;
            LuaValue val = n.arg(2);
            String cle = k.tojstring();
            if (val.isboolean()) c.put(cle, ByteTag.valueOf(val.toboolean()));
            else if (val.isint()) c.put(cle, IntTag.valueOf(val.toint()));
            else if (val.isnumber()) c.putDouble(cle, val.todouble());
            else if (val.isstring()) c.put(cle, StringTag.valueOf(val.tojstring()));
            else if (val.istable()) c.put(cle, versTag((LuaTable) val, profondeur + 1));
        }
        return c;
    }

    static LuaTable depuisTag(CompoundTag c) {
        LuaTable t = new LuaTable();
        for (String cle : c.getAllKeys()) {
            Tag x = c.get(cle);
            LuaValue val = switch (x) {
                case ByteTag b -> LuaValue.valueOf(b.getAsByte() != 0);
                case IntTag i -> LuaValue.valueOf(i.getAsInt());
                case NumericTag n -> LuaValue.valueOf(n.getAsDouble());
                case CompoundTag sous -> depuisTag(sous);
                default -> LuaValue.valueOf(x.getAsString());
            };
            LuaValue k;
            try { k = LuaValue.valueOf(Integer.parseInt(cle)); } catch (NumberFormatException e) { k = LuaValue.valueOf(cle); }
            t.set(k, val);
        }
        return t;
    }
}
