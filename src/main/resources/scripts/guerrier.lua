-- ============================================================================
-- Guerrier
-- « Suivre » : il escorte son chef et tue ce qui le menace.
-- « Travailler ici » : il garde la zone et fait sa ronde.
-- Il n'attaque JAMAIS un joueur de lui-même : seulement celui qui s'en prend
-- à lui ou à son chef (règle du serveur : pas de violence hors cadre RP).
-- ============================================================================
local M = {
  nom = "Guerrier",
  description = "Suit et défend son chef, ou garde une zone en tuant les monstres. Il n'attaque jamais un joueur de lui-même.",
  combattant = true,      -- le cerveau commun le fait riposter au lieu de fuir
}

M.rayonGarde = 16
M.garder = { "nourriture", "#minecraft:swords", "#minecraft:axes" }

local function armer(pnj)
  if not pnj.equiper("#minecraft:swords") then pnj.equiper("#minecraft:axes") end
end

-- Appelé même quand il est occupé (en marche, au bureau...) : un monstre surgit, il lâche tout.
function M.vigilance(pnj)
  if pnj.action() == "attaquer" then return end
  local c = pnj.cibles(10)[1]
  if c then armer(pnj); pnj.attaquer(c.id) end
end

-- En mode « suivre » : il protège son chef (renvoie true s'il a trouvé quelqu'un à combattre).
function M.escorter(pnj)
  local c = pnj.cibles(12)[1]
  if not c then return false end
  armer(pnj)
  pnj.attaquer(c.id)
  return true
end

-- En mode « travailler ici » : il garde la zone.
function M.travailler(pnj, commun)
  armer(pnj)
  local lx, ly, lz = pnj.lieu()
  local c = pnj.cibles(M.rayonGarde)[1]
  if c then return pnj.attaquer(c.id) end
  if pnj.distance(lx, ly, lz) > M.rayonGarde then return pnj.aller(lx, ly, lz, 3) end
  if math.random() < 0.3 then
    return pnj.aller(lx + math.random(-8, 8), ly, lz + math.random(-8, 8), 2)
  end
  return pnj.attendre(3)
end

return M
