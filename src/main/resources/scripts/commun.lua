-- ============================================================================
-- Cerveau commun à tous les PNJ employés.
-- Appelé toutes les demi-secondes, AVANT le métier. Il s'occupe de ce que tout
-- PNJ fait, quel que soit son métier : manger, toucher sa paie, faire grève,
-- fuir le danger, obéir aux ordres « suivre » et « attendre », vider son sac.
-- Ensuite seulement, il laisse la main au métier : metier.travailler(pnj, commun).
-- Guide complet : GUIDE-SCRIPTS.md (onglet « Guide » du panneau).
-- ============================================================================
local M = {}

-- Ce que le PNJ garde sur lui quand il vide son sac au bureau
-- (une pile de chaque : une pioche, des torches, de quoi manger...).
M.garder = { "nourriture", "minecraft:torch", "#minecraft:pickaxes", "#minecraft:axes",
             "#minecraft:swords", "#c:cobblestones", "#minecraft:saplings" }

-- Dire quelque chose au plus une fois toutes les `delai` secondes (évite de radoter).
-- auChef = true : message privé au chef ; sinon, à voix haute autour de lui.
function M.parfois(pnj, cle, texte, auChef, delai)
  local m = pnj.memoire
  m.dits = m.dits or {}
  local maintenant = pnj.temps()
  if m.dits[cle] and maintenant - m.dits[cle] < (delai or 600) then return end
  m.dits[cle] = maintenant
  if auChef then pnj.direAuChef(texte) else pnj.dire(texte) end
end

function M.cerveau(pnj, metier)
  -- Une action est en cours : le métier peut seulement surveiller
  -- (le guerrier lâche tout s'il voit un monstre).
  if pnj.occupe() then
    if metier.vigilance then metier.vigilance(pnj) end
    return
  end

  local mode = pnj.mode()

  -- En grève : il attend au bureau que la caisse couvre ce qu'on lui doit.
  if mode == "greve" then
    local du = pnj.du()
    if du > 0 and pnj.caisse() >= du then return pnj.toucherPaie() end
    local bx, by, bz = pnj.bureau()
    if bx and pnj.distance(bx, by, bz) > 4 then return pnj.aller(bx, by, bz, 3) end
    M.parfois(pnj, "greve", "Toujours en grève. Il me faut " .. pnj.montant(du) .. " dans la caisse du bureau.", true, 1800)
    return pnj.attendre(20)
  end

  -- Attaqué : un combattant riposte, les autres s'enfuient.
  local agresseur = pnj.agresseur()
  if agresseur then
    if metier.combattant then return pnj.attaquer(agresseur.id) end
    M.parfois(pnj, "fuite", "À l'aide ! On m'attaque !", false, 30)
    return pnj.fuir(agresseur.id)
  end

  -- La faim : il mange ce qu'il a ; s'il n'a rien, il se sert au dépôt ou demande à son chef.
  if pnj.faim() <= 14 and pnj.manger() then return end
  if pnj.faim() <= 8 then
    if pnj.depotContient("nourriture") > 0 then return pnj.prendre("nourriture", 16) end
    return pnj.demander("J'ai faim, chef. Il me faudrait de quoi manger.", "nourriture", 1)
  end

  -- La paie : il va la chercher à la caisse du bureau.
  if pnj.paieDue() then return pnj.toucherPaie() end

  -- Les ordres du chef.
  if mode == "suivre" then
    if metier.escorter and metier.escorter(pnj) then return end
    return pnj.suivreChef()
  end
  if mode == "attendre" then return pnj.attendre(2) end

  -- Au travail. Sac plein : direction le dépôt du bureau.
  if pnj.plein() then
    if pnj.resultat() == "depot_plein" then
      M.parfois(pnj, "depot", "Le dépôt du bureau est plein, chef ! Vide-le, je ne peux plus rien ranger.", true, 900)
      return pnj.attendre(30)
    end
    if pnj.resultat() == "pas_de_bureau" then
      M.parfois(pnj, "bureau", "Mon sac est plein et je ne trouve pas ton bureau. Pose un bureau près d'ici.", true, 900)
      return pnj.attendre(30)
    end
    return pnj.deposer(metier.garder or M.garder)
  end
  return metier.travailler(pnj, M)
end

return M
