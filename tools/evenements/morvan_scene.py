"""Événement « La Crypte de Morvan » : construit la tour, la crypte, les leviers ; écrit morvan_pos.json (positions des leviers)."""
import json, re, subprocess, sys, time
from pathlib import Path

X0, Y0, Z0 = -763, 112, -674          # pied de la tour (Y0 = premier bloc d'air au-dessus du sol)
PANEL = str(Path(__file__).parent.parent / "panel.py")


def cmd(c):
    subprocess.run([sys.executable, PANEL, "cmd", c], capture_output=True)


def fill(a, b, bloc):
    cmd(f"fill {a[0]} {a[1]} {a[2]} {b[0]} {b[1]} {b[2]} {bloc}")


def sol(x, z):  # hauteur du premier bloc d'air à cette colonne
    cmd(f'execute positioned {x} 0 {z} positioned over motion_blocking_no_leaves run summon marker ~ ~ ~ {{Tags:["morvan_sol"]}}')
    cmd("execute as @e[tag=morvan_sol] run data get entity @s Pos")
    time.sleep(2)
    out = subprocess.run([sys.executable, PANEL, "cat", "/logs/latest.log", "6"], capture_output=True, text=True, encoding="utf-8").stdout
    cmd("kill @e[tag=morvan_sol]")
    return int(float(re.findall(r"\[-?[\d.]+d, (-?[\d.]+)d, -?[\d.]+d\]", out)[-1]))


X, Y, Z = X0, Y0, Z0
for dx in (-16, 0, 16):
    for dz in (-16, 0, 16):
        cmd(f"forceload add {X + dx} {Z + dz}")
time.sleep(6)

# --- la tour (7 x 7, 12 de haut), en ruine
fill((X - 6, Y, Z - 6), (X + 6, Y + 24, Z + 6), "air")
fill((X - 3, Y - 8, Z - 3), (X + 3, Y - 1, Z + 3), "stone_bricks")
fill((X - 3, Y, Z - 3), (X + 3, Y + 11, Z + 3), "stone_bricks hollow")
fill((X - 3, Y, Z - 3), (X - 3, Y + 11, Z + 3), "mossy_stone_bricks replace stone_bricks")
fill((X - 3, Y, Z - 3), (X + 3, Y + 11, Z - 3), "cracked_stone_bricks replace stone_bricks")
fill((X + 2, Y + 8, Z + 3), (X + 3, Y + 11, Z + 3), "air")                     # coin effondré
fill((X + 3, Y + 9, Z + 2), (X + 3, Y + 11, Z + 2), "air")
cmd(f"setblock {X + 2} {Y} {Z + 2} cobblestone")                              # gravats
cmd(f"setblock {X - 2} {Y} {Z + 2} mossy_cobblestone")
fill((X, Y, Z + 3), (X, Y + 1, Z + 3), "air")                                  # porte au sud
cmd(f"setblock {X + 3} {Y + 6} {Z - 1} air")                                   # les yeux, à l'est
cmd(f"setblock {X + 3} {Y + 6} {Z + 1} air")
# le signal : balise rouge au sommet
fill((X - 1, Y + 11, Z - 1), (X + 1, Y + 11, Z + 1), "iron_block")
cmd(f"setblock {X} {Y + 12} {Z} beacon")
cmd(f"setblock {X} {Y + 13} {Z} red_stained_glass")

# --- la crypte (17 x 10 x 17), sous la tour
C = (X, Y - 19, Z)                                                             # centre, au sol de la crypte
fill((X - 8, Y - 20, Z - 8), (X + 8, Y - 11, Z + 8), "deepslate_bricks hollow")
fill((X - 7, Y - 20, Z - 7), (X + 7, Y - 20, Z + 7), "polished_deepslate")
for dx, dz in ((-5, -5), (5, -5), (-5, 5), (5, 5), (0, -6), (0, 6), (-6, 0), (6, 0)):
    cmd(f"setblock {X + dx} {Y - 12} {Z + dz} soul_lantern[hanging=true]")
for dx, dz in ((-6, -6), (6, -6), (-6, 6), (6, 6)):                            # tombes
    cmd(f"setblock {X + dx} {Y - 19} {Z + dz} chiseled_deepslate")
    cmd(f"setblock {X + dx} {Y - 18} {Z + dz} skeleton_skull")
for dx in (-2, 2):
    fill((X + dx, Y - 19, Z - 7), (X + dx, Y - 12, Z - 7), "polished_blackstone_bricks")
cmd(f"setblock {X} {Y - 18} {Z - 7} wither_skeleton_wall_skull[facing=south]")

