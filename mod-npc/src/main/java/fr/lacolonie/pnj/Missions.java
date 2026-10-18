package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Set;

/**
 * Donner des missions (refonte 1.0.0, parties 1 et 4) : la seule porte d'entrée côté serveur. La fiche de l'ouvrier
 * (clic droit) et le joueur fantôme passent tous deux par {@link #donner} ; les boutons de la fiche par {@link #action}.
 * Une mission impossible ou mortelle est refusée avec sa raison ; sinon elle s'ajoute à sa file.
 */
public final class Missions {
    private Missions() {}

    /** Au plus 8 missions dans une file. */
    public static final int FILE_MAX = 8;
    /** Un lieu imposé à plus de 512 blocs du poste : il n'en reviendrait pas. */
    static final int LOIN = 512;
    static final Set<String> REPETITIONS = Set.of("fois", "jour", "stock");

    /** La réponse au joueur : acceptée ou non, et la phrase courte qu'il a lue. */
    public record Reponse(boolean ok, String texte) {}

    /** Son chef, ou un joueur que son chef autorise à le commander ({@link Commandement}, {@code Courrier.commandants}). */
    public static boolean autorise(ServerPlayer p, Travailleur t) {
        if (t.chef == null || t.mode == Travailleur.Mode.CHOMEUR) return false;
        if (p.getUUID().equals(t.chef)) return true;
        Courrier c = Courrier.de(p.server);
        return c.commande(t.chef, p.getGameProfile().getName()) && !c.enGuerre(t.chef, p.getUUID()); // un ennemi déclaré ne commande plus
    }

    /**
     * Donne une mission à un ouvrier. {@code id} : une ligne du catalogue de son métier ({@code Metier.commandes}) ;
     * {@code repetition} : « fois », « jour » (tous les jours) ou « stock » (garder N au dépôt) ; {@code horaires} : « jour »,
     * « nuit », « toujours » ou « 8-12 » ; {@code lieu} : null (il choisit) ou la position imposée. Le joueur lit la réponse.
     */
    public static Reponse donner(ServerPlayer p, Travailleur t, String id, String repetition, int nombre, String horaires, BlockPos lieu) {
        Reponse r = valider(p, t, id, repetition, nombre, horaires, lieu);
        if (r.ok()) {
            Metier.Commande c = Metier.catalogue(t.metier, id);
            Travailleur.Mission m = new Travailleur.Mission(id, repetition.equals("stock") ? "stock" : "quantite", c.objet == null ? 1 : nombre);
            m.quotidienne = repetition.equals("jour") && c.objet != null;
            m.uneFois = repetition.equals("fois");
            m.horaires = horaires;
            m.lieu = lieu == null ? null : lieu.immutable();
            t.missions.removeIf(x -> x.id.equals(id) && x.horaires.equals(horaires)); // la même mission, redonnée : elle est remplacée
            t.missions.add(m);
            t.missionAjoutee();
            Consignes.annuler(t); // un « garde à vous » ou une tâche du répertoire s'arrête
            t.ordreChef = t.level().getGameTime();
            reprendre(t);
            if (t.missionActive() == m) t.finirAction("ordre"); // sa première mission : il s'y met tout de suite
            t.debloquer();
            t.majNom();
            t.dire("Bien, chef.");
        }
        Travailleur.signaler(p, r.texte());
        return r;
    }

