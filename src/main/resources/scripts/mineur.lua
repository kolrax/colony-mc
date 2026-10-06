-- ============================================================================
-- Mineur (autonome)
-- Commande : un minerai ou une pierre. Il se débrouille seul :
--   1. il extrait les filons qu'il voit autour de lui (le minerai demandé d'abord) ;
--   2. sinon, il choisit un sol naturel le plus près possible du bureau (jamais dans
--      une construction ; s'il n'y en a pas, il en cherche plus loin) et creuse un
--      escalier en colimaçon jusqu'à la profondeur du minerai ;
--   3. là, il mine en galeries parallèles espacées de 3 blocs, comme un joueur.
-- Il pose une torche tous les 8 blocs s'il en a, comble les trous du sol avec de la
-- pierre et abandonne une galerie devant la lave ou l'eau. S'il lui faut une meilleure
-- pioche (fer pour le diamant), il vient la demander.
-- ============================================================================
local M = {
  nom = "Mineur",
  description = "Trouve seul le minerai demandé : filons, escalier jusqu'à la bonne profondeur, galeries parallèles.",
}

M.garder = { "nourriture", "minecraft:torch", "#minecraft:pickaxes", "#c:cobblestones" }

local PIOCHE = "#minecraft:pickaxes"
local PIERRE_OU_MIEUX = "minecraft:stone_pickaxe|minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe"
local FER_OU_MIEUX = "minecraft:iron_pickaxe|minecraft:diamond_pickaxe|minecraft:netherite_pickaxe"

-- Le catalogue des commandes (écran du bureau). « profondeur » : hauteur (Y) où il creuse ses galeries.
M.commandes = {
  { id = "charbon",  nom = "Charbon",           objet = "minecraft:coal",          blocs = "#c:ores/coal",     profondeur = 48 },
  { id = "fer",      nom = "Fer",               objet = "minecraft:raw_iron",      blocs = "#c:ores/iron",     profondeur = 16,  outil = PIERRE_OU_MIEUX },
  { id = "cuivre",   nom = "Cuivre",            objet = "minecraft:raw_copper",    blocs = "#c:ores/copper",   profondeur = 48,  outil = PIERRE_OU_MIEUX },
  { id = "zinc",     nom = "Zinc (Create)",     objet = "create:raw_zinc",         blocs = "#c:ores/zinc",     profondeur = 10,  outil = PIERRE_OU_MIEUX },
  { id = "or",       nom = "Or",                objet = "minecraft:raw_gold",      blocs = "#c:ores/gold",     profondeur = -16, outil = FER_OU_MIEUX },
  { id = "lapis",    nom = "Lapis-lazuli",      objet = "minecraft:lapis_lazuli",  blocs = "#c:ores/lapis",    profondeur = 0,   outil = PIERRE_OU_MIEUX },
  { id = "redstone", nom = "Redstone",          objet = "minecraft:redstone",      blocs = "#c:ores/redstone", profondeur = -58, outil = FER_OU_MIEUX },
  { id = "diamant",  nom = "Diamant",           objet = "minecraft:diamond",       blocs = "#c:ores/diamond",  profondeur = -58, outil = FER_OU_MIEUX },
  { id = "pierre",   nom = "Pierre",            objet = "#c:cobblestones",         blocs = "minecraft:stone",  profondeur = 50 },
  { id = "andesite", nom = "Andésite",          objet = "minecraft:andesite",      blocs = "minecraft:andesite", profondeur = 40 },
  { id = "granite",  nom = "Granite",           objet = "minecraft:granite",       blocs = "minecraft:granite",  profondeur = 40 },
  { id = "diorite",  nom = "Diorite",           objet = "minecraft:diorite",       blocs = "minecraft:diorite",  profondeur = 40 },
  { id = "abimes",   nom = "Ardoise des abîmes", objet = "minecraft:cobbled_deepslate", blocs = "minecraft:deepslate", profondeur = -20 },
}

M.palier = 6        -- l'escalier tourne tous les 6 blocs : il reste dans un carré de 6 x 6
M.galerie = 24      -- longueur d'une galerie
M.galeries = 8      -- galeries de chaque côté du couloir
M.torches = 8

local DIRS = { {1, 0}, {0, 1}, {-1, 0}, {0, -1} }

