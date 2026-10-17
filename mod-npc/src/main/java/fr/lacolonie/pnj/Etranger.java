package fr.lacolonie.pnj;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Un autre joueur que son chef parle à un ouvrier (refonte 1.0.0) : autorisé par le chef ({@link Commandement},
 * {@code Courrier.commandants}), il donne des missions comme le chef, sauf celles que seul le chef donne. Sinon,
 * l'ouvrier refuse poliment. Plus de pourboire, de débauche ni de rumeurs. Rien à chaque tick : seulement quand on lui parle.
 */
final class Etranger {
    private Etranger() {}

    /** La dernière phrase de chaque joueur (temps du monde) : une par seconde au plus. Non sauvegardé. */
    static final Map<UUID, Long> DERNIERE = new HashMap<>();

    /** Un autre joueur lui fait un clic droit : il le salue, et la fenêtre de conversation s'ouvre (comme pour un client de la boutique). */
    static void aborder(Travailleur t, ServerPlayer p) {
        t.getLookControl().setLookAt(p);
        if (Cerveau.parfois(t, "etranger_accueil", 20)) t.dire(texte(t, "etranger_salut", p.getName().getString(), 0, "Le bonjour."));
        if (p.connection != null && p.connection.hasChannel(Reseau.Conversation.TYPE))
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p, new Reseau.Conversation(t.getId(), t.fiche(false)));
    }

    /** Un autre que son chef lui parle. */
    static void parler(Travailleur t, ServerPlayer p, String dit) {
        if (t.chef == null || t.mode == Travailleur.Mode.CHOMEUR || t.aTerre || !t.isAlive() || p.level() != t.level()
                || p.distanceToSqr(t) > Dialogue.PORTEE * Dialogue.PORTEE || dit.isBlank() || dit.length() > 256) return;
        long maintenant = t.level().getGameTime();
        Long avant = DERNIERE.get(p.getUUID());
        if (avant != null && maintenant >= avant && maintenant - avant < 20) return;
        if (DERNIERE.size() > 1000) DERNIERE.clear();
        DERNIERE.put(p.getUUID(), maintenant);
        t.getLookControl().setLookAt(p);
        String nom = p.getName().getString();
        String r = repondre(t, p, nom, dit);
        Paroles.noter(t, nom, dit);
        if (r.isBlank()) return;
        Paroles.noter(t, "Toi", r);
        t.dire(r);
    }

    static String repondre(Travailleur t, ServerPlayer p, String nom, String dit) {
        Ordres.Lecture l = Ordres.lire(dit, t.metier);
        Courrier c = Courrier.de(p.getServer());
        if (!Commandement.ordre(l)) return bavarder(t, l, nom);
        // En guerre avec son chef, ou pas autorisé par lui : il refuse.
        if (c.enGuerre(t.chef, p.getUUID()) || !c.commande(t.chef, nom)) return texte(t, "etranger_refus", nom, 0, "Je n'obéis qu'à " + t.chefNom + ".");
        boolean reserve = Commandement.reserver(l, id -> { var e = Repertoire.PAR_ID.get(id); return e == null ? null : e.comportement(); });
        String fait = l.vide() ? "" : t.appliquer(l, p);
        String non = reserve ? texte(t, "etranger_reserve", nom, 0, "Ça, c'est à " + t.chefNom + " d'en décider.") : "";
        return String.join(" ", java.util.stream.Stream.of(non, fait).filter(s -> !s.isBlank()).toList());
    }

    /** Une question ou une politesse d'un inconnu : il répond, sans rien dire de plus que ce que tout le monde voit. */
    static String bavarder(Travailleur t, Ordres.Lecture l, String nom) {
        if (l.intentions.contains(Ordres.Intention.ETAT) || l.intentions.contains(Ordres.Intention.AVANCEMENT) || l.intentions.contains(Ordres.Intention.POURQUOI))
            return texte(t, "etranger_ouvrage", nom, 0, "Je suis au service de " + t.chefNom + ", et l'ouvrage ne manque pas.");
        if (l.intentions.contains(Ordres.Intention.MERCI)) return texte(t, "etranger_merci", nom, 0, "De rien.");
        return texte(t, "etranger_salut", nom, 0, "Le bonjour, " + nom + ".");
    }

    /** La réplique {@code cle} (etrangers.txt), avec {nom} celui qui parle et {prix} ; {@code secours} si elle manque. */
    static String texte(Travailleur t, String cle, String nom, int prix, String secours) {
        return Boutiquier.texte(t, cle, nom, null, prix, secours);
    }
}
