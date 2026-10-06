-- ============================================================================
-- Cerveau commun à tous les PNJ employés.
-- Appelé toutes les demi-secondes, AVANT le métier. Il s'occupe de ce que tout
-- PNJ fait, quel que soit son métier : grève, fuite, faim, paie, ordres
-- « suivre » et « attendre », bureau, horaires et repos, sac plein, commande
-- terminée. Ensuite seulement, il laisse la main au métier : metier.travailler(pnj, commun).
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

-- Un point au hasard dans la zone de travail (pour explorer), à la surface si elle est connue.
function M.pointDansZone(pnj)
  local cx, cy, cz, r = pnj.zone()
  local angle, d = math.random() * 2 * math.pi, math.sqrt(math.random()) * r
  local x, z = math.floor(cx + math.cos(angle) * d), math.floor(cz + math.sin(angle) * d)
  return x, pnj.surface(x, z) or cy, z
end

function M.cerveau(pnj, metier)
  -- Une action est en cours : le métier peut seulement surveiller
  -- (le guerrier lâche tout s'il voit un monstre).
  if pnj.occupe() then
    if metier.vigilance then metier.vigilance(pnj) end
    return
  end

  local mode = pnj.mode()
  local m = pnj.memoire

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

  -- Au travail. Sans bureau, pas de travail.
  if not pnj.aBureau() then
    M.parfois(pnj, "bureau", "Il me faut un bureau pour travailler : pose-en un, puis rattache-moi dans son écran.", true, 1800)
    return pnj.attendre(20)
  end

  -- Hors de ses heures, ou épuisé : il vide son sac, puis va se reposer (dans un lit près du bureau s'il y en a un).
  if pnj.fatigue() >= 100 then m.epuise = true elseif pnj.fatigue() <= 40 then m.epuise = nil end
  if not pnj.enService() or m.epuise then
    if m.epuise then M.parfois(pnj, "epuise", "Je suis épuisé, chef : je vais me reposer.", true, 1800) end
    if pnj.libres() < 24 and not m.sacVide then
      m.sacVide = true
      return pnj.deposer(metier.garder or M.garder)
    end
    return pnj.seReposer()
  end
  m.sacVide = nil

  -- Sac plein : direction le dépôt du bureau.
  if pnj.plein() then
    if pnj.resultat() == "depot_plein" then
      M.parfois(pnj, "depot", "Le dépôt du bureau est plein, chef ! Vide-le (ou colle un coffre au bureau), je ne peux plus rien ranger.", true, 900)
      return pnj.attendre(30)
    end
    if pnj.resultat() == "pas_de_bureau" then return pnj.attendre(30) end
    return pnj.deposer(metier.garder or M.garder)
  end

  -- Commande remplie : en mode quantité, c'est fini ; en mode stock, il attend que le stock baisse.
  local c = pnj.commande()
  if c and pnj.commandeRemplie() then
    if pnj.libres() < 24 and not m.livre then
      m.livre = true
      return pnj.deposer(metier.garder or M.garder)
    end
    local fait, total = pnj.avancement()
    if c.mode == "quantite" then
      M.parfois(pnj, "commande_finie", "Commande terminée : " .. total .. " " .. c.nom .. ". Donne-moi la suivante dans le bureau.", true, 3600)
    else
      M.parfois(pnj, "stock_plein", "Le stock de " .. c.nom .. " est plein (" .. fait .. "/" .. total .. "). Je reprends dès qu'il baisse.", true, 3600)
    end
    return pnj.attendre(20)
  end
  m.livre = nil

  return metier.travailler(pnj, M)
end

return M