local function cle(x, y, z) return x .. "," .. y .. "," .. z end

local function casser(pnj, m, x, y, z)
  m.cible = cle(x, y, z)
  return pnj.casser(x, y, z)
end

local function echecSur(pnj, m, x, y, z)
  local r = pnj.resultat()
  return m.cible == cle(x, y, z) and r ~= "" and r ~= "ok"
end

-- Prépare une case de tunnel (pieds en x, y, z ; « haut » blocs de hauteur) :
-- renvoie "action" si une action a été lancée, "liquide" ou "bloque" en cas de problème, nil si la case est prête.
local function creuserCase(pnj, m, x, y, z, haut)
  for h = -1, haut do
    for _, d in ipairs({ {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1} }) do
      if pnj.liquide(x + d[1], y + h, z + d[2]) then return "liquide" end
    end
  end
  for h = 0, haut - 1 do
    if not pnj.libre(x, y + h, z) then
      if echecSur(pnj, m, x, y + h, z) then return "bloque" end
      casser(pnj, m, x, y + h, z)
      return "action"
    end
  end
  if pnj.libre(x, y - 1, z) then
    if echecSur(pnj, m, x, y - 1, z) then return "bloque" end
    if pnj.a("#c:cobblestones") then
      m.cible = cle(x, y - 1, z)
      pnj.poser(x, y - 1, z, "#c:cobblestones")
      return "action"
    end
    pnj.demander("Il y a un trou sous mes pieds : il me faudrait de la pierre (cobblestone) pour le combler.", "#c:cobblestones", 8)
    return "action"
  end
  return nil
end

-- Avance d'une case prête, en posant une torche de temps en temps.
local function avancer(pnj, m, commun, x, y, z, compteur)
  if compteur % M.torches == 0 and pnj.lumiere(x, y + 1, z) < 8 then
    if pnj.a("minecraft:torch") then
      m.torche = (m.torche or 0) + 1
      if m.torche % 2 == 1 then return pnj.poser(x, y + 1, z, "minecraft:torch") end
    else
      commun.parfois(pnj, "torches", "Je n'ai plus de torches, chef : mes galeries vont rester sombres.", true, 1800)
    end
  end
  m.torche = 0
  return pnj.aller(x, y, z, 0.7)
end

-- Position de la marche k de l'escalier en colimaçon (k = 0 : l'entrée, en surface).
local function marche(m, k)
  local x, z = m.puits.x, m.puits.z
  for i = 1, k do
    local d = DIRS[(math.floor((i - 1) / M.palier) % 4) + 1]
    x, z = x + d[1], z + d[2]
  end
  return x, m.puits.y - k, z
end

local function nouveauPuits(pnj, m, commun)
  -- Le plus près possible du bureau : il aura moins de marche pour rapporter le minerai.
  local x, y, z = pnj.solNaturel(64)
  if not x and not pnj.chezLui() then         -- en route vers plus loin : autour de lui
    local px, py, pz = pnj.position()
    x, y, z = pnj.solNaturel(24, { x = px, y = py, z = pz })
  end
  if not x then                               -- tout est construit autour du bureau : il cherche plus loin
    local ex, ey, ez, raison = commun.pointAExplorer(pnj, "puits")
    if ex then return pnj.allerVers(ex, ez, 4) end
    if raison == "tout_fouille" then
      commun.parfois(pnj, "pas_de_sol", "Je ne trouve aucun sol naturel où creuser sans abîmer de construction.", true, 1800)
    end
    if not pnj.chezLui() then return pnj.rentrer() end
    return pnj.attendre(30)
  end
  m.puits = { x = x, y = y, z = z }
  m.marche, m.fond, m.cote, m.branche, m.couloir, m.pas = 0, nil, 1, 1, 0, 0
  return pnj.aller(x, y, z, 2)
end

