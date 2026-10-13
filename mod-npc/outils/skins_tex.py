"""Skins des ouvriers (colonie_pnj) : 64x64 au format joueur 1.8+, bras larges, dessinés par programme.

Chaque métier a sa silhouette (casque à lampe du mineur, bonnet et carreaux du bûcheron, tabard du guerrier,
tablier blanc du cuisinier, robe et sacoche du guérisseur...) et au moins trois variantes : teint, cheveux,
barbe, homme ou femme, couleurs du tissu. La couche 2 (chapeau, veste, manches, jambières) porte les pièces
de tenue qui dépassent : coiffes, tabliers, tabards, sacs.

Rien n'est tiré d'une image existante : tout est tracé ici, avec des teintes proches des blocs vanilla.

Usage : python outils/skins_tex.py [dossier de sortie] [aperçu agrandi]
"""
import os
import random
import sys
from PIL import Image, ImageDraw

ICI = os.path.dirname(os.path.abspath(__file__))
SORTIE = os.path.join(ICI, '..', 'src', 'main', 'resources', 'assets', 'colonie_pnj', 'textures', 'entity', 'ouvrier')
APERCU = os.path.join(ICI, 'apercu_skins.png')


def hexa(c):
    if isinstance(c, tuple):
        return c[:3]
    c = c.lstrip('#')
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))


def nuance(c, f):
    """Assombrit (f < 1) ou éclaircit (f > 1) une couleur."""
    return tuple(max(0, min(255, int(v * f))) for v in hexa(c))


def melange(a, b, t):
    a, b = hexa(a), hexa(b)
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


# --- Le patron du format joueur -------------------------------------------------------------------------------
COTES = ('droite', 'avant', 'gauche', 'arriere')


class Boite:
    """Une boîte du modèle joueur (largeur w, hauteur h, profondeur d) posée en (u, v) sur la texture.

    Les quatre côtés forment une bande continue : droite | avant | gauche | arrière. « a » est la position
    le long de cette bande, ce qui permet de tracer des rayures ou des sangles qui font le tour."""

    def __init__(self, skin, u, v, w, h, d):
        self.s, self.u, self.v, self.w, self.h, self.d = skin, u, v, w, h, d
        self.f = {
            'dessus': (u + d, v, w, d), 'dessous': (u + d + w, v, w, d),
            'droite': (u, v + d, d, h), 'avant': (u + d, v + d, w, h),
            'gauche': (u + d + w, v + d, d, h), 'arriere': (u + 2 * d + w, v + d, w, h),
        }

    def larg(self, face):
        return self.f[face][2]

    def p(self, face, x, y, c, bruit=None):
        fx, fy, fw, fh = self.f[face]
        if 0 <= x < fw and 0 <= y < fh:
            self.s.pose(fx + x, fy + y, c, bruit)

    def efface(self, face, x, y):
        fx, fy, fw, fh = self.f[face]
        if 0 <= x < fw and 0 <= y < fh:
            self.s.px[fx + x, fy + y] = (0, 0, 0, 0)

    def rect(self, face, x0, y0, x1, y1, c, bruit=None):
        for x in range(x0, x1):
            for y in range(y0, y1):
                self.p(face, x, y, c, bruit)

    def remplit(self, face, c, bruit=None):
        _, _, fw, fh = self.f[face]
        self.rect(face, 0, 0, fw, fh, c, bruit)

    def tour(self, a, y, c, bruit=None):
        """Pose un pixel à la position a de la bande des côtés."""
        a %= 2 * (self.w + self.d)
        for face in COTES:
            fw = self.larg(face)
            if a < fw:
                self.p(face, a, y, c, bruit)
                return
            a -= fw

    def bande(self, y0, y1, c, bruit=None):
        """Remplit les rangées y0..y1-1 de tous les côtés. c : couleur ou fonction (a, y) -> couleur."""
        for a in range(2 * (self.w + self.d)):
            for y in range(y0, y1):
                self.tour(a, y, c(a, y) if callable(c) else c, bruit)

    def cote(self, face, xf, y, c, bruit=None):
        """Sur un côté droit ou gauche, xf = distance au bord avant (0 = contre la face avant)."""
        x = self.d - 1 - xf if face == 'droite' else xf
        self.p(face, x, y, c, bruit)


class Skin:
    def __init__(self, graine):
        self.img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
        self.px = self.img.load()
        self.rnd = random.Random(graine)
        self.bruit = 0.05
        b = lambda u, v, w, h, d: Boite(self, u, v, w, h, d)
        # Couche 1 et couche 2 (décalée) de chaque partie
        self.tete, self.chapeau = b(0, 0, 8, 8, 8), b(32, 0, 8, 8, 8)
        self.corps, self.veste = b(16, 16, 8, 12, 4), b(16, 32, 8, 12, 4)
        self.bd, self.manche_d = b(40, 16, 4, 12, 4), b(40, 32, 4, 12, 4)
        self.bg, self.manche_g = b(32, 48, 4, 12, 4), b(48, 48, 4, 12, 4)
        self.jd, self.jambiere_d = b(0, 16, 4, 12, 4), b(0, 32, 4, 12, 4)
        self.jg, self.jambiere_g = b(16, 48, 4, 12, 4), b(0, 48, 4, 12, 4)
        self.bras, self.manches = (self.bd, self.bg), (self.manche_d, self.manche_g)
        self.jambes, self.jambieres = (self.jd, self.jg), (self.jambiere_d, self.jambiere_g)

    def pose(self, x, y, c, bruit=None):
        if c is None:
            return
        b = self.bruit if bruit is None else bruit
        f = 1 + self.rnd.uniform(-b, b)
        self.px[x, y] = nuance(c, f) + (255,)


# --- Palettes -------------------------------------------------------------------------------------------------
PEAUX = {
    'claire': '#e8bf9a', 'rose': '#d9a07e', 'mate': '#b98258', 'brune': '#8d5b3b', 'foncee': '#5f3d27',
}
CHEVEUX = {
    'noir': '#24180f', 'brun': '#4a2f1b', 'chatain': '#6b4527', 'roux': '#8e3f1c', 'blond': '#c6a15a',
    'gris': '#8a8783', 'blanc': '#d4d0c8', 'auburn': '#6e2c17',
}
YEUX = {'brun': '#4e321b', 'bleu': '#3d5d96', 'vert': '#4b7438', 'gris': '#5f666e', 'noir': '#20160e'}
BLANC_OEIL = '#ecebe6'

CUIR = '#7a5230'
CUIR_SOMBRE = '#5a3a20'
CUIR_CLAIR = '#94683c'
BOTTE = '#3e2c1d'
FER = '#a9a9a9'
FER_SOMBRE = '#6f6f6f'
LIN = '#cbbd96'
CORDE = '#a68d58'


