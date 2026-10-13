"""Icônes 16x16 au-dessus de la tête des ouvriers (colonie_pnj) : états (faim, colère, attente...) et métiers.

Style pixel vanilla : couleurs proches des items du jeu, fond transparent, contour sombre d'un pixel posé
automatiquement autour de chaque forme, pour rester lisible sur un ciel clair comme sur une nuit.

Rien n'est tiré d'une image existante : tout est tracé ici, pixel par pixel ou par petites formules.

Usage : python outils/icones_tex.py [dossier textures/gui] [aperçu agrandi]
"""
import math
import os
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
SORTIE = os.path.join(ICI, '..', 'src', 'main', 'resources', 'assets', 'colonie_pnj', 'textures', 'gui')
APERCU = os.path.join(ICI, 'apercu_icones.png')

# '.' = vide (reçoit le contour s'il touche une forme), '_' = vide qui ne reçoit jamais de contour.
PAL = {
    'K': '#1a120b',                                                                 # contour
    'R': '#a8572e', 'r': '#6e3418', 'M': '#d48a52',                                 # viande cuite
    'W': '#f0ead6', 'w': '#c8bfa6',                                                 # os, lait, papier clair
    'E': '#d83a2a', 'e': '#8e1c12', 'F': '#ff8a6a',                                 # rouge
    'Y': '#fcee4b', 'y': '#e9b115', 'o': '#c48a10', 'b': '#6e470b',                 # or
    't': '#a07a48', 'u': '#6b4a26', 'd': '#4a3218',                                 # bois
    'S': '#e8d690', 's': '#c8a858',                                                 # sable
    'G': '#cfe8f0', 'g': '#8fb3c4',                                                 # verre
    'I': '#e4e4e4', 'i': '#a8a8a8', 'j': '#6a6a6a',                                 # fer
    'Z': '#f4f8ff', 'z': '#9fb6e0',                                                 # Zzz
    'A': '#3f8fe8', 'a': '#2457b0', 'C': '#bfe4ff',                                 # eau
    'V': '#5ec43a', 'v': '#2f8a1e', 'N': '#b4f58a',                                 # vert potion
    'P': '#f4e8c8', 'p': '#c9b28a',                                                 # papier, bandage
    'H': '#f0384c', 'h': '#9c1426', 'L': '#ff9aa6',                                 # cœur
    'x': '#e6e6e6',                                                                 # fil de pêche
    'B': '#d8a050', 'c': '#b06a28', 'n': '#7a4416',                                 # pain
}


def vide():
    return [['.'] * 16 for _ in range(16)]


def grille(lignes):
    assert len(lignes) == 16, len(lignes)
    for l in lignes:
        assert len(l) == 16, l
    return [list(l) for l in lignes]


def pt(g, r, c, ch):
    if 0 <= r < 16 and 0 <= c < 16:
        g[r][c] = ch


def masque(g, f, ch):
    """Remplit les pixels (r, c) où f(r, c) est vrai ; f peut aussi rendre directement une lettre."""
    for r in range(16):
        for c in range(16):
            v = f(r, c)
            if v:
                g[r][c] = v if isinstance(v, str) else ch


def contour(g):
    """Ajoute un contour sombre (4 voisins) autour de tout ce qui est dessiné."""
    a_faire = []
    for r in range(16):
        for c in range(16):
            if g[r][c] != '.':
                continue
            for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                rr, cc = r + dr, c + dc
                if 0 <= rr < 16 and 0 <= cc < 16 and g[rr][cc] not in '._K':
                    a_faire.append((r, c))
                    break
    for r, c in a_faire:
        g[r][c] = 'K'
    return g


def image(g):
    im = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for r in range(16):
        for c in range(16):
            ch = g[r][c]
            if ch in '._':
                continue
            h = PAL[ch].lstrip('#')
            im.putpixel((c, r), tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,))
    return im


# --- Formes partagées --------------------------------------------------------------------------------------------

