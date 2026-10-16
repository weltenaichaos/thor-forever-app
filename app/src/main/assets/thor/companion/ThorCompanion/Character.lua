-- Character.lua
-- The app's Character tab:
--   TP1|class|classFile|race|xp|xpMax|rested|itemLevel|guild|str,agi,sta,int|armor
--   TQ1|slot:itemID:itemLevel[:durability%],...      (what you wear)
-- Item names come with the bag item names (TN2). Values the game keeps secret
-- (stats in combat) arrive as "?"; the stats are only read out of combat.

local _, ns = ...

-- Inventory slot ids the paper doll shows, in its order.
ns.GearSlots = { 1, 2, 3, 15, 5, 4, 19, 9, 10, 6, 7, 8, 11, 12, 13, 14, 16, 17 }

local function plain(v)
    if v == nil or (issecretvalue and issecretvalue(v)) then return "?" end
    return tostring(v):gsub("|", "/")
end

local function try(f, ...)
    local ok, a, b = pcall(f, ...)
    if ok then return a, b end
end

local lastStats, lastArmor = "?,?,?,?", "?"

-- Built again only when something it shows changes (see ns.Remember).
ns.CharacterPayload = ns.Remember(function()
    local className, classFile = try(UnitClass, "player")
    local race = try(UnitRace, "player")
    local _, equipped = try(GetAverageItemLevel)
    local guild = try(GetGuildInfo, "player")
    if not InCombatLockdown() then
        local s = {}
        for i = 1, 4 do s[i] = plain((select(2, try(UnitStat, "player", i)))) end
        lastStats = table.concat(s, ",")
        lastArmor = plain((select(2, try(UnitArmor, "player"))))
    end
    return table.concat({ "TP1", plain(className), plain(classFile), plain(race),
        plain(try(UnitXP, "player")), plain(try(UnitXPMax, "player")), plain(try(GetXPExhaustion) or 0),
        equipped and string.format("%.1f", equipped) or "?", plain(guild or ""), lastStats, lastArmor }, "|")
end, { "PLAYER_XP_UPDATE", "PLAYER_LEVEL_UP", "UPDATE_EXHAUSTION", "PLAYER_UPDATE_RESTING", "PLAYER_GUILD_UPDATE",
    "PLAYER_EQUIPMENT_CHANGED", "UNIT_STATS", "UNIT_RESISTANCES", "UNIT_INVENTORY_CHANGED", "PLAYER_REGEN_ENABLED" }, 5)

local equippedIDs = {}

-- Built again when you equip something, durability changes, or an item's level arrives.
ns.GearPayload = ns.Remember(function()
    local out, ids = {}, {}
    for _, slot in ipairs(ns.GearSlots) do
        local id = GetInventoryItemID("player", slot)
        if id then
            ids[#ids + 1] = id
            local link = GetInventoryItemLink("player", slot)
            local level = link and try(C_Item.GetDetailedItemLevelInfo, link)
            local cur, max = GetInventoryItemDurability(slot)
            local entry = slot .. ":" .. id .. ":" .. (level or 0)
            if cur and max and max > 0 then entry = entry .. ":" .. math.floor(cur * 100 / max + 0.5) end
            out[#out + 1] = entry
        end
    end
    equippedIDs = ids
    return "TQ1|" .. table.concat(out, ",")
end, { "PLAYER_EQUIPMENT_CHANGED", "UNIT_INVENTORY_CHANGED", "UPDATE_INVENTORY_DURABILITY", "GET_ITEM_INFO_RECEIVED" }, 5)

-- Worn items, so their names go out with the bag item names.
function ns.EquippedIDs()
    return equippedIDs
end
