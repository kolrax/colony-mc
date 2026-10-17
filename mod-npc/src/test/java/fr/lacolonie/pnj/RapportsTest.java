package fr.lacolonie.pnj;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RapportsTest {
    @Test
    void refuseVideTropLongEtTropRapproche() {
        assertNotNull(Rapport.refus("  ", 0, 100_000));
        assertNotNull(Rapport.refus("a".repeat(Rapport.MAX + 1), 0, 100_000));
        assertNotNull(Rapport.refus("ça bug", 95_000, 100_000));
        assertNull(Rapport.refus("ça bug", 0, 100_000));
    }

    @Test
    void ligneJsonAvecAccentsGuillemetsEtPnj() {
        JsonObject pnj = new JsonObject();
        pnj.addProperty("prenom", "Raoul");
        String l = Rapport.ligne("2026-10-17T12:00:00Z", "Nielscutt", "minecraft:overworld", 1, 64, -2, "0.9.43",
                "Le bûcheron « coupe » mal\nici", pnj);
        assertFalse(l.contains("\n"), "une seule ligne par rapport");
        JsonObject o = JsonParser.parseString(l).getAsJsonObject();
        assertEquals("Le bûcheron « coupe » mal\nici", o.get("texte").getAsString());
        assertEquals("Raoul", o.getAsJsonObject("pnj").get("prenom").getAsString());
        assertEquals(-2, o.get("z").getAsInt());
    }
}
