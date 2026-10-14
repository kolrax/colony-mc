package fr.lacolonie.pnj;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Les sons des PNJ (lot 8, cahier 10.7 : {@link Sons}, {@link Cadence}). Coins 291 à 295. */
@GameTestHolder(ColoniePnj.MODID)
@PrefixGameTestTemplate(false)
public class TestsSons {
    /** Un ouvrier qui attend (rien ne le distrait), loin des autres tests. */
    static Travailleur ouvrier(GameTestHelper h, String metier, int coin, BlockPos[] b, ItemStack outil) {
        Travailleur t = TestsOutils.ouvrier(h, metier, coin, b, outil);
        t.mode = Travailleur.Mode.ATTENDRE;
        return t;
    }

    /** Blessé, mort, tombé, à la nage : les sons d'un joueur ; le réglage « sons » coupé, ceux d'une créature. */
    @GameTest(template = "vide")
    public static void sonsDeJoueur(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 291, b, ItemStack.EMPTY);
        var degats = h.getLevel().damageSources();
        h.assertTrue(t.getHurtSound(degats.generic()) == SoundEvents.PLAYER_HURT, "blessé : " + t.getHurtSound(degats.generic()));
        h.assertTrue(t.getHurtSound(degats.drown()) == SoundEvents.PLAYER_HURT_DROWN, "noyé : " + t.getHurtSound(degats.drown()));
        h.assertTrue(t.getHurtSound(degats.onFire()) == SoundEvents.PLAYER_HURT_ON_FIRE, "brûlé : " + t.getHurtSound(degats.onFire()));
        h.assertTrue(t.getDeathSound() == SoundEvents.PLAYER_DEATH, "à terre : " + t.getDeathSound());
        h.assertTrue(t.getFallSounds().big() == SoundEvents.PLAYER_BIG_FALL, "grande chute : " + t.getFallSounds().big());
        h.assertTrue(t.getSwimSound() == SoundEvents.PLAYER_SWIM, "à la nage : " + t.getSwimSound());
        boolean avant = Reglages.actuel.sons;
        try {
            Reglages.actuel.sons = false; // dans ce même tick : aucun autre test ne joue entre-temps
            h.assertTrue(t.getHurtSound(degats.generic()) == SoundEvents.GENERIC_HURT, "sons coupés : " + t.getHurtSound(degats.generic()));
            Sons.paie(t);
            h.assertTrue(t.sons.joues(Cadence.Genre.PAIE) == 0, "sons coupés : pas de tintement");
        } finally {
            Reglages.actuel.sons = avant;
        }
        t.discard();
        h.succeed();
    }

    /** À mains nues sur la pierre : un coup sonne au rythme des gestes, jamais plus d'un tous les 4 ticks. */
    @GameTest(template = "vide", timeoutTicks = 600)
    public static void coupsDOutilEspaces(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 292, b, ItemStack.EMPTY);
        ServerLevel l = h.getLevel();
        BlockPos p = t.blockPosition().east();
        l.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        long debut = l.getGameTime();
        Cerveau.lancer(t, new Actions.Casser(p));
        h.succeedWhen(() -> {
            h.assertTrue(l.getBlockState(p).isAir(), "la pierre cassée (" + TestsJeu.etat(t, b[0]) + ")");
            int n = t.sons.joues(Cadence.Genre.COUP);
            long duree = l.getGameTime() - debut;
            h.assertTrue(n >= 10, "les coups sonnent : " + n + " en " + duree + " ticks");
            h.assertTrue(n <= duree / Cadence.Genre.COUP.delai + 1, "pas plus d'un coup tous les 4 ticks : " + n + " en " + duree + " ticks");
            t.discard();
        });
    }

    /** Le guerrier frappe un monstre : le coup d'arme d'un joueur. */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void coupsDArme(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "guerrier", 293, b, new ItemStack(Items.IRON_SWORD));
        Husk m = EntityType.HUSK.spawn(h.getLevel(), t.blockPosition().east(2), MobSpawnType.MOB_SUMMONED);
        m.setNoAi(true);
        Cerveau.lancer(t, new Actions.Attaquer(m));
        h.succeedWhen(() -> {
            h.assertTrue(t.sons.joues(Cadence.Genre.FRAPPE) >= 2, "ses coups sonnent : " + t.sons.joues(Cadence.Genre.FRAPPE) + " (" + TestsJeu.etat(t, b[0]) + ")");
            t.discard();
            m.discard();
        });
    }

    /** La paie tinte une fois ; deux paies coup sur coup ne font qu'un tintement. */
    @GameTest(template = "vide")
    public static void paieTinteUneFois(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 294, b, ItemStack.EMPTY);
        t.paye(2, false);
        t.paye(2, false);
        h.assertTrue(t.sons.joues(Cadence.Genre.PAIE) == 1, "un seul tintement : " + t.sons.joues(Cadence.Genre.PAIE));
        t.discard();
        h.succeed();
    }

    /** Endormi dans son lit, il ronfle ; à terre, il gémit (pas à chaque tick). */
    @GameTest(template = "vide", timeoutTicks = 400)
    public static void ronfleEtGemit(GameTestHelper h) {
        BlockPos[] b = new BlockPos[1];
        Travailleur t = ouvrier(h, "mineur", 295, b, ItemStack.EMPTY);
        BlockPos lit = t.blockPosition().east(2);
        TestsJeu.lit(h.getLevel(), lit);
        t.startSleeping(lit);
        Travailleur blesse = TestsJeu.employe(h, "mineur", b[0], Vec3.atBottomCenterOf(b[0].west(6)), ItemStack.EMPTY);
        blesse.mode = Travailleur.Mode.ATTENDRE;
        blesse.tomberATerre();
        h.succeedWhen(() -> {
            h.assertTrue(t.isSleeping() && t.sons.joues(Cadence.Genre.RONFLE) >= 1, "il ronfle (" + TestsJeu.etat(t, b[0]) + ")");
            h.assertTrue(blesse.sons.joues(Cadence.Genre.PLAINTE) == 1, "il gémit, une fois : " + blesse.sons.joues(Cadence.Genre.PLAINTE));
            t.discard();
            blesse.mortDefinitive = true;
            blesse.discard();
        });
    }
}
