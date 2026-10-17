"""Événement « Le Convoi de l'Intendant » : annonce, boussole, apparition à l'approche, laisse de 20 blocs, barre de vie, victoire."""
import re, subprocess, sys, time

ROOT = str(__import__("pathlib").Path(__file__).parent.parent.parent)
X, Y, Z = -578, 65, -570
J = "Nielscutt"
ICI = f"x={X},y={Y},z={Z}"


def cmd(c):
    subprocess.run([sys.executable, ROOT + r"\tools\panel.py", "cmd", c], capture_output=True)


def lire(c, motif):  # lance une commande et lit sa réponse dans le journal
    cmd(c)
    time.sleep(0.6)
    r = subprocess.run([sys.executable, ROOT + r"\tools\panel.py", "cat", "/logs/latest.log", "8"], capture_output=True, text=True, encoding="utf-8")
    m = re.findall(motif, r.stdout)
    return m[-1] if m else None


def score(nom):
    v = lire(f"scoreboard players get {nom} convoi", rf"{re.escape(nom)} has (\d+) \[convoi\]")
    return int(v) if v else -1


def log(t):
    print(time.strftime("%H:%M:%S"), t, flush=True)


# --- 1. Annonce et boussole
cmd("scoreboard objectives add convoi dummy")
cmd(f'title {J} times 10 80 20')
cmd(f'title {J} subtitle {{"text":"Une cargaison précieuse gît à l\'ouest…","color":"gray","italic":true}}')
cmd(f'title {J} title {{"text":"Le convoi de l\'Intendant a été attaqué !","color":"gold","bold":true}}')
cmd(f"execute at {J} run playsound minecraft:block.bell.use master {J} ~ ~ ~ 1 0.8")
cmd(f"execute at {J} run playsound minecraft:event.raid.horn master {J} ~ ~ ~ 0.6 1")
cmd(f'tellraw {J} ["",{{"text":"[Intendant] ","color":"gold"}},{{"text":"Des pillards ont pris le convoi qui venait vers la colonie. Retrouve la cargaison, la boussole te guide. Méfie-toi : leur chef, '
    f'le Borgne, n\'est pas loin. La cargaison est sous clé… et c\'est lui qui l\'a.","color":"yellow"}}]')
cmd(f'give {J} compass[lodestone_tracker={{target:{{pos:[I;{X},{Y},{Z}],dimension:"minecraft:overworld"}},tracked:false}},'
    f'custom_name=\'{{"text":"Boussole de l\\\'Intendant","color":"gold","italic":false}}\','
    f'lore=[\'{{"text":"Elle pointe vers le convoi attaqué.","color":"gray"}}\']] 1')
log("annonce et boussole envoyées")

# --- 2. Attente de l'approche (40 blocs : assez loin pour ne pas voir l'apparition derrière le décor)
debut = time.time()
while True:
    cmd(f"execute store result score #proche convoi if entity @a[name={J},{ICI},distance=..40]")
    if score("#proche") == 1:
        break
    if time.time() - debut > 3600:
        log("abandon : pas venu en 1 h"); sys.exit()
    time.sleep(3)
log("Nielscutt approche : apparition")

cle = ('{id:"minecraft:tripwire_hook",count:1,components:{"minecraft:custom_name":\'{"text":"Clé du convoi","color":"gold","italic":false}\','
       '"minecraft:lore":[\'{"text":"Elle ouvre la cargaison du convoi.","color":"gray"}\']}}')
base = 'PersistenceRequired:1b,CanPickUpLoot:0b,Tags:["pillard_convoi"'
cmd(f'summon vindicator {X - 3} {Y} {Z} {{{base},"borgne"],CustomName:\'{{"text":"Le Borgne","color":"red","bold":true}}\',CustomNameVisible:1b,'
    f'Health:60f,attributes:[{{id:"minecraft:generic.max_health",base:60d}},{{id:"minecraft:generic.attack_damage",base:6d}}],'
    f'HandItems:[{{id:"minecraft:iron_axe",count:1}},{cle}],HandDropChances:[0f,1f],'
    f'ArmorItems:[{{}},{{}},{{id:"minecraft:iron_chestplate",count:1}},{{id:"minecraft:iron_helmet",count:1}}],ArmorDropChances:[0f,0f,0f,0f]}}')
for dx, dz in ((2, 3), (-2, -3), (3, -2)):
    cmd(f'summon pillager {X + dx} {Y} {Z + dz} {{{base}],HandItems:[{{id:"minecraft:crossbow",count:1}},{{}}],HandDropChances:[0f,0f]}}')
cmd('bossbar add colonie:borgne {"text":"Le Borgne, chef des pillards","color":"red"}')
cmd("bossbar set colonie:borgne color red")
cmd("bossbar set colonie:borgne max 60")
cmd("bossbar set colonie:borgne value 60")
cmd(f"bossbar set colonie:borgne players @a[name={J}]")
cmd(f"execute at {J} run playsound minecraft:entity.pillager.celebrate hostile {J} ~ ~ ~ 1 0.9")

# --- 3. Combat : laisse de 20 blocs et barre de vie
debut = time.time()
while True:
    cmd(f"execute as @e[tag=pillard_convoi] at @s unless entity @s[{ICI},distance=..20] facing {X} {Y} {Z} run tp @s ^ ^ ^3")
    cmd("execute store result bossbar colonie:borgne value run data get entity @e[tag=borgne,limit=1] Health")
    cmd("execute store result score #vivant convoi if entity @e[tag=borgne]")
    if score("#vivant") == 0:
        break
    if time.time() - debut > 3600:
        log("abandon : combat non fini en 1 h"); sys.exit()
    time.sleep(2)
log("le Borgne est tombé")

# --- 4. Victoire
cmd("bossbar remove colonie:borgne")
cmd(f'title {J} subtitle {{"text":"La clé du Borgne ouvre la cargaison.","color":"gray","italic":true}}')
cmd(f'title {J} title {{"text":"Le convoi est sauvé !","color":"gold","bold":true}}')
cmd(f"execute at {J} run playsound minecraft:ui.toast.challenge_complete master {J} ~ ~ ~ 1 1")
cmd(f'tellraw @a ["",{{"text":"[Intendant] ","color":"gold"}},{{"text":"{J} a abattu le Borgne et sauvé le convoi de l\'Intendant !","color":"yellow"}}]')
# les pillards survivants restent tenus à la scène encore 5 minutes
fin = time.time() + 300
while time.time() < fin:
    cmd(f"execute as @e[tag=pillard_convoi] at @s unless entity @s[{ICI},distance=..20] facing {X} {Y} {Z} run tp @s ^ ^ ^3")
    time.sleep(3)
log("fin de l'événement")
