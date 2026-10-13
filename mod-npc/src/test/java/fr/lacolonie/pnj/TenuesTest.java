package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TenuesTest {
    static final UUID A = UUID.fromString("3f2a8c1e-7b4d-4e9a-a1c2-5d6e7f809a1b");

    @Test
    void metierEtVariante() {
        String c = Tenues.chemin("mineur", A);
        assertTrue(c.matches("textures/entity/ouvrier/mineur_[123]\\.png"), c);
    }

    @Test
    void stable() {
        for (int i = 0; i < 20; i++) {
            UUID u = UUID.randomUUID();
            UUID copie = UUID.fromString(u.toString());
            assertEquals(Tenues.chemin("bucheron", u), Tenues.chemin("bucheron", copie));
            // la variante ne dépend pas du métier : il garde la même tête en changeant de tenue
            assertEquals(Tenues.chemin("chomeur", u).replace("chomeur", "guerrier"), Tenues.chemin("guerrier", u));
        }
    }

    @Test
    void chomeurTantQuIlNEstPasEmbauche() {
        assertEquals("chomeur", Tenues.tenue("mineur", false));
        assertEquals("mineur", Tenues.tenue("mineur", true));
        assertTrue(Tenues.chemin(Tenues.tenue("guerrier", false), A).contains("/chomeur_"));
    }

    @Test
    void metierInconnuEnChomeur() {
        assertTrue(Tenues.chemin("alchimiste", A).contains("/chomeur_"));
        assertTrue(Tenues.chemin("", A).contains("/chomeur_"));
        assertTrue(Tenues.chemin(null, A).contains("/chomeur_"));
    }

    @Test
    void lesTroisVariantesServent() {
        Set<String> vues = new HashSet<>();
        for (int i = 0; i < 30; i++) vues.add(Tenues.chemin("fermier", new UUID(0x1234L, i))); // des UUID très proches
        assertEquals(Set.of("textures/entity/ouvrier/fermier_1.png", "textures/entity/ouvrier/fermier_2.png",
                "textures/entity/ouvrier/fermier_3.png"), vues);
        vues.clear();
        Random r = new Random(7);
        for (int i = 0; i < 30; i++) vues.add(Tenues.chemin("fermier", new UUID(r.nextLong(), r.nextLong())));
        assertEquals(3, vues.size(), vues.toString());
    }

    @Test
    void chaqueTextureExiste() {
        Path dossier = Path.of("src/main/resources/assets/colonie_pnj");
        for (String m : Tenues.METIERS)
            for (int n = 1; n <= Tenues.VARIANTES; n++) {
                Path p = dossier.resolve("textures/entity/ouvrier/" + m + "_" + n + ".png");
                assertTrue(Files.exists(p), p.toString());
            }
    }
}
