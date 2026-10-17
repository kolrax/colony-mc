package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Les rumeurs côté jeu (lot 14, cahier 7.6) : ce que les ouvriers voient des autres colonies, ce qu'ils s'en racontent, et
 * l'ordre d'aller espionner. Le savoir et le récit sont dans {@link Rumeurs} (pur Java). Coût : un coup d'œil par ouvrier
 * et par minute au plus ({@link #PERIODE}, réparti sur les ticks), une petite recherche d'entités à 16 blocs ; l'échange
 * entre colonies, une fois par 5 minutes au plus ; jamais de recherche de blocs ni de téléportation.
 */
final class Espions {
    private Espions() {}

    /** Entre deux coups d'œil d'un même ouvrier (ticks ; multiple de 10, le rythme du cerveau). Les tests le raccourcissent. */
    static int PERIODE = 1200;
    /** Il remarque le poste d'une autre colonie jusque-là ; des ouvriers d'un autre chef jusque-là (3 au moins, hors de leur poste). */
    static final double POSTE = 24, VUE = 16;
    /** L'espion : arrivé à tant de blocs du poste, il guette tant de ticks. */
    static final double ARRIVE = 12;
    static final int GUET = 400;
    /** Entre deux échanges avec un ouvrier d'une autre colonie (ticks), et la distance pour bavarder. */
    static final long ECHANGE = 6000;
    static final double PRES = 6;

    /** Ses répliques (rumeurs.txt), avec {chef} son chef. */
    static Rumeurs.Textes textes(Travailleur t) {
        return (cle, v) -> {
            Map<String, String> m = new HashMap<>(v);
            m.put("chef", t.chefNom == null || t.chefNom.isEmpty() ? "chef" : t.chefNom);
            return Vie.tirer(cle, t.temperament, m, t.getRandom()::nextDouble);
        };
    }

    static String dire(Travailleur t, String cle, Map<String, String> v) { return Rumeurs.dire(textes(t), cle, v); }

    /** Le pseudo d'un chef : connecté, ou d'après un de ses ouvriers ; null sinon (jamais le cache des profils). */
    static String nomDe(MinecraftServer s, UUID chef) {
        ServerPlayer p = s.getPlayerList().getPlayer(chef);
        if (p != null) return p.getGameProfile().getName();
        for (Travailleur w : ColoniePnj.tous(s)) if (chef.equals(w.chef) && !w.chefNom.isEmpty()) return w.chefNom;
        return null;
    }

    // --- Ce qu'il voit en passant ---------------------------------------------------------------------------------

    /**
     * Un coup d'œil (une fois par {@link #PERIODE}, depuis le cerveau) : près du poste d'une autre colonie, ou devant 3 ouvriers
     * d'un autre chef ou plus, il note combien il en a vu, leurs métiers, une boutique, et où est leur poste. Pas un enfant.
     */
    static void observer(Travailleur t) {
        if (t.chef == null || Famille.mineur(t) || !(t.level() instanceof ServerLevel sl)) return;
        Courrier c = Courrier.de(sl.getServer());
        Map<UUID, List<Travailleur>> vus = new HashMap<>();
        for (Travailleur o : sl.getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(VUE),
                o -> o.chef != null && !o.chef.equals(t.chef) && o.mode != Travailleur.Mode.CHOMEUR && t.distanceToSqr(o) <= VUE * VUE && t.hasLineOfSight(o)))
            vus.computeIfAbsent(o.chef, k -> new ArrayList<>()).add(o);
        for (var e : c.postes.entrySet()) {
            if (e.getKey().equals(t.chef) || e.getValue().monde() != sl.dimension() || t.blockPosition().distSqr(e.getValue().pos()) > POSTE * POSTE) continue;
            noter(t, e.getKey(), vus.getOrDefault(e.getKey(), List.of()), e.getValue().pos());
            vus.remove(e.getKey());
        }
        for (var e : vus.entrySet()) if (e.getValue().size() >= 3) noter(t, e.getKey(), e.getValue(), null);
    }

    /** Il note ce qu'il voit de la colonie de {@code chef} : ses ouvriers {@code vus}, et son poste s'il l'a sous les yeux. */
    static void noter(Travailleur t, UUID chef, List<Travailleur> vus, BlockPos poste) {
        String nom = vus.isEmpty() ? nomDe(t.getServer(), chef) : vus.get(0).chefNom;
        if (nom == null || nom.isEmpty()) return;
        long jour = t.jour();
        List<Travailleur> grands = vus.stream().filter(o -> !Famille.enfant(o)).toList();
        if (!grands.isEmpty()) {
            t.rumeurs.voir(nom, Rumeurs.Genre.NOMBRE, String.valueOf(grands.size()), jour);
            t.rumeurs.voir(nom, Rumeurs.Genre.METIERS, Rumeurs.metiers(Rumeurs.compter(grands.stream().map(o -> Metier.nom(o.metier)).toList())), jour);
        }
        for (Travailleur o : vus) if (Boutiquier.tient(o)) { t.rumeurs.voir(nom, Rumeurs.Genre.BOUTIQUE, o.prenom, jour); break; }
        if (poste != null) {
            BlockPos d = t.bureau != null ? t.bureau : t.blockPosition();
            t.rumeurs.voir(nom, Rumeurs.Genre.POSTE, Messages.direction(d, poste) + "|" + Rumeurs.distance(Math.sqrt(d.distSqr(poste))), jour);
        }
    }

    /**
     * Ce qui arrive dans une colonie (une guerre déclarée, un mort, une naissance, un ouvrier débauché) : ses ouvriers
     * présents chez eux le voient. {@code sujet} : le chef dont parle le fait.
     */
    static void evenement(MinecraftServer s, UUID chef, String sujet, Rumeurs.Genre g, String valeur) {
        if (chef == null || sujet == null || sujet.isEmpty()) return;
        for (Travailleur w : ColoniePnj.pnjDe(s, chef))
            if (!Famille.mineur(w) && w.bureau != null && w.chezLui(w.blockPosition())) w.rumeurs.voir(sujet, g, valeur, w.jour());
    }

    // --- Ce qu'ils se racontent -------------------------------------------------------------------------------------

    /** {@code a} passe à {@code b} un fait qu'il ignore (parmi ceux que {@code permis} laisse dire), et le lui dit en bulle. */
    static boolean passer(Travailleur a, Travailleur b, Predicate<Rumeurs.Fait> permis) {
        Rumeurs.Fait f = a.rumeurs.aTransmettre(b.rumeurs, permis, a.getRandom()::nextDouble);
        if (f == null || !b.rumeurs.entendre(f, a.prenom)) return false;
        a.dire(dire(a, "rumeur_passe", Map.of("a", a.prenom, "b", b.prenom, "nom", f.sujet())));
        return true;
    }

    /** Une causerie entre deux ouvriers du même chef s'achève : chacun peut passer une nouvelle à l'autre (Causeries). */
    static void causerie(Travailleur a, Travailleur b) {
        passer(a, b, f -> true);
        passer(b, a, f -> true);
    }

    /**
     * Deux ouvriers de colonies différentes, en paix, oisifs l'un près de l'autre (une fois par 5 min au plus, depuis le
     * cerveau toutes les 5 s) : chacun peut passer à l'autre une nouvelle, jamais sur son propre chef.
     */
    static void echanger(Travailleur t) {
        if (t.chef == null || Famille.mineur(t) || !(t.level() instanceof ServerLevel sl)) return;
        long maintenant = sl.getGameTime(), der = t.memoire.getLong("rumeur_echange");
        if (der > 0 && maintenant >= der && maintenant - der < ECHANGE || !Causeries.oisif(t) || t.rumeurs.liste.isEmpty()) return;
        Courrier c = Courrier.de(sl.getServer());
        Travailleur o = sl.getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(PRES), x -> x != t && x.chef != null && !x.chef.equals(t.chef)
                && !c.enGuerre(t.chef, x.chef) && !Famille.mineur(x) && t.distanceToSqr(x) <= PRES * PRES && Causeries.oisif(x) && t.hasLineOfSight(x)).stream().findFirst().orElse(null);
        if (o == null) return;
        t.memoire.putLong("rumeur_echange", maintenant);
        o.memoire.putLong("rumeur_echange", maintenant);
        passer(t, o, f -> !f.sujet().equalsIgnoreCase(t.chefNom));
        passer(o, t, f -> !f.sujet().equalsIgnoreCase(o.chefNom));
    }

    // --- Ce qu'il en dit ------------------------------------------------------------------------------------------

    /** Un ouvrier qui en dit trop à un étranger : un fourbe, ou un mécontent (moral sous 30). */
    static boolean bavard(Travailleur t) { return "fourbe".equals(t.temperament) || t.moral < 30; }

    /**
     * Un autre joueur que son chef lui demande des nouvelles : il dit ce qu'il sait des AUTRES colonies, jamais de la sienne,
     * sauf s'il est fourbe ou mécontent : alors il lâche, à voix basse, un fait sur sa colonie.
     */
    static String repondreEtranger(Travailleur t, Rumeurs.Question q) {
        String moi = t.chefNom == null ? "" : t.chefNom;
        boolean surMoi = q.sujet() != null && q.sujet().equalsIgnoreCase(moi);
        if (surMoi && !bavard(t)) return dire(t, "rumeur_tais_chef", Map.of());
        String r = surMoi ? "" : t.rumeurs.raconter(q, t.jour(), textes(t), f -> !f.sujet().equalsIgnoreCase(moi));
        if (bavard(t) && (surMoi || q.sujet() == null)) r = (r + " " + trahir(t)).strip();
        return r;
    }

    /** Un fait sur sa propre colonie : le plus récent qu'il sache, sinon le nombre de ses compagnons. */
    static String trahir(Travailleur t) {
        long jour = t.jour();
        Rumeurs.Fait f = t.rumeurs.sur(t.chefNom).stream().findFirst().orElse(null);
        if (f == null && t.getServer() != null) {
            long n = ColoniePnj.pnjDe(t.getServer(), t.chef).stream().filter(o -> !Famille.enfant(o)).count();
            f = new Rumeurs.Fait(t.chefNom, Rumeurs.Genre.NOMBRE, String.valueOf(n), jour, Rumeurs.Source.VU, "");
        }
        if (f == null) return "";
        return dire(t, "rumeur_trahit_chef", Map.of()) + " " + Rumeurs.phrase(f, jour, textes(t));
    }

    // --- L'espion ---------------------------------------------------------------------------------------------------

    /** « Espionne Raoul » (le chef seulement) : il part s'il sait où est son poste, sinon il le dit. Sa réponse va dans {@code r}. */
    static void ordre(Travailleur t, String nom, List<String> r) {
        if (!(t.level() instanceof ServerLevel sl) || t.chef == null) return;
        Map<String, String> v = Map.of("nom", nom);
        if (Famille.mineur(t)) { r.add(dire(t, "espion_refus_enfant", v)); return; }
        Map.Entry<UUID, String> x = Armee.chefNomme(sl.getServer(), nom);
        if (x != null && x.getKey().equals(t.chef)) { r.add("C'est notre propre colonie, chef."); return; }
        Courrier.Poste po = x == null ? null : Courrier.de(sl.getServer()).postes.get(x.getKey());
        if (t.rumeurs.fait(nom, Rumeurs.Genre.POSTE) == null || po == null || po.monde() != t.level().dimension()) { r.add(dire(t, "espion_inconnu", v)); return; }
        CompoundTag e = new CompoundTag();
        e.putString("chef", x.getKey().toString());
        e.putString("nom", x.getValue());
        e.putLong("depuis", sl.getGameTime());
        e.putString("etape", "aller");
        t.memoire.put("espion", e);
        if (t.action instanceof Actions.Reposer || t.action instanceof Actions.Attendre || t.action instanceof Actions.Aller) t.finirAction("espion");
        r.add(dire(t, "espion_part", Map.of("nom", x.getValue())));
    }

    static void finir(Travailleur t) { t.memoire.remove("espion"); }

    /** Sa mission d'espion, depuis le cerveau (pendant ses heures de travail) : aller, guetter, rentrer, rapporter. Vrai s'il a de quoi faire. */
    static boolean mission(Travailleur t) {
        CompoundTag e = t.memoire.getCompound("espion");
        if (e.isEmpty() || !(t.level() instanceof ServerLevel sl)) return false;
        String nom = e.getString("nom"), etape = e.getString("etape");
        long maintenant = sl.getGameTime();
        if (etape.equals("retour")) {
            if (t.bureau == null) { finir(t); return false; }
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 5 * 5) { Cerveau.rentrer(t); return true; }
            finir(t);
            rapporter(t, nom, e.getBoolean("repere"));
            Cerveau.attendre(t, 2);
            return true;
        }
        UUID u = Armee.uuid(e.getString("chef"));
        Courrier.Poste po = u == null ? null : Courrier.de(sl.getServer()).postes.get(u);
        if (po == null || po.monde() != t.level().dimension()) { finir(t); return false; }
        if (maintenant - e.getLong("depuis") > Armee.LIMITE) {
            t.dire("Impossible d'arriver jusque chez " + nom + ", chef. Je rentre.");
            finir(t);
            return false;
        }
        if (etape.equals("guetter")) {
            long fin = e.getLong("fin");
            if (maintenant < fin) { t.getLookControl().setLookAt(Vec3.atCenterOf(po.pos())); Cerveau.attendre(t, Math.max(1, (fin - maintenant) / 20.0)); return true; }
            guetter(t, u, nom, po.pos(), e);
            e.putString("etape", "retour");
            t.memoire.put("espion", e);
            if (t.bureau != null) Cerveau.rentrer(t);
            return true;
        }
        Vec3 cible = Vec3.atBottomCenterOf(po.pos());
        if (t.distanceToSqr(cible) > ARRIVE * ARRIVE) {
            Cerveau.Portee p = Cerveau.portee(t, po.pos());
            if (p == Cerveau.Portee.DEMAIN || p == Cerveau.Portee.TROP_LOIN) {
                t.dire("La colonie de " + nom + " est trop loin pour mes jambes, chef.");
                finir(t);
                return false;
            }
            Cerveau.partir(t, po.pos(), "espionner la colonie de " + nom);
            if (t.action instanceof Actions.Aller) Cerveau.lancer(t, new Actions.Aller(cible, ARRIVE - 2, true)); // il s'arrête à distance du poste
            return true;
        }
        e.putString("etape", "guetter");
        e.putLong("fin", maintenant + GUET);
        t.memoire.put("espion", e);
        Cerveau.attendre(t, GUET / 20.0);
        return true;
    }

    /** Au bout du guet : il note tout ce qu'il voit de la colonie (et ses guerres) ; les ouvriers de là-bas qui le voient s'en souviennent. */
    static void guetter(Travailleur t, UUID chef, String nom, BlockPos poste, CompoundTag e) {
        ServerLevel sl = (ServerLevel) t.level();
        List<Travailleur> vus = sl.getEntitiesOfClass(Travailleur.class, t.getBoundingBox().inflate(POSTE),
                o -> chef.equals(o.chef) && o.mode != Travailleur.Mode.CHOMEUR && t.hasLineOfSight(o));
        noter(t, chef, vus, poste);
        for (String ennemi : Courrier.de(sl.getServer()).ennemis(chef).values()) t.rumeurs.voir(nom, Rumeurs.Genre.GUERRE, ennemi, t.jour());
        for (Travailleur o : vus) {
            if (Famille.enfant(o) || o.distanceToSqr(t) > VUE * VUE) continue;
            o.rumeurs.voir(t.chefNom, Rumeurs.Genre.ESPION, t.prenom, o.jour());
            e.putBoolean("repere", true);
        }
    }

    /** De retour au poste : il dit à son chef (de vive voix s'il est là, sinon par lettre) ce qu'il a vu. */
    static void rapporter(Travailleur t, String nom, boolean repere) {
        String r = dire(t, "espion_retour", Map.of("nom", nom)) + " " + t.rumeurs.raconter(new Rumeurs.Question(nom), t.jour(), textes(t), f -> true);
        if (repere) r += " " + dire(t, "espion_repere", Map.of("nom", nom));
        Courrier.ecrireTexte(t, "Des nouvelles de " + nom, r, false);
        IA.noter(t, "Toi", r);
    }
}
