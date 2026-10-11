"""Génère emblem.png (512x512) : un engrenage de laiton autour d'une tour, pour l'icône du Discord et du bot.
python emblem.py 64 icone.png  -> même dessin en 64x64 (icône du serveur dans Minecraft)."""
import math, struct, sys, zlib
from pathlib import Path

SIZE, SS = int(sys.argv[1]) if len(sys.argv) > 1 else 512, 3   # sur-échantillonnage 3x pour l'anticrénelage
K = SIZE / 512                          # le dessin est défini en coordonnées 512
N, R_ROOT, R_TIP, R_HOLE = 12, 168, 205, 112
BRASS, BRASS_DARK, LIGHT = (222, 172, 82), (124, 82, 28), (240, 205, 120)
BG_IN, BG_OUT, INNER = (36, 70, 60), (12, 26, 22), (21, 44, 38)


def mix(a, b, t):
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))


def tooth(theta):                       # profil trapézoïdal d'une dent, 0..1
    t = (theta / (2 * math.pi / N)) % 1
    return min(1, t / 0.1) if t < 0.1 else 1 if t < 0.4 else max(0, (0.5 - t) / 0.1) if t < 0.5 else 0


def tower(x, y):                        # None = hors tour, sinon couleur
    door = (abs(x) <= 13 and y >= 35) or x * x + (y - 35) ** 2 <= 169
    slit = abs(x) <= 4 and -12 <= y <= 10
    body = abs(x) <= 38 and -30 <= y <= 70
    merlon = -58 <= y < -30 and (-38 <= x <= -22 or -8 <= x <= 8 or 22 <= x <= 38)
    ground = 70 < y <= 78 and abs(x) <= 70
    if (body and (door or slit)):
        return INNER
    return LIGHT if body or merlon or ground else None


def pixel(x, y):                        # coordonnées centrées, en pixels finaux
    r, th = math.hypot(x, y), math.atan2(y, x)
    if r < R_HOLE:
        return tower(x, y) or mix(INNER, BG_OUT, r / R_HOLE * 0.5)
    edge = R_ROOT + (R_TIP - R_ROOT) * tooth(th)
    if r <= edge:
        if min(r - R_HOLE, edge - r) < 5:
            return BRASS_DARK
        return mix(BRASS, LIGHT, max(0, math.cos(th + 2.3)) * 0.6)
    return mix(BG_IN, BG_OUT, min(1, r / 360))


rows = []
for py in range(SIZE):
    row = bytearray(b"\x00")
    for px in range(SIZE):
        acc = [0, 0, 0]
        for sy in range(SS):
            for sx in range(SS):
                c = pixel((px + (sx + .5) / SS - SIZE / 2) / K, (py + (sy + .5) / SS - SIZE / 2) / K)
                acc = [a + b for a, b in zip(acc, c)]
        row += bytes(v // (SS * SS) for v in acc)
    rows.append(bytes(row))


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)) \
      + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
out = Path(__file__).parent / (sys.argv[2] if len(sys.argv) > 2 else "emblem.png")
out.write_bytes(png)
print(out.name, len(png), "octets")