GRAND_COEUR = [
    "...XXXX..XXXX...",
    "..XXXXXXXXXXXX..",
    ".XXXXXXXXXXXXXX.",
    ".XXXXXXXXXXXXXX.",
    ".XXXXXXXXXXXXXX.",
    "..XXXXXXXXXXXX..",
    "...XXXXXXXXXX...",
    "....XXXXXXXX....",
    ".....XXXXXX.....",
    "......XXXX......",
    ".......XX.......",
]
PETIT_COEUR = [
    "....XXX..XXX....",
    "...XXXXXXXXXX...",
    "...XXXXXXXXXX...",
    "...XXXXXXXXXX...",
    "....XXXXXXXX....",
    ".....XXXXXX.....",
    "......XXXX......",
    ".......XX.......",
]


def forme(lignes, haut):
    """Rend une fonction (r, c) -> vrai si le pixel est dans la forme posée à partir de la ligne haut."""
    return lambda r, c: 0 <= r - haut < len(lignes) and 0 <= c < 16 and lignes[r - haut][c] == 'X'


def pioche(g, fer='i', clair='I', manche=True):
    """Pioche en diagonale, manche du coin bas-gauche, fer en arc dans le coin haut-droit."""
    tete = {1: (6, 8), 2: (8, 11), 3: (10, 12), 4: (11, 13), 5: (12, 13), 6: (13, 13), 7: (13, 14), 8: (14, 14), 9: (14, 14)}
    for r, (a, b) in tete.items():
        for c in range(a, b + 1):
            g[r][c] = fer
    for r, c in ((1, 6), (2, 8), (3, 10), (4, 11)):
        g[r][c] = clair
    if manche:
        for r in range(5, 15):
            g[r][15 - r] = 't' if r % 2 else 'u'


# --- États ---------------------------------------------------------------------------------------------------------

def faim():
    # Cuisse de viande cuite, os vers le bas à gauche.
    g = grille([
        "................",
        "........rrrr....",
        "......rRRRRRr...",
        ".....RMMRRRRRr..",
        ".....RMRRRRRRr..",
        "....RMRRRRRRRr..",
        "....RRRRRRRRrr..",
        ".....RRRRRRrr...",
        ".....rRRRRrr....",
        "....WwrRrrr.....",
        "...WWw.rr.......",
        "..WWw...........",
        ".WWWw...........",
        ".WWWw...........",
        "..ww............",
        "................",
    ])
    g[2][7] = 'M'
    return g


def colere():
    # Veine de colère : quatre coins rouges tournés vers le centre.
    g = grille([
        "................",
        ".....EE..EE.....",
        ".....FE..EF.....",
        ".....FE..EF.....",
        ".....EE..EE.....",
        ".FFEEEe..eEEEFF.",
        ".EEEEee..eeEEEE.",
        "................",
        "................",
        ".EEEEee..eeEEEE.",
        ".eeeeEe..eEeeee.",
        ".....Ee..eE.....",
        ".....Ee..eE.....",
        ".....Ee..eE.....",
        ".....ee..ee.....",
        "................",
    ])
    return g


def attente():
    # Sablier : socles de bois, verre clair, sable qui coule.
    return grille([
        "................",
        ".uttttttttttttu.",
        ".dddddddddddddd.",
        "..gGGGGGGGGGGg..",
        "..gSSSSSSSSSSg..",
        "...gSSSSSSSSg...",
        "....gsSSSSsg....",
        ".....gGSSGg.....",
        ".....gGSsGg.....",
        "....gGGSsGGg....",
        "...gGGGSsGGGg...",
        "..gGGGGSsGGGGg..",
        "..gGsSSSSSSsGg..",
        ".dddddddddddddd.",
        ".uttttttttttttu.",
        "................",
    ])


def sommeil():
    return grille([
        "................",
        "................",
        ".........ZZZZZ..",
        "...........ZZz..",
        "..........ZZ....",
        ".........ZZ.....",
        ".........zzzzz..",
        "................",
        ".ZZZZZZZ........",
        "......ZZz.......",
        ".....ZZ.........",
        "....ZZ..........",
        "...ZZ...........",
        "..ZZ............",
        ".zzzzzzz........",
        "................",
    ])


