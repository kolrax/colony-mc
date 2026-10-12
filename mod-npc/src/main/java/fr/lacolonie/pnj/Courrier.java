package fr.lacolonie.pnj;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Le courrier, les postes de travail et l'effectif de chaque joueur. Sauvegardé avec le monde.
 * <ul>
 * <li>Plus de messages dans le chat : face à face, l'ouvrier parle (bulle) ; sinon, il écrit une lettre. Dans
 *   l'espace de vie, il l'apporte au poste à pied ; plus loin (ou si c'est urgent), un corbeau la porte.</li>
 * <li>Le chef écrit aussi à ses ouvriers depuis son poste : proche, l'ouvrier passe la prendre ; loin, un corbeau
 *   la lui porte. Il exécute les ordres qu'elle contient et répond.</li>
 * <li>Un corbeau vole à 15 blocs par seconde. On le voit partir et arriver ; entre les deux, son vol est seulement
 *   compté (le faire voler pour de vrai chargerait et générerait tout le terrain sous lui).</li>
 * </ul>
 */
final class Courrier extends SavedData {
    static final String NOM = "colonie_pnj_courrier";
    static final double VITESSE_CORBEAU = 15; // blocs par seconde
    static final int MAX_LETTRES = 200;

    /**
     * Une lettre. {@code pour} : le chef (courrier reçu au poste) ou un ouvrier. {@code type} : sa situation
     * (« torches », « faim »…), pour regrouper les lettres de routine ; vide, elle ne se regroupe jamais (voir
     * {@link Boite}). {@code nombre} : combien de lettres semblables elle rassemble.
     */
    static final class Lettre {
        long id;
        String de = "", objet = "", texte = "", date = "", type = "";
        /** Lettre regroupée de plusieurs ouvriers : tous les noms (bornés, {@link Boite#AUTEURS_MAX}) ; vide sinon. */
        String auteurs = "";
        UUID deUuid, pour;
        boolean lue, duChef, importante, archivee;
        int nombre = 1;

        CompoundTag sauver() {
            CompoundTag t = new CompoundTag();
            t.putLong("id", id);
            t.putString("de", de);
            t.putString("objet", objet);
            t.putString("texte", texte);
            t.putString("date", date);
            if (deUuid != null) t.putUUID("deUuid", deUuid);
            if (pour != null) t.putUUID("pour", pour);
            t.putBoolean("lue", lue);
            t.putBoolean("duChef", duChef);
            t.putString("type", type);
            t.putBoolean("importante", importante);
            t.putBoolean("archivee", archivee);
            t.putInt("nombre", nombre);
            if (!auteurs.isEmpty()) t.putString("auteurs", auteurs);
            return t;
        }

        static Lettre lire(CompoundTag t) {
            Lettre l = new Lettre();
            l.id = t.getLong("id");
            l.de = t.getString("de");
            l.objet = t.getString("objet");
            l.texte = t.getString("texte");
            l.date = t.getString("date");
            l.deUuid = t.hasUUID("deUuid") ? t.getUUID("deUuid") : null;
            l.pour = t.hasUUID("pour") ? t.getUUID("pour") : null;
            l.lue = t.getBoolean("lue");
            l.duChef = t.getBoolean("duChef");
            l.type = t.getString("type"); // absents des anciennes sauvegardes : valeurs par défaut
            l.importante = t.getBoolean("importante");
            l.archivee = t.getBoolean("archivee");
            l.nombre = t.contains("nombre") ? Math.max(1, t.getInt("nombre")) : 1;
            l.auteurs = t.getString("auteurs");
            return l;
        }
    }

    /** Un poste de travail : son monde et sa position. */
    record Poste(ResourceKey<Level> monde, BlockPos pos) {}

    /** Un ouvrier de l'effectif d'un joueur (pour savoir qui est mort ou a disparu). */
    static final class Membre {
        String nom = "", metier = "", mort = "";
        int niveau;
    }

    /** Un corbeau en vol : la lettre, d'où il part, et à quel moment (temps du jeu) il arrive. */
    static final class Vol {
        Lettre lettre;
        UUID chef;
        boolean versPoste;
        BlockPos depart;
        long arrivee;
        /** Le corbeau est en train de se poser (il livrera lui-même) ; s'il disparaît avant, la lettre est livrée à {@code limite}. */
        boolean atterrissage;
        long limite;
    }

    final Map<UUID, List<Lettre>> boites = new HashMap<>();
    final Map<UUID, Poste> postes = new HashMap<>();
    final Map<UUID, LinkedHashMap<UUID, Membre>> effectifs = new HashMap<>();
    final List<Vol> vols = new ArrayList<>();
    long prochainId = 1;

    static Courrier de(MinecraftServer s) {
        return s.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Courrier::new, Courrier::charger), NOM);
    }

    // --- Sauvegarde -------------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag t, HolderLookup.Provider reg) {
        t.putLong("ProchainId", prochainId);
        ListTag b = new ListTag();
        boites.forEach((chef, l) -> {
            CompoundTag x = new CompoundTag();
            x.putUUID("chef", chef);
            ListTag ls = new ListTag();
            for (Lettre lt : l) ls.add(lt.sauver());
            x.put("lettres", ls);
            b.add(x);
        });
        t.put("Boites", b);
        ListTag p = new ListTag();
        postes.forEach((chef, po) -> {
            CompoundTag x = new CompoundTag();
            x.putUUID("chef", chef);
            x.putString("monde", po.monde().location().toString());
            x.put("pos", NbtUtils.writeBlockPos(po.pos()));
            p.add(x);
        });
        t.put("Postes", p);
        ListTag e = new ListTag();
        effectifs.forEach((chef, m) -> m.forEach((id, mb) -> {
            CompoundTag x = new CompoundTag();
            x.putUUID("chef", chef);
            x.putUUID("id", id);
            x.putString("nom", mb.nom);
            x.putString("metier", mb.metier);
            x.putInt("niveau", mb.niveau);
            x.putString("mort", mb.mort);
            e.add(x);
        }));
        t.put("Effectifs", e);
        ListTag v = new ListTag();
        for (Vol vo : vols) {
            CompoundTag x = new CompoundTag();
            x.put("lettre", vo.lettre.sauver());
            if (vo.chef != null) x.putUUID("chef", vo.chef);
            x.putBoolean("versPoste", vo.versPoste);
            x.put("depart", NbtUtils.writeBlockPos(vo.depart));
            x.putLong("arrivee", vo.arrivee);
            x.putBoolean("atterrissage", vo.atterrissage);
            x.putLong("limite", vo.limite);
            v.add(x);
        }
        t.put("Vols", v);
        return t;
    }

    static Courrier charger(CompoundTag t, HolderLookup.Provider reg) {
        Courrier c = new Courrier();
        c.prochainId = Math.max(1, t.getLong("ProchainId"));
        for (Tag x : t.getList("Boites", Tag.TAG_COMPOUND)) {
            CompoundTag b = (CompoundTag) x;
            List<Lettre> l = new ArrayList<>();
            for (Tag y : b.getList("lettres", Tag.TAG_COMPOUND)) l.add(Lettre.lire((CompoundTag) y));
            c.boites.put(b.getUUID("chef"), l);
        }
        for (Tag x : t.getList("Postes", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) x;
            ResourceLocation monde = ResourceLocation.tryParse(p.getString("monde"));
            BlockPos pos = NbtUtils.readBlockPos(p, "pos").orElse(null);
            if (monde != null && pos != null) c.postes.put(p.getUUID("chef"), new Poste(ResourceKey.create(Registries.DIMENSION, monde), pos));
        }
        for (Tag x : t.getList("Effectifs", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) x;
            Membre m = new Membre();
            m.nom = e.getString("nom");
            m.metier = e.getString("metier");
            m.niveau = e.getInt("niveau");
            m.mort = e.getString("mort");
            c.effectifs.computeIfAbsent(e.getUUID("chef"), k -> new LinkedHashMap<>()).put(e.getUUID("id"), m);
        }
        for (Tag x : t.getList("Vols", Tag.TAG_COMPOUND)) {
            CompoundTag v = (CompoundTag) x;
            Vol vo = new Vol();
            vo.lettre = Lettre.lire(v.getCompound("lettre"));
            vo.chef = v.hasUUID("chef") ? v.getUUID("chef") : null;
            vo.versPoste = v.getBoolean("versPoste");
            vo.depart = NbtUtils.readBlockPos(v, "depart").orElse(BlockPos.ZERO);
            vo.arrivee = v.getLong("arrivee");
            vo.atterrissage = v.getBoolean("atterrissage");
            vo.limite = v.getLong("limite");
            c.vols.add(vo);
        }
        return c;
    }

    // --- Postes de travail ------------------------------------------------------------------------

    Poste poste(UUID chef) { return chef == null ? null : postes.get(chef); }

    void definirPoste(UUID chef, Level l, BlockPos pos) {
        postes.put(chef, new Poste(l.dimension(), pos.immutable()));
        setDirty();
    }

    void oublierPoste(UUID chef, Level l, BlockPos pos) {
        Poste p = postes.get(chef);
        if (p != null && p.monde() == l.dimension() && p.pos().equals(pos)) { postes.remove(chef); setDirty(); }
    }

    // --- L'effectif ---------------------------------------------------------------------------------

    void inscrire(Travailleur t) {
        if (t.chef == null) return;
        Membre m = effectifs.computeIfAbsent(t.chef, k -> new LinkedHashMap<>()).computeIfAbsent(t.getUUID(), k -> new Membre());
        if (!m.nom.equals(t.prenom) || !m.metier.equals(t.metier) || m.niveau != t.niveau || !m.mort.isEmpty()) {
            m.nom = t.prenom;
            m.metier = t.metier;
            m.niveau = t.niveau;
            m.mort = "";
            setDirty();
        }
    }

    void retirer(UUID chef, UUID id) {
        var m = effectifs.get(chef);
        if (m != null && m.remove(id) != null) setDirty();
    }

    void marquerMort(Travailleur t, String quand) {
        if (t.chef == null) return;
        inscrire(t);
        effectifs.get(t.chef).get(t.getUUID()).mort = quand;
        setDirty();
    }

    // --- Le courrier --------------------------------------------------------------------------------

    Lettre nouvelle(String de, UUID deUuid, UUID pour, String objet, String texte, Level l) {
        Lettre x = new Lettre();
        x.id = prochainId++;
        x.de = de;
        x.deUuid = deUuid;
        x.pour = pour;
        x.objet = objet;
        x.texte = texte;
        x.date = Messages.date(l);
        setDirty();
        return x;
    }

    List<Lettre> boite(UUID chef) { return boites.computeIfAbsent(chef, k -> new ArrayList<>()); }

    long nonLues(UUID chef) { return boite(chef).stream().filter(l -> !l.lue && !l.archivee).count(); }

    /**
     * Une lettre arrive au poste du chef : on la range (une lettre de routine se fond dans sa semblable encore non lue,
     * voir {@link Boite#ranger}), et le chef (s'il est là) l'apprend d'un mot discret, sans le chat : seulement pour
     * une nouvelle lettre, pas quand une lettre déjà là compte une fois de plus.
     */
    void recevoir(MinecraftServer s, UUID chef, Lettre l) {
        boolean nouvelle = Boite.ranger(boite(chef), l, MAX_LETTRES);
        setDirty();
        ServerPlayer p = s.getPlayerList().getPlayer(chef);
        if (p != null && nouvelle) {
            p.displayClientMessage(Component.literal("✉ Une lettre de " + l.de + " t'attend au poste : « " + l.objet + " »").withStyle(ChatFormatting.GOLD), true);
            p.playNotifySound(SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8f, 1.1f);
        }
    }

    /**
     * Un avis du poste lui-même. Les avis non lus du même {@code type} se regroupent (« ramene ») ; un avis important
     * (un décès) ne se regroupe jamais.
     */
    static void avis(MinecraftServer s, UUID chef, String type, boolean importante, String objet, String texte) {
        Courrier c = de(s);
        Lettre l = c.nouvelle("Le poste", null, chef, objet, texte, s.overworld());
        l.type = type;
        l.importante = importante;
        c.recevoir(s, chef, l);
    }

    // --- Ce que les ouvriers écrivent ---------------------------------------------------------------

    /**
     * Un ouvrier a quelque chose à dire à son chef. Face à face, il le lui dit ; sinon, il lui écrit : dans l'espace
     * de vie, il apporte la lettre au poste à pied ; plus loin, ou si c'est urgent, un corbeau la porte.
     */
    static void ecrire(Travailleur t, String type, Object... vals) {
        envoyer(t, Messages.SEULES.contains(type) ? "" : type, Messages.IMPORTANTES.contains(type), Messages.objet(type),
                Messages.corps(t, type, vals), Messages.URGENTES.contains(type));
    }

    /** Pareil, avec son propre texte (une réponse écrite par l'IA, par exemple) : une lettre seule, jamais regroupée. */
    static void ecrireTexte(Travailleur t, String objet, String corps, boolean urgent) { envoyer(t, "", false, objet, corps, urgent); }

    private static void envoyer(Travailleur t, String type, boolean importante, String objet, String corps, boolean urgent) {
        if (t.chef == null || !(t.level() instanceof ServerLevel sl)) return;
        ServerPlayer chef = t.chefJoueur();
        if (chef != null && chef.level() == t.level() && chef.distanceTo(t) < 12 && t.hasLineOfSight(chef)) { t.dire(corps); return; }
        Courrier c = de(sl.getServer());
        Lettre l = c.nouvelle(t.prenom, t.getUUID(), t.chef, objet, Messages.lettre(t, corps), t.level());
        l.type = type;
        l.importante = importante;
        c.expedier(t, l, urgent);
    }

    /** Proche et rien d'urgent : il la garde pour la poser au poste en passant. Sinon : un corbeau. */
    void expedier(Travailleur t, Lettre l, boolean urgent) {
        if (!urgent && t.bureau != null && t.chezLui(t.blockPosition()) && !t.aTerre && poste(t.chef) != null) {
            t.lettresADeposer.add(l);
            return;
        }
        envoyerCorbeau(t.level(), t.blockPosition(), t.chef, l, true);
    }

    /** Il est au poste : il y pose ses lettres. */
    static void deposer(Travailleur t) {
        if (t.lettresADeposer.isEmpty() || !(t.level() instanceof ServerLevel sl)) return;
        Courrier c = de(sl.getServer());
        for (Lettre l : t.lettresADeposer) c.recevoir(sl.getServer(), t.chef, l);
        t.lettresADeposer.clear();
    }

    // --- Les corbeaux -------------------------------------------------------------------------------

    /** Un corbeau part de {@code depart} avec la lettre, vers le poste du chef ou vers l'ouvrier destinataire. */
    void envoyerCorbeau(Level l, BlockPos depart, UUID chef, Lettre lettre, boolean versPoste) {
        Vol v = new Vol();
        v.lettre = lettre;
        v.chef = chef;
        v.versPoste = versPoste;
        v.depart = depart.immutable();
        BlockPos arrivee = versPoste ? (poste(chef) != null ? poste(chef).pos() : depart) : depart;
        if (!versPoste && l.getServer() != null) {
            Travailleur dest = trouver(l.getServer(), lettre.pour);
            if (dest != null) arrivee = dest.blockPosition();
        }
        double d = Math.sqrt(depart.distSqr(arrivee));
        v.arrivee = l.getGameTime() + Math.max(100, (long) (d / VITESSE_CORBEAU * 20));
        vols.add(v);
        setDirty();
        Corbeau.envol(l, depart, arrivee, lettre);
    }

    static Travailleur trouver(MinecraftServer s, UUID id) {
        if (id == null) return null;
        for (ServerLevel lvl : s.getAllLevels()) if (lvl.getEntity(id) instanceof Travailleur t && t.isAlive()) return t;
        return null;
    }

    /** Chaque seconde : les corbeaux arrivés se montrent et se posent ; s'il n'y a personne pour les voir, la lettre est livrée tout de suite. */
    static void tick(MinecraftServer s) {
        Courrier c = de(s);
        if (c.vols.isEmpty()) return;
        long maintenant = s.overworld().getGameTime();
        for (Vol v : List.copyOf(c.vols)) {
            if (v.arrivee > maintenant) continue;
            if (v.atterrissage) { if (maintenant > v.limite) c.livrer(s, v); continue; }
            Level lvl = null;
            BlockPos dest = null;
            if (v.versPoste) {
                Poste p = c.poste(v.chef);
                if (p != null) { lvl = s.getLevel(p.monde()); dest = p.pos(); }
            } else {
                Travailleur t = trouver(s, v.lettre.pour);
                if (t != null) { lvl = t.level(); dest = t.blockPosition(); }
            }
            if (lvl != null && Corbeau.arrivee(lvl, v.depart, dest, v.versPoste, v.lettre.id)) {
                v.atterrissage = true;
                v.limite = maintenant + 600;
                c.setDirty();
                continue;
            }
            c.livrer(s, v);
        }
    }

    /** Le corbeau qui porte cette lettre s'est posé : il la livre. */
    static void livrer(MinecraftServer s, long id) {
        Courrier c = de(s);
        for (Vol v : List.copyOf(c.vols)) if (v.lettre.id == id) c.livrer(s, v);
    }

    void livrer(MinecraftServer s, Vol v) {
        vols.remove(v);
        setDirty();
        if (v.versPoste) { recevoir(s, v.chef, v.lettre); return; }
        Travailleur t = trouver(s, v.lettre.pour);
        if (t == null) { // l'ouvrier n'est plus là (mort, renvoyé) : la lettre revient au poste
            v.lettre.objet = "Retour : " + v.lettre.objet;
            recevoir(s, v.chef, v.lettre);
            return;
        }
        t.recevoirLettre(v.lettre);
    }

    // --- Ce que le chef écrit -------------------------------------------------------------------------

    /** Le chef écrit à un ouvrier depuis son poste. */
    static void ecrireAuPnj(ServerPlayer chef, Travailleur t, String texte) {
        Courrier c = de(chef.server);
        Lettre l = c.nouvelle(chef.getName().getString(), chef.getUUID(), t.getUUID(), "Lettre du chef", texte, chef.level());
        l.duChef = true;
        Poste p = c.poste(chef.getUUID());
        if (p != null && t.level().dimension() == p.monde() && t.chezLui(t.blockPosition())) {
            t.lettresALire.add(l); // il passera la prendre au poste
            return;
        }
        c.envoyerCorbeau(chef.level(), p != null ? p.pos() : chef.blockPosition(), chef.getUUID(), l, false);
    }
}
