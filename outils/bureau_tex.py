"""Textures 16x16 du bureau (colonie_pnj), dessinées pixel par pixel avec la palette des blocs vanilla."""
from PIL import Image
import sys

PAL = {
    '0': '#291a0c', '1': '#3a2411', '2': '#3e2912', '3': '#492f17', '4': '#4f3218', '5': '#53381a',   # chêne noir
    's': '#614b2e', 't': '#70522e', 'u': '#7a5a34', 'v': '#82613a', 'w': '#886539',                   # sapin
    'K': '#161005',                                                                                    # encre / fente
    'P': '#fcfcf2', 'p': '#f9e8bf', 'q': '#bca160',                                                    # papier
    'L': '#391003', 'l': '#5d2210', 'm': '#75321e',                                                    # cuir du registre
    'Y': '#f9f969', 'y': '#e9b115', 'o': '#dc9613', 'b': '#7f520c', 'n': '#6e470b',                   # laiton / pièces
    'W': '#dedede', 'x': '#999999',                                                                    # plume
    'G': '#1e2433', 'h': '#4a5878',                                                                    # encrier
}

def surface():
    # Plateau en planches de sapin horizontales, cadre en chêne noir.
    g = []
    for r in range(16):
        if r in (0, 15):
            g.append('0' + '2' * 14 + '0')
            continue
        if r % 4 == 0:
            row = 's' * 14
        else:
            base = 'uvuuvuuuvuuvuu' if r % 2 else 'uutuuuvuuutuuu'
            row = base
        g.append('2' + row + '2')
    return [list(x) for x in g]

def poser(g, x, y, sprite):
    for dy, ligne in enumerate(sprite):
        for dx, c in enumerate(ligne):
            if c != ' ':
                g[y + dy][x + dx] = c

def dessus():
    g = surface()
    poser(g, 2, 3, [            # registre ouvert, reliure de cuir
        "LlllllllL",
        "lPPPmPPpl",
        "lxxqmxxql",
        "lPPPmPPPl",
        "lqxxmqxxl",
        "lpPPmPPpl",
        "LlllLlllL",
    ])
    poser(g, 11, 1, [           # plume plantée dans l'encrier
        "   W",
        "  Wx",
        " x  ",
        "xKx ",
        "GhG ",
        "GGG ",
    ])
    poser(g, 9, 10, [           # quelques pièces
        " nn   ",
        "nYyn  ",
        "nyon n",
        " nn nY",
        "    nn",
    ])
    return g

COTE = [
    "1111111111111111",
    "0333333333333330",
    "2444444444444442",
    "24y4444444444y42",
    "2444oooooooo4442",
    "2444oKKKKKKo4442",
    "2444nbbbbbbn4442",
    "24y4444444444y42",
    "2000000000000002",
    "2ssssssssssssss2",
    "2suvvvussuvvvus2",
    "2suvvvyssyvvvus2",
    "2sutttbssbtttus2",
    "2sutttussutttus2",
    "2ssssssssssssss2",
    "0000000000000000",
]

def image(grille):
    im = Image.new('RGBA', (16, 16))
    for y, ligne in enumerate(grille):
        assert len(ligne) == 16, (y, ''.join(ligne))
        for x, c in enumerate(ligne):
            im.putpixel((x, y), Image.new('RGB', (1, 1), PAL[c]).getpixel((0, 0)) + (255,))
    return im

if __name__ == '__main__':
    out = sys.argv[1]
    image(dessus()).save(f'{out}/bureau_dessus.png')
    image([list(r) for r in COTE]).save(f'{out}/bureau_cote.png')
    print('ok')