# --- La tête : visage, cheveux, barbe --------------------------------------------------------------------------
def tete(S, v):
    t = S.tete
    peau, chev = v['peau'], v['cheveux']
    sourcil = nuance(chev, 0.8) if sum(hexa(chev)) > 300 else nuance(chev, 0.95)
    for face in COTES + ('dessous',):
        t.remplit(face, peau, 0.025)
    t.remplit('dessus', chev)
    # Ombre légère du menton et des joues
    for x in range(8):
        t.p('avant', x, 7, nuance(peau, 0.93), 0.02)
    # Yeux, sourcils, nez, bouche
    oeil = v.get('yeux', YEUX['brun'])
    t.p('avant', 1, 4, BLANC_OEIL, 0.02)
    t.p('avant', 2, 4, oeil, 0.02)
    t.p('avant', 5, 4, oeil, 0.02)
    t.p('avant', 6, 4, BLANC_OEIL, 0.02)
    for x in (1, 2, 5, 6):
        t.p('avant', x, 3, sourcil, 0.03)
    t.p('avant', 3, 5, nuance(peau, 0.86), 0.01)
    t.p('avant', 4, 5, nuance(peau, 0.82), 0.01)
    femme = v.get('femme', False)
    levres = melange(peau, '#9a3c34', 0.45) if femme else nuance(peau, 0.66)
    t.p('avant', 3, 6, levres, 0.02)
    t.p('avant', 4, 6, levres, 0.02)
    if femme:
        t.p('avant', 0, 4, nuance(peau, 0.92), 0.01)
        t.p('avant', 7, 4, nuance(peau, 0.92), 0.01)
    if v.get('age'):           # rides au coin des yeux
        t.p('avant', 0, 5, nuance(peau, 0.88), 0.01)
        t.p('avant', 7, 5, nuance(peau, 0.88), 0.01)
    # Oreilles
    for face in ('droite', 'gauche'):
        t.cote(face, 3, 4, nuance(peau, 0.85), 0.01)
        t.cote(face, 3, 5, nuance(peau, 0.88), 0.01)
    coiffure(t, v.get('coupe', 'court'), chev, peau)
    barbe(t, v.get('barbe'), v.get('couleur_barbe', chev), peau)
    for x, y in v.get('salete', ()):
        t.p('avant', x, y, nuance(peau, 0.62), 0.05)


def coiffure(t, coupe, chev, peau):
    if coupe == 'chauve':
        t.remplit('dessus', peau, 0.02)
        for face in ('droite', 'gauche'):
            for y in (3, 4, 5):
                for xf in range(4, 8):
                    if not (xf == 4 and y == 3):
                        t.cote(face, xf, y, chev)
        for y in (3, 4, 5):
            t.rect('arriere', 0, y, 8, y + 1, chev)
        t.rect('arriere', 0, 2, 8, 3, nuance(peau, 0.95), 0.02)
        return
    longs = coupe in ('long', 'tresse', 'chignon')
    # Front
    t.rect('avant', 0, 0, 8, 1, chev)
    if coupe == 'frange':
        t.rect('avant', 0, 1, 8, 2, chev)
        for x in (0, 7):
            t.p('avant', x, 2, chev)
        t.p('avant', 3, 2, chev)
    elif longs:
        t.rect('avant', 0, 1, 3, 2, chev)
        t.rect('avant', 5, 1, 8, 2, chev)
        t.p('avant', 3, 1, nuance(chev, 0.85))      # la raie
        t.p('avant', 4, 1, peau, 0.02)
        for y in range(2, 8 if coupe == 'long' else 4):
            t.p('avant', 0, y, chev)
            t.p('avant', 7, y, chev)
    else:   # court
        for x in (0, 1, 2, 5, 6, 7):
            t.p('avant', x, 1, chev)
        t.p('avant', 0, 2, chev)
        t.p('avant', 7, 2, chev)
    # Côtés
    for face in ('droite', 'gauche'):
        for y in range(8):
            for xf in range(8):
                if coupe == 'long':
                    poil = y < 2 or xf >= 2 or (xf == 1 and y < 4)
                elif coupe in ('tresse', 'chignon'):
                    poil = y < 3 or (xf >= 3 and y < 6) or (xf >= 5 and y < 7)
                elif coupe == 'frange':
                    poil = y < 3 or (xf >= 4 and y < 6) or (xf >= 6 and y < 7)
                else:
                    poil = y < 2 or (y == 2 and xf >= 1) or (xf >= 5 and y < 6)
                if poil:
                    t.cote(face, xf, y, chev)
    # Arrière et reflets
    bas = 8 if longs else 7 if coupe == 'frange' else 6
    t.rect('arriere', 0, 0, 8, bas, chev)
    if coupe == 'court':
        t.rect('arriere', 1, 6, 7, 7, chev)
    for x in range(1, 8, 3):
        t.p('dessus', x, (x * 5) % 8, nuance(chev, 1.15))
        t.p('arriere', x, 2 + x % 3, nuance(chev, 0.85))
    if coupe == 'tresse':
        for y in range(0, 8):
            t.p('arriere', 3, y, nuance(chev, 0.8 if y % 2 else 1.1))
            t.p('arriere', 4, y, nuance(chev, 1.1 if y % 2 else 0.8))


def barbe(t, genre, c, peau):
    if not genre:
        return
    fonce = nuance(c, 0.85)
    if genre == 'rasee':
        for x, y in ((0, 6), (1, 7), (2, 6), (5, 7), (6, 6), (7, 7), (3, 7), (6, 7)):
            t.p('avant', x, y, melange(peau, c, 0.35), 0.02)
        return
    if genre == 'moustache':
        for x in range(2, 6):
            t.p('avant', x, 6, c)
        t.p('avant', 3, 7, nuance(peau, 0.66))
        t.p('avant', 4, 7, nuance(peau, 0.66))
        return
    # courte ou pleine
    t.rect('avant', 0, 7, 8, 8, c)
    for x in (0, 1, 6, 7):
        t.p('avant', x, 6, c)
    t.p('avant', 2, 6, c)
    t.p('avant', 5, 6, c)
    t.p('avant', 3, 6, fonce)
    t.p('avant', 4, 6, fonce)
    if genre == 'pleine':
        t.p('avant', 0, 5, c)
        t.p('avant', 7, 5, c)
        t.p('avant', 2, 5, c)
        t.p('avant', 5, 5, c)
    t.remplit('dessous', c)
    for face in ('droite', 'gauche'):
        for y in range(4 if genre == 'pleine' else 5, 8):
            for xf in range(0, 4):
                if not (xf == 3 and y < 6):
                    t.cote(face, xf, y, c)


