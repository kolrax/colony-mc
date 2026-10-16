package fr.lacolonie.pnj;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReserveTest {
    static Reserve<String> reserve() { return new Reserve<>(String::equals); }

    @Test
    void fusionneParSorte() {
        Reserve<String> r = reserve();
        assertEquals(64, r.ajouter("pierre", 64));
        assertEquals(10, r.ajouter("fer", 10));
        assertEquals(500, r.ajouter("pierre", 500));
        assertEquals(2, r.tas.size(), "une seule ligne par sorte");
        assertEquals(564, r.compter("pierre"::equals));
        assertEquals(574, r.total());
        assertEquals(List.of("pierre", "fer"), r.plusGros(5).stream().map(x -> x.sorte).toList());
    }

    @Test
    void prendSansPerteNiDoublon() {
        Reserve<String> r = reserve();
        r.ajouter("pain", 20);
        r.ajouter("fer", 5);
        r.ajouter("pain_rassis", 7);
        var pris = r.prendre(s -> s.startsWith("pain"), 24);
        assertEquals(24, pris.stream().mapToLong(x -> x.nombre).sum());
        assertEquals(3, r.compter(s -> s.startsWith("pain")));
        assertEquals(8, r.total());
        assertTrue(r.prendre("or"::equals, 3).isEmpty());
        r.prendre(s -> true, 100);
        assertTrue(r.vide(), "les tas vidés s'en vont");
        assertEquals(0, r.total());
    }

    @Test
    void gardeFous() {
        Reserve<String> r = reserve();
        for (int i = 0; i < Reserve.SORTES_MAX; i++) assertEquals(1, r.ajouter("s" + i, 1));
        assertEquals(0, r.ajouter("nouvelle", 5), "au-delà de 2000 sortes, refusé comme avant");
        assertEquals(5, r.ajouter("s3", 5), "une sorte déjà là se fusionne toujours");
        Reserve<String> t = reserve();
        assertEquals(Reserve.TOTAL_MAX, t.ajouter("pierre", Reserve.TOTAL_MAX + 50));
        assertEquals(0, t.ajouter("pierre", 1));
    }

    @Test
    void questionSurLaReserve() {
        RepertoireTest.demande("Qu'y a-t-il dans la réserve ?", "mineur", "reserve");
        RepertoireTest.demande("qu'est-ce qu'il y a dans la réserve ?", "bucheron", "reserve");
        RepertoireTest.demande("Que contient la réserve du poste ?", "fermier", "reserve");
        RepertoireTest.demande("Qu'est-ce que tu as sur toi ?", "mineur", "sac");
    }
}
