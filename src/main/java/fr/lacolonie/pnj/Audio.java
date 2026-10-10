package fr.lacolonie.pnj;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

/**
 * Le son, en mono 16 bits : lire et écrire du WAV, changer de fréquence (Simple Voice Chat travaille à 48 kHz,
 * Gemini parle à 24 kHz, Whisper écoute très bien à 16 kHz), mesurer le volume, et reconnaître les phrases que
 * Whisper invente sur du silence. Aucune dépendance au jeu (testé seul).
 */
final class Audio {
    private Audio() {}

    static final int FREQ_VOIX = 48_000;

    record Son(short[] echantillons, int frequence) {
        double secondes() { return echantillons.length / (double) frequence; }
    }

    /** Un WAV (avec son en-tête RIFF) ou du PCM brut 16 bits petit-boutiste (alors à {@code frequenceSiBrut}). Stéréo : la voie de gauche. */
    static Son lire(byte[] octets, int frequenceSiBrut) {
        if (octets.length >= 12 && new String(octets, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(octets, 8, 4, StandardCharsets.US_ASCII).equals("WAVE")) {
            ByteBuffer b = ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN);
            int frequence = frequenceSiBrut, voies = 1, bits = 16, i = 12;
            while (i + 8 <= octets.length) {
                String id = new String(octets, i, 4, StandardCharsets.US_ASCII);
                int taille = b.getInt(i + 4);
                if (id.equals("fmt ")) {
                    voies = Math.max(1, b.getShort(i + 10));
                    frequence = b.getInt(i + 12);
                    bits = b.getShort(i + 22);
                } else if (id.equals("data")) {
                    int fin = taille < 0 || i + 8 + taille > octets.length ? octets.length : i + 8 + taille; // taille parfois inconnue (flux)
                    if (bits != 16) throw new IllegalArgumentException("WAV en " + bits + " bits non pris en charge");
                    int n = (fin - i - 8) / 2 / voies;
                    short[] s = new short[n];
                    for (int k = 0; k < n; k++) s[k] = b.getShort(i + 8 + k * 2 * voies);
                    return new Son(s, frequence);
                }
                i += 8 + taille + (taille & 1);
            }
            throw new IllegalArgumentException("WAV sans données");
        }
        ByteBuffer b = ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN);
        short[] s = new short[octets.length / 2];
        for (int k = 0; k < s.length; k++) s[k] = b.getShort(k * 2);
        return new Son(s, frequenceSiBrut);
    }

    /** Un WAV mono 16 bits. */
    static byte[] ecrire(short[] pcm, int frequence) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length * 2).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(frequence).putInt(frequence * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length * 2);
        for (short x : pcm) b.putShort(x);
        return b.array();
    }

    /** Change de fréquence par interpolation linéaire (largement suffisant pour une voix). */
    static short[] reechantillonner(short[] pcm, int de, int vers) {
        if (de == vers || pcm.length == 0) return pcm;
        int n = (int) ((long) pcm.length * vers / de);
        short[] r = new short[n];
        double pas = (double) de / vers;
        for (int i = 0; i < n; i++) {
            double x = i * pas;
            int a = (int) x;
            int b = Math.min(a + 1, pcm.length - 1);
            double t = x - a;
            r[i] = (short) Math.round(pcm[a] * (1 - t) + pcm[b] * t);
        }
        return r;
    }

    /** Volume moyen (0 à 32767). */
    static double rms(short[] pcm) {
        if (pcm.length == 0) return 0;
        double s = 0;
        for (short x : pcm) s += (double) x * x;
        return Math.sqrt(s / pcm.length);
    }

    /** Retire les silences du début et de la fin (une voix synthétique en a souvent). */
    static short[] rogner(short[] pcm, int frequence) {
        int seuil = 300, marge = frequence / 20; // 50 ms gardées autour
        int debut = 0, fin = pcm.length - 1;
        while (debut < pcm.length && Math.abs(pcm[debut]) < seuil) debut++;
        while (fin > debut && Math.abs(pcm[fin]) < seuil) fin--;
        if (debut >= fin) return new short[0];
        debut = Math.max(0, debut - marge);
        fin = Math.min(pcm.length - 1, fin + marge);
        short[] r = new short[fin - debut + 1];
        System.arraycopy(pcm, debut, r, 0, r.length);
        return r;
    }

    /** Recolle des morceaux de 20 ms. */
    static short[] coller(List<short[]> morceaux) {
        int n = 0;
        for (short[] m : morceaux) n += m.length;
        short[] r = new short[n];
        int i = 0;
        for (short[] m : morceaux) { System.arraycopy(m, 0, r, i, m.length); i += m.length; }
        return r;
    }

    /** Whisper « entend » parfois des phrases toutes faites dans le silence ou le bruit (souvenirs de sous-titres) : on les ignore. */
    static boolean inventee(String texte) {
        String n = Normalizer.normalize(texte.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
        if (n.length() < 2 || n.equals("musique")) return true;
        for (String f : List.of("sous titres", "amara org", "merci d avoir regarde", "abonnez vous", "sous titrage", "st 501",
                "merci a tous et a bientot", "n oubliez pas de vous abonner", "radio canada", "transcription par"))
            if (n.contains(f)) return true;
        return false; // « merci » seul peut être vrai : le volume de la voix a déjà été vérifié avant de transcrire
    }

    /**
     * Whisper recopie parfois les mots qu'on lui donne en indice (« Raoul, les gars, chef, mineur… ») quand il n'entend
     * rien de clair : plusieurs morceaux de la phrase, séparés par des virgules, sont des mots de l'indice.
     */
    static boolean recopieIndice(String texte, String indice) {
        java.util.Set<String> mots = new java.util.HashSet<>();
        for (String m : indice.split("[,.]")) mots.add(normaliser(m));
        String[] morceaux = texte.split("[,.!?;:]");
        int pris = 0, total = 0;
        for (String m : morceaux) {
            String n = normaliser(m);
            if (n.isEmpty()) continue;
            total++;
            if (mots.contains(n)) pris++;
        }
        return pris >= 2 && pris * 2 >= total;
    }

    static String normaliser(String s) {
        return Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]+", " ").replaceAll("\\s+", " ").trim();
    }

    static byte[] concat(byte[]... parties) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parties) o.writeBytes(p);
        return o.toByteArray();
    }
}
