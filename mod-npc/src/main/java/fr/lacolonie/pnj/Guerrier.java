package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Le guerrier. Commandes : garder le bureau, faire la ronde dans son espace de vie, ou escorter son chef.
 * Il tue les créatures hostiles, et celles qui s'en prennent à lui ou à son chef. Il ne frappe un joueur que sur ordre
 * de son chef (en guerre, une consigne de combat) ; frappé par un joueur, il recule sans riposter. Il ne frappe jamais
 * un villageois, un golem ou un animal (règle du serveur : pas de violence hors RP).
 */
final class Guerrier extends Metier {
    Guerrier() {
        super("guerrier", "Guerrier", "Garde le bureau, fait la ronde autour ou escorte son chef, et tue les monstres. Il ne lève son arme sur un homme que sur l'ordre de son chef ; si quelqu'un le frappe, il recule sans rendre le coup. Il ne touche jamais aux villageois ni aux bêtes.",
                true, "nourriture", "#minecraft:swords", "#minecraft:axes");
        commandes.add(new Commande("garder", "Garder le bureau", null));
        commandes.add(new Commande("ronde", "Faire la ronde (50 blocs)", null));
        commandes.add(new Commande("escorter", "Escorter son chef", null));
    }

    static void armer(Travailleur t) {
        if (!t.equiper(Actions.Filtre.de("#minecraft:swords"))) t.equiper(Actions.Filtre.de("#minecraft:axes"));
    }

    /** Un ennemi surgit pendant qu'il fait autre chose (il marche, il est au bureau…) : il lâche tout. */
    @Override
    void vigilance(Travailleur t) {
        if (t.action instanceof Actions.Attaquer || t.action instanceof Actions.Echapper || t.action instanceof Actions.Reposer && t.isSleeping()) return;
        List<LivingEntity> c = Monde.cibles(t, 10);
        if (!c.isEmpty()) { armer(t); Cerveau.lancer(t, new Actions.Attaquer(c.get(0))); }
    }

    @Override
    boolean escorter(Travailleur t) {
        List<LivingEntity> c = Monde.cibles(t, 12);
        if (c.isEmpty()) return false;
        armer(t);
        Cerveau.lancer(t, new Actions.Attaquer(c.get(0)));
        return true;
    }

    @Override
    void travailler(Travailleur t) {
        armer(t);
        Commande c = Metier.commande(t);
        String id = c == null ? "garder" : c.id;

        if (id.equals("escorter")) {
            ServerPlayer chef = t.chefJoueur();
            if (chef != null && chef.level() == t.level()) {
                if (!escorter(t)) Cerveau.lancer(t, new Actions.SuivreChef());
                return;
            }
            // Le chef n'est pas là (déconnecté, ou dans une autre dimension) : il garde le bureau en l'attendant.
            Cerveau.dire(t, "chef_absent", 1800, "Tu n'es pas là, chef : je garde le poste en t'attendant.");
            id = "garder";
        }

        BlockPos b = t.bureau;
        int r = id.equals("garder") ? 12 : Travailleur.ESPACE_DE_VIE;
        List<LivingEntity> cibles = Monde.cibles(t, Math.min(r, 32));
        if (!cibles.isEmpty()) { Cerveau.lancer(t, new Actions.Attaquer(cibles.get(0))); return; }
        if (t.distanceToSqr(Vec3.atBottomCenterOf(b)) > r * r) { Cerveau.lancer(t, new Actions.Aller(Vec3.atBottomCenterOf(b), 3)); return; }

        // Une ronde, ce n'est pas marcher au hasard : un circuit de huit postes autour du bureau, toujours le même.
        // Pour garder le bureau : quatre postes tout près, avec une pause à chacun.
        int postes = id.equals("ronde") ? 8 : 4;
        double rayon = id.equals("ronde") ? (t.memoire.getInt("poste") % 2 == 0 ? 24 : 42) : 6;
        if (t.memoire.getLong("pause") > Cerveau.temps(t)) { Cerveau.attendre(t, 2); return; }
        int poste = (t.memoire.getInt("poste") + 1) % postes;
        t.memoire.putInt("poste", poste);
        t.memoire.putLong("pause", Cerveau.temps(t) + (id.equals("ronde") ? 4 : 15));
        double a = poste * 2 * Math.PI / postes;
        int x = b.getX() + (int) Math.round(Math.cos(a) * rayon), z = b.getZ() + (int) Math.round(Math.sin(a) * rayon);
        int y = Monde.surface(t.level(), x, z);
        Actions.Aller ronde = new Actions.Aller(new Vec3(x + 0.5, y == Integer.MIN_VALUE ? b.getY() : y, z + 0.5), 2, true);
        ronde.travail = true; // arrivé à son poste de ronde : il fait son travail
        Cerveau.lancer(t, ronde);
    }
}
