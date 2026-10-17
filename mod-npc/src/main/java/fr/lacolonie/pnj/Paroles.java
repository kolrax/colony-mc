package fr.lacolonie.pnj;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * Ce qu'un ouvrier dit au chef : une phrase courte, écrite à l'avance, en bulle au-dessus de sa tête. Les derniers
 * échanges restent dans sa mémoire (« conv »), pour l'écran et pour les tests.
 */
final class Paroles {
    private Paroles() {}

    /** Il le dit en bulle (rien s'il a reçu l'ordre de se taire). {@code ton} : gardé pour plus tard, sans effet. */
    static void dire(Travailleur t, String texte, String ton) {
        if (texte == null || texte.isBlank() || t.muet() || t.level().getServer() == null) return;
        t.dire(texte);
    }

    /** L'heure du jeu (0 à 23). */
    static int heure(Travailleur t) { return (int) ((t.level().getDayTime() + 6000) % 24000 / 1000); }

    /** Les derniers échanges avec son chef. */
    static String echange(Travailleur t) { return echange(t, 8); }

    static String echange(Travailleur t, int n) {
        ListTag l = t.memoire.getList("conv", Tag.TAG_STRING);
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.size() - n); i < l.size(); i++) b.append(l.getString(i)).append('\n');
        return b.toString().strip();
    }

    static void noter(Travailleur t, String qui, String texte) {
        ListTag l = t.memoire.getList("conv", Tag.TAG_STRING);
        l.add(StringTag.valueOf(qui + " : " + (texte.length() > 200 ? texte.substring(0, 200) + "…" : texte)));
        while (l.size() > 8) l.remove(0);
        t.memoire.put("conv", l);
    }
}