function M.travailler(pnj, commun)
  local m = pnj.memoire
  local c = pnj.commande()
  if not c then
    commun.parfois(pnj, "sans_commande", "Je n'ai pas de commande, chef : choisis ce que je dois miner dans l'écran du bureau.", true, 1800)
    return pnj.attendre(20)
  end

  -- Nouvelle commande ou nouveau bureau : on repart de zéro.
  local bx, by, bz = pnj.bureau()
  local chantier = c.id .. "@" .. cle(bx, by, bz) .. "/" .. (c.profondeur or 0)
  if m.chantier ~= chantier then
    m.chantier, m.puits, m.ignores, m.cible = chantier, nil, {}, nil
  end
  m.ignores = m.ignores or {}

  -- La bonne pioche.
  local outil = c.outil or PIOCHE
  if not pnj.equiper(outil) then
    if pnj.a(PIOCHE) and c.outil then
      return pnj.demander("Pour le " .. c.nom:lower() .. ", il me faut une meilleure pioche (en fer ou mieux).", outil, 1)
    end
    return pnj.demander("Il me faut une pioche pour miner.", outil, 1)
  end

  -- 1. Les filons qu'il voit autour de lui : le minerai demandé d'abord, puis tout autre minerai récoltable.
  for _, f in ipairs({ { c.blocs, 3 }, { "#c:ores", 2 } }) do
    local fx, fy, fz = pnj.chercherBloc(f[1], f[2], { expose = true, recoltable = true })
    if fx and not m.ignores[cle(fx, fy, fz)] then
      if echecSur(pnj, m, fx, fy, fz) then m.ignores[cle(fx, fy, fz)] = true
      else return casser(pnj, m, fx, fy, fz) end
    end
  end

  -- 2. Pas encore de puits : il en choisit l'entrée, sur un sol naturel.
  if not m.puits then return nouveauPuits(pnj, m, commun) end
  local cible = math.max(-59, c.profondeur or 0)

  -- 3. L'escalier, marche après marche, jusqu'à la profondeur du minerai.
  if not m.fond then
    local k = m.marche + 1
    local x, y, z = marche(m, k)
    if m.puits.y - m.marche <= cible then
      local fx, fy, fz = marche(m, m.marche)
      m.fond = { x = fx, y = fy, z = fz }
    else
      local r = creuserCase(pnj, m, x, y, z, 3)
      if r == "action" then return end
      if r then
        pnj.dire(r == "liquide" and "De l'eau ou de la lave dans mon escalier : je creuse ailleurs." or "Je n'arrive pas à creuser ici : je change d'endroit.")
        m.puits = nil
        return pnj.attendre(2)
      end
      m.marche = k
      return avancer(pnj, m, commun, x, y, z, k)
    end
  end

  -- 4. Les galeries : un couloir le long d'un axe, une galerie tous les 3 blocs, de chaque côté.
  local f = m.fond
  local px, pz = 0, m.cote    -- le couloir part vers le sud (cote = 1) puis vers le nord (cote = -1)
  local gx, gz = 1, 0         -- les galeries partent vers l'est
  if m.branche > M.galeries then
    if m.cote == 1 then m.cote, m.branche, m.couloir, m.pas = -1, 1, 0, 0; return pnj.attendre(1) end
    pnj.direAuChef("J'ai fini mes galeries à cet endroit : je creuse un nouveau puits un peu plus loin.")
    m.puits = nil
    return pnj.attendre(2)
  end
  -- 4a. Le couloir jusqu'au départ de la galerie.
  local depart = 3 * m.branche
  if m.couloir < depart then
    local j = m.couloir + 1
    local x, y, z = f.x + px * j, f.y, f.z + pz * j
    local r = creuserCase(pnj, m, x, y, z, 2)
    if r == "action" then return end
    if r then m.branche = M.galeries + 1; return pnj.attendre(1) end
    m.couloir = j
    return avancer(pnj, m, commun, x, y, z, j)
  end
  -- 4b. La galerie elle-même.
  local s = m.pas + 1
  local sx, sz = f.x + px * depart, f.z + pz * depart
  local x, y, z = sx + gx * s, f.y, sz + gz * s
  if s > M.galerie then
    m.branche, m.pas = m.branche + 1, 0
    return pnj.aller(sx, f.y, sz, 1)   -- retour au couloir
  end
  local r = creuserCase(pnj, m, x, y, z, 2)
  if r == "action" then return end
  if r then
    if r == "liquide" then pnj.dire("De la lave ou de l'eau devant : j'abandonne cette galerie.") end
    m.branche, m.pas = m.branche + 1, 0
    return pnj.aller(sx, f.y, sz, 1)
  end
  m.pas = s
  return avancer(pnj, m, commun, x, y, z, s)
end

return M