def greve():
    # Pancarte de bois barrée d'une grosse croix rouge, sur son piquet.
    g = vide()
    for r in range(1, 10):
        for c in range(1, 15):
            g[r][c] = 'u' if r in (1, 9) or c in (1, 14) else ('S' if r % 3 else 'B')
    for r in range(2, 9):
        k = (r - 2) * 10 / 6
        for dc in (0, 1, 2):
            a = int(round(2 + k)) + dc - 1
            b = int(round(13 - k)) - dc + 1
            for c in (a, b):
                if 2 <= c <= 13:
                    g[r][c] = 'e' if dc == 2 else 'E'
    for r in range(10, 15):
        g[r][7] = 't'
        g[r][8] = 'u'
    return g


def blesse():
    # Cœur rouge entouré d'un bandage en travers.
    g = vide()
    f = forme(GRAND_COEUR, 2)
    masque(g, f, 'H')
    masque(g, lambda r, c: f(r, c) and (not f(r + 1, c) or not f(r, c + 1)), 'h')
    for r, c in ((4, 3), (3, 4), (4, 4), (5, 2), (5, 3)):
        if f(r, c):
            g[r][c] = 'L'
    masque(g, lambda r, c: f(r, c) and 13 <= r + c <= 15, 'P')
    masque(g, lambda r, c: f(r, c) and r + c == 14 and (c - r) % 4 == 0, 'p')
    masque(g, lambda r, c: f(r, c) and r + c == 16, 'p')
    return g


def peur():
    # Grosse goutte de sueur, et une petite à côté.
    g = vide()
    goutte = lambda r, c, r0, c0, k: (r - r0) / k <= 3.6 and (
        ((c - c0) / k) ** 2 + ((r - r0) / k) ** 2 <= 3.6 ** 2 or
        (r - r0) / k < 0 and abs((c - c0) / k) <= 3.6 * (1 + (r - r0) / k / 7.0) - 0.2 and (r - r0) / k >= -7.2)
    big = lambda r, c: goutte(r, c, 9.6, 6.0, 1.0)
    small = lambda r, c: goutte(r, c, 4.8, 12.5, 0.45)
    masque(g, lambda r, c: big(r, c) or small(r, c), 'A')
    masque(g, lambda r, c: big(r, c) and (not big(r + 1, c) or not big(r, c + 1)), 'a')
    for r, c in ((7, 4), (8, 4), (9, 4), (6, 5), (10, 4)):
        g[r][c] = 'C'
    g[4][12] = 'C'
    return g


def content():
    g = vide()
    f = forme(PETIT_COEUR, 4)
    masque(g, f, 'H')
    masque(g, lambda r, c: f(r, c) and (not f(r + 1, c) or not f(r, c + 1)), 'h')
    for r, c in ((5, 4), (5, 5), (6, 4)):
        g[r][c] = 'L'
    # petites étincelles
    for r, c in ((1, 13), (2, 12), (2, 14), (3, 13), (2, 13)):
        g[r][c] = 'Y'
    return g


def piece(g, cr=7.5, cc=7.5, ray=6.9):
    f = lambda r, c: (r - cr) ** 2 + (c - cc) ** 2 <= ray ** 2
    masque(g, f, 'y')
    masque(g, lambda r, c: f(r, c) and (r - cr) ** 2 + (c - cc) ** 2 > (ray - 1.3) ** 2, 'o')
    masque(g, lambda r, c: f(r, c) and (r - cr) ** 2 + (c - cc) ** 2 > (ray - 1.3) ** 2 and r + c < cr + cc - 3, 'Y')
    return f


def paie():
    g = vide()
    piece(g)
    for r in range(3, 9):
        g[r][7] = 'b'
        g[r][8] = 'b'
    g[3][7] = g[4][7] = 'o'
    for r in (10, 11):
        g[r][7] = 'b'
        g[r][8] = 'b'
    return g