# --- Le corps de base ---------------------------------------------------------------------------------------------
def habit(S, peau, haut, bas, bottes=BOTTE, ceinture=CUIR, manche=12, haut_bas=10, tige=3, col=True,
          motif=None, pieds_nus=False, mains=None):
    """Chemise ou tunique, ceinture, pantalon, bottes. manche = longueur de la manche (12 : jusqu'au poignet)."""
    tissu = motif or (lambda a, y: haut)
    # Corps : tunique jusqu'à haut_bas, ceinture juste au-dessus, puis le pantalon
    S.corps.bande(0, 12, tissu)
    for face in ('dessus',):
        S.corps.remplit(face, haut)
    S.corps.remplit('dessous', bas)
    if haut_bas < 12:
        S.corps.bande(haut_bas, 12, bas)
    if ceinture:
        S.corps.bande(haut_bas - 2 if haut_bas >= 10 else haut_bas - 1, haut_bas - 1 if haut_bas >= 10 else haut_bas,
                      ceinture)
    if col:
        S.corps.p('avant', 3, 0, peau, 0.02)
        S.corps.p('avant', 4, 0, peau, 0.02)
        S.corps.p('avant', 3, 1, nuance(haut, 0.8))
        S.corps.p('avant', 4, 1, nuance(haut, 0.8))
    # Bras
    for b in S.bras:
        b.bande(0, 12, tissu)
        b.remplit('dessus', haut)
        if manche < 12:
            b.bande(manche, 12, peau, 0.025)
            b.bande(manche, manche + 1, nuance(haut, 0.85))   # le revers de la manche retroussée
        b.bande(11, 12, mains or peau, 0.025)
        b.remplit('dessous', mains or peau, 0.025)
        b.bande(10, 11, mains or (nuance(haut, 0.9) if manche >= 11 else peau), 0.025)
        if manche >= 11 and not mains:
            b.bande(10, 11, nuance(haut, 0.85))
    # Jambes
    for j in S.jambes:
        j.bande(0, 12, bas)
        j.remplit('dessus', bas)
        if pieds_nus:
            j.bande(11, 12, peau, 0.02)
            j.remplit('dessous', nuance(peau, 0.8), 0.02)
        else:
            j.bande(12 - tige, 12, bottes)
            j.bande(12 - tige, 13 - tige, nuance(bottes, 1.15))
            j.remplit('dessous', nuance(bottes, 0.7))
    # Plis du pantalon : la face intérieure un peu plus sombre
    for y in range(0, 12 - tige):
        S.jd.p('gauche', 0, y, nuance(bas, 0.88))
        S.jg.p('droite', 3, y, nuance(bas, 0.88))


def carreaux(clair, fonce):
    def f(a, y):
        if a % 4 == 0 and y % 4 == 0:
            return nuance(fonce, 0.8)
        if a % 4 == 0 or y % 4 == 0:
            return fonce
        return clair
    return f


def tricot(c):
    return lambda a, y: nuance(c, 0.88) if (a + y) % 2 and y % 2 else c


def maille(a, y):
    m = (a + y) % 2
    if y % 2 == 0:
        return '#8c8c8c' if m else '#5e5e5e'
    return '#737373' if m else '#4c4c4c'


# --- Les pièces de la couche 2 -------------------------------------------------------------------------------------
def tablier(S, c, haut=2, bas_jambe=8, sangle=None, poche=None, taches=()):
    v = S.veste
    v.rect('avant', 1, haut, 7, 12, c)
    v.rect('avant', 0, 8, 8, 12, c)
    for x in (1, 6):
        for y in range(0, haut):
            v.p('avant', x, y, sangle or nuance(c, 0.85))
    v.bande(8, 9, sangle or nuance(c, 0.85))            # le cordon qui fait le tour de la taille
    for j, fx in ((S.jambiere_d, (0, 4)), (S.jambiere_g, (0, 4))):
        j.rect('avant', fx[0], 0, fx[1], bas_jambe, c)
        for x in range(4):
            j.p('avant', x, bas_jambe - 1, nuance(c, 0.88))
    # On garde les bords du tablier bien nets : un liseré plus sombre
    for y in range(haut, 12):
        v.p('avant', 1 if y < 8 else 0, y, nuance(c, 0.9))
        v.p('avant', 6 if y < 8 else 7, y, nuance(c, 0.9))
    if poche:
        v.rect('avant', 2, 9, 6, 11, poche)
        v.p('avant', 3, 9, nuance(poche, 0.7))
    for x, y in taches:
        v.p('avant', x, y, melange(c, '#7a4a2a', 0.45))


def sangle_diagonale(S, c, de_droite=True):
    """Bandoulière sur la poitrine (de l'épaule droite vers la hanche gauche) et dans le dos."""
    for y in range(0, 10):
        x = 1 + (y * 6) // 9
        xa = x if de_droite else 7 - x
        S.veste.p('avant', xa, y, c)
        S.veste.p('arriere', 7 - xa, y, c)
    S.veste.p('dessus', 0 if de_droite else 7, 1, c)
    S.veste.p('dessus', 0 if de_droite else 7, 2, c)


def bonnet(S, c, revers=None, hauteur=2, pompon=False):
    h = S.chapeau
    h.remplit('dessus', c)
    h.bande(0, hauteur + 1, tricot(c))
    h.bande(hauteur, hauteur + 1, revers or nuance(c, 0.8))
    if pompon:
        h.p('dessus', 3, 3, nuance(c, 1.2))
        h.p('dessus', 4, 4, nuance(c, 1.2))


def capuche(S, c, doublure=None):
    """Capuche relevée : couvre le dessus, les côtés et l'arrière, encadre le visage."""
    h = S.chapeau
    h.remplit('dessus', c)
    for face in ('droite', 'gauche', 'arriere'):
        h.remplit(face, c)
    h.rect('avant', 0, 0, 8, 2, c)
    for y in range(2, 8):
        h.p('avant', 0, y, c)
        h.p('avant', 7, y, c)
    h.rect('avant', 1, 1, 7, 2, doublure or nuance(c, 0.7))   # l'ombre sous le bord
    for face in ('droite', 'gauche'):
        for y in range(8):
            h.cote(face, 0, y, nuance(c, 0.85))
    h.p('dessus', 3, 1, nuance(c, 0.8))
    h.p('dessus', 4, 0, nuance(c, 0.8))
    # Le bas de la capuche tombe sur les épaules
    S.veste.bande(0, 1, c)
    S.veste.remplit('dessus', c)
    for x in (2, 3, 4, 5):
        S.veste.efface('avant', x, 0)


