-- Professions.lua
-- The app's Crafting tab: your professions, their recipes, what you made.
--   TF1|<now>                          (now: time(), the clock the times below are in)
--   p<tab><profession><tab><level><tab><max>           one row per profession
--   e<tab><time><tab><text>                            what you made and skill points, newest last
--   TR1|<profession>|<level>|<max>|<page>|<pages>|<read at>
--   <kind><tab><itemID><tab><made min><tab><made max><tab><ready at><tab><materials><tab><name>
--                                                      the recipes, in the profession window's order
--   TK1|<read at>|itemID:count,...                     the materials of these recipes in your bank
-- <kind>: o, m, e or t (the game's orange, yellow, green and grey: how sure a skill point
-- is), h for a heading. <itemID> is what the recipe makes (0 for an enchantment).
-- <ready at> is when a recipe with a cooldown can be made again (0: now). <materials> is
-- "itemID:count;itemID:count", an item the game hasn't loaded yet as "0:count:name".
-- The game shows the recipes only while a profession window is open: they are read then
-- and kept per character (saved with the addon's settings), so the app has them after the
-- next login too. The same for the bank, which the game shows only while it is open.
-- Only read: nothing here crafts, buys or presses anything.

local _, ns = ...

local MAX_NAME = 36
local MAX_RESULTS = 8

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function number(v)
    if v == nil or secret(v) then return nil end
    return tonumber(v)
end

local function clean(s, max, keep)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("|H.-|h(.-)|h", "%1"):gsub(keep and "[\t\r\n|]" or "[\t\r\n|;:]", " ")
    if #s > max then s = s:sub(1, max - 1) .. "…" end
    return s
end

local function itemID(link)
    if not link or secret(link) then return nil end
    return tonumber(tostring(link):match("item:(%d+)"))
end

local function who()
    return (UnitName("player") or "?") .. "-" .. (GetRealmName and GetRealmName() or "")
end

-- This character's saved part: { recipes = { [profession] = {...} }, results = {...}, bank = {...} }.
local function mine()
    ThorCompanionDB = ThorCompanionDB or {}
    ThorCompanionDB.prof = ThorCompanionDB.prof or {}
    local key = who()
    local m = ThorCompanionDB.prof[key]
    if not m then
        m = { recipes = {}, results = {}, bank = { at = 0, counts = {} } }
        ThorCompanionDB.prof[key] = m
    end
    return m
end

local changed = true      -- something to build again (levels, recipes, results or bank)
local recipesVersion = 0  -- counts the times recipes were read (for ns.ProfessionItemIDs)

-- Your professions and their levels.
local PROFESSION_HEADERS = {}
for _, h in ipairs({ TRADE_SKILLS, SECONDARY_SKILLS, "Professions", "Secondary Skills" }) do
    if type(h) == "string" then PROFESSION_HEADERS[h] = true end
end

local function levels()
    local out = {}
    if GetNumSkillLines and GetSkillLineInfo then
        local under = false
        for i = 1, GetNumSkillLines() or 0 do
            local name, header, _, rank, _, _, max = GetSkillLineInfo(i)
            if header then
                under = PROFESSION_HEADERS[name] == true
            elseif under and name and not secret(name) then
                out[#out + 1] = { name = clean(name, MAX_NAME), rank = number(rank) or 0, max = number(max) or 0 }
            end
        end
    end
    if #out == 0 and GetProfessions and GetProfessionInfo then
        local list = { GetProfessions() }
        for i = 1, 5 do
            local index = list[i]
            if index then
                local name, _, rank, max = GetProfessionInfo(index)
                if name then out[#out + 1] = { name = clean(name, MAX_NAME), rank = number(rank) or 0, max = number(max) or 0 } end
            end
        end
    end
    return out
end

-- The open profession window's recipes, as rows (see TR1 above).
local function row(kind, id, low, high, readyAt, materials, name)
    return table.concat({ kind, id or 0, low or 1, high or low or 1, readyAt or 0, table.concat(materials or {}, ";"), clean(name, MAX_NAME) }, "\t")
end

local function material(link, count, name)
    local id = itemID(link)
    count = number(count) or 1
    if id then return id .. ":" .. count end
    return "0:" .. count .. ":" .. clean(name, MAX_NAME)
end

local KINDS = { optimal = "o", medium = "m", easy = "e", trivial = "t", header = "h", subheader = "h" }
local MODERN_KINDS = { [0] = "o", [1] = "m", [2] = "e", [3] = "t" }

-- The trade skill window (all professions but enchanting in this game's older form).
local function readTradeSkills()
    if not (GetTradeSkillLine and GetNumTradeSkills and GetTradeSkillInfo) then return nil end
    local profession, rank, max = GetTradeSkillLine()
    if not profession or secret(profession) or profession == "UNKNOWN" then return nil end
    local rows = {}
    local now = time()
    for i = 1, GetNumTradeSkills() or 0 do
        local name, kind = GetTradeSkillInfo(i)
        if name and not secret(name) then
            local k = KINDS[kind] or "t"
            if k == "h" then
                rows[#rows + 1] = row("h", 0, 0, 0, 0, nil, name)
            else
                local low, high = 1, 1
                if GetTradeSkillNumMade then low, high = GetTradeSkillNumMade(i) end
                local materials = {}
                for r = 1, (GetTradeSkillNumReagents and GetTradeSkillNumReagents(i)) or 0 do
                    local rname, _, count = GetTradeSkillReagentInfo(i, r)
                    materials[#materials + 1] = material(GetTradeSkillReagentItemLink and GetTradeSkillReagentItemLink(i, r), count, rname)
                end
                local wait = GetTradeSkillCooldown and number(GetTradeSkillCooldown(i))
                rows[#rows + 1] = row(k, itemID(GetTradeSkillItemLink and GetTradeSkillItemLink(i)), number(low), number(high),
                    wait and wait > 0 and math.floor(now + wait) or 0, materials, name)
            end
        end
    end
    return clean(profession, MAX_NAME), number(rank) or 0, number(max) or 0, rows
end

-- The craft window (enchanting in this game's older form).
local function readCrafts()
    if not (GetCraftDisplaySkillLine and GetNumCrafts and GetCraftInfo) then return nil end
    local profession, rank, max = GetCraftDisplaySkillLine()
    if not profession or secret(profession) then return nil end
    local rows = {}
    for i = 1, GetNumCrafts() or 0 do
        local name, _, kind = GetCraftInfo(i)
        if name and not secret(name) then
            local k = KINDS[kind] or "t"
            if k == "h" then
                rows[#rows + 1] = row("h", 0, 0, 0, 0, nil, name)
            else
                local materials = {}
                for r = 1, (GetCraftNumReagents and GetCraftNumReagents(i)) or 0 do
                    local rname, _, count = GetCraftReagentInfo(i, r)
                    materials[#materials + 1] = material(GetCraftReagentItemLink and GetCraftReagentItemLink(i, r), count, rname)
                end
                rows[#rows + 1] = row(k, itemID(GetCraftItemLink and GetCraftItemLink(i)), 1, 1, 0, materials, name)
            end
        end
    end
    return clean(profession, MAX_NAME), number(rank) or 0, number(max) or 0, rows
end

-- The newer profession window, for a game that has it instead.
local function readModern()
    local ui = C_TradeSkillUI
    if not (ui and ui.GetAllRecipeIDs and ui.GetRecipeInfo and ui.GetBaseProfessionInfo) then return nil end
    local info = ui.GetBaseProfessionInfo()
    if not info or not info.professionName or secret(info.professionName) then return nil end
    local rows = {}
    local now = time()
    for _, id in ipairs(ui.GetAllRecipeIDs() or {}) do
        local r = ui.GetRecipeInfo(id)
        if r and r.learned and r.name and not secret(r.name) then
            local materials, out, low, high = {}, 0, 1, 1
            local okS, s = pcall(ui.GetRecipeSchematic, id, false)
            if okS and s then
                out, low, high = number(s.outputItemID) or 0, number(s.quantityMin) or 1, number(s.quantityMax) or 1
                for _, slot in ipairs(s.reagentSlotSchematics or {}) do
                    local first = slot.reagents and slot.reagents[1]
                    if first and first.itemID and (slot.reagentType == nil or slot.reagentType == 1) then
                        materials[#materials + 1] = first.itemID .. ":" .. (number(slot.quantityRequired) or 1)
                    end
                end
            end
            local wait = ui.GetRecipeCooldown and number((ui.GetRecipeCooldown(id)))
            rows[#rows + 1] = row(MODERN_KINDS[r.relativeDifficulty] or "t", out, low, high,
                wait and wait > 0 and math.floor(now + wait) or 0, materials, r.name)
        end
    end
    return clean(info.professionName, MAX_NAME), number(info.skillLevel) or 0, number(info.maxSkillLevel) or 0, rows
end

local function store(read)
    local ok, profession, rank, max, rows = pcall(read)
    if not ok or not profession or not rows or #rows == 0 then return end
    local m = mine()
    m.recipes[profession] = { rank = rank, max = max, at = time(), rows = rows }
    changed = true
    recipesVersion = recipesVersion + 1
end

-- In your bank, the materials of the recipes kept (read while the bank is open).
local function readBank()
    local m = mine()
    local counts = {}
    for _, p in pairs(m.recipes) do
        for _, r in ipairs(p.rows) do
            for id in (r:match("^[^\t]*\t[^\t]*\t[^\t]*\t[^\t]*\t[^\t]*\t([^\t]*)") or ""):gmatch("(%d+):%d+") do
                id = tonumber(id)
                if id and id > 0 and counts[id] == nil then
                    local all, carried = number(GetItemCount(id, true)) or 0, number(GetItemCount(id)) or 0
                    counts[id] = math.max(0, all - carried)
                end
            end
        end
    end
    m.bank = { at = time(), counts = counts }
    changed = true
end

-- What you made and the skill points, from the game's own chat lines.
local function pattern(format)
    if type(format) ~= "string" then return nil end
    return "^" .. format:gsub("([%(%)%.%%%+%-%*%?%[%]%^%$])", "%%%1"):gsub("%%%%s", "(.+)"):gsub("%%%%d", "(%%d+)") .. "$"
end
local CREATED = { pattern(LOOT_ITEM_CREATED_SELF_MULTIPLE), pattern(LOOT_ITEM_CREATED_SELF) }

local function addResult(text)
    local m = mine()
    m.results[#m.results + 1] = { at = time(), text = clean(text, 60, true) }
    while #m.results > MAX_RESULTS do table.remove(m.results, 1) end
    changed = true
end

local reading = false
local function readSoon(read)
    -- The window sends many updates at once: read once, a moment after the last.
    if reading then return end
    reading = true
    C_Timer.After(0.3, function()
        reading = false
        store(read)
    end)
end

local events = CreateFrame("Frame")
for _, e in ipairs({ "TRADE_SKILL_SHOW", "TRADE_SKILL_UPDATE", "TRADE_SKILL_LIST_UPDATE", "CRAFT_SHOW", "CRAFT_UPDATE",
    "BANKFRAME_OPENED", "PLAYERBANKSLOTS_CHANGED", "CHAT_MSG_SKILL", "CHAT_MSG_LOOT", "SKILL_LINES_CHANGED", "PLAYER_LOGIN" }) do
    pcall(events.RegisterEvent, events, e)
end
local bankOpen = false
events:RegisterEvent("BANKFRAME_CLOSED")
events:SetScript("OnEvent", function(_, event, text)
    if event == "TRADE_SKILL_SHOW" or event == "TRADE_SKILL_UPDATE" or event == "TRADE_SKILL_LIST_UPDATE" then
        readSoon(function()
            local r = { readTradeSkills() }
            if r[1] then return unpack(r) end
            return readModern()
        end)
    elseif event == "CRAFT_SHOW" or event == "CRAFT_UPDATE" then
        readSoon(readCrafts)
    elseif event == "BANKFRAME_OPENED" then
        bankOpen = true
        pcall(readBank)
    elseif event == "BANKFRAME_CLOSED" then
        bankOpen = false
    elseif event == "PLAYERBANKSLOTS_CHANGED" then
        if bankOpen then pcall(readBank) end
    elseif event == "CHAT_MSG_SKILL" then
        if type(text) == "string" and not secret(text) then addResult(text) end
    elseif event == "CHAT_MSG_LOOT" then
        if type(text) == "string" and not secret(text) then
            for _, p in ipairs(CREATED) do
                if text:match(p) then addResult(text) break end
            end
        end
    else
        changed = true
    end
end)
-- Bank bags change too while it is open (moving things in and out).
local bagEvents = CreateFrame("Frame")
bagEvents:RegisterEvent("BAG_UPDATE_DELAYED")
bagEvents:SetScript("OnEvent", function() if bankOpen then pcall(readBank) end end)

-- Built again only when something changed.
local head, pages, bankPayload = nil, {}, nil
local function build()
    if not changed then return end
    changed = false
    local m = mine()
    local now = time()
    local list = levels()
    -- (Recipes are kept under the name the profession window gives, which may not be the
    -- skill's: the app shows those as professions of their own.)
    local out = { "TF1|" .. now }
    for _, l in ipairs(list) do out[#out + 1] = table.concat({ "p", l.name, l.rank, l.max }, "\t") end
    for _, r in ipairs(m.results) do out[#out + 1] = table.concat({ "e", r.at, r.text }, "\t") end
    head = table.concat(out, "\n")
    -- The recipes, a profession at a time, cut into pages that fit the square.
    pages = {}
    local names = {}
    for name in pairs(m.recipes) do names[#names + 1] = name end
    table.sort(names)
    local room = ns.StripCapacity() - 80
    for _, name in ipairs(names) do
        local p = m.recipes[name]
        local cut, page, used = {}, {}, 0
        for _, r in ipairs(p.rows) do
            if #page > 0 and used + #r + 1 > room then cut[#cut + 1], page, used = page, {}, 0 end
            if #r + 1 <= room then page[#page + 1], used = r, used + #r + 1 end
        end
        cut[#cut + 1] = page
        for n, rows in ipairs(cut) do
            pages[#pages + 1] = string.format("TR1|%s|%d|%d|%d|%d|%d", name, p.rank or 0, p.max or 0, n, #cut, p.at or 0)
                .. "\n" .. table.concat(rows, "\n")
        end
    end
    local counts = {}
    for id, c in pairs(m.bank.counts or {}) do if c > 0 then counts[#counts + 1] = id .. ":" .. c end end
    table.sort(counts)
    bankPayload = "TK1|" .. (m.bank.at or 0) .. "|" .. table.concat(counts, ",")
end

function ns.ProfessionsPayload()
    build()
    return head
end

function ns.BankPayload()
    build()
    return bankPayload
end

-- The recipe pages, each twice after it changed (as the quest pages, Quests.lua).
local sentPage, sentCount = {}, {}
function ns.RecipePagesPayload()
    build()
    for n, p in ipairs(pages) do
        if p ~= sentPage[n] then sentPage[n], sentCount[n] = p, 0 end
    end
    for n = #pages + 1, #sentPage do sentPage[n], sentCount[n] = nil, nil end
    local pick
    for times = 0, 1 do
        for n = 1, #pages do
            if not pick and sentCount[n] == times then pick = n end
        end
    end
    if not pick then return nil end
    sentCount[pick] = sentCount[pick] + 1
    return pages[pick]
end

function ns.RecipePagesAgain()
    sentPage, sentCount = {}, {}
end

-- What the recipes make and need, for the item names and tooltips the app is sent.
local idsVersion, ids = -1, {}
function ns.ProfessionItemIDs()
    if idsVersion == recipesVersion then return ids end
    idsVersion, ids = recipesVersion, {}
    local seen = {}
    local ok, m = pcall(mine)
    if not ok then return ids end
    for _, p in pairs(m.recipes) do
        for _, r in ipairs(p.rows) do
            local f = { strsplit("\t", r) }
            local out = tonumber(f[2])
            if out and out > 0 and not seen[out] then seen[out] = true ids[#ids + 1] = out end
            for id in (f[6] or ""):gmatch("(%d+):%d+") do
                id = tonumber(id)
                if id and id > 0 and not seen[id] then seen[id] = true ids[#ids + 1] = id end
            end
        end
    end
    return ids
end

-- /thor make <count|all> <itemID or recipe name>: the app's Make buttons (typed into the
-- chat box by the app, one tap one command, as if you had typed it). Makes the recipe with
-- the game's own crafting, in the profession window that is open; nothing else.
local function findTradeSkill(want)
    for i = 1, GetNumTradeSkills() or 0 do
        local name, kind, available = GetTradeSkillInfo(i)
        if name and kind ~= "header" and kind ~= "subheader" then
            local id = itemID(GetTradeSkillItemLink and GetTradeSkillItemLink(i))
            if (tonumber(want) and id == tonumber(want)) or clean(name, MAX_NAME):lower() == want then
                return i, name, number(available) or 0
            end
        end
    end
end

local function findCraft(want)
    for i = 1, GetNumCrafts() or 0 do
        local name, _, kind, available = GetCraftInfo(i)
        if name and kind ~= "header" then
            local id = itemID(GetCraftItemLink and GetCraftItemLink(i))
            if (tonumber(want) and id == tonumber(want)) or clean(name, MAX_NAME):lower() == want then
                return i, name, number(available) or 0
            end
        end
    end
end

local function findModern(want)
    local ui = C_TradeSkillUI
    for _, id in ipairs(ui.GetAllRecipeIDs() or {}) do
        local r = ui.GetRecipeInfo(id)
        if r and r.learned and r.name then
            local okS, s = pcall(ui.GetRecipeSchematic, id, false)
            local out = okS and s and number(s.outputItemID)
            if (tonumber(want) and out == tonumber(want)) or clean(r.name, MAX_NAME):lower() == want then
                return id, r.name, number(r.numAvailable) or 0
            end
        end
    end
end

function ns.Make(args)
    local count, want = (args or ""):match("^(%S+)%s+(.+)$")
    want = want and want:lower():gsub("^%s+", ""):gsub("%s+$", "")
    if not want then return "use: /thor make <count|all> <item id or recipe name>" end
    local all = count == "all"
    local n = all and 0 or math.max(1, math.min(tonumber(count) or 1, 999))
    local function done(name, made, available)
        if available <= 0 then
            addResult("Not enough materials for " .. clean(name, MAX_NAME))
            return "not enough materials for " .. clean(name, MAX_NAME)
        end
        made = all and available or math.min(made, available)
        return "making " .. made .. " " .. clean(name, MAX_NAME)
    end
    local open = GetTradeSkillLine and select(1, GetTradeSkillLine())
    if GetNumTradeSkills and open and open ~= "UNKNOWN" and (GetNumTradeSkills() or 0) > 0 then
        local i, name, available = findTradeSkill(want)
        if i then
            local said = done(name, n, available)
            if available > 0 then
                local ok, err = pcall(DoTradeSkill, i, all and available or math.min(n, available))
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    if GetNumCrafts and (GetNumCrafts() or 0) > 0 then
        local i, name, available = findCraft(want)
        if i then
            local said = done(name, 1, available)
            if available > 0 then
                local ok, err = pcall(DoCraft, i)
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    if C_TradeSkillUI and C_TradeSkillUI.GetAllRecipeIDs and C_TradeSkillUI.CraftRecipe then
        local okF, id, name, available = pcall(findModern, want)
        if okF and id then
            local said = done(name, n, available)
            if available > 0 then
                local ok, err = pcall(C_TradeSkillUI.CraftRecipe, id, all and available or math.min(n, available))
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    addResult("Open the profession window in the game to make things")
    return "open the profession window that has this recipe first"
end

-- /thor prof: what this game offers, for a first test.
function ns.ProfessionsInfo()
    local m = mine()
    local parts = {}
    for _, l in ipairs(levels()) do parts[#parts + 1] = l.name .. " " .. l.rank .. "/" .. l.max end
    local kept = {}
    for name, p in pairs(m.recipes) do kept[#kept + 1] = name .. " (" .. #p.rows .. " rows)" end
    return "professions: " .. (#parts > 0 and table.concat(parts, ", ") or "none found") ..
        "; recipes kept: " .. (#kept > 0 and table.concat(kept, ", ") or "none (open a profession window)") ..
        "; game has: " .. (GetNumTradeSkills and "trade skill window" or "") .. (GetNumCrafts and ", craft window" or "") ..
        (C_TradeSkillUI and C_TradeSkillUI.GetAllRecipeIDs and ", new profession window" or "") ..
        "; bank items kept: " .. (function() local n = 0 for _ in pairs(m.bank.counts or {}) do n = n + 1 end return n end)()
end
