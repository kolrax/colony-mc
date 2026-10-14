package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Le moral au fil des jours, côté jeu (lot 5, cahier 5.2 et 5.3) : le bilan régulier des causes ({@link Moral}), la lenteur
 * d'un moral bas, les lettres au chef et la démission. Rien à chaque tick : le bilan revient toutes les
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
        boolean toit = t.lit != null && !l.canSeeSky(t.lit.above());
        int jourDeuil = m.contains("deuil_le") ? (int) (t.jour() - m.getLong("deuil_le")) : -1;
        boolean pluie = l.isRaining(), orage = l.isThundering();
        int bilansPluie = pluie ? m.getInt("bilans_pluie") + 1 : 0;
        m.putInt("bilans_pluie", bilansPluie);
        boolean coup = m.contains("coup_chef") && l.getGameTime() - m.getLong("coup_chef") < 24000;
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
        if (t.moral >= Moral.APAISE) { m.remove("avertis"); m.remove("avertis_depuis"); }
        int bas = t.moral < r.moral_seuil_demission ? m.getInt("bilans_bas") + 1 : 0;
        m.putInt("bilans_bas", bas);
        int depuis = m.getInt("avertis_depuis") + 1, avertis = m.getInt("avertis");
        m.putInt("avertis_depuis", depuis);
        int avantDepart = r.moral_jours_avant_demission <= 0 ? Integer.MAX_VALUE : r.moral_jours_avant_demission * bilansParJour();
        switch (Moral.suite(t.moral, avertis, depuis, bas, r.moral_seuil_lettre, bilansParJour(), avantDepart)) {
            case AVERTIT -> {
                m.putInt("avertis", avertis + 1);
                m.putInt("avertis_depuis", 0);
                Courrier.ecrire(t, avertis == 0 ? "moral_bas" : "moral_avertit", pire == null ? "le cœur n'y est plus" : pire.dit);
            }
            case DEMISSIONNE -> m.putBoolean("demission", true);
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
        boolean vide = true;
        for (int i = 0; i < t.inventaire.getContainerSize() && vide; i++) vide = t.inventaire.getItem(i).isEmpty();
        if (!vide) {
            if (Bureau.entite(t) != null && Cerveau.parfois(t, "demission_depot", 30)) Cerveau.lancer(t, new Actions.Deposer(List.of()));
            else Cerveau.attendre(t, 5);
            return true;
        }
        Besoins.dire(t, "demission", null);
        Courrier.ecrire(t, "demission", cause(t).isEmpty() ? "le cœur n'y est plus" : cause(t));
        for (String k : List.of("demission", "avertis", "avertis_depuis", "bilans_bas", "bilan", "bilan_cause", "bilan_le", "deuil_le", "deuil_nom"))
            t.memoire.remove(k);
        t.licencier();
        t.moral = Obeissance.DEPART; // libre, il repart d'un bon pied
        return true;
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

    /** Un ouvrier du même chef est mort : le deuil (moral −10 tout de suite, puis moins chaque jour). Crochet pour la case « Deuil, enterrement ». */
    static void deuil(Travailleur mort) {
        if (mort.chef == null || mort.level().getServer() == null) return;
        for (Travailleur t : ColoniePnj.pnjDe(mort.level().getServer(), mort.chef)) {
            if (t == mort) continue;
            t.changerMoral(Moral.DEUIL_COUP);
            t.memoire.putLong("deuil_le", t.jour());
            t.memoire.putString("deuil_nom", mort.prenom);
        }
    }
}
