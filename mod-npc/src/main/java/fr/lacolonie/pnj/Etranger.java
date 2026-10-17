package fr.lacolonie.pnj;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lot 14, cahier 5.8, 6.1 et 6.2 : un autre joueur que son chef parle à un ouvrier (fenêtre de conversation, ou au micro
 * en le regardant). Autorisé par le chef ({@link Commandement}, {@code Courrier.commandants}) : il obéit à ses ordres de
 * travail comme à ceux du chef, sauf ceux que seul le chef donne. Sinon : il répond poliment mais refuse ses ordres,
 * sauf s'il le paie assez pour cet ordre-là. Il peut aussi tenter de le débaucher ({@link Debauche}) : l'ouvrier quitte
 * son chef s'il y gagne nettement et reçoit la somme d'avance, en vraies pièces. Rien à chaque tick : seulement quand on
 * lui parle.
 */
final class Etranger {
    private Etranger() {}

    /** La dernière phrase de chaque joueur (temps du monde) : une par seconde au plus. Non sauvegardé. */
    static final Map<UUID, Long> DERNIERE = new HashMap<>();
    /** Après un refus, le même joueur attend un jour du jeu avant de retenter sa chance. */
    static final long RANCUNE = 24000;

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
        IA.noter(t, nom, dit);
        if (r.isBlank()) return;
        IA.noter(t, "Toi", r);
        t.dire(r);
    }

    static String repondre(Travailleur t, ServerPlayer p, String nom, String dit) {
        Debauche.Offre offre = Debauche.lire(dit);
        if (offre != null) return debaucher(t, p, nom, offre);
        // « Quelles nouvelles ? », « que sais-tu de Raoul ? » : ce qu'il sait des autres colonies, pas de la sienne (Espions).
        Rumeurs.Question rumeur = Rumeurs.question(dit);
        if (rumeur != null) return Espions.repondreEtranger(t, rumeur);
        Debauche.Pourboire paye = Debauche.pourboire(dit);
        Ordres.Lecture l = Ordres.lire(paye != null ? paye.reste() : dit, t.metier);
        Courrier c = Courrier.de(p.getServer());
        if (!Commandement.ordre(l)) return bavarder(t, l, nom);
        // En guerre avec son chef : ni ordre ni pièce de lui.
        if (c.enGuerre(t.chef, p.getUUID())) return texte(t, "etranger_refus", nom, 0, "Je n'obéis qu'à " + t.chefNom + ".");
        boolean reserve = Commandement.reserver(l, id -> { var e = Repertoire.PAR_ID.get(id); return e == null ? null : e.comportement(); });
        String paie = "";
        if (!c.commande(t.chef, nom)) {
            if (paye == null) return texte(t, "etranger_refus", nom, 0, "Je n'obéis qu'à " + t.chefNom + ".");
            // Un enfant ou un apprenti ne se loue pas à un autre : rien n'est pris (lot 13).
            if (Famille.mineur(t)) return texte(t, "etranger_mineur", nom, 0, "Garde tes pièces : je suis trop jeune. C'est " + t.chefNom + " qui décide de moi.");
            // Ce qu'il ne fera pas de toute façon (réservé au chef, grève, ordre mortel, hors métier, moral trop bas) : rien n'est pris.
            if (reserve || l.vide() || !accepterait(t, l)) return texte(t, "etranger_refus", nom, 0, "Je n'obéis qu'à " + t.chefNom + ".");
            if (!Debauche.assez(paye.pieces(), t.salaireVerse()))
                return texte(t, "etranger_trop_peu", nom, (int) paye.pieces(), "C'est trop peu pour laisser l'ouvrage de " + t.chefNom + ".");
            if (!encaisser(t, p, paye.pieces())) return texte(t, "etranger_sans_pieces", nom, (int) paye.pieces(), "Montre-moi d'abord les pièces.");
            paie = texte(t, "etranger_paye", nom, (int) paye.pieces(), "Marché conclu.");
        }
        String fait = l.vide() ? "" : t.appliquer(l, p);
        String non = reserve ? texte(t, "etranger_reserve", nom, 0, "Ça, c'est à " + t.chefNom + " d'en décider.") : "";
        return String.join(" ", java.util.stream.Stream.of(paie, non, fait).filter(s -> !s.isBlank()).toList());
    }

    /** Obéirait-il à cet ordre payé, avant de prendre les pièces ? Pas en grève, pas un ordre mortel ni hors de son métier, et son moral le permet. */
    static boolean accepterait(Travailleur t, Ordres.Lecture l) {
        if (t.mode == Travailleur.Mode.GREVE || Obeissance.mortel(l.texte) != null || !l.horsMetier.isEmpty() || !l.pourAutres.isEmpty()) return false;
        Obeissance.Verdict v = Obeissance.decider(t.moral, t.temperament, Obeissance.peine(l, t.metier, !t.enService()), false);
        return v == Obeissance.Verdict.OBEIT || v == Obeissance.Verdict.RALE;
    }

    /** Une question ou une politesse d'un inconnu : il répond, sans rien dire de plus que ce que tout le monde voit. */
    static String bavarder(Travailleur t, Ordres.Lecture l, String nom) {
        if (l.intentions.contains(Ordres.Intention.ETAT) || l.intentions.contains(Ordres.Intention.AVANCEMENT) || l.intentions.contains(Ordres.Intention.POURQUOI))
            return texte(t, "etranger_ouvrage", nom, 0, "Je suis au service de " + t.chefNom + ", et l'ouvrage ne manque pas.");
        if (l.intentions.contains(Ordres.Intention.MERCI)) return texte(t, "etranger_merci", nom, 0, "De rien.");
        return texte(t, "etranger_salut", nom, 0, "Le bonjour, " + nom + ".");
    }

    /** Le joueur lui verse {@code pieces} de ses pièces : elles passent dans son sac (la monnaie rendue des siennes). Faux si rien n'a bougé. */
    static boolean encaisser(Travailleur t, ServerPlayer p, long pieces) {
        boolean libre = false; // la monnaie rendue doit pouvoir entrer dans son sac : sinon, rien ne se fait
        for (int i = 0; i < t.inventaire.getContainerSize() && !libre; i++) libre = t.inventaire.getItem(i).isEmpty();
        if (!libre) return false;
        int[] v = Reglages.valeurs(), bourse = Reglages.quantites(p.getInventory()), sac = Reglages.quantites(t.inventaire);
        Boutique.Reglement rg = Boutique.regler(v, bourse, sac, pieces);
        if (rg == null) return false;
        Boutiquier.changer(p.getInventory(), bourse, rg.client(), s -> Boutiquier.donner(p, s), p::spawnAtLocation);
        Boutiquier.changer(t.inventaire, sac, rg.caisse(), t.inventaire::addItem, t::spawnAtLocation);
        Sons.paie(t);
        return true;
    }

    /**
     * « Viens travailler pour moi, je te paie 20 pièces » : il compare ({@link Debauche#decider}). S'il accepte et reçoit
     * la somme d'avance, il quitte son chef (une lettre le lui dit ; sa réputation n'en souffre pas) et entre au service
     * de l'autre, à ce salaire. S'il refuse, il le dit, s'en souviendra un jour, et son chef l'apprend.
     */
    static String debaucher(Travailleur t, ServerPlayer p, String nom, Debauche.Offre o) {
        var serveur = p.getServer();
        Courrier c = Courrier.de(serveur);
        long maintenant = t.level().getGameTime();
        CompoundTag refus = t.memoire.getCompound("debauche_refus");
        String cle = p.getUUID().toString();
        boolean rancune = refus.contains(cle) && maintenant - refus.getLong(cle) < RANCUNE && maintenant >= refus.getLong(cle);
        // Un enfant ou un apprenti ne quitte pas les siens : ni lettre ni rancune (lot 13).
        if (Famille.mineur(t))
            return texte(t, "debauche_mineur", nom, 0, "Je suis trop jeune pour partir. Vois ça avec " + t.chefNom + ".");
        // Pendant sa formation payée par son chef, il ne part pas (lot 13).
        if (t.memoire.getLong("formation_fin") > 0)
            return texte(t, "debauche_formation", nom, 0, "Je suis en apprentissage, payé par " + t.chefNom + ". Pas maintenant.");
        boolean place = !Reglages.actuel.metiers_inactifs.contains(t.metier) && c.poste(p.getUUID()) != null && ColoniePnj.pnjDe(serveur, p.getUUID()).size() < Reglages.actuel.pnj_max_par_joueur;
        Liens liens = Relations.de(t);
        Debauche.Cas cas = new Debauche.Cas(o.pieces(), t.salaireVerse(), t.moral, t.temperament, c.reputation(t.chef), c.reputation(p.getUUID()),
                liens.conjoint() != null, liens.amis().size(), c.enGuerre(t.chef, p.getUUID()), rancune, place);
        Debauche.Raison raison = Debauche.decider(cas);
        int prix = (int) Math.min(Integer.MAX_VALUE, Math.max(0, o.pieces()));
        if (raison == Debauche.Raison.ACCEPTE) {
            if (!encaisser(t, p, o.pieces())) return texte(t, "debauche_pieces", nom, prix, "Montre-moi d'abord les pièces, et on en reparlera.");
            rendre(t);
            Espions.evenement(serveur, t.chef, nom, Rumeurs.Genre.DEBAUCHE, t.prenom); // ses compagnons présents savent qui débauche (Rumeurs)
            Courrier.ecrireTexte(t, "Mon départ", texte(t, "debauche_lettre_depart", nom, prix,
                    nom + " m'offre " + Boutique.pieces(o.pieces()) + " : je pars à son service. Sans rancune, " + t.chefNom + "."), true);
            Cerveau.journal(t.prenom + " quitte " + t.chefNom + " pour " + nom + " (" + o.pieces() + " pièces)");
            t.licencier();
            t.entrerAuService(p, o.pieces(), o.pieces());
            return texte(t, "debauche_accepte", nom, prix, "Marché conclu. Je suis à toi, " + nom + ".");
        }
        if (raison != Debauche.Raison.SOMME && raison != Debauche.Raison.RANCUNE && raison != Debauche.Raison.PLACE) {
            refus.putLong(cle, maintenant);
            t.memoire.put("debauche_refus", refus);
            Courrier.ecrireTexte(t, "Une offre", texte(t, "debauche_lettre_tentative", nom, prix,
                        nom + " a voulu me débaucher. Je lui ai dit non."), false);
        }
        return texte(t, "debauche_" + raison.name().toLowerCase(java.util.Locale.ROOT), nom, prix, switch (raison) {
            case SOMME -> "Et tu me paierais combien ?";
            case GUERRE -> "Toi ? Tu fais la guerre à " + t.chefNom + ". Jamais.";
            case RANCUNE -> "Je t'ai déjà répondu.";
            case PLACE -> "Tu n'as ni poste ni place pour moi.";
            case LIENS -> "Les miens sont ici. Je reste.";
            case REPUTATION -> "On dit trop de mal de ta colonie.";
            case FIDELE -> "Je suis bien chez " + t.chefNom + ".";
            default -> "C'est à peine plus que ce que j'ai. Non.";
        });
    }

    /** Avant de partir : tout ce qu'il porte, sauf ses pièces, va au dépôt de son ancien chef (au sol, à ses pieds, sans dépôt). */
    static void rendre(Travailleur t) {
        Bureau.Entite b = Bureau.entite(t);
        for (int i = 0; i < t.inventaire.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = t.inventaire.getItem(i);
            if (s.isEmpty() || Reglages.piece(s) >= 0) continue;
            t.inventaire.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
            net.minecraft.world.item.ItemStack reste = b == null ? s : b.ranger(s);
            if (!reste.isEmpty()) t.spawnAtLocation(reste);
        }
        if (b != null) b.setChanged();
        t.inventaire.setChanged();
    }

    /** La réplique {@code cle} (etrangers.txt), avec {nom} celui qui parle et {prix} ; {@code secours} si elle manque. */
    static String texte(Travailleur t, String cle, String nom, int prix, String secours) {
        return Boutiquier.texte(t, cle, nom, null, prix, secours);
    }
}
