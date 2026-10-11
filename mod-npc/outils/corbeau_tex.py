"""Texture 64x32 du corbeau voyageur (colonie_pnj), au format des modèles d'entité de Minecraft.

Chaque boîte du modèle (voir Client.ModeleCorbeau) occupe une zone de la texture : on la remplit de plumes noires
aux reflets bleutés, puis on ajoute les yeux, le bec, les pattes et la lettre (papier et cachet de cire).
Usage : python outils/corbeau_tex.py [chemin de sortie] [aperçu agrandi]
"""
import random
import sys
from PIL import Image

random.seed(7)
W, H = 64, 32
img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
px = img.load()

PLUMES = ['#141418', '#18181d', '#1c1d23', '#202128', '#23252e']
REFLETS = ['#2a3040', '#303850', '#26304a']


def hexa(c):
    c = c.lstrip('#')
    return tuple(int(c[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def zone(u, v, w, h, couleurs, reflets=0.0):
    for x in range(u, u + w):
        for y in range(v, v + h):
            c = random.choice(REFLETS) if random.random() < reflets else random.choice(couleurs)
            px[x, y] = hexa(c)


def boite(u, v, w, h, d, couleurs, reflets=0.0):
    """Toute la zone d'une boîte w x h x d posée en (u, v) : (2d + 2w) x (d + h)."""
    zone(u, v, 2 * d + 2 * w, d + h, couleurs, reflets)


# Corps (4 x 4 x 8), plumes avec des reflets sur le dos
boite(0, 0, 4, 4, 8, PLUMES, 0.18)
zone(8, 0, 4, 8, PLUMES, 0.35)              # le dos, plus brillant
# Tête (3 x 3 x 3) et ses yeux, de chaque côté
boite(0, 12, 3, 3, 3, PLUMES, 0.15)
for x, y in ((1, 16), (7, 16)):
    px[x, y] = hexa('#050505')
px[1, 15] = hexa('#9a9aa0')                  # un éclat dans l'œil
px[7, 15] = hexa('#9a9aa0')
# Bec (1 x 1 x 2), gris sombre
boite(12, 12, 1, 1, 2, ['#2e2e30', '#3a3a3d', '#454548'])
# Queue (3 x 1 x 5), plumes plus longues avec un liseré
boite(0, 18, 3, 1, 5, PLUMES, 0.25)
for x in range(5, 8):
    px[x, 22] = hexa('#2c3346')
# Ailes (1 x 3 x 7), rémiges marquées
boite(24, 0, 1, 3, 7, PLUMES, 0.2)
for x in range(24, 40, 2):
    for y in range(7, 10):
        px[x, y] = hexa('#101014')
# Pattes (1 x 3 x 1)
boite(24, 12, 1, 3, 1, ['#2a2622', '#332e29'])
# La lettre (3 x 2 x 1) : papier crème et cachet de cire rouge
boite(28, 12, 3, 2, 1, ['#efe6cc', '#e9dfc2', '#f4ecd6'])
px[30, 13] = hexa('#a3281f')
px[30, 14] = hexa('#7d1c15')
px[34, 13] = hexa('#a3281f')

sortie = sys.argv[1] if len(sys.argv) > 1 else 'corbeau.png'
img.save(sortie)
if len(sys.argv) > 2:
    img.resize((W * 8, H * 8), Image.NEAREST).save(sys.argv[2])
print('écrit :', sortie)
