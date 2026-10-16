"""Dessine la cabane d'exemple du maçon : src/main/resources/schemas/cabane.nbt (format des blocs de structure, Minecraft 1.21.1).

5 x 5 au sol, 4 de haut : sol de pierre taillée, murs de planches de chêne, bûches aux coins, porte au sud (face au chef qui
regarde au nord en disant « construis la cabane ici »), une vitre à l'est et à l'ouest, une torche au mur nord, toit de planches.
Lancer : python3 outils/cabane_nbt.py
"""
import gzip
import os
import struct

DATA_VERSION = 3955  # Minecraft 1.21.1


def s(x):
    b = x.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag(t, nom, charge):
    return bytes([t]) + s(nom) + charge


def compound(items):  # items : liste de (type, nom, charge)
    return b"".join(tag(t, n, c) for t, n, c in items) + b"\x00"


def liste(t, elems):
    return bytes([t]) + struct.pack(">i", len(elems)) + b"".join(elems)


def i32(v):
    return struct.pack(">i", v)


palette, index = [], {}


def etat(nom, **props):
    cle = (nom, tuple(sorted(props.items())))
    if cle not in index:
        index[cle] = len(palette)
        palette.append(cle)
    return index[cle]


blocs = {}
SX, SY, SZ = 5, 4, 5
for x in range(SX):
    for z in range(SZ):
        blocs[(x, 0, z)] = etat("minecraft:cobblestone")
        blocs[(x, 3, z)] = etat("minecraft:oak_planks")
        for y in (1, 2):
            bord = x in (0, SX - 1) or z in (0, SZ - 1)
            coin = x in (0, SX - 1) and z in (0, SZ - 1)
            if coin:
                blocs[(x, y, z)] = etat("minecraft:oak_log", axis="y")
            elif bord:
                blocs[(x, y, z)] = etat("minecraft:oak_planks")
            else:
                blocs[(x, y, z)] = etat("minecraft:air")
# La porte au milieu du mur sud.
porte = dict(facing="north", hinge="left", open="false", powered="false")
blocs[(2, 1, 4)] = etat("minecraft:oak_door", half="lower", **porte)
blocs[(2, 2, 4)] = etat("minecraft:oak_door", half="upper", **porte)
# Les vitres, à l'ouest et à l'est.
vitre = dict(east="false", west="false", north="true", south="true", waterlogged="false")
blocs[(0, 2, 2)] = etat("minecraft:glass_pane", **vitre)
blocs[(4, 2, 2)] = etat("minecraft:glass_pane", **vitre)
# La torche, au mur nord, dedans.
blocs[(2, 2, 1)] = etat("minecraft:wall_torch", facing="south")

pal = []
for nom, props in palette:
    items = [(8, "Name", s(nom))]
    if props:
        items.append((10, "Properties", compound([(8, k, s(v)) for k, v in props])))
    pal.append(compound(items))
bl = []
for (x, y, z), st in sorted(blocs.items()):
    bl.append(compound([(9, "pos", liste(3, [i32(x), i32(y), i32(z)])), (3, "state", i32(st))]))
racine = tag(10, "", compound([
    (3, "DataVersion", i32(DATA_VERSION)),
    (9, "size", liste(3, [i32(SX), i32(SY), i32(SZ)])),
    (9, "palette", liste(10, pal)),
    (9, "blocks", liste(10, bl)),
    (9, "entities", liste(10, [])),
]))
sortie = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "schemas", "cabane.nbt")
os.makedirs(os.path.dirname(sortie), exist_ok=True)
with gzip.open(sortie, "wb") as f:
    f.write(racine)
print(sortie, len(blocs), "blocs,", len(palette), "états")
