package fr.lacolonie.pnj;

/**
 * Les gestes des ouvriers, dessinés chez le joueur : pour chaque geste du répertoire, la position de la tête, du
 * corps, des bras et des jambes du modèle de joueur, et le mouvement du corps entier (assis, allongé, qui tourne…).
 * Aucune dépendance au jeu : le client recopie ces angles dans son modèle ({@code Client.ModeleOuvrier}).
 * <p>
 * Repère du modèle (en pixels, 16 par bloc) : y vers le bas, z vers l'arrière (le visage est en z = -4) ; l'épaule
 * droite est en x = -5, la gauche en x = 5 ; un bras pend de son épaule sur 12 pixels. Pour placer une main, on
 * vise un point ({@link #viser}) plutôt que de deviner des angles.
 */
final class Poses {
    private Poses() {}

    static final class Partie {
        float x, y, z, xRot, yRot, zRot;

        void rot(float rx, float ry, float rz) { xRot = rx; yRot = ry; zRot = rz; }

        void copier(Partie p) { x = p.x; y = p.y; z = p.z; xRot = p.xRot; yRot = p.yRot; zRot = p.zRot; }
    }

    /** Le modèle à poser. {@code dy} (blocs, vers le haut), {@code rx}, {@code ry} (degrés) : le mouvement du corps entier. */
    static final class Corps {
        final Partie tete = new Partie(), corps = new Partie(), brasD = new Partie(), brasG = new Partie(), jambeD = new Partie(), jambeG = new Partie();
        float dy, rx, ry;
        /** Position de repos des épaules (le modèle fin les a un demi-pixel plus bas). */
        float epaule = 2;
    }

    static final float PI = (float) Math.PI;

    static float sin(float a) { return (float) Math.sin(a); }

    static float cos(float a) { return (float) Math.cos(a); }

