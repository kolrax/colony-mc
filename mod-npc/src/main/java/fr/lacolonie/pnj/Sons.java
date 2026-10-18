package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Les sons des PNJ (lot 8, cahier 10.7 : pas, travail, réactions), pour qu'ils sonnent comme des joueurs. Seulement des
 * sons du jeu, joués par le serveur : rien à installer chez les joueurs. Chaque genre a son délai minimal par PNJ
 * ({@link Cadence}) ; le réglage {@code sons} les coupe tous (les PNJ gardent alors les sons ordinaires d'une créature).
 * Les pas, la nage et les chutes sont ceux du jeu : {@link Travailleur} prend ceux d'un joueur.
 */
final class Sons {
    private Sons() {}

    static boolean actifs() { return Reglages.actuel.sons; }

    /** Joue {@code s} en (x, y, z) si les sons sont actifs et le délai de ce genre passé ; vrai s'il a sonné. */
    static boolean jouer(Travailleur t, Cadence.Genre g, double x, double y, double z, Holder<SoundEvent> s, SoundSource source, float volume, float hauteur) {
        if (!actifs() || t.isSilent() || !(t.level() instanceof ServerLevel l) || !t.sons.peut(g, l.getGameTime())) return false;
        l.playSound(null, x, y, z, s, source, volume, Cadence.hauteur(hauteur, t.getRandom().nextFloat(), t.getRandom().nextFloat()));
        return true;
    }

    static boolean jouer(Travailleur t, Cadence.Genre g, SoundEvent s, float volume, float hauteur) {
        return jouer(t, g, t.getX(), t.getY() + 1, t.getZ(), BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s), SoundSource.NEUTRAL, volume, hauteur);
    }

    static boolean jouer(Travailleur t, Cadence.Genre g, BlockPos p, SoundEvent s, float volume, float hauteur) {
        return jouer(t, g, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s), SoundSource.BLOCKS, volume, hauteur);
    }

    // --- Travail ----------------------------------------------------------------------------------------------

    /** Un coup d'outil sur le bloc qu'il casse : le bruit que le jeu fait entendre au joueur qui mine (même volume, même hauteur). */
    static void coup(Travailleur t, BlockPos p, BlockState st) {
        SoundType s = st.getSoundType(t.level(), p, t);
        jouer(t, Cadence.Genre.COUP, p, s.getHitSound(), (s.getVolume() + 1) / 8, s.getPitch() * 0.5f);
    }

    /** Le maçon pose un bloc : le bruit que le jeu fait entendre au joueur qui le pose. */
    static void pose(Travailleur t, BlockPos p, BlockState st) {
        SoundType s = st.getSoundType(t.level(), p, t);
        jouer(t, Cadence.Genre.COUP, p, s.getPlaceSound(), (s.getVolume() + 1) / 2, s.getPitch() * 0.8f);
    }

    /** Un coup d'arme : fort s'il touche, faible s'il manque. */
    static void frappe(Travailleur t, boolean touche) {
        jouer(t, Cadence.Genre.FRAPPE, touche ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK, 0.8f, 1);
    }

    /** Il vide son sac au dépôt. */
    static void depot(Travailleur t, BlockPos p) { jouer(t, Cadence.Genre.DEPOT, p, SoundEvents.BUNDLE_DROP_CONTENTS, 0.7f, 0.9f); }

    /** Il pose ses lettres au poste. */
    static void lettre(Travailleur t, BlockPos p) { jouer(t, Cadence.Genre.LETTRE, p, SoundEvents.BOOK_PUT, 0.8f, 1); }

    /** Il ouvre un coffre (ou un tonneau) pour y prendre ou y laisser quelque chose. */
    static void coffre(Travailleur t, BlockPos p) {
        if (p == null || !t.level().isLoaded(p)) return; // jamais un coin non chargé : le lire ferait générer le terrain (plantage du 18/10)
        boolean tonneau = t.level().getBlockState(p).getBlock() instanceof BarrelBlock;
        jouer(t, Cadence.Genre.COFFRE, p, tonneau ? SoundEvents.BARREL_OPEN : SoundEvents.CHEST_OPEN, 0.4f, 1);
    }

    /** Il charge un four : le feu crépite. */
    static void four(Travailleur t, BlockPos p) { jouer(t, Cadence.Genre.FOUR, p, SoundEvents.FURNACE_FIRE_CRACKLE, 1, 1); }

    /** Il panse un blessé (bandes de cuir). */
    static void soin(Travailleur t) { jouer(t, Cadence.Genre.SOIN, t.getX(), t.getY() + 0.5, t.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.NEUTRAL, 0.8f, 1); }

    // --- Réactions --------------------------------------------------------------------------------------------

    /** Sa paie touchée : les pièces tintent dans sa bourse. */
    static void paie(Travailleur t) {
        jouer(t, Cadence.Genre.PAIE, t.getX(), t.getY() + 1, t.getZ(), SoundEvents.ARMOR_EQUIP_CHAIN, SoundSource.NEUTRAL, 0.5f, 1.6f);
    }

    /** Il râle ou refuse un ordre : un grognement. */
    static void grogne(Travailleur t) {
        if (!t.muet()) jouer(t, Cadence.Genre.GROGNE, SoundEvents.VILLAGER_NO, 0.6f, 0.8f);
    }

    /** Après un repas, parfois, un rot. */
    static void rot(Travailleur t) {
        if (t.getRandom().nextInt(3) == 0) jouer(t, Cadence.Genre.ROT, SoundEvents.PLAYER_BURP, 0.5f, 0.95f);
    }

    /**
     * À chaque tick : endormi depuis 10 s, il ronfle tout bas, toutes les 20 à 30 s, et seulement si personne n'a ronflé
     * tout près ({@link Cadence.Ronfleurs} : un ronfleur par pièce).
     */
    static void sommeil(Travailleur t) {
        boolean dort = t.isSleeping();
        long maintenant = t.level().getGameTime();
        if (dort != t.sons.dort) {
            t.sons.dort = dort;
            if (dort) t.sons.retarder(Cadence.Genre.RONFLE, maintenant, Cadence.ENDORMI);
            return;
        }
        if (!dort || !actifs()) return;
        if (!t.sons.pret(Cadence.Genre.RONFLE, maintenant)) return;
        if (!Cadence.Ronfleurs.prendre(t.getX(), t.getY(), t.getZ(), maintenant)) {
            t.sons.retarder(Cadence.Genre.RONFLE, maintenant, Cadence.Ronfleurs.FENETRE + t.getRandom().nextInt(100));
            return;
        }
        if (jouer(t, Cadence.Genre.RONFLE, t.getX(), t.getY() + 0.3, t.getZ(), BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.FOX_SLEEP), SoundSource.NEUTRAL, 0.15f, 0.5f))
            t.sons.retarder(Cadence.Genre.RONFLE, maintenant, Cadence.Genre.RONFLE.delai + t.getRandom().nextInt(200));
    }

    /** À terre : un gémissement sourd, toutes les 20 à 30 s (pas le cri d'un coup : on ne croit pas l'avoir touché). */
    static void plainte(Travailleur t) {
        if (jouer(t, Cadence.Genre.PLAINTE, SoundEvents.VILLAGER_AMBIENT, 0.4f, 0.65f))
            t.sons.retarder(Cadence.Genre.PLAINTE, t.level().getGameTime(), Cadence.Genre.PLAINTE.delai + t.getRandom().nextInt(200));
    }
}