def chapeau_bord(S, c, ruban=None, hauteur=2):
    h = S.chapeau
    h.remplit('dessus', c)
    h.bande(0, hauteur + 1, c)
    if ruban:
        h.bande(hauteur - 1, hauteur, ruban)
    h.bande(hauteur, hauteur + 1, nuance(c, 0.82))
    for x in range(0, 8, 3):
        h.p('dessus', x, (x * 3) % 8, nuance(c, 1.12))


def fichu(S, c, motif=None):
    """Foulard noué sur la tête (femmes, cuisinières, fermières)."""
    h = S.chapeau
    h.remplit('dessus', c)
    h.bande(0, 2, c)
    for face in ('arriere',):
        h.rect(face, 0, 0, 8, 4, c)
    for face in ('droite', 'gauche'):
        for xf in range(3, 8):
            h.cote(face, xf, 2, c)
            h.cote(face, xf + 2, 3, c)
    h.p('arriere', 3, 4, nuance(c, 0.85))   # le nœud
    h.p('arriere', 4, 4, nuance(c, 0.85))
    h.p('arriere', 3, 5, nuance(c, 0.8))
    if motif:
        for a in range(0, 32, 3):
            h.tour(a, 0, motif)


# --- Les métiers -----------------------------------------------------------------------------------------------------
def chomeur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['tunique'], v['chausses'], bottes='#4f3d2c', ceinture=CORDE, manche=7, haut_bas=11,
          pieds_nus=v.get('pieds_nus', False), tige=2)
    # Tunique effilochée : le bas déchiré, les manches en lambeaux
    for a in range(0, 24, 3):
        S.corps.tour(a, 10, v['chausses'])
    for b in S.bras:
        for a in range(1, 16, 3):
            b.tour(a, 7, v['peau'], 0.02)
    # Pièces rapiécées
    S.corps.rect('avant', 5, 2, 7, 4, v['piece'])
    S.corps.rect('arriere', 1, 5, 3, 7, v['piece'])
    S.jg.rect('avant', 1, 3, 3, 5, v['piece2'])
    S.jd.rect('droite', 1, 6, 3, 8, v['piece'])
    for j in S.jambes:                 # chausses usées aux genoux
        j.p('avant', 1, 6, nuance(v['chausses'], 1.15))
        j.p('avant', 2, 7, nuance(v['chausses'], 0.8))
    if v.get('coiffe') == 'capuche':
        capuche(S, v['tunique'])
    elif v.get('coiffe') == 'bonnet':
        bonnet(S, '#6a5f50', hauteur=1)


def mineur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['tunique'], '#3d3328', bottes='#2c221a', ceinture=CUIR_SOMBRE, manche=12, tige=4,
          mains='#4a3a2c')
    tablier(S, v.get('tablier', '#6b4a2a'), haut=3, bas_jambe=6, sangle=CUIR_SOMBRE)
    if v.get('coiffe') == 'capuche':
        capuche(S, '#2f2f35')
    else:
        # Casque de cuir bouilli et sa lampe (une chandelle dans une coupelle de fer)
        h = S.chapeau
        cuir = v.get('casque', '#5e4128')
        h.remplit('dessus', cuir)
        h.bande(0, 3, cuir)
        h.bande(2, 3, nuance(cuir, 0.75))
        for x in range(0, 8, 2):
            h.p('dessus', x, 3, nuance(cuir, 0.8))
            h.p('dessus', 3, x, nuance(cuir, 0.8))
        h.rect('avant', 3, 0, 5, 3, '#3c3c3c')
        h.p('avant', 3, 0, '#ffd85a', 0.0)
        h.p('avant', 4, 0, '#ffb12a', 0.0)
        h.p('avant', 3, 1, '#e6e0c8', 0.0)
        h.p('avant', 4, 1, '#d8d0b0', 0.0)
    # Suie sur les manches et les genoux
    for b in S.bras:
        b.p('avant', 1, 6, nuance(v['tunique'], 0.7))
        b.p('avant', 2, 8, nuance(v['tunique'], 0.7))


def bucheron(S, v):
    tete(S, v)
    clair, fonce = v['carreaux']
    habit(S, v['peau'], clair, '#4f3b28', ceinture=CUIR, manche=v.get('manche', 12), motif=carreaux(clair, fonce),
          tige=3)
    # Bretelles de cuir
    for y in range(0, 9):
        S.veste.p('avant', 1, y, CUIR_SOMBRE)
        S.veste.p('avant', 6, y, CUIR_SOMBRE)
        S.veste.p('arriere', 2 + (y > 4), y, CUIR_SOMBRE)
        S.veste.p('arriere', 5 - (y > 4), y, CUIR_SOMBRE)
    S.veste.p('avant', 1, 9, '#c0c0c0', 0.02)
    S.veste.p('avant', 6, 9, '#c0c0c0', 0.02)
    if v.get('bonnet'):
        bonnet(S, v['bonnet'], hauteur=v.get('h_bonnet', 2), pompon=v.get('pompon', False))
    # Bas de pantalon rentré dans les bottes, avec une bande molletière
    for jb in S.jambieres:
        jb.bande(7, 8, '#7a6a52')


