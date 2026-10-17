package fr.lacolonie.pnj;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Les tests des lettres sont partis avec elles (1.0.0 : le journal du poste les remplace, voir {@link TestsPoste}).
 * Reste l'outil dont d'autres tests se servent.
 */
final class TestsMessages {
    private TestsMessages() {}

    /** Un sol, un bureau dont {@code chef} est le propriétaire et le poste, et le chef à côté. */
    static Bureau.Entite poste(GameTestHelper h, ServerPlayer chef) { return TestsPoste.poste(h, chef); }
}