def lettre():
    g = vide()
    for r in range(3, 13):
        for c in range(1, 15):
            g[r][c] = 'P'
    for c in range(1, 15):
        g[12][c] = 'p'
        rr = 3 + min(c - 1, 14 - c)
        g[rr][c] = 'p'
    for c in (1, 14):
        for r in range(3, 13):
            g[r][c] = 'p'
    for r in range(4, 9):           # rabat un peu plus clair que le corps
        for c in range(1, 15):
            if r < 3 + min(c - 1, 14 - c):
                g[r][c] = 'W'
    sceau = {8: (7, 8), 9: (6, 9), 10: (6, 9), 11: (7, 8)}
    for r, (a, b) in sceau.items():
        for c in range(a, b + 1):
            g[r][c] = 'E'
    g[9][9] = g[10][9] = g[11][8] = 'e'
    g[9][7] = 'F'
    return g


def travail():
    # Pioche et marteau croisés.
    g = vide()
    pioche(g)
    for r in range(5, 14):
        g[r][r] = 't' if r % 2 else 'u'
    tete = lambda r, c: 5 <= r + c <= 8 and abs(r - c) <= 3 and r >= 1 and c >= 1
    masque(g, tete, 'i')
    masque(g, lambda r, c: tete(r, c) and r + c == 5, 'I')
    masque(g, lambda r, c: tete(r, c) and r + c == 8, 'j')
    return g


def danger():
    g = vide()
    tri = lambda r, c: 1 <= r <= 14 and abs(c - 7.5) <= 0.6 + (r - 1) * 0.54
    masque(g, tri, 'Y')
    masque(g, lambda r, c: tri(r, c) and (not tri(r, c + 1) or not tri(r + 1, c) or not tri(r, c - 1)), 'y')
    masque(g, lambda r, c: tri(r, c) and r == 14, 'o')
    for r in range(5, 11):
        g[r][7] = 'E'
        g[r][8] = 'e'
    g[5][8] = 'E'
    for r in (12, 13):
        g[r][7] = 'E'
        g[r][8] = 'e'
    g[13][7] = g[13][8] = 'e'
    return g


# --- Métiers ----------------------------------------------------------------------------------------------------

def chomeur():
    # Baluchon : bâton sur l'épaule, tissu rouge à pois noué au bout et qui pend.
    g = vide()
    for r in range(3, 15):
        g[r][15 - r] = 't' if r % 2 else 'u'
    sac = lambda r, c: (r - 8.0) ** 2 / 4.3 ** 2 + (c - 10.5) ** 2 / 3.9 ** 2 <= 1 and r >= 5
    masque(g, sac, 'E')
    masque(g, lambda r, c: sac(r, c) and (not sac(r + 1, c) or not sac(r, c + 1)), 'e')
    for r, c in ((6, 10), (8, 12), (9, 9), (11, 11), (7, 8)):
        g[r][c] = 'W'
    for r, c in ((4, 10), (4, 11), (3, 13), (2, 13), (4, 12), (5, 11)):     # nœud qui serre le tissu au bâton
        g[r][c] = 'E'
    g[3][12] = 'u'
    g[2][14] = 'e'
    return g


def mineur():
    g = vide()
    pioche(g)
    return g


def bucheron():
    g = vide()
    for r in range(1, 15):
        g[r][15 - r] = 't' if r % 2 else 'u'
    tete = {1: (7, 9), 2: (6, 11), 3: (5, 11), 4: (5, 10), 5: (6, 9), 6: (7, 8)}
    for r, (a, b) in tete.items():
        for c in range(a, b + 1):
            if c != 15 - r:
                g[r][c] = 'i'
    for r, c in ((2, 6), (3, 5), (4, 5), (5, 6), (1, 7)):
        g[r][c] = 'I'
    g[3][10] = g[4][10] = 'j'
    return g


def guerrier():
    g = vide()
    for r in range(1, 10):
        g[r][15 - r] = 'I'
        g[r][14 - r] = 'i'
    g[1][13] = '.'
    g[9][5] = 'j'
    for r, c in ((8, 3), (9, 4), (10, 5), (11, 6), (12, 7), (8, 4), (10, 6), (11, 7)):
        g[r][c] = 'b' if (r + c) % 2 else 'o'
    g[11][4] = 't'
    g[12][3] = 'u'
    g[13][2] = 'y'
    g[14][1] = 'o'
    g[13][1] = 'o'
    g[14][2] = 'b'
    return g


