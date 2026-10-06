-- ============================================================================
-- Guerrier
-- Commandes : garder le bureau, faire la ronde dans son espace de vie (50 blocs
-- autour du bureau), ou escorter son chef.
-- « Suivre » (fiche du PNJ) : il escorte son chef et tue ce qui le menace.
-- Il n'attaque JAMAIS un joueur de lui-même : seulement celui qui s'en prend
-- à lui ou à son chef (règle du serveur : pas de violence hors cadre RP).
-- ============================================================================
local M = {
  nom = "Guerrier",
  description = "Garde le bureau, fait la ronde autour ou escorte son chef, et tue les monstres. Il n'attaque jamais un joueur de lui-même.",
  combattant = true,      -- le cerveau commun le fait riposter au lieu de fuir
}

M.garder = { "nourriture", "#minecraft:swords", "#minecraft:axes" }

-- Pas d'« objet » : ces commandes n'ont ni stock ni quantité.
M.commandes = {
  { id = "garder",   nom = "Garder le bureau" },
  { id = "ronde",    nom = "Faire la ronde (50 blocs)" },
  { id = "escorter", nom = "Escorter son chef" },
}

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

function M.travailler(pnj, commun)
  armer(pnj)
  local c = pnj.commande()
  local id = c and c.id or "garder"

  if id == "escorter" then
    if M.escorter(pnj) then return end
    return pnj.suivreChef()
  end

  local cx, cy, cz, r = pnj.espaceDeVie()
  if id == "garder" then r = 12 end
  local cible = pnj.cibles(math.min(r, 32))[1]
  if cible then return pnj.attaquer(cible.id) end
  if pnj.distance(cx, cy, cz) > r then return pnj.aller(cx, cy, cz, 3) end
  if math.random() < 0.4 then
    if id == "ronde" then
      local x, y, z = commun.pointDansEspaceDeVie(pnj)
      return pnj.aller(x, y, z, 3)
    end
    return pnj.aller(cx + math.random(-8, 8), cy, cz + math.random(-8, 8), 2)
  end
  return pnj.attendre(3)
end

return M