def guerrier(S, v):
    tete(S, v)
    habit(S, v['peau'], '#6c6c6c', '#3d3a36', bottes='#4a3a2a', ceinture=CUIR, manche=12, motif=maille, col=False,
          mains='#5a3e24', tige=4)
    # Jambières de fer
    for j in S.jambes:
        j.rect('avant', 0, 5, 4, 9, FER_SOMBRE)
        j.rect('avant', 0, 5, 4, 6, FER)
        j.p('avant', 1, 7, FER)
    # Tabard aux couleurs du chef
    tab, emb = v['tabard'], v['embleme']
    for face in ('avant', 'arriere'):
        S.veste.rect(face, 1, 0, 7, 12, tab)
        S.veste.p(face, 0, 0, tab)
        S.veste.p(face, 7, 0, tab)
        for y in range(1, 12):
            S.veste.p(face, 1, y, nuance(tab, 0.85))
            S.veste.p(face, 6, y, nuance(tab, 0.85))
    S.veste.remplit('dessus', tab)
    for x in (2, 3, 4, 5):
        S.veste.efface('avant', x, 0)
    S.veste.p('avant', 3, 0, nuance(tab, 0.7))
    S.veste.p('avant', 4, 0, nuance(tab, 0.7))
    forme = v.get('forme', 'croix')
    for face in ('avant', 'arriere'):
        if forme == 'croix':
            S.veste.rect(face, 3, 2, 5, 8, emb)
            S.veste.rect(face, 2, 4, 6, 5, emb)
        elif forme == 'bande':
            S.veste.rect(face, 3, 1, 5, 12, emb)
        else:   # chevron
            for i in range(3):
                S.veste.p(face, 1 + i, 3 + i, emb)
                S.veste.p(face, 6 - i, 3 + i, emb)
                S.veste.p(face, 1 + i, 4 + i, emb)
                S.veste.p(face, 6 - i, 4 + i, emb)
    S.veste.bande(8, 9, CUIR)
    S.veste.p('avant', 3, 8, '#b8b8b8', 0.02)            # la boucle
    S.veste.p('avant', 4, 8, '#b8b8b8', 0.02)
    for jb in S.jambieres:                              # le tabard tombe sur les cuisses
        jb.rect('avant', 0, 0, 4, 3, tab)
        jb.rect('arriere', 0, 0, 4, 3, tab)
        for x in range(4):
            jb.p('avant', x, 2, nuance(tab, 0.8))
            jb.p('arriere', x, 2, nuance(tab, 0.8))
    # Épaulières
    for m in S.manches:
        m.remplit('dessus', FER)
        m.bande(0, 2, FER)
        m.bande(1, 2, FER_SOMBRE)
    # Casque
    h = S.chapeau
    casque = v.get('casque', 'nasal')
    if casque == 'camail':
        h.remplit('dessus', '#7a7a7a')
        for face in ('droite', 'gauche', 'arriere'):
            for y in range(8):
                for x in range(8):
                    h.p(face, x, y, maille(x, y))
        h.rect('avant', 0, 0, 8, 2, FER)
        for y in range(2, 8):
            h.p('avant', 0, y, maille(0, y))
            h.p('avant', 7, y, maille(1, y))
        h.rect('avant', 1, 7, 7, 8, '#737373')
    else:
        h.remplit('dessus', FER)
        h.bande(0, 3, FER)
        h.bande(2, 3, FER_SOMBRE)
        h.p('dessus', 3, 3, '#c8c8c8')
        h.p('dessus', 4, 4, '#c8c8c8')
        if casque == 'nasal':
            for y in range(3, 6):
                h.p('avant', 3, y, FER_SOMBRE)
                h.p('avant', 4, y, FER)
        else:   # chapel de fer, rebord marqué
            h.bande(1, 2, '#c0c0c0')
            h.bande(3, 4, FER_SOMBRE)
            for x in range(1, 7):
                h.efface('avant', x, 3)


def fermier(S, v):
    tete(S, v)
    habit(S, v['peau'], v.get('chemise', LIN), v.get('bas', '#5b4630'), bottes='#4a3626', ceinture=CUIR,
          manche=v.get('manche', 8), tige=3)
    if v.get('salopette'):
        c = v['salopette']
        S.veste.rect('avant', 1, 3, 7, 12, c)
        for y in range(0, 3):
            S.veste.p('avant', 1, y, c)
            S.veste.p('avant', 6, y, c)
            S.veste.p('arriere', 2, y, c)
            S.veste.p('arriere', 5, y, c)
        S.veste.rect('arriere', 1, 3, 7, 12, c)
        S.veste.rect('avant', 3, 4, 5, 6, nuance(c, 0.85))
    # Boue sur les bottes
    for j in S.jambes:
        j.p('avant', 0, 9, '#5a4128')
        j.p('avant', 2, 10, '#5a4128')
    if v.get('coiffe') == 'paille':
        chapeau_bord(S, '#c9a94e', ruban='#7a5a2a')
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])


def eleveur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['tunique'], v.get('bas', '#5a4a36'), bottes='#3e2c1d', ceinture=CUIR, manche=12, tige=4)
    # Gilet en peau de mouton, ouvert devant
    laine = v.get('laine', '#e4dfd2')
    ve = S.veste
    ve.remplit('dessus', laine)
    for face in ('droite', 'gauche', 'arriere'):
        ve.rect(face, 0, 0, ve.larg(face), 9, laine)
    for x in (0, 1, 2, 5, 6, 7):
        ve.rect('avant', x, 0, x + 1, 9, laine)
    for face in COTES:
        for x in range(ve.larg(face)):
            if (x * 7 + len(face)) % 3 == 0:
                ve.p(face, x, 8, nuance(laine, 0.85))
    for y in range(9):
        ve.p('avant', 2, y, nuance(laine, 0.8))
        ve.p('avant', 5, y, nuance(laine, 0.8))
    for i in range(14):
        ve.p('arriere', (i * 5) % 8, (i * 3) % 9, nuance(laine, 0.9))
    # Molletières croisées
    for jb in S.jambieres:
        for y in range(4, 8):
            jb.p('avant', (y % 2) * 3, y, CUIR_CLAIR)
            jb.p('avant', 1 + (y % 2), y, CUIR_CLAIR)
    if v.get('coiffe') == 'chapeau':
        chapeau_bord(S, v.get('feutre', '#5a4632'), hauteur=2)
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])
    elif v.get('coiffe') == 'capuche':
        capuche(S, v['capuche'])


def pecheur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['tricot'], '#5c5444', bottes='#2f261c', ceinture=CUIR_SOMBRE, manche=v.get('manche', 12),
          motif=tricot(v['tricot']), tige=6, col=False)
    # Col roulé
    S.corps.bande(0, 1, nuance(v['tricot'], 1.12))
    # Cuissardes : le haut replié
    for j in S.jambes:
        j.bande(6, 7, '#463a2c')
    # Corde enroulée à la ceinture et couteau
    S.veste.bande(9, 10, CORDE)
    S.veste.rect('avant', 5, 9, 7, 11, CORDE)
    S.veste.p('avant', 6, 10, nuance(CORDE, 0.7))
    S.veste.p('avant', 1, 10, '#5a3a20')
    S.veste.p('avant', 1, 11, '#b0b0b0')
    if v.get('coiffe') == 'bonnet':
        bonnet(S, v['bonnet'], hauteur=1)
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])
    elif v.get('coiffe') == 'chapeau':
        chapeau_bord(S, '#6e6440', hauteur=2)


def chasseur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['chemise'], '#4a3a2a', bottes='#3e2c1d', ceinture=CUIR_SOMBRE, manche=12, tige=5)
    # Pourpoint de cuir sans manches
    cuir = v.get('pourpoint', CUIR)
    S.corps.rect('avant', 0, 1, 8, 10, cuir)
    S.corps.rect('arriere', 0, 0, 8, 10, cuir)
    for face in ('droite', 'gauche'):
        S.corps.rect(face, 0, 1, 4, 10, cuir)
    for y in range(2, 9, 2):
        S.corps.p('avant', 3, y, nuance(cuir, 0.7))     # le laçage
        S.corps.p('avant', 4, y + 1, nuance(cuir, 0.7))
    # Bracelets de cuir
    for m in S.manches:
        m.bande(7, 10, CUIR_SOMBRE)
        m.bande(8, 9, CUIR)
    capuche(S, v['capuche'])
    # Carquois dans le dos (bois sombre cerclé de cuir clair), bandoulière devant, empennes qui dépassent
    sangle_diagonale(S, CUIR_SOMBRE)
    ve = S.veste
    ve.rect('arriere', 4, 1, 7, 11, '#3a2616')
    for y in (2, 6, 10):
        ve.rect('arriere', 4, y, 7, y + 1, CUIR_CLAIR)
    for x, c in ((4, '#e8e4dc'), (5, '#a83a2a'), (6, '#e8e4dc')):
        ve.p('arriere', x, 0, c, 0.02)
    ve.p('dessus', 1, 3, '#e8e4dc', 0.02)
    ve.p('dessus', 2, 3, '#a83a2a', 0.02)