# --- le puits : manchon, échelle, grille (barreaux + barrière invisible, retirés quand l'énigme est résolue)
fill((X, Y - 10, Z), (X + 2, Y - 1, Z + 2), "stone_bricks")
fill((X + 1, Y - 11, Z + 1), (X + 1, Y - 1, Z + 1), "air")
fill((X + 1, Y - 11, Z + 1), (X + 1, Y - 3, Z + 1), "ladder[facing=south]")
fill((X + 1, Y - 19, Z), (X + 1, Y - 12, Z), "deepslate_bricks")              # pilier pour finir la descente
fill((X + 1, Y - 19, Z + 1), (X + 1, Y - 12, Z + 1), "ladder[facing=south]")
cmd(f"setblock {X + 1} {Y - 1} {Z + 1} iron_bars")
cmd(f"setblock {X + 1} {Y - 2} {Z + 1} barrier")

# --- le journal du guetteur, sur un pupitre
def page(t):
    return "'" + json.dumps({"text": t}, ensure_ascii=False).replace("\\", "\\\\") + "'"
pages = ",".join(page(t) for t in [
    "Jour 12.\n\nDes bruits sous la tour, la nuit. On dit qu’une crypte dort sous nos pieds : celle de Morvan, le nécromancien.",
    "Jour 13.\n\nLa grille de la crypte s’est refermée seule. Trois leviers l’ouvrent. Je les ai cachés :\n\n- la pierre qui pleure\n- là où dort le corbeau\n- sous les yeux de la tour",
    "Jour 14.\n\nIl est réveillé. J’allume le signal.\n\nSi quelqu’un lit ceci : descends, et finis-le.\n\n— Le guetteur"])
cmd(f'setblock {X - 1} {Y} {Z - 1} lectern[facing=south,has_book=true]{{Book:{{id:"minecraft:written_book",count:1,components:'
    f'{{"minecraft:written_book_content":{{title:"Journal du guetteur",author:"Le guetteur",pages:[{pages}]}}}}}}}}')

# --- les trois leviers
# 3. sous les yeux de la tour : mur est, sous les fenêtres
L3 = (X + 4, Y + 1, Z)
cmd(f"setblock {L3[0]} {L3[1]} {L3[2]} lever[face=wall,facing=east]")
# 1. la pierre qui pleure : obsidienne pleureuse sur un socle, à l'ouest
x1, z1 = X - 10, Z + 4
y1 = sol(x1, z1)
fill((x1, y1 - 3, z1), (x1, y1 - 1, z1), "mossy_cobblestone")
cmd(f"setblock {x1} {y1} {z1} crying_obsidian")
fill((x1, y1, z1 + 1), (x1, y1 + 1, z1 + 1), "air")
L1 = (x1, y1, z1 + 1)
cmd(f"setblock {L1[0]} {L1[1]} {L1[2]} lever[face=wall,facing=south]")
# 2. là où dort le corbeau : souche de chêne noir, bannière noire, levier derrière
x2, z2 = X + 8, Z - 9
y2 = sol(x2, z2)
fill((x2, y2, z2), (x2, y2 + 1, z2), "dark_oak_log")
cmd(f"setblock {x2} {y2 + 2} {z2} dead_bush")
fill((x2, y2, z2 + 1), (x2, y2 + 1, z2 + 1), "air")
cmd(f"setblock {x2} {y2 + 1} {z2 + 1} black_wall_banner[facing=south]")
fill((x2, y2, z2 - 1), (x2, y2 + 1, z2 - 1), "air")
L2 = (x2, y2, z2 - 1)
cmd(f"setblock {L2[0]} {L2[1]} {L2[2]} lever[face=wall,facing=north]")

# --- le coffre de Morvan
butin = ('[{Slot:10b,id:"numismatics:cog",count:4},'
         '{Slot:12b,id:"minecraft:enchanted_book",count:1,components:{"minecraft:stored_enchantments":{levels:{"minecraft:mending":1}}}},'
         '{Slot:14b,id:"minecraft:golden_apple",count:2},{Slot:16b,id:"minecraft:cooked_beef",count:16},'
         '{Slot:22b,id:"minecraft:blaze_rod",count:1,components:{'
         '"minecraft:custom_name":\'{"text":"Bâton de Morvan","color":"dark_purple","italic":false}\','
         '"minecraft:lore":[\'{"text":"Arraché au nécromancien de la crypte.","color":"gray","italic":true}\'],'
         '"minecraft:enchantments":{levels:{"minecraft:knockback":2,"minecraft:fire_aspect":1}}}}]')
cmd(f'setblock {X} {Y - 19} {Z - 6} chest[facing=south]{{CustomName:\'{{"text":"Trésor de Morvan","color":"dark_purple"}}\','
    f'Lock:"Sceau de Morvan",Items:{butin}}}')

for dx in (-16, 0, 16):
    for dz in (-16, 0, 16):
        cmd(f"forceload remove {X + dx} {Z + dz}")
pos = {"X": X, "Y": Y, "Z": Z, "C": C, "L1": L1, "L2": L2, "L3": L3}
(Path(__file__).parent / "morvan_pos.json").write_text(json.dumps(pos), encoding="utf-8")
print("scène posée", pos)
