package fr.lacolonie.pnj;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * La réserve du poste (cahier 14.4) : ce que les ouvriers déposent quand le dépôt visible est plein. Sans limite pour
 * eux, invisible pour le joueur ; elle reverse dans le dépôt dès qu'une place s'y libère. Pur Java : un objet est une
 * « sorte » (côté jeu, une pile d'un seul objet) et un nombre ; deux sortes pareilles ({@code meme}) se fusionnent.
 * Garde-fous contre une sauvegarde sans fin : {@link #SORTES_MAX} sortes distinctes et {@link #TOTAL_MAX} objets.
 */
final class Reserve<K> {
    static final int SORTES_MAX = 2000;
    static final long TOTAL_MAX = 100_000;

    static final class Tas<K> {
        final K sorte;
        long nombre;

        Tas(K sorte, long nombre) { this.sorte = sorte; this.nombre = nombre; }
    }

    private final BiPredicate<K, K> meme;
    final List<Tas<K>> tas = new ArrayList<>();
    private long total;

    Reserve(BiPredicate<K, K> meme) { this.meme = meme; }

    boolean vide() { return tas.isEmpty(); }

    long total() { return total; }

    /** Range n objets de cette sorte ; renvoie combien sont entrés (moins si un garde-fou est atteint). */
    long ajouter(K sorte, long n) {
        n = Math.min(n, TOTAL_MAX - total);
        if (n <= 0) return 0;
        Tas<K> x = trouver(sorte);
        if (x == null) {
            if (tas.size() >= SORTES_MAX) return 0;
            tas.add(x = new Tas<>(sorte, 0));
        }
        x.nombre += n;
        total += n;
        return n;
    }

    Tas<K> trouver(K sorte) {
        for (Tas<K> x : tas) if (meme.test(x.sorte, sorte)) return x;
        return null;
    }

    long compter(Predicate<K> f) {
        long n = 0;
        for (Tas<K> x : tas) if (f.test(x.sorte)) n += x.nombre;
        return n;
    }

    /** Retire jusqu'à n objets que le filtre accepte, dans l'ordre de la réserve ; renvoie ce qui est sorti. */
    List<Tas<K>> prendre(Predicate<K> f, long n) {
        List<Tas<K>> pris = new ArrayList<>();
        for (var it = tas.iterator(); it.hasNext() && n > 0; ) {
            Tas<K> x = it.next();
            if (!f.test(x.sorte)) continue;
            long k = Math.min(n, x.nombre);
            pris.add(new Tas<>(x.sorte, k));
            retirer(x, k);
            n -= k;
            if (x.nombre == 0) it.remove();
        }
        return pris;
    }

    /** Retire k objets d'un tas (sans l'enlever de la liste s'il tombe à zéro : {@link #nettoyer()}). */
    void retirer(Tas<K> x, long k) {
        k = Math.min(k, x.nombre);
        x.nombre -= k;
        total -= k;
    }

    void nettoyer() { tas.removeIf(x -> x.nombre <= 0); }

    /** Les n plus grosses sortes, de la plus grosse à la plus petite. */
    List<Tas<K>> plusGros(int n) {
        return tas.stream().sorted(Comparator.comparingLong((Tas<K> x) -> -x.nombre)).limit(n).toList();
    }

    void vider() { tas.clear(); total = 0; }
}