def cuisinier(S, v):
    tete(S, v)
    habit(S, v['peau'], v.get('chemise', '#ddd5c2'), v.get('bas', '#5e5648'), bottes='#3e2c1d', ceinture=None,
          manche=7, tige=2)
    tablier(S, '#efebe0', haut=3, bas_jambe=9, sangle='#d6d0c0', taches=v.get('taches', ((2, 9), (5, 6))))
    # Torchon passé à la ceinture
    S.veste.rect('gauche', 1, 8, 3, 11, '#b8b0a0')
    S.veste.p('gauche', 1, 10, '#8a3a2a')
    if v.get('foulard'):
        S.veste.rect('avant', 2, 0, 6, 2, v['foulard'])
        S.veste.p('avant', 3, 2, v['foulard'])
    if v.get('coiffe') == 'toque':
        h = S.chapeau
        h.remplit('dessus', '#f2efe6')
        h.bande(0, 3, '#f2efe6')
        h.bande(2, 3, '#d8d2c2')
        for x in range(0, 8, 2):
            h.p('dessus', x, (x + 3) % 8, '#ddd8ca')
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])


def transporteur(S, v):
    tete(S, v)
    habit(S, v['peau'], v['tunique'], '#4e4232', bottes='#3a2a1c', ceinture=CUIR, manche=12, tige=5)
    # Hotte de portage : sac dans le dos, couverture roulée dessus, bretelles devant
    ve = S.veste
    sac = v.get('sac', '#6b5032')
    ve.rect('arriere', 0, 2, 8, 11, sac)
    ve.rect('arriere', 0, 1, 8, 3, '#8c7c5c')            # la couverture roulée
    for x in range(0, 8, 2):
        ve.p('arriere', x, 1, '#6e6248')
    ve.rect('arriere', 2, 5, 6, 8, nuance(sac, 0.82))     # le rabat
    ve.p('arriere', 4, 7, '#b0a070')
    for face in ('droite', 'gauche'):
        ve.rect(face, 0, 2, 4, 11, nuance(sac, 0.9))
        ve.rect(face, 0, 1, 4, 3, '#8c7c5c')
    ve.remplit('dessus', '#8c7c5c')
    for y in range(0, 10):
        ve.p('avant', 1, y, CUIR_SOMBRE)
        ve.p('avant', 6, y, CUIR_SOMBRE)
    ve.p('avant', 1, 4, '#a0a0a0', 0.02)
    ve.p('avant', 6, 4, '#a0a0a0', 0.02)
    # Bourse à la ceinture
    S.corps.rect('avant', 5, 9, 7, 11, CUIR_CLAIR)
    if v.get('coiffe') == 'chapeau':
        chapeau_bord(S, v.get('feutre', '#4f4234'), ruban=v.get('ruban'), hauteur=2)
    elif v.get('coiffe') == 'capuche':
        capuche(S, v['capuche'])
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])


def guerisseur(S, v):
    tete(S, v)
    robe = v['robe']
    habit(S, v['peau'], robe, robe, bottes='#3a2c20', ceinture=None, manche=12, haut_bas=12, tige=1)
    # Robe longue jusqu'aux chevilles, ourlet plus sombre
    for j in S.jambes:
        j.bande(10, 11, nuance(robe, 0.8))
        for y in range(0, 10):
            j.p('gauche' if j is S.jd else 'droite', 0 if j is S.jd else 3, y, robe)
    S.jd.p('avant', 3, 4, nuance(robe, 0.85))
    S.jg.p('avant', 0, 6, nuance(robe, 0.85))
    S.corps.bande(8, 9, v.get('cordon', CORDE))
    S.corps.p('avant', 2, 9, v.get('cordon', CORDE))
    S.corps.p('avant', 2, 10, v.get('cordon', CORDE))
    # Manches larges (couche 2) jusqu'au poignet, revers clair
    for m in S.manches:
        m.bande(6, 10, robe)
        m.bande(9, 10, v.get('revers', LIN))
    # Bandoulière et sacoche à la hanche droite, des herbes qui dépassent
    sangle_diagonale(S, CUIR, de_droite=False)
    ve = S.veste
    ve.rect('avant', 0, 8, 3, 11, '#7a5432')
    ve.rect('avant', 0, 8, 3, 9, '#5e3e22')
    ve.p('avant', 1, 7, '#4f7a2e', 0.0)
    ve.p('avant', 2, 7, '#6a9a3a', 0.0)
    ve.rect('droite', 0, 8, 4, 11, '#6e4a2a')
    if v.get('coiffe') == 'capuche':
        capuche(S, v.get('capuche', robe))
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])
    else:   # capuche rabattue dans le dos
        ve.rect('arriere', 1, 0, 7, 3, nuance(robe, 0.9))
        ve.rect('arriere', 2, 3, 6, 4, nuance(robe, 0.8))


def macon(S, v):
    tete(S, v)
    habit(S, v['peau'], v.get('chemise', '#9a958a'), '#5a5040', bottes='#4a3a2a', ceinture=CUIR, manche=8, tige=3)
    tablier(S, v.get('tablier', '#7a5634'), haut=4, bas_jambe=7, sangle=CUIR_SOMBRE, poche=nuance('#7a5634', 0.85))
    S.veste.p('avant', 2, 8, '#8a6a40')                  # le manche du marteau dans la poche
    S.veste.p('avant', 2, 7, '#5a5a5a')
    S.veste.p('avant', 5, 8, '#9a9a9a')                  # la truelle
    # Poussière de pierre sur le pantalon et les bottes
    rnd = random.Random(v.get('graine', 3))
    for j in S.jambes:
        for _ in range(4):
            face = rnd.choice(COTES)
            j.p(face, rnd.randrange(4), rnd.randrange(3, 12), melange('#5a5040', '#b0aca2', 0.45))
    if v.get('coiffe') == 'calot':
        bonnet(S, v.get('calot', '#d0c6aa'), hauteur=1)
    elif v.get('coiffe') == 'fichu':
        fichu(S, v['fichu'])
    elif v.get('coiffe') == 'chapeau':
        chapeau_bord(S, '#7a6a50', hauteur=1)