    static Reponse valider(ServerPlayer p, Travailleur t, String id, String repetition, int nombre, String horaires, BlockPos lieu) {
        if (t.mode == Travailleur.Mode.CHOMEUR || t.chef == null) return non(t.prenom + " ne travaille pour personne.");
        if (!autorise(p, t)) return non(t.prenom + " ne prend ses missions que de " + (t.chefNom.isEmpty() ? "son chef" : t.chefNom) + " et de ceux qu'il autorise.");
        if (t.aTerre) return non(t.prenom + " est à terre : relève-le d'abord.");
        if (t.bureau == null) return non(t.prenom + " n'a pas de poste de travail.");
        Metier.Commande c = Metier.catalogue(t.metier, id);
        if (c == null) return non(t.prenom + " est " + Metier.nom(t.metier).toLowerCase() + " : il ne sait pas faire cela.");
        if (repetition == null || !REPETITIONS.contains(repetition)) return non("Choisis : une fois, tous les jours, ou garder au dépôt.");
        if (c.objet != null && (nombre < 1 || nombre > 10_000)) return non("Une quantité entre 1 et 10 000.");
        if (!Statut.horairesValides(horaires)) return non("Horaires illisibles : le jour, la nuit, toujours, ou de telle heure à telle heure.");
        if (t.missions.size() >= FILE_MAX && t.missions.stream().noneMatch(x -> x.id.equals(id) && x.horaires.equals(horaires)))
            return non("Sa file est pleine (" + FILE_MAX + " missions) : annules-en une d'abord.");
        if (lieu != null) {
            if (lieu.distManhattan(t.bureau) > LOIN * 2 || Math.abs(lieu.getX() - t.bureau.getX()) > LOIN || Math.abs(lieu.getZ() - t.bureau.getZ()) > LOIN)
                return non("Ce lieu est trop loin du poste : " + t.prenom + " n'en reviendrait pas.");
            if (lieu.getY() < t.level().getMinBuildHeight() || lieu.getY() >= t.level().getMaxBuildHeight() || t.level().isLoaded(lieu) && t.level().getFluidState(lieu).is(FluidTags.LAVA))
                return non("Ce lieu est mortel : choisis-en un autre.");
        }
        return new Reponse(true, Statut.confirmation(t.prenom, t.metier, c.nom, c.objet != null, repetition, nombre, horaires, lieu != null));
    }

    static Reponse non(String texte) { return new Reponse(false, texte); }

    /** Attendre ou suivre : une mission (ou « Travaille ici ») le remet au travail. Un gréviste reste en grève. */
    static void reprendre(Travailleur t) {
        if (t.mode == Travailleur.Mode.ATTENDRE || t.mode == Travailleur.Mode.SUIVRE) {
            t.mode = Travailleur.Mode.TRAVAIL;
            t.memoire.remove("rappel");
            t.memoire.remove("attendre_apres");
        }
    }

    // --- Les boutons de la fiche ---------------------------------------------------------------------------