def fermier():
    g = vide()
    for r in range(3, 15):
        g[r][15 - r] = 't' if r % 2 else 'u'
    for c in range(8, 14):
        g[2][c] = 'i'
    g[1][12] = g[1][13] = 'i'
    g[1][11] = 'I'
    g[2][8] = 'I'
    g[3][8] = 'I'
    g[4][8] = 'i'
    g[3][9] = 'j'
    return g


def eleveur():
    g = grille([
        "................",
        "................",
        "....jjjjjjjj....",
        "...j________j...",
        "..j__________j..",
        ".IIIIIIIIIIIIII.",
        ".iWWWWWWWWWWWWi.",
        "..IWWWwwWWWWWj..",
        "..IiiiiiiiiiIj..",
        "..IIiiiiiiiiij..",
        "..IIiiiiiiiiij..",
        "...Iiiiiiiiij...",
        "...IIiiiiiijj...",
        "...jjjjjjjjjj...",
        "................",
        "................",
    ])
    return g


def pecheur():
    g = vide()
    for r in range(2, 15):
        g[r][15 - r] = 't' if r % 2 else 'u'
    g[1][14] = 'u'
    for r in range(2, 11):
        g[r][14] = 'x'
    g[11][14] = 'i'
    g[12][13] = 'i'
    g[11][12] = 'i'
    g[13][2] = 'd'
    g[12][3] = 'd'
    return g


def chasseur():
    g = vide()
    p0, p1, p2 = (1.0, 2.0), (-1.0, 15.0), (13.0, 14.0)
    for i in range(400):
        t = i / 399
        r = (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0]
        c = (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]
        rr, cc = int(round(r)), int(round(c))
        if 1 <= rr <= 14 and 1 <= cc <= 14:
            g[rr][cc] = 'u' if 0.4 < t < 0.6 else 't'
    for i in range(200):
        t = i / 199
        r = p0[0] + (p2[0] - p0[0]) * t
        c = p0[1] + (p2[1] - p0[1]) * t
        rr, cc = int(round(r)), int(round(c))
        if g[rr][cc] == '.':
            g[rr][cc] = 'x'
    # flèche encochée
    for k in range(0, 9):
        r, c = 12 - k, 3 + k
        if g[r][c] in '.x':
            g[r][c] = 'i' if k < 7 else 'I'
    g[4][11] = 'I'
    g[12][2] = 'W'
    g[13][3] = 'W'
    return g


def cuisinier():
    # Miche de pain en diagonale, entailles sombres.
    g = vide()
    def loaf(r, c):
        u = ((c - 7.5) - (r - 7.5)) / math.sqrt(2)    # le long de la miche (bas-gauche vers haut-droit)
        v = ((c - 7.5) + (r - 7.5)) / math.sqrt(2)
        return (u / 7.6) ** 2 + (v / 3.9) ** 2 <= 1
    masque(g, loaf, 'c')
    masque(g, lambda r, c: loaf(r, c) and ((c - 7.5) + (r - 7.5)) / math.sqrt(2) < -0.5, 'B')
    masque(g, lambda r, c: loaf(r, c) and not (loaf(r + 1, c) and loaf(r, c + 1)), 'n')
    for k in (-3, 0, 3):
        for d in (-1, 0):
            r = 7 - k + d
            c = int(8 + k) + d
            if loaf(r, c) and g[r][c] != 'n':
                g[r][c] = 'n'
    return g