# --- Les variantes ------------------------------------------------------------------------------------------------
P, C, Y = PEAUX, CHEVEUX, YEUX
SALE = ((1, 6), (6, 2), (2, 7))
VARIANTES = {
    'chomeur': (chomeur, [
        dict(peau=P['rose'], cheveux=C['chatain'], coupe='court', barbe='rasee', tunique='#7a6a55', chausses='#5a4a3a',
             piece='#8e8166', piece2='#6e5a44', pieds_nus=True, salete=SALE),
        dict(femme=True, peau=P['brune'], cheveux=C['noir'], coupe='long', yeux=Y['noir'], tunique='#6b6a5c',
             chausses='#4e4636', piece='#7c6650', piece2='#5e5a50', coiffe='capuche'),
        dict(peau=P['claire'], cheveux=C['gris'], coupe='chauve', barbe='pleine', age=True, yeux=Y['bleu'],
             tunique='#857258', chausses='#5e5444', piece='#6a5c48', piece2='#7a7466', coiffe='bonnet'),
    ]),
    'mineur': (mineur, [
        dict(peau=P['claire'], cheveux=C['roux'], coupe='court', barbe='pleine', yeux=Y['vert'], tunique='#4a4a48',
             salete=SALE),
        dict(peau=P['foncee'], cheveux=C['noir'], coupe='court', barbe='courte', tunique='#52463a', casque='#6b4a2a',
             salete=((6, 1), (1, 7))),
        dict(femme=True, peau=P['mate'], cheveux=C['brun'], coupe='tresse', tunique='#3e3e44', coiffe='capuche',
             tablier='#5e4128', salete=((6, 5),)),
    ]),
    'bucheron': (bucheron, [
        dict(peau=P['rose'], cheveux=C['brun'], coupe='court', barbe='pleine', carreaux=('#8a3a2c', '#3f201a'),
             bonnet='#5d6b4a', yeux=Y['brun']),
        dict(peau=P['mate'], cheveux=C['noir'], coupe='frange', barbe='courte', carreaux=('#4c6638', '#24321c'),
             bonnet='#7a4a32', h_bonnet=1),
        dict(femme=True, peau=P['claire'], cheveux=C['blond'], coupe='tresse', yeux=Y['bleu'],
             carreaux=('#44587a', '#232c40'), bonnet='#8a8478', pompon=True, manche=8),
    ]),
    'guerrier': (guerrier, [
        dict(peau=P['rose'], cheveux=C['brun'], coupe='court', barbe='courte', tabard='#8a2626', embleme='#d8c070',
             forme='croix', casque='nasal'),
        dict(peau=P['brune'], cheveux=C['noir'], coupe='court', barbe='moustache', tabard='#2c4078', embleme='#e6e2d6',
             forme='bande', casque='chapel'),
        dict(femme=True, peau=P['claire'], cheveux=C['auburn'], coupe='chignon', yeux=Y['vert'], tabard='#2f5a2c',
             embleme='#d8c070', forme='chevron', casque='camail'),
    ]),
    'fermier': (fermier, [
        dict(peau=P['rose'], cheveux=C['blond'], coupe='court', barbe='rasee', yeux=Y['bleu'], coiffe='paille',
             salopette='#5e4a30'),
        dict(femme=True, peau=P['mate'], cheveux=C['chatain'], coupe='long', coiffe='fichu', fichu='#9a4a3a',
             chemise='#d6c8a4', bas='#6a5a3e', manche=12),
        dict(peau=P['foncee'], cheveux=C['gris'], coupe='court', barbe='courte', couleur_barbe=C['gris'], age=True,
             coiffe='paille', chemise='#b9a782', bas='#4b5a3a'),
    ]),
    'eleveur': (eleveur, [
        dict(peau=P['claire'], cheveux=C['chatain'], coupe='court', barbe='courte', yeux=Y['gris'], tunique='#55683e',
             coiffe='chapeau'),
        dict(femme=True, peau=P['rose'], cheveux=C['roux'], coupe='tresse', yeux=Y['vert'], tunique='#7a5a3a',
             coiffe='fichu', fichu='#5a6a8a', laine='#d9d2c0'),
        dict(peau=P['brune'], cheveux=C['noir'], coupe='frange', tunique='#6a4a3a', coiffe='capuche',
             capuche='#5e5a40', laine='#3a3530'),
    ]),
    'pecheur': (pecheur, [
        dict(peau=P['mate'], cheveux=C['blanc'], coupe='court', barbe='pleine', age=True, yeux=Y['bleu'],
             tricot='#3a5278', coiffe='bonnet', bonnet='#8a3a2c'),
        dict(peau=P['claire'], cheveux=C['roux'], coupe='frange', barbe='rasee', tricot='#5c6470', coiffe='bonnet',
             bonnet='#3a4a5a', manche=8),
        dict(femme=True, peau=P['foncee'], cheveux=C['noir'], coupe='chignon', tricot='#2f5a58', coiffe='chapeau'),
    ]),
    'chasseur': (chasseur, [
        dict(peau=P['rose'], cheveux=C['chatain'], coupe='court', barbe='courte', chemise='#4a5a34', capuche='#3e5230'),
        dict(femme=True, peau=P['claire'], cheveux=C['blond'], coupe='long', yeux=Y['vert'], chemise='#6a5a40',
             capuche='#5a4630', pourpoint='#6a4a2a'),
        dict(peau=P['foncee'], cheveux=C['noir'], coupe='chauve', barbe='courte', chemise='#5a4a3a', capuche='#2f3a2a',
             pourpoint='#5a3a22'),
    ]),
    'cuisinier': (cuisinier, [
        dict(peau=P['rose'], cheveux=C['brun'], coupe='court', barbe='moustache', coiffe='toque', foulard='#8a2a2a'),
        dict(femme=True, peau=P['brune'], cheveux=C['noir'], coupe='chignon', coiffe='fichu', fichu='#c8b878',
             chemise='#c8b8a0', taches=((3, 10), (6, 4))),
        dict(peau=P['claire'], cheveux=C['blond'], coupe='chauve', barbe='pleine', yeux=Y['bleu'], coiffe='toque',
             chemise='#d0c8b0', bas='#4a4a52'),
    ]),
    'transporteur': (transporteur, [
        dict(peau=P['mate'], cheveux=C['brun'], coupe='court', barbe='courte', tunique='#8a6a34', coiffe='chapeau',
             ruban='#7a2a22'),
        dict(peau=P['claire'], cheveux=C['gris'], coupe='court', barbe='moustache', age=True, tunique='#6a3a2e',
             coiffe='capuche', capuche='#4a4a3e', sac='#5a4a32'),
        dict(femme=True, peau=P['brune'], cheveux=C['auburn'], coupe='tresse', tunique='#4a5a6a', coiffe='fichu',
             fichu='#8a7a4a'),
    ]),
    'guerisseur': (guerisseur, [
        dict(peau=P['claire'], cheveux=C['blanc'], coupe='chauve', barbe='pleine', age=True, yeux=Y['gris'],
             robe='#4a5a48'),
        dict(femme=True, peau=P['rose'], cheveux=C['chatain'], coupe='long', yeux=Y['vert'], robe='#5e3a3a',
             coiffe='fichu', fichu='#e0dac8'),
        dict(peau=P['brune'], cheveux=C['noir'], coupe='court', barbe='courte', robe='#3a4458', coiffe='capuche',
             capuche='#2e3646'),
    ]),
    'macon': (macon, [
        dict(peau=P['rose'], cheveux=C['chatain'], coupe='court', barbe='courte', coiffe='calot', graine=1),
        dict(peau=P['foncee'], cheveux=C['noir'], coupe='chauve', barbe='moustache', chemise='#a89c84', graine=2,
             tablier='#6a4a2c'),
        dict(femme=True, peau=P['mate'], cheveux=C['brun'], coupe='chignon', coiffe='fichu', fichu='#6a7a5a',
             chemise='#8e8a80', graine=3),
    ]),
}