    /** Oriente le bras (ou la jambe) pour que son bout aille vers ce point, sans tourner sur lui-même. */
    static void viser(Partie b, float tx, float ty, float tz) {
        float dx = tx - b.x, dy = ty - b.y, dz = tz - b.z;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-4f) return;
        dx /= l; dy /= l; dz /= l;
        // Le bras pend selon +y ; le modèle tourne d'abord autour de x (xRot), puis de z (zRot) :
        // bout = (-cos(xRot)·sin(zRot), cos(xRot)·cos(zRot), sin(xRot)). Bras levé (dy < 0) : on passe par l'avant.
        float c = (float) Math.sqrt(Math.max(0, 1 - dz * dz)) * (dy >= 0 ? 1 : -1);
        if (Math.abs(c) < 1e-3f) c = dy >= 0 ? 1e-3f : -1e-3f;
        b.xRot = (float) Math.atan2(dz, c);
        b.yRot = 0;
        b.zRot = (float) Math.atan2(-dx / c, dy / c);
    }

    /** Remet bras et jambes droits (garde-à-vous), sans toucher à la tête. */
    static void droit(Corps c) {
        c.brasD.rot(0, 0, 0.05f);
        c.brasG.rot(0, 0, -0.05f);
        c.jambeD.rot(0, 0, 0);
        c.jambeG.rot(0, 0, 0);
    }

    /**
     * Penche le buste en avant de {@code a} radians, plié aux hanches (les jambes ne bougent pas) : le cou avance et
     * descend, les épaules suivent, les bras pendent.
     */
    static void pencher(Corps c, float a) {
        float y = 12 - 12 * cos(a), z = -12 * sin(a);
        c.corps.xRot = a;
        c.corps.y = y;
        c.corps.z = z;
        c.tete.y = y;
        c.tete.z = z;
        c.tete.xRot += a * 0.6f;
        for (Partie b : new Partie[]{c.brasD, c.brasG}) {
            b.y = y + c.epaule * cos(a);
            b.z = z + c.epaule * sin(a);
        }
    }

    /** Assis par terre, les jambes devant (comme un cavalier, posé au sol). */
    static void assis(Corps c) {
        c.jambeD.rot(-1.41f, PI / 10, 0.07f);
        c.jambeG.rot(-1.41f, -PI / 10, -0.07f);
        c.dy = -0.62f;
    }

    /** À genoux, assis sur les talons. */
    static void genoux(Corps c) {
        c.jambeD.rot(1.5f, 0, 0.05f);
        c.jambeG.rot(1.5f, 0, -0.05f);
        c.dy = -0.62f;
    }

    static boolean connu(String g) { return Repertoire.GESTES.contains(g); }

    /**
     * Pose le geste {@code g} à l'instant {@code t} (en ticks, avec la fraction). Le corps arrive avec la pose que le
     * jeu lui a donnée (marche, regard) ; on n'en garde que ce que le geste laisse libre.
     */
    static void appliquer(Corps c, String g, float t) {
        float e = c.epaule;
        float s = sin(t * 0.6f), s2 = sin(t * 0.3f), s4 = sin(t * 1.2f);
        switch (g) {
            // --- Postures --------------------------------------------------------------------------------
            case "garde_a_vous" -> { droit(c); c.tete.xRot = 0; }
            case "salut_militaire" -> { droit(c); c.tete.xRot = -0.05f; viser(c.brasD, -2.5f, -6.5f, -4.8f); }
            case "repos" -> { viser(c.brasD, -1.5f, 10.5f, 3.5f); viser(c.brasG, 1.5f, 10.5f, 3.5f); c.jambeD.rot(0, 0, 0.12f); c.jambeG.rot(0, 0, -0.12f); }
            case "bras_croises" -> { viser(c.brasD, 3.5f, 6f, -4.5f); viser(c.brasG, -3.5f, 5.5f, -5f); c.brasD.yRot = -0.2f; c.brasG.yRot = 0.2f; }
            case "mains_hanches" -> { c.brasD.rot(-0.25f, 0.4f, 0.55f); c.brasG.rot(-0.25f, -0.4f, -0.55f); }
            case "assis" -> { assis(c); c.brasD.rot(-0.6f, 0, 0.1f); c.brasG.rot(-0.6f, 0, -0.1f); }
            case "a_genoux" -> { genoux(c); c.brasD.rot(-0.2f, 0, 0.05f); c.brasG.rot(-0.2f, 0, -0.05f); }
            case "accroupi" -> {
                pencher(c, 0.5f);
                c.jambeD.rot(-0.9f, 0, 0.1f); c.jambeG.rot(-0.9f, 0, -0.1f);
                c.brasD.rot(-0.9f, 0, 0.05f); c.brasG.rot(-0.9f, 0, -0.05f);
                c.dy = -0.3f;
            }
            case "allonge", "dormir" -> {
                droit(c);
                c.brasD.zRot = 0.15f; c.brasG.zRot = -0.15f;
                c.tete.xRot = 0; c.tete.yRot = g.equals("dormir") ? 0.3f : c.tete.yRot * 0.3f;
                c.rx = 90; c.dy = 0.2f;
            }
            case "en_garde" -> {
                c.brasD.rot(-0.9f + 0.05f * s2, -0.35f, 0.1f);
                c.brasG.rot(-0.5f, 0.4f, -0.1f);
                c.jambeD.rot(0.25f, 0, 0.1f); c.jambeG.rot(-0.3f, 0, -0.1f);
            }
            case "bouclier" -> { c.brasG.rot(-0.94f, PI / 6, 0); c.brasD.rot(-0.5f, -0.3f, 0.1f); c.jambeD.rot(0.25f, 0, 0.1f); c.jambeG.rot(-0.3f, 0, -0.1f); }
            case "priere" -> { genoux(c); viser(c.brasD, -0.5f, -1f, -6f); viser(c.brasG, 0.5f, -1f, -6f); c.tete.xRot = 0.4f; }
            case "mains_en_l_air" -> { c.brasD.rot(PI, 0, -0.25f); c.brasG.rot(PI, 0, 0.25f); }
            case "guetter" -> { viser(c.brasD, -1.5f, -5.5f, -5f); c.tete.xRot = -0.1f; c.tete.yRot = 0.6f * s2; }
            case "reflechir" -> { viser(c.brasD, -0.8f, -0.5f, -5f); viser(c.brasG, 2.5f, 7f, -4f); c.tete.xRot = -0.25f; c.tete.zRot = 0.15f; }
            case "tete_basse" -> { droit(c); c.tete.xRot = 0.7f; c.brasD.zRot = 0.02f; c.brasG.zRot = -0.02f; }
            case "fier" -> { viser(c.brasD, -1.5f, 10.5f, 3.5f); viser(c.brasG, 1.5f, 10.5f, 3.5f); c.tete.xRot = -0.35f; c.corps.xRot = -0.08f; }
            case "mediter" -> {
                c.jambeD.rot(-1.41f, 0.9f, 0.4f); c.jambeG.rot(-1.41f, -0.9f, -0.4f);
                c.dy = -0.62f;
                c.brasD.rot(-0.8f, -0.1f, 0.35f); c.brasG.rot(-0.8f, 0.1f, -0.35f);
                c.tete.xRot = 0.1f; c.tete.yRot = 0;
            }
            case "epuise" -> { pencher(c, 0.8f); viser(c.brasD, -2.5f, 17f, -3.5f); viser(c.brasG, 2.5f, 17f, -3.5f); c.tete.xRot = -0.3f; c.corps.y += 0.3f * s4; }
            case "cache_yeux" -> { viser(c.brasD, -1.5f, -4.5f, -5.5f); viser(c.brasG, 1.5f, -4.5f, -5.5f); c.tete.xRot = 0.2f; }
            case "pointer" -> { c.brasD.rot(-PI / 2 + Math.clamp(c.tete.xRot, -1f, 1f), c.tete.yRot - 0.1f, 0); }
            case "montrer" -> { c.brasD.rot(-1.1f, -0.25f, 0); c.brasG.rot(-1.1f, 0.25f, 0); }
            case "zombie" -> { c.brasD.rot(-PI / 2 + 0.05f * s2, 0, 0); c.brasG.rot(-PI / 2 - 0.05f * s2, 0, 0); c.tete.zRot = 0.15f; }
            case "salut_epee" -> { droit(c); viser(c.brasD, -0.5f, -2f, -7f); c.tete.xRot = 0; }
            case "statue" -> { c.brasD.rot(PI - 0.15f, 0, 0.3f); c.brasG.rot(-0.2f, 0, -0.35f); c.tete.xRot = -0.4f; c.jambeG.rot(-0.25f, 0, -0.05f); }
            case "chut" -> { viser(c.brasD, -0.5f, -2.5f, -5f); }
            case "calin" -> { c.brasD.rot(-1.3f, 0.5f, 0); c.brasG.rot(-1.3f, -0.5f, 0); }
            case "crier" -> { viser(c.brasD, -2f, -2.5f, -5.5f); viser(c.brasG, 2f, -2.5f, -5.5f); c.brasD.yRot = -0.5f; c.brasG.yRot = 0.5f; c.tete.xRot = -0.3f; }
            case "tirer_arc" -> {
                c.brasD.rot(-PI / 2 + c.tete.xRot, -0.1f + c.tete.yRot, 0);
                c.brasG.rot(-PI / 2 + c.tete.xRot, 0.5f + c.tete.yRot, 0);
            }
            case "timide" -> { viser(c.brasD, -1.5f, 10.5f, 3.5f); viser(c.brasG, 1.5f, 10.5f, 3.5f); c.tete.xRot = 0.5f; c.corps.yRot = 0.15f * s2; c.jambeG.rot(0, 0, -0.1f); }
            // --- Gestes animés ------------------------------------------------------------------------------
            case "saluer_main" -> { c.brasD.rot(PI - 0.3f, 0, -0.1f + 0.45f * s4); }
            case "applaudir" -> {
                float o = 0.35f + 0.3f * Math.abs(sin(t * 0.9f));
                c.brasD.rot(-1.25f, -o, 0);
                c.brasG.rot(-1.25f, o, 0);
            }
            case "danser" -> {
                c.brasD.rot(PI * 0.75f * Math.max(0, s), 0, 0.3f + 0.3f * s);
                c.brasG.rot(PI * 0.75f * Math.max(0, -s), 0, -0.3f + 0.3f * s);
                c.jambeD.xRot = 0.5f * s; c.jambeG.xRot = -0.5f * s;
                c.corps.zRot = 0.08f * s; c.tete.zRot = 0.12f * s;
                c.dy = 0.05f * Math.abs(s4);
            }
            case "danse_bras" -> {
                c.brasD.rot(PI - 0.2f, 0, -0.35f + 0.35f * s4);
                c.brasG.rot(PI - 0.2f, 0, 0.35f + 0.35f * s4);
                c.corps.zRot = 0.1f * s; c.tete.zRot = -0.1f * s;
                c.jambeD.zRot = 0.1f + 0.1f * s; c.jambeG.zRot = -0.1f + 0.1f * s;
            }
            case "gigue" -> {
                c.brasD.rot(-0.25f, 0.4f, 0.55f); c.brasG.rot(-0.25f, -0.4f, -0.55f);
                c.jambeD.xRot = -0.7f * Math.max(0, s4); c.jambeG.xRot = -0.7f * Math.max(0, -s4);
                c.dy = 0.08f * Math.abs(s4);
            }
            case "rire" -> {
                c.tete.xRot = -0.45f + 0.1f * sin(t * 2.2f);
                viser(c.brasD, -2f, 7f, -4f); viser(c.brasG, 2f, 7f, -4f);
                c.corps.y = 0.4f * Math.abs(sin(t * 2.2f));
                c.dy = 0.02f * sin(t * 2.2f);
            }
            case "pleurer" -> { viser(c.brasD, -1.5f, -4f, -5.5f); viser(c.brasG, 1.5f, -4f, -5.5f); c.tete.xRot = 0.5f + 0.06f * sin(t * 1.8f); c.corps.y = 0.25f * Math.abs(sin(t * 1.8f)); }
            case "trembler" -> {
                float j = 0.05f * sin(t * 4.1f);
                viser(c.brasD, 1f, 5f, -4f); viser(c.brasG, -1f, 5f, -4f);
                c.brasD.zRot += j; c.brasG.zRot -= j;
                c.tete.xRot = 0.3f; c.tete.yRot = j * 3; c.corps.x = j * 4;
                c.jambeD.zRot = 0.05f + j; c.jambeG.zRot = -0.05f + j;
            }
            case "hocher_tete" -> c.tete.xRot = 0.35f * Math.max(0, sin(t * 0.9f));
            case "secouer_tete" -> c.tete.yRot = 0.55f * sin(t * 0.9f);
            case "hausser_epaules" -> {
                float h = Math.max(0, sin(t * 0.5f));
                c.brasD.rot(-0.4f, -0.4f, 0.6f); c.brasG.rot(-0.4f, 0.4f, -0.6f);
                c.brasD.y = e - 1.5f * h; c.brasG.y = e - 1.5f * h;
                c.tete.zRot = 0.2f * h; c.tete.xRot = -0.1f;
            }
            case "gratter_tete" -> { viser(c.brasD, -2.5f, -8.5f, -1f); c.brasD.zRot += 0.08f * sin(t * 2.5f); c.tete.zRot = -0.15f; c.tete.xRot = 0.1f; }
            case "s_etirer" -> {
                float h = (sin(t * 0.25f) + 1) / 2;
                c.brasD.rot(PI * h, 0, -0.15f); c.brasG.rot(PI * h, 0, 0.15f);
                c.tete.xRot = -0.4f * h; c.corps.xRot = -0.1f * h;
            }
            case "bailler" -> { viser(c.brasD, -0.5f, -2.5f, -5f); c.brasG.rot(PI - 0.3f, 0, 0.2f + 0.1f * s2); c.tete.xRot = -0.45f; }
            case "pompes" -> {
                droit(c);
                c.brasD.rot(-PI / 2, 0, 0.2f); c.brasG.rot(-PI / 2, 0, -0.2f);
                c.tete.xRot = -0.6f; c.tete.yRot = 0;
                float h = Math.abs(sin(t * 0.35f));
                c.rx = -80 + 10 * h; c.dy = 0.25f + 0.25f * h;
            }
            case "echauffement" -> {
                c.brasD.rot(t * 0.35f, 0, 0.4f); c.brasG.rot(t * 0.35f + PI, 0, -0.4f);
                c.jambeD.zRot = 0.15f; c.jambeG.zRot = -0.15f;
            }
            case "marcher_au_pas" -> {
                c.jambeD.xRot = -1.1f * Math.max(0, s4); c.jambeG.xRot = -1.1f * Math.max(0, -s4);
                c.brasD.rot(0.6f * s4, 0, 0.05f); c.brasG.rot(-0.6f * s4, 0, -0.05f);
                c.tete.xRot = 0; c.dy = 0.04f * Math.abs(s4);
            }
            case "tambour" -> {
                c.brasD.rot(-1.0f - 0.4f * Math.max(0, sin(t * 1.6f)), -0.3f, 0);
                c.brasG.rot(-1.0f - 0.4f * Math.max(0, -sin(t * 1.6f)), 0.3f, 0);
                c.tete.xRot = 0.35f;
            }
            case "jouer_flute" -> { viser(c.brasD, -1f, -2f, -7f); viser(c.brasG, 1.5f, -1.5f, -6f); c.tete.xRot = 0.1f + 0.08f * s2; }
            case "jouer_luth" -> { c.brasG.rot(-0.9f, 0.7f, -0.3f); c.brasD.rot(-0.6f + 0.2f * sin(t * 1.5f), -0.5f, 0); c.tete.xRot = 0.35f; c.tete.zRot = 0.1f * s2; }
            case "boire" -> { viser(c.brasD, -0.5f, -2.5f, -5.5f); c.tete.xRot = -0.55f + 0.1f * s2; }
            case "manger" -> { viser(c.brasD, -0.5f, -2f, -5.5f); c.brasD.xRot += 0.25f * Math.max(0, s4); c.tete.xRot = 0.1f * Math.max(0, s4); }
            case "muscles" -> {
                float h = Math.max(0, sin(t * 0.4f));
                c.brasD.rot(-0.2f - 0.4f * h, 0, 1.4f + 0.3f * h); c.brasG.rot(-0.2f - 0.4f * h, 0, -1.4f - 0.3f * h);
                c.tete.xRot = -0.2f;
            }
            case "faire_signe" -> { c.brasD.rot(-1.2f - 0.6f * Math.max(0, s4), -0.3f, 0); }
            case "frapper" -> {
                float f = (t * 0.12f) % 1;
                c.brasD.rot(f < 0.3f ? -2.6f * f / 0.3f : -2.6f + 3.1f * Math.min(1, (f - 0.3f) / 0.2f), -0.3f, 0.1f);
                c.brasG.rot(-0.4f, 0.3f, -0.1f);
                c.jambeD.rot(0.3f, 0, 0.1f); c.jambeG.rot(-0.35f, 0, -0.1f);
            }
            case "moulinet" -> { c.brasD.rot(-PI + 0.3f, 0, 0); c.brasD.yRot = t * 0.6f; c.tete.xRot = -0.3f; }
            case "victoire" -> { c.brasD.rot(PI - 0.15f - 0.35f * Math.max(0, s4), 0, -0.1f); c.brasG.rot(-0.1f, 0, -0.1f); c.tete.xRot = -0.25f; }
            case "compter" -> { c.brasD.rot(-1.0f, -0.3f, 0); c.brasG.rot(-1.0f + 0.15f * Math.max(0, sin(t * 0.8f)), 0.3f, 0); c.tete.xRot = 0.45f; }
            case "s_incliner" -> { pencher(c, 0.7f * Math.min(1, Math.max(0, sin(t * 0.25f)) * 1.6f)); c.brasD.rot(0, 0, 0.05f); c.brasG.rot(0, 0, -0.05f); }
            case "reverence" -> {
                float h = Math.min(1, Math.max(0, sin(t * 0.2f)) * 1.5f);
                pencher(c, 0.8f * h);
                c.brasD.rot(-0.9f * h, 0.6f * h, 0); c.brasG.rot(0.4f * h, 0, -0.5f * h);
                c.jambeD.rot(-0.35f * h, 0, 0); c.jambeG.rot(0.25f * h, 0, 0);
            }
            case "chanter" -> {
                c.brasD.rot(-0.8f + 0.15f * s2, -0.4f, 0.4f); c.brasG.rot(-0.8f - 0.15f * s2, 0.4f, -0.4f);
                c.tete.xRot = -0.35f; c.tete.zRot = 0.12f * s2; c.corps.zRot = 0.04f * s2;
            }
            case "parler" -> {
                c.brasD.rot(-0.7f + 0.25f * s, -0.3f, 0.15f + 0.1f * s2); c.brasG.rot(-0.4f - 0.2f * s2, 0.3f, -0.15f);
                c.tete.yRot += 0.1f * s2;
            }
            case "serrer_main" -> { c.brasD.rot(-1.1f + 0.15f * sin(t * 1.4f), -0.15f, 0); }
            case "caresser" -> { pencher(c, 0.5f); c.brasD.rot(-0.9f + 0.2f * s4, -0.1f, 0); c.tete.xRot = 0.5f; }
            case "oiseau" -> { float a = 1.2f + 0.6f * s4; c.brasD.rot(0, 0, a); c.brasG.rot(0, 0, -a); c.tete.xRot = -0.2f; }
            case "singe" -> {
                pencher(c, 0.35f);
                viser(c.brasD, -7f, 4f, -2f); viser(c.brasG, 3.5f, -7.5f + 1.5f * s4, -1f);
                c.jambeD.rot(-0.4f, 0, 0.3f); c.jambeG.rot(-0.4f, 0, -0.3f);
                c.dy = 0.06f * Math.abs(s4);
            }
            case "robot" -> {
                int pas = (int) (t / 8) % 4;
                c.brasD.rot(pas == 0 || pas == 3 ? -PI / 2 : 0, 0, pas == 1 ? 1.5f : 0.05f);
                c.brasG.rot(pas == 2 || pas == 3 ? -PI / 2 : 0, 0, pas == 0 ? -1.5f : -0.05f);
                c.tete.yRot = (pas - 1.5f) * 0.4f; c.tete.xRot = 0;
            }
            case "nager" -> { c.brasD.rot(t * 0.4f, 0, 0.3f); c.brasG.rot(t * 0.4f + PI, 0, -0.3f); c.tete.xRot = -0.2f; c.corps.xRot = 0.15f; }
            case "boxe" -> {
                float d = Math.max(0, sin(t * 0.9f)), g2 = Math.max(0, -sin(t * 0.9f));
                c.brasD.rot(-1.3f - 0.25f * d, -0.3f + 0.25f * d, 0); c.brasG.rot(-1.3f - 0.25f * g2, 0.3f - 0.25f * g2, 0);
                c.brasD.z = -3 * d; c.brasG.z = -3 * g2;
                c.jambeD.rot(0.3f, 0, 0.1f); c.jambeG.rot(-0.3f, 0, -0.1f);
                c.dy = 0.03f * Math.abs(s4);
            }
            case "lancer" -> {
                float f = (t * 0.07f) % 1;
                c.brasD.rot(f < 0.5f ? 1.2f * f * 2 : 1.2f - 4.2f * Math.min(1, (f - 0.5f) / 0.15f), -0.2f, 0.1f);
                c.tete.xRot = -0.2f;
            }
            case "piocher" -> {
                float f = (t * 0.1f) % 1;
                c.brasD.rot(f < 0.6f ? -2.8f * f / 0.6f : -2.8f + 3.3f * Math.min(1, (f - 0.6f) / 0.12f), -0.2f, 0);
                c.brasG.rot(c.brasD.xRot * 0.8f, 0.4f, 0);
                c.tete.xRot = 0.35f;
            }
            case "scier" -> {
                float a = 0.3f * s4;
                c.brasD.rot(-1.1f, -0.25f, 0); c.brasG.rot(-1.1f, 0.25f, 0);
                c.brasD.z = -2 + 3 * a; c.brasG.z = -2 + 3 * a;
                pencher(c, 0.3f); c.tete.xRot = 0.6f;
            }
            case "balayer" -> {
                c.brasD.rot(-0.7f, -0.4f + 0.5f * s, 0); c.brasG.rot(-0.5f, -0.2f + 0.5f * s, 0);
                c.tete.xRot = 0.4f; c.corps.yRot = 0.2f * s;
            }
            case "coucou" -> {
                boolean cache = (int) (t / 20) % 2 == 0;
                if (cache) { viser(c.brasD, -1.5f, -4.5f, -5.5f); viser(c.brasG, 1.5f, -4.5f, -5.5f); }
                else { c.brasD.rot(-2.2f, 0, 0.6f); c.brasG.rot(-2.2f, 0, -0.6f); }
            }
            case "impatient" -> {
                viser(c.brasD, 3.5f, 6f, -4.5f); viser(c.brasG, -3.5f, 5.5f, -5f); c.brasD.yRot = -0.2f; c.brasG.yRot = 0.2f;
                c.jambeD.xRot = -0.25f * Math.max(0, sin(t * 1.4f));
                c.tete.yRot += 0.15f * s2;
            }
            default -> {}
        }
    }

    /** Ces gestes font tourner le corps entier sur lui-même (en degrés, selon le temps). */
    static float tourne(float t) { return (t * 18) % 360; }
}