def transporteur():
    # Hotte d'osier chargée de bûches, sangle de cuir sur le côté.
    g = vide()
    for r in range(5, 15):
        a = 2 + (r - 5) // 4
        b = 13 - (r - 5) // 4
        for c in range(a, b + 1):
            g[r][c] = 't' if (r + c) % 2 else 'u'
        g[r][a] = 'd'
        g[r][b] = 'd'
    for c in range(1, 15):
        g[5][c] = 'd'
    for c in range(3, 13):
        g[14][c] = 'd'
    for r in range(1, 5):
        g[r][4] = 'i' if r == 1 else 'u'
        g[r][5] = 'u'
    for r in range(2, 5):
        g[r][8] = 'B'
        g[r][9] = 'c'
    g[2][8] = 'S'
    for r in range(1, 5):
        g[r][11] = 'u'
    g[1][11] = 't'
    for r in range(7, 12):
        g[r][14] = 'r'
    g[6][14] = 'r'
    g[12][13] = 'r'
    return g


def guerisseur():
    g = grille([
        "................",
        "......uttu......",
        "......dttd......",
        "......GggG......",
        "......GggG......",
        ".....GGggGG.....",
        "....GgVVVVgG....",
        "...GNNVVVVVvg...",
        "..GNNVVVVVVVvg..",
        "..GNVVVVVVVVvg..",
        "..gVVVVVVVVVvg..",
        "..gVVVVVVVVvvg..",
        "...gVVVVVvvvg...",
        "....ggvvvvgg....",
        "......gggg......",
        "................",
    ])
    return g


def macon():
    # Truelle de maçon : large lame triangulaire en fer, col coudé, manche de bois.
    g = grille([
        "................",
        "...........tu...",
        "..........tu....",
        ".........tu.....",
        "........tu......",
        ".......jj.......",
        "......jj........",
        ".....IIIi.......",
        "....IIIIii......",
        "...IIIiiiii.....",
        "..IIIiiiiiij....",
        ".IIiiiiiiijj....",
        "..Iiiiiijjj.....",
        "...iijjj........",
        "................",
        "................",
    ])
    return g


ETATS = ['faim', 'colere', 'attente', 'sommeil', 'greve', 'blesse', 'peur', 'content', 'paie', 'lettre', 'travail', 'danger']
METIERS = ['chomeur', 'mineur', 'bucheron', 'guerrier', 'fermier', 'eleveur', 'pecheur', 'chasseur', 'cuisinier',
           'transporteur', 'guerisseur', 'macon']


def genere():
    m = globals()
    return ([('etat', n, image(contour(m[n]()))) for n in ETATS] +
            [('metier', n, image(contour(m[n]()))) for n in METIERS])


def apercu(icones, chemin, k=8):
    """Deux bandeaux : ciel de jour et nuit, chaque icône agrandie k fois, nom dessous."""
    colonnes = 12
    case = 16 * k + 12
    fonds = [('jour', (150, 196, 255)), ('nuit', (16, 18, 30))]
    groupes = [[i for i in icones if i[0] == 'etat'], [i for i in icones if i[0] == 'metier']]
    h_ligne = case + 18
    im = Image.new('RGB', (colonnes * case + 12, len(fonds) * len(groupes) * h_ligne + 12), (60, 60, 60))
    d = ImageDraw.Draw(im)
    y = 6
    for nom_fond, fond in fonds:
        for groupe in groupes:
            d.rectangle([6, y, im.width - 7, y + h_ligne - 3], fill=fond)
            for i, (_, nom, ic) in enumerate(groupe):
                x = 12 + i * case
                im.paste(ic.resize((16 * k, 16 * k), Image.NEAREST), (x, y + 4), ic.resize((16 * k, 16 * k), Image.NEAREST))
                d.text((x, y + 16 * k + 5), nom, fill=(255, 255, 255) if nom_fond == 'nuit' else (20, 20, 20))
            y += h_ligne
    im.save(chemin)


def main():
    sortie = sys.argv[1] if len(sys.argv) > 1 else SORTIE
    chemin_apercu = sys.argv[2] if len(sys.argv) > 2 else APERCU
    icones = genere()
    for dossier, nom, ic in icones:
        os.makedirs(os.path.join(sortie, dossier), exist_ok=True)
        ic.save(os.path.join(sortie, dossier, nom + '.png'))
    apercu(icones, chemin_apercu)
    print(f'{len(icones)} icônes écrites dans {os.path.normpath(sortie)}, aperçu : {os.path.normpath(chemin_apercu)}')


if __name__ == '__main__':
    main()
