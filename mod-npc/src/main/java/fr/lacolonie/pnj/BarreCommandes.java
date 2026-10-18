package fr.lacolonie.pnj;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * La barre de commandes (refonte 1.0.0, partie 4.3) : une touche (B par défaut, catégorie « La Colonie ») ouvre une
 * barre de saisie ; on tape « gar… » et elle propose les actions du {@link Repertoire} que l'ouvrier visé sait faire
 * (« Garde à vous », « Garde cette position »…). On ne peut envoyer qu'une ligne de la liste, jamais une phrase libre.
 * <p>
 * Trois parties : la recherche (pur Java, {@link #chercher}, testée par {@code BarreCommandesTest}) ; le serveur
 * ({@link #executer}, appelé par le paquet {@code Reseau.Barre} et par le joueur fantôme) ; l'écran et la touche, chez
 * le joueur seulement ({@link Ecran}, {@link Touche}).
 */
public final class BarreCommandes {
    private BarreCommandes() {}

    /** L'ouvrier que l'on regarde, jusqu'à tant de blocs ; sinon le plus proche des siens, jusqu'à {@link #PROCHE}. */
    static final double REGARD = 8, PROCHE = 16;

    // --- La recherche (pur Java) ----------------------------------------------------------------------------

    /** La recherche, à part : sans rien du jeu (les tests JUnit la chargent sans Minecraft). */
    static final class Recherche {
        private Recherche() {}

        /** Au plus tant de propositions. */
        static final int MAX = 8;

        /** Une ligne proposée : l'action {@code id}, ce qu'on lit ({@code libelle}, {@code detail}) et ce où l'on cherche ({@code textes}, aplatis). */
        record Ligne(String id, String libelle, String detail, Set<String> metiers, List<String> textes) {
            boolean pour(String metier) { return metiers.isEmpty() || metiers.contains(metier); }
        }

        /** Sans accents, sans majuscules, la ponctuation en espaces : « Garde-à-vous ! » → « garde a vous ». */
        static String plat(String s) {
            String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            return n.replace("œ", "oe").replace("æ", "ae").replaceAll("[^a-z0-9]+", " ").strip();
        }

        /** La ligne d'une action : sa première formule pour libellé (« Garde à vous »), ce qu'elle fait en dessous. */
        static Ligne ligne(Repertoire.Entree e) {
            String f = (e.exemples().isEmpty() ? e.nom() : e.exemples().get(0)).replace("@", "…").strip();
            String libelle = f.isEmpty() ? e.id() : Character.toUpperCase(f.charAt(0)) + f.substring(1);
            List<String> textes = new ArrayList<>();
            textes.add(plat(libelle));
            textes.add(plat(e.nom()));
            for (String d : e.dits()) {
                String x = plat(d);
                if (!x.isEmpty() && !textes.contains(x)) textes.add(x);
            }
            return new Ligne(e.id(), libelle, e.nom(), e.metiers(), textes);
        }

        private static List<Ligne> lignes;

        /** Toutes les lignes du répertoire, préparées une fois, sauf celles dont la précision ne se choisit pas dans une liste. */
        static List<Ligne> lignes() {
            if (lignes == null) lignes = Repertoire.TOUTES.stream().filter(e -> besoin(e) != Besoin.RETIRE).map(Recherche::ligne).toList();
            return lignes;
        }

        // --- La deuxième liste : qui, quoi, combien -----------------------------------------------------------

        /** Ce qu'un ordre demande en plus, choisi dans une deuxième liste ; {@code RETIRE} : ça ne se choisit pas (ce qu'il faut dire), pas dans la barre. */
        enum Besoin {
            RIEN, QUI, QUOI, QUI_QUOI, RETIRE;

            boolean qui() { return this == QUI || this == QUI_QUOI; }

            boolean quoi() { return this == QUOI || this == QUI_QUOI; }
        }

        static final Set<String> AVEC_OBJET = Set.of("donner", "jeter", "tenir", "prendre");
        /** Les quantités proposées (prendre au dépôt, donner). */
        static final List<Integer> QUANTITES = List.of(1, 8, 16, 64);

        static Besoin besoin(Repertoire.Entree e) {
            if (e.comportement().equals("message")) return Besoin.RETIRE; // « dis à Jean que… » : ce qu'il faut dire ne se choisit pas
            boolean qui = e.visePersonne() && !(e.comportement().equals("combat") && e.arg().equals("cible")); // « attaque » : le monstre regardé
            boolean quoi = AVEC_OBJET.contains(e.comportement()) && e.arg().equals("objet");
            return qui && quoi ? Besoin.QUI_QUOI : qui ? Besoin.QUI : quoi ? Besoin.QUOI : Besoin.RIEN;
        }

        /** On choisit aussi combien : pour prendre au dépôt et pour donner. */
        static boolean combien(Repertoire.Entree e) { return besoin(e).quoi() && Set.of("prendre", "donner").contains(e.comportement()); }

        /** Le complément choisi, tel que la barre l'envoie : « qui|quoi|combien » (« Jean|minecraft:bread|8 »), vide s'il n'en faut pas. */
        record Complement(String qui, String quoi, int combien) {
            static Complement lire(String s) {
                String[] p = (s == null ? "" : s).split("\\|", -1);
                int n = 0;
                if (p.length > 2) try { n = Integer.parseInt(p[2].strip()); } catch (NumberFormatException ignore) {}
                return new Complement(p[0].strip(), p.length > 1 ? p[1].strip() : "", Math.clamp(n, 0, 64));
            }

            String texte() { return qui + "|" + quoi + "|" + combien; }
        }

        /** La phrase de l'ordre, comme si le chef l'avait dite avec le nom et l'objet : « montre-moi Jean », « donne à Jean minecraft:bread ». */
        static String phrase(Repertoire.Entree e, String qui, String quoi) {
            String f = e.exemples().isEmpty() ? "" : e.exemples().get(0);
            f = qui.isBlank() ? f.replace("@", "") : f.contains("@") ? f.replace("@", qui) : f + " " + qui;
            if (!quoi.isBlank()) f += " " + quoi;
            return f.replaceAll("\\s+", " ").strip();
        }

        /** Une ligne de la deuxième liste : la valeur envoyée ({@code id}), ce qu'on lit, et un détail en gris. */
        static Ligne choix(String valeur, String libelle, String detail) { return new Ligne(valeur, libelle, detail, Set.of(), List.of(plat(libelle))); }

        /**
         * Le rang d'une ligne pour la saisie (déjà aplatie) : 0, le libellé commence par elle ; 1, un mot du libellé ;
         * 2, un mot d'une formule ou de ce qu'elle fait ; 3, chaque mot saisi commence un mot d'un même texte ; -1, rien.
         */
        static int rang(Ligne l, String q) {
            if (q.isEmpty()) return 3;
            List<String> t = l.textes();
            if (t.get(0).startsWith(q)) return 0;
            if ((" " + t.get(0)).contains(" " + q)) return 1;
            for (String x : t) if ((" " + x).contains(" " + q)) return 2;
            String[] mots = q.split(" ");
            for (String x : t) {
                String y = " " + x;
                boolean tous = true;
                for (String m : mots) if (!y.contains(" " + m)) { tous = false; break; }
                if (tous) return 3;
            }
            return -1;
        }

        /** Les actions à proposer pour {@code saisie} à un ouvrier de ce métier : celles de tous, et les siennes ; {@link #MAX} au plus. */
        static List<Ligne> chercher(String saisie, String metier, List<Ligne> parmi) {
            String q = plat(saisie);
            record Note(Ligne l, int r, int i) {}
            List<Note> r = new ArrayList<>();
            for (int i = 0; i < parmi.size(); i++) {
                Ligne l = parmi.get(i);
                if (!l.pour(metier)) continue;
                int k = rang(l, q);
                if (k >= 0) r.add(new Note(l, k, i));
            }
            r.sort(Comparator.comparingInt(Note::r).thenComparingInt(Note::i));
            return r.stream().limit(MAX).map(Note::l).toList();
        }

        static List<Ligne> chercher(String saisie, String metier) { return chercher(saisie, metier, lignes()); }
    }

    // --- Le serveur ------------------------------------------------------------------------------------------

    /**
     * Le joueur {@code p} choisit l'action {@code idAction} pour l'ouvrier {@code t}. Mêmes droits que de vive voix :
     * son chef, ou un joueur que le chef autorise ({@link Commandement}), hors guerre et hors ordres réservés au chef.
     * L'action est jouée par {@link Consignes#ordonner}. Renvoie ce que l'ouvrier répond (vide : rien n'est fait, ou
     * rien à dire), aussi montré au joueur (« Raoul : Garde-à-vous, chef ! ») et en bulle.
     */
    public static String executer(ServerPlayer p, Travailleur t, String idAction) { return executer(p, t, idAction, ""); }

    /** Le paquet de la barre : un ordre avec son complément, ou (« ? ») la demande des objets à choisir. */
    static void recevoir(ServerPlayer p, Travailleur t, String idAction, String complement) {
        if ("?".equals(complement)) envoyerObjets(p, t, idAction);
        else executer(p, t, idAction, complement);
    }

    static boolean aPortee(ServerPlayer p, Travailleur t) {
        return p != null && t != null && t.isAlive() && !t.aTerre && t.chef != null && t.mode != Travailleur.Mode.CHOMEUR
                && p.level() == t.level() && p.distanceToSqr(t) <= (PROCHE + 4) * (PROCHE + 4);
    }

    /**
     * Comme {@link #executer(ServerPlayer, Travailleur, String)}, avec le complément choisi dans la deuxième liste
     * ({@link Recherche.Complement}) : le prénom et l'objet sont ajoutés à la phrase, comme s'ils avaient été dits. Un ordre
     * qui en a besoin et ne l'a pas, ou un ordre retiré de la barre, ne fait rien : jamais de question sans réponse.
     */
    public static String executer(ServerPlayer p, Travailleur t, String idAction, String complement) {
        Repertoire.Entree e = idAction == null ? null : Repertoire.PAR_ID.get(idAction);
        if (e == null || !aPortee(p, t)) return "";
        Recherche.Besoin besoin = Recherche.besoin(e);
        Recherche.Complement choisi = Recherche.Complement.lire(complement);
        if (besoin == Recherche.Besoin.RETIRE || besoin.qui() && choisi.qui().isEmpty()
                || besoin.quoi() && !Consignes.OBJET_NOMME.matcher(choisi.quoi()).matches()) return "";
        String dit;
        if (!p.getUUID().equals(t.chef)) {
            Courrier c = Courrier.de(p.getServer());
            String chef = t.chefNom.isEmpty() ? "mon maître" : t.chefNom;
            if (c.enGuerre(t.chef, p.getUUID()) || !c.commande(t.chef, p.getName().getString())) dit = "Je n'obéis qu'à " + chef + ".";
            else if (Commandement.GESTES_AU_CHEF.contains(e.comportement()) || Commandement.ouvrageDuChef(e.id())) dit = "Ça, c'est à " + chef + " d'en décider.";
            else dit = null;
            if (dit != null) { repondre(p, t, dit); return dit; }
        }
        t.getLookControl().setLookAt(p);
        String phrase = Recherche.phrase(e, besoin.qui() ? choisi.qui() : "", besoin.quoi() ? choisi.quoi() : "");
        int n = Recherche.combien(e) ? choisi.combien() : 0;
        dit = Consignes.ordonner(t, new Ordres.Faire(e.id(), "", n, 0, false), p, phrase, 0, 1);
        // Celui qu'on a choisi est parti entre-temps : il le dit, sans reposer la question.
        if (dit.equals("Qui ça, chef ?") || dit.equals("À qui, chef ?")) dit = "Je ne le vois plus par ici, chef.";
        if (dit.equals("Quoi donc, chef ?")) dit = "Je ne vois pas de quoi tu parles, chef.";
        if (dit.isBlank()) dit = "Bien, chef.";
        repondre(p, t, dit);
        return dit;
    }

    /** Les objets à choisir pour cet ordre, envoyés à la barre du joueur (s'il a le mod). */
    static void envoyerObjets(ServerPlayer p, Travailleur t, String idAction) {
        Repertoire.Entree e = idAction == null ? null : Repertoire.PAR_ID.get(idAction);
        if (e == null || !Recherche.besoin(e).quoi() || !aPortee(p, t) || p.connection == null || !p.connection.hasChannel(Reseau.BarreObjets.TYPE)) return;
        PacketDistributor.sendToPlayer(p, new Reseau.BarreObjets(t.getId(), e.id(), objets(t, e)));
    }

    /** Ce qu'on peut choisir : ce qu'il y a au dépôt pour « prends au dépôt », ce qu'il a sur lui sinon. Une ligne par sorte d'objet, avec combien. */
    static CompoundTag objets(Travailleur t, Repertoire.Entree e) {
        boolean depot = e.comportement().equals("prendre");
        List<ItemStack> tas = new ArrayList<>();
        Bureau.Entite b = depot ? Bureau.entite(t) : null;
        Container c = depot ? (b == null ? null : b.depot) : t.inventaire;
        if (c != null) for (int i = 0; i < c.getContainerSize(); i++) tas.add(c.getItem(i));
        if (!depot) { tas.add(t.getMainHandItem()); tas.add(t.getOffhandItem()); }
        Map<String, Integer> nombres = new LinkedHashMap<>();
        Map<String, String> noms = new LinkedHashMap<>();
        for (ItemStack s : tas) {
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            nombres.merge(id, s.getCount(), Integer::sum);
            noms.putIfAbsent(id, Consignes.nom(s));
        }
        ListTag l = new ListTag();
        nombres.forEach((id, n) -> {
            CompoundTag o = new CompoundTag();
            o.putString("id", id);
            o.putString("nom", noms.get(id));
            o.putInt("n", n);
            l.add(o);
        });
        CompoundTag r = new CompoundTag();
        r.put("objets", l);
        r.putBoolean("depot", depot);
        return r;
    }

    private static void repondre(ServerPlayer p, Travailleur t, String dit) {
        t.dire(dit);
        Travailleur.signaler(p, t.prenom + " : " + dit);
    }

    // --- Chez le joueur ----------------------------------------------------------------------------------------

    /** La touche, et ce qu'elle ouvre. Seulement chez le joueur. */
    @EventBusSubscriber(modid = ColoniePnj.MODID, value = Dist.CLIENT)
    static final class Touche {
        private Touche() {}

        // Les noms s'affichent tels quels dans les contrôles (pas de clé de traduction).
        static final KeyMapping BARRE = new KeyMapping("Barre de commandes", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B, "La Colonie");

        @SubscribeEvent
        static void touches(RegisterKeyMappingsEvent e) { e.register(BARRE); }

        @SubscribeEvent
        static void tick(ClientTickEvent.Post e) {
            Minecraft mc = Minecraft.getInstance();
            while (BARRE.consumeClick()) {
                if (mc.player == null || mc.screen != null) continue;
                Travailleur t = cible(mc.player);
                if (t == null) mc.player.displayClientMessage(Component.literal("Aucun de tes ouvriers en vue."), true);
                else mc.setScreen(new Ecran(t));
            }
        }

        /** L'ouvrier regardé (8 blocs, sans mur entre), sinon le plus proche des siens (16 blocs). */
        static Travailleur cible(Player p) {
            Vec3 oeil = p.getEyePosition(), loin = oeil.add(p.getViewVector(1.0f).scale(REGARD));
            HitResult bloc = p.pick(REGARD, 1.0f, false);
            double max = bloc.getType() == HitResult.Type.MISS ? REGARD * REGARD : bloc.getLocation().distanceToSqr(oeil);
            EntityHitResult vu = ProjectileUtil.getEntityHitResult(p, oeil, loin, p.getBoundingBox().expandTowards(loin.subtract(oeil)).inflate(1),
                    x -> x instanceof Travailleur w && embauche(w), max);
            if (vu != null && vu.getEntity() instanceof Travailleur w) return w;
            String nom = p.getName().getString();
            Travailleur meilleur = null;
            for (Travailleur w : p.level().getEntitiesOfClass(Travailleur.class, new AABB(p.blockPosition()).inflate(PROCHE),
                    w -> embauche(w) && w.role().endsWith(" · " + nom) && w.distanceToSqr(p) <= PROCHE * PROCHE))
                if (meilleur == null || w.distanceToSqr(p) < meilleur.distanceToSqr(p)) meilleur = w;
            return meilleur;
        }

        static boolean embauche(Travailleur w) { return w.isAlive() && !metier(w).equals(Tenues.CHOMEUR); }

        /** Son métier, lu dans sa tenue (synchronisée). */
        static String metier(Travailleur w) { return w.getEntityData().get(Travailleur.TENUE); }

        /** Son prénom, lu dans son nom affiché (« Raoul (Mineur) · KOLRAX_MC »). */
        static String prenom(Entity w) {
            String n = w.getName().getString();
            for (String fin : new String[]{" (", " ·"}) { int i = n.indexOf(fin); if (i > 0) n = n.substring(0, i); }
            return n;
        }
    }

    /**
     * La barre : une saisie en bas de l'écran, les propositions au-dessus. Un ordre qui demande qui ou quoi ouvre une
     * deuxième liste (ses ouvriers et les gens proches ; ce qu'il a sur lui ou ce qu'il y a au dépôt ; puis combien).
     */
    static final class Ecran extends Screen {
        static final int LARGEUR = 320, HAUTEUR_LIGNE = 12;
        static final String INVITE = "Tape le début d'un ordre…";
        /** Les étapes : l'ordre, puis qui, quoi, combien. */
        static final int ORDRE = 0, QUI = 1, QUOI = 2, COMBIEN = 3;
        final Travailleur cible;
        final String metier, prenom;
        EditBox saisie;
        List<Recherche.Ligne> vues = List.of();
        int choix;
        int etape = ORDRE;
        Repertoire.Entree ordre;
        String libelle = "", qui = "", quoi = "";
        /** Les lignes de la deuxième liste ; {@code attente} : les objets sont demandés au serveur. */
        List<Recherche.Ligne> parmi = List.of();
        boolean attente, depot;

        Ecran(Travailleur cible) {
            super(Component.literal("Barre de commandes"));
            this.cible = cible;
            this.metier = Touche.metier(cible);
            this.prenom = Touche.prenom(cible);
        }

        int gauche() { return (width - LARGEUR) / 2; }

        int basListe() { return height - 34; }

        @Override
        protected void init() {
            String avant = saisie == null ? "" : saisie.getValue();
            saisie = new EditBox(font, gauche() + 4, height - 31, LARGEUR - 8, 14, Component.literal("Ordre"));
            saisie.setMaxLength(48);
            saisie.setBordered(true);
            saisie.setValue(avant);
            // Le jeu ne montre l'invite (setHint) que si la saisie n'a pas la main ; elle l'a toujours ici : une suggestion, en gris après le curseur.
            saisie.setResponder(s -> { saisie.setSuggestion(s.isEmpty() ? invite() : null); majListe(); });
            saisie.setSuggestion(avant.isEmpty() ? invite() : null);
            addRenderableWidget(saisie);
            setInitialFocus(saisie);
            majListe();
        }

        void majListe() {
            vues = etape == ORDRE ? Recherche.chercher(saisie.getValue(), metier) : Recherche.chercher(saisie.getValue(), "", parmi);
            choix = vues.isEmpty() ? -1 : 0;
        }

        String invite() {
            return switch (etape) {
                case QUI -> "Tape le début d'un nom…";
                case QUOI -> "Tape le début d'un objet…";
                case COMBIEN -> "Choisis combien…";
                default -> INVITE;
            };
        }

        /** Passe à l'étape suivante, avec ses lignes ; la saisie repart de zéro. */
        void etape(int e, List<Recherche.Ligne> lignes) {
            etape = e;
            parmi = lignes;
            saisie.setValue("");
            saisie.setSuggestion(invite());
            majListe();
        }

        /** Ses ouvriers (ceux de son chef) et les gens proches, par prénom ; les plus proches d'abord. */
        List<Recherche.Ligne> gens() {
            Minecraft mc = Minecraft.getInstance();
            List<Recherche.Ligne> r = new ArrayList<>();
            if (mc.level == null) return r;
            Set<String> vus = new HashSet<>();
            String role = cible.role();
            int k = role.lastIndexOf(" · ");
            String chef = k < 0 ? null : role.substring(k);
            List<Entity> gens = new ArrayList<>(mc.level.getEntitiesOfClass(Travailleur.class, cible.getBoundingBox().inflate(64),
                    w -> w != cible && chef != null && Touche.embauche(w) && w.role().endsWith(chef)));
            for (Player j : mc.level.players()) if (j != mc.player && j.distanceToSqr(cible) < 64 * 64) gens.add(j);
            gens.sort(Comparator.comparingDouble(cible::distanceToSqr));
            for (Entity g : gens) {
                String nom = g instanceof Travailleur w ? Touche.prenom(w) : g.getName().getString();
                if (!nom.isBlank() && vus.add(Recherche.plat(nom)))
                    r.add(Recherche.choix(nom, nom, g instanceof Travailleur w ? Metier.nom(Touche.metier(w)).toLowerCase(Locale.ROOT) : "voisin"));
            }
            return r;
        }

        /** Les objets reçus du serveur (son sac, ou le dépôt) : la liste « quoi ». */
        static void objets(Reseau.BarreObjets p) {
            if (!(Minecraft.getInstance().screen instanceof Ecran e) || e.cible.getId() != p.id() || e.etape != QUOI || e.ordre == null
                    || !e.ordre.id().equals(p.action())) return;
            List<Recherche.Ligne> l = new ArrayList<>();
            for (Tag x : p.liste().getList("objets", Tag.TAG_COMPOUND)) {
                CompoundTag o = (CompoundTag) x;
                String nom = o.getString("nom");
                l.add(Recherche.choix(o.getString("id"), nom.isEmpty() ? o.getString("id") : Character.toUpperCase(nom.charAt(0)) + nom.substring(1), "× " + o.getInt("n")));
            }
            e.attente = false;
            e.depot = p.liste().getBoolean("depot");
            e.parmi = l;
            e.majListe();
        }

        void demanderObjets() {
            etape(QUOI, List.of());
            depot = ordre.comportement().equals("prendre");
            attente = true;
            PacketDistributor.sendToServer(new Reseau.Barre(cible.getId(), ordre.id(), "?"));
        }

        void finir(int combien) {
            String complement = Recherche.besoin(ordre) == Recherche.Besoin.RIEN ? "" : new Recherche.Complement(qui, quoi, combien).texte();
            PacketDistributor.sendToServer(new Reseau.Barre(cible.getId(), ordre.id(), complement));
            onClose();
        }

        /** La ligne sous la souris, ou -1. Les lignes montent depuis la saisie : la première est juste au-dessus. */
        int ligneSous(double x, double y) {
            if (x < gauche() || x > gauche() + LARGEUR) return -1;
            int i = (int) ((basListe() - y) / HAUTEUR_LIGNE);
            return y <= basListe() && i >= 0 && i < vues.size() ? i : -1;
        }

        void envoyer(int i) {
            if (i < 0 || i >= vues.size() || !cible.isAlive()) return;
            Recherche.Ligne l = vues.get(i);
            switch (etape) {
                case ORDRE -> {
                    ordre = Repertoire.PAR_ID.get(l.id());
                    if (ordre == null) return;
                    libelle = l.libelle();
                    Recherche.Besoin b = Recherche.besoin(ordre);
                    if (b.qui()) etape(QUI, gens());
                    else if (b.quoi()) demanderObjets();
                    else finir(0);
                }
                case QUI -> {
                    qui = l.id();
                    if (Recherche.besoin(ordre).quoi()) demanderObjets();
                    else finir(0);
                }
                case QUOI -> {
                    quoi = l.id();
                    if (Recherche.combien(ordre)) etape(COMBIEN, Recherche.QUANTITES.stream().map(n -> Recherche.choix(String.valueOf(n), String.valueOf(n), "")).toList());
                    else finir(0);
                }
                default -> finir(Integer.parseInt(l.id()));
            }
        }

        /** Le titre de la liste, selon l'étape. */
        String titre() {
            String o = "« " + libelle + " »";
            return switch (etape) {
                case QUI -> o + " : à qui ?";
                case QUOI -> o + " : quoi ?" + (attente ? "" : depot ? " (au dépôt)" : " (sur lui)");
                case COMBIEN -> o + " : combien ?";
                default -> "Ordre à " + prenom + " (" + Metier.nom(metier).toLowerCase(Locale.ROOT) + ")";
            };
        }

        /** Ce qu'on lit quand la liste est vide. */
        String vide() {
            if (etape == ORDRE) return "Aucun ordre ne commence ainsi.";
            if (etape == QUOI && attente) return "Un instant…";
            if (!parmi.isEmpty()) return "Rien ne commence ainsi.";
            return etape == QUI ? "Personne par ici." : depot ? "Le dépôt est vide." : "Il n'a rien sur lui.";
        }

        @Override
        public boolean keyPressed(int key, int scan, int mods) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { envoyer(choix); return true; }
            if (!vues.isEmpty() && key == GLFW.GLFW_KEY_UP) { choix = (choix + 1) % vues.size(); return true; }
            if (!vues.isEmpty() && key == GLFW.GLFW_KEY_DOWN) { choix = (choix - 1 + vues.size()) % vues.size(); return true; }
            return super.keyPressed(key, scan, mods);
        }

        @Override
        public boolean mouseClicked(double x, double y, int bouton) {
            int i = ligneSous(x, y);
            if (bouton == 0 && i >= 0) { envoyer(i); return true; }
            return super.mouseClicked(x, y, bouton);
        }

        @Override
        public void mouseMoved(double x, double y) {
            int i = ligneSous(x, y);
            if (i >= 0) choix = i;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            int x = gauche(), bas = basListe();
            int haut = bas - Math.max(1, vues.size()) * HAUTEUR_LIGNE - 14;
            g.fill(x, haut, x + LARGEUR, height - 2, 0xE8101010); // assez sombre : les cœurs de la barre de vie, dessous, ne se lisent plus à travers
            g.drawString(font, font.plainSubstrByWidth(titre(), LARGEUR - 8), x + 4, haut + 3, Client.OR);
            if (vues.isEmpty()) g.drawString(font, vide(), x + 6, bas - HAUTEUR_LIGNE + 2, Client.GRIS);
            for (int i = 0; i < vues.size(); i++) {
                Recherche.Ligne l = vues.get(i);
                int y = bas - (i + 1) * HAUTEUR_LIGNE;
                if (i == choix) g.fill(x + 2, y, x + LARGEUR - 2, y + HAUTEUR_LIGNE, 0x60F2C14E);
                g.drawString(font, l.libelle(), x + 6, y + 2, i == choix ? Client.BLANC : 0xDDDDDD);
                int w = font.width(l.libelle()) + 12;
                int place = Math.max(0, LARGEUR - w - 10);
                String d = font.width(l.detail()) <= place ? l.detail() : font.plainSubstrByWidth(l.detail(), Math.max(0, place - font.width("…"))) + "…";
                g.drawString(font, d, x + w, y + 2, Client.GRIS);
            }
            super.render(g, mx, my, pt);
            g.drawString(font, "↑ ↓ choisir · Entrée : valider · Échap : fermer", x + 4, height - 12, Client.GRIS);
        }

        @Override
        public void renderBackground(GuiGraphics g, int mx, int my, float pt) {} // le monde reste visible, comme le chat

        @Override
        public boolean isPauseScreen() { return false; }
    }
}