    /** Un bouton de la fiche : mission, ici, suivre, attendre, rentrer, licencier, annuler. Puis la fiche est rafraîchie. */
    public static void action(ServerPlayer p, Travailleur t, String action, CompoundTag d) {
        if (!autorise(p, t)) { Travailleur.signaler(p, t.prenom + " ne prend ses ordres que de " + t.chefNom + "."); return; }
        boolean chef = p.getUUID().equals(t.chef);
        switch (action) {
            case "mission" -> donner(p, t, d.getString("id"), d.getString("repetition"), d.getInt("nombre"), d.getString("horaires"),
                    d.getBoolean("ici") ? p.blockPosition() : null);
            case "ici" -> {
                t.travailleIci(p.blockPosition());
                reprendre(t);
                t.finirAction("ordre");
                t.dire("Bien, je travaillerai ici.");
                Travailleur.signaler(p, t.prenom + " travaillera ici : " + p.blockPosition().toShortString() + ".");
            }
            case "suivre" -> {
                if (!chef) { Travailleur.signaler(p, t.prenom + " ne suit que " + t.chefNom + "."); break; }
                if (t.mode == Travailleur.Mode.GREVE) { Travailleur.signaler(p, t.prenom + " est en grève : paie-le d'abord."); break; }
                Consignes.annuler(t);
                t.memoire.remove("rappel");
                t.memoire.remove("attendre_apres");
                t.mode = Travailleur.Mode.SUIVRE;
                t.finirAction("ordre");
                t.dire("Je te suis.");
            }
            case "attendre" -> {
                if (t.mode == Travailleur.Mode.GREVE) { Travailleur.signaler(p, t.prenom + " est en grève : paie-le d'abord."); break; }
                Consignes.annuler(t);
                t.mode = Travailleur.Mode.ATTENDRE;
                t.finirAction("ordre");
                t.dire("J'attends ici.");
            }
            case "rentrer" -> {
                if (t.bureau == null) { Travailleur.signaler(p, t.prenom + " n'a pas de poste de travail."); break; }
                if (t.mode == Travailleur.Mode.GREVE) { Travailleur.signaler(p, t.prenom + " est en grève : il reste au poste."); break; }
                Consignes.annuler(t);
                t.memoire.putBoolean("rappel", true);
                t.mode = Travailleur.Mode.TRAVAIL;
                t.finirAction("ordre");
                t.dire("Je rentre au poste.");
            }
            case "annuler" -> {
                int k = d.getInt("index");
                // La file vue par l'écran (« n ») doit être celle d'aujourd'hui : sinon l'index viserait une autre mission.
                if (d.getInt("n") != t.missions.size()) Travailleur.signaler(p, "Sa file a changé : rouvre sa fiche.");
                else if (k >= 0 && k < t.missions.size()) {
                    Travailleur.Mission m = t.missions.remove(k);
                    if (t.action != null) t.finirAction("ordre");
                    Travailleur.signaler(p, "Mission annulée : " + Metier.nomMission(t.metier, m) + ".");
                }
            }
            case "licencier" -> {
                if (!chef) { Travailleur.signaler(p, "Seul " + t.chefNom + " peut le renvoyer."); break; }
                t.dire("Adieu, chef.");
                Courrier.reputer(t, Recrutement.RENVOI); // comme l'ancien renvoi : la colonie le sait
                t.licencier();
                Travailleur.signaler(p, t.prenom + " ne travaille plus pour toi.");
                return; // plus de fiche à montrer
            }
            default -> { return; }
        }
        t.ordreChef = t.level().getGameTime();
        t.majNom();
        montrer(p, t);
    }

    /** Ouvre (ou rafraîchit) la fiche chez le joueur. */
    public static void montrer(ServerPlayer p, Travailleur t) {
        if (p.connection != null && p.connection.hasChannel(Reseau.FicheOuvrier.TYPE)) PacketDistributor.sendToPlayer(p, new Reseau.FicheOuvrier(t.getId(), fiche(p, t)));
    }

    /** Ce que montre la fiche : la fiche de base, sa file, le catalogue de son métier, son équipement. */
    static CompoundTag fiche(ServerPlayer p, Travailleur t) {
        CompoundTag f = t.fiche(false);
        f.putString("metierId", t.metier);
        f.putBoolean("chef", p.getUUID().equals(t.chef));
        f.putString("salaireTexte", Reglages.texte(t.salaireVerse()) + " " + f.getString("periode"));
        ListTag ms = new ListTag();
        Travailleur.Mission active = t.missionActive();
        for (Travailleur.Mission m : t.missions) {
            CompoundTag x = new CompoundTag();
            x.putString("texte", Metier.avancementTexte(t, m) + Statut.horairesTexte(m.horaires)
                    + (m.lieu != null ? ", en " + m.lieu.toShortString() : "") + (m.uneFois ? ", une fois" : ""));
            x.putBoolean("active", m == active);
            ms.add(x);
        }
        f.put("file", ms);
        ListTag cs = new ListTag();
        Metier metier = Metier.de(t.metier);
        if (metier != null) for (Metier.Commande c : metier.commandes) {
            CompoundTag x = new CompoundTag();
            x.putString("id", c.id);
            x.putString("nom", c.nom);
            x.putBoolean("compte", c.objet != null);
            cs.add(x);
        }
        f.put("commandes", cs);
        ListTag eq = new ListTag();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = t.getItemBySlot(s);
            if (!st.isEmpty()) eq.add(StringTag.valueOf(st.getHoverName().getString()));
        }
        f.put("equipement", eq);
        if (t.lieuImpose != null) f.putString("ici", t.lieuImpose.toShortString());
        return f;
    }
}