def genere():
    skins = []
    for metier, (fn, variantes) in VARIANTES.items():
        for n, v in enumerate(variantes, 1):
            S = Skin(sum(map(ord, metier)) * 10 + n)
            fn(S, v)
            skins.append((f'{metier}_{n}', S.img))
    return skins


# --- Vérifications et aperçu ----------------------------------------------------------------------------------------
BASE = [(0, 0, 8, 8, 8), (16, 16, 8, 12, 4), (40, 16, 4, 12, 4), (32, 48, 4, 12, 4), (0, 16, 4, 12, 4),
        (16, 48, 4, 12, 4)]
COUCHE2 = [(32, 0, 8, 8, 8), (16, 32, 8, 12, 4), (40, 32, 4, 12, 4), (48, 48, 4, 12, 4), (0, 32, 4, 12, 4),
           (0, 48, 4, 12, 4)]


def zones(u, v, w, h, d):
    return [(u + d, v, w, d), (u + d + w, v, w, d), (u, v + d, 2 * d + 2 * w, h)]


def verifie(nom, img):
    """La couche 1 doit être pleine (pas de trou), rien hors des zones, alpha seulement 0 ou 255."""
    px = img.load()
    utile = set()
    for b in BASE + COUCHE2:
        for x0, y0, w, h in zones(*b):
            utile.update((x, y) for x in range(x0, x0 + w) for y in range(y0, y0 + h))
    pleine = set()
    for b in BASE:
        for x0, y0, w, h in zones(*b):
            pleine.update((x, y) for x in range(x0, x0 + w) for y in range(y0, y0 + h))
    erreurs = []
    for x in range(64):
        for y in range(64):
            a = px[x, y][3]
            if a not in (0, 255):
                erreurs.append(f'alpha {a} en {x},{y}')
            if (x, y) in pleine and a != 255:
                erreurs.append(f'trou en {x},{y}')
            if (x, y) not in utile and a != 0:
                erreurs.append(f'pixel hors zone en {x},{y}')
    if erreurs:
        raise SystemExit(f'{nom} : ' + ', '.join(erreurs[:8]))


def face(img, x, y, w, h):
    return img.crop((x, y, x + w, y + h))


def vue(img, dos=False):
    """Rendu à plat 16x32 (face ou dos), couche 2 par-dessus."""
    out = Image.new('RGBA', (16, 32), (0, 0, 0, 0))
    if not dos:
        parties = [((8, 8), (40, 8), 8, 8, (4, 0)), ((20, 20), (20, 36), 8, 12, (4, 8)),
                   ((44, 20), (44, 36), 4, 12, (0, 8)), ((36, 52), (52, 52), 4, 12, (12, 8)),
                   ((4, 20), (4, 36), 4, 12, (4, 20)), ((20, 52), (4, 52), 4, 12, (8, 20))]
    else:
        parties = [((24, 8), (56, 8), 8, 8, (4, 0)), ((32, 20), (32, 36), 8, 12, (4, 8)),
                   ((52, 20), (52, 36), 4, 12, (12, 8)), ((44, 52), (60, 52), 4, 12, (0, 8)),
                   ((12, 20), (12, 36), 4, 12, (8, 20)), ((28, 52), (12, 52), 4, 12, (4, 20))]
    for base, couche, w, h, pos in parties:
        out.alpha_composite(face(img, *base, w, h), pos)
        out.alpha_composite(face(img, *couche, w, h), pos)
    return out


def vue_profil(img):
    """Profil droit 8x32 : côté droit de la tête, du corps, du bras droit et de la jambe droite."""
    out = Image.new('RGBA', (8, 32), (0, 0, 0, 0))
    for base, couche, w, h, pos in [((0, 8), (32, 8), 8, 8, (0, 0)), ((40, 20), (40, 36), 4, 12, (2, 8)),
                                    ((0, 20), (0, 36), 4, 12, (2, 20))]:
        out.alpha_composite(face(img, *base, w, h), pos)
        out.alpha_composite(face(img, *couche, w, h), pos)
    return out


def apercu(skins, chemin, k=4):
    colonnes = 6
    tw, th = (16 + 4 + 16 + 4 + 8) * k + 16, 32 * k + 26
    lignes = (len(skins) + colonnes - 1) // colonnes
    out = Image.new('RGBA', (colonnes * tw + 8, lignes * th + 8), (58, 62, 70, 255))
    d = ImageDraw.Draw(out)
    for i, (nom, img) in enumerate(skins):
        x0, y0 = 8 + (i % colonnes) * tw, 8 + (i // colonnes) * th
        for dx, v in ((0, vue(img)), (20, vue(img, dos=True)), (40, vue_profil(img))):
            out.alpha_composite(v.resize((v.width * k, v.height * k), Image.NEAREST), (x0 + dx * k, y0))
        d.text((x0, y0 + 32 * k + 4), nom, fill=(230, 230, 230, 255))
    out.save(chemin)


def main():
    sortie = sys.argv[1] if len(sys.argv) > 1 else SORTIE
    chemin_apercu = sys.argv[2] if len(sys.argv) > 2 else APERCU
    os.makedirs(sortie, exist_ok=True)
    skins = genere()
    for nom, img in skins:
        verifie(nom, img)
        img.save(os.path.join(sortie, nom + '.png'))
    apercu(skins, chemin_apercu)
    print(f'{len(skins)} skins écrits dans {os.path.normpath(sortie)}, aperçu : {os.path.normpath(chemin_apercu)}')


if __name__ == '__main__':
    main()
