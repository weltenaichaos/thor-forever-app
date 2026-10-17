-- Professions.lua
-- The app's Crafting tab: your professions, their recipes, what you made.
--   TF1|<now>                          (now: time(), the clock the times below are in)
--   p<tab><profession><tab><level><tab><max>           one row per profession
--   e<tab><time><tab><text>                            what you made and skill points, newest last
--   w<tab><profession>                                 the profession window open now (no row: none)
--   k<tab><profession><tab><key>                       the key that opens that profession's window (an
--                                                      index into ns.ActionKeys, see the open keys below)
--   f<tab>1                                            the Make keys are bound (see the Make keys below)
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

local bindOpenKeys  -- the open keys (below), bound again when a new profession comes

local function store(read)
    local ok, profession, rank, max, rows = pcall(read)
    if not ok or not profession or not rows or #rows == 0 then return end
    local m = mine()
    local new = m.recipes[profession] == nil
    m.recipes[profession] = { rank = rank, max = max, at = time(), rows = rows }
    changed = true
    if new then pcall(bindOpenKeys) end
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

-- A Make that came while its window was still opening (the app presses the open key and
-- types the command right after): made once the window is there, if that is soon.
local pendingMake
local reading = false
local function readSoon(read)
    -- The window sends many updates at once: read once, a moment after the last.
    if reading then return end
    reading = true
    C_Timer.After(0.3, function()
        reading = false
        store(read)
        local p = pendingMake
        if p and GetTime() < p.before then
            pendingMake = nil
            print("|cff66ccffForever Companion|r " .. ns.Make(p.args, true))
        end
    end)
end

local events = CreateFrame("Frame")
for _, e in ipairs({ "TRADE_SKILL_SHOW", "TRADE_SKILL_UPDATE", "TRADE_SKILL_LIST_UPDATE", "CRAFT_SHOW", "CRAFT_UPDATE",
    "BANKFRAME_OPENED", "PLAYERBANKSLOTS_CHANGED", "CHAT_MSG_SKILL", "CHAT_MSG_LOOT", "SKILL_LINES_CHANGED", "PLAYER_LOGIN" }) do
    pcall(events.RegisterEvent, events, e)
end
local bankOpen = false
events:RegisterEvent("BANKFRAME_CLOSED")
-- Which profession window is open (for the app's Make: it opens the window first when not).
local openWindow = nil
for _, e in ipairs({ "TRADE_SKILL_CLOSE", "CRAFT_CLOSE" }) do pcall(events.RegisterEvent, events, e) end
events:SetScript("OnEvent", function(_, event, text)
    if event == "TRADE_SKILL_SHOW" or event == "TRADE_SKILL_UPDATE" or event == "TRADE_SKILL_LIST_UPDATE" then
        readSoon(function()
            local r = { readTradeSkills() }
            if not r[1] then r = { readModern() } end
            if r[1] and r[1] ~= openWindow then openWindow, changed = r[1], true end
            return unpack(r)
        end)
    elseif event == "CRAFT_SHOW" or event == "CRAFT_UPDATE" then
        readSoon(function()
            local r = { readCrafts() }
            if r[1] and r[1] ~= openWindow then openWindow, changed = r[1], true end
            return unpack(r)
        end)
    elseif event == "TRADE_SKILL_CLOSE" or event == "CRAFT_CLOSE" then
        if openWindow then openWindow, changed = nil, true end
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

-- The open keys: one per profession whose recipes are kept (so one with a window), the
-- game's own profession spell on a secure button, as if you clicked it in the spellbook.
-- ALT-SHIFT-F1 to F5 (kept free in Actions.lua). Bound out of combat, like the bag keys.
local OPEN_KEYS = {}
for i, key in ipairs(ns.ActionKeys) do
    local n = tonumber(key:match("^ALT%-SHIFT%-F(%d+)$"))
    if n and n <= 5 then OPEN_KEYS[n] = i end
end
local openOwner = CreateFrame("Frame")
local openButtons = {}
local openKeys = {}      -- profession -> key index, as bound
local openPending = false

local function windowSpell(profession)
    if profession == "Mining" then return "Smelting" end
    return profession
end

bindOpenKeys = function()
    if InCombatLockdown() then openPending = true return end
    openPending = false
    ClearOverrideBindings(openOwner)
    local was = openKeys
    openKeys = {}
    if ns.TapsEnabled and ns.TapsEnabled() then
        local names = {}
        for name in pairs(mine().recipes) do names[#names + 1] = name end
        table.sort(names)
        for n, name in ipairs(names) do
            if not OPEN_KEYS[n] then break end
            local b = openButtons[n]
            if not b then
                b = CreateFrame("Button", "ThorCompanionOpen" .. n, UIParent, "SecureActionButtonTemplate")
                b:SetAttribute("type", "spell")
                b:SetAttribute("useOnKeyDown", false)  -- on release, as the bag keys (Actions.lua)
                b:RegisterForClicks("AnyUp", "AnyDown")
                openButtons[n] = b
            end
            b:SetAttribute("spell", windowSpell(name))
            SetOverrideBindingClick(openOwner, true, ns.ActionKeys[OPEN_KEYS[n]], b:GetName())
            openKeys[name] = OPEN_KEYS[n]
        end
    end
    for k, v in pairs(openKeys) do if was[k] ~= v then changed = true end end
    for k in pairs(was) do if not openKeys[k] then changed = true end end
end
ns.BindProfessionKeys = bindOpenKeys

-- The Make keys: the app's Make without typing in the chat. It presses, one after another,
--   <profession n> SEP <row> SEP <count> MAKE
-- each number as digit keys: ALT-CTRL-NUMPAD0 to 9, SEP is ALT-CTRL-F1 and MAKE ALT-CTRL-F2.
-- <profession n> is that profession's open key (ALT-SHIFT-F<n>), <row> the recipe's place in
-- its TR1 rows (1 first, headings counted). MAKE is the key press that crafts (the game wants
-- one); the digits before it only say what. One tap in the app, one craft, as with typing.
local makeOwner = CreateFrame("Frame")
local digits, digitsAt = "", 0
local makeBound = false
local function keyButton(name, onPress)
    local b = CreateFrame("Button", name, UIParent)
    b:RegisterForClicks("AnyDown")
    b:SetScript("OnClick", function(_, _, down)
        if down == false then return end  -- the key's release, where a game sends it
        onPress()
    end)
    return b
end
local function addDigit(d)
    if GetTime() - digitsAt > 2 then digits = "" end  -- what was left of an earlier tap
    digits, digitsAt = digits .. d, GetTime()
end
local function makeNow()
    local text = digits
    digits = ""
    if GetTime() - digitsAt > 2 then return end
    local n, row, count = text:match("^(%d+)%*(%d+)%*(%d+)$")
    n, row, count = tonumber(n), tonumber(row), tonumber(count)
    local names = {}
    for name in pairs(mine().recipes) do names[#names + 1] = name end
    table.sort(names)
    local p = n and names[n] and mine().recipes[names[n]]
    local r = p and p.rows[row]
    if not r then
        addResult("Couldn't tell which recipe to make (the app's list is older than the game's)")
        return
    end
    local f = { strsplit("\t", r) }
    local id = tonumber(f[2]) or 0
    local what = id > 0 and tostring(id) or (f[7] or ""):lower()
    print("|cff66ccffForever Companion|r " .. ns.Make(math.max(1, count or 1) .. " " .. what))
end
local makeButtons = {}
for d = 0, 9 do makeButtons[#makeButtons + 1] = { "ALT-CTRL-NUMPAD" .. d, keyButton("ThorCompanionDigit" .. d, function() addDigit(tostring(d)) end) } end
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F1", keyButton("ThorCompanionMakeSep", function() addDigit("*") end) }
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F2", keyButton("ThorCompanionMake", makeNow) }
local makePending = false
local function bindMakeKeys()
    if InCombatLockdown() then makePending = true return end
    makePending = false
    ClearOverrideBindings(makeOwner)
    local on = not ns.TapsEnabled or ns.TapsEnabled()
    if on then
        for _, kb in ipairs(makeButtons) do SetOverrideBindingClick(makeOwner, true, kb[1], kb[2]:GetName()) end
    end
    if on ~= makeBound then makeBound, changed = on, true end
end
makeOwner:RegisterEvent("PLAYER_LOGIN")
makeOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
makeOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or makePending then pcall(bindMakeKeys) end
end)

openOwner:RegisterEvent("PLAYER_LOGIN")
openOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
openOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or openPending then pcall(bindOpenKeys) end
end)

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
    if openWindow then out[#out + 1] = "w\t" .. openWindow end
    for name, key in pairs(openKeys) do out[#out + 1] = "k\t" .. name .. "\t" .. key end
    if makeBound then out[#out + 1] = "f\t1" end
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
-- How many times the materials in your bags make recipe i: the game's own count when it
-- gives one, else worked out from the materials (nil when that can't be read). In this game
-- the count GetTradeSkillInfo gives can be 0 with the materials in the bags.
local function ownCount(reagents, info, link)
    local n
    for r = 1, reagents or 0 do
        local rname, _, need, have = info(r)
        need = number(need) or 1
        local id = itemID(link and link(r))
        local carried = id and number(GetItemCount(id)) or number(have)
        -- Without a link, by its name (GetItemCount takes the name of an item in the bags).
        if not carried and type(rname) == "string" and not secret(rname) then carried = number(GetItemCount(rname)) end
        if not carried or need <= 0 then return nil end
        n = math.min(n or math.huge, math.floor(carried / need))
    end
    return n
end

local function findTradeSkill(want)
    for i = 1, GetNumTradeSkills() or 0 do
        local name, kind, available = GetTradeSkillInfo(i)
        if name and kind ~= "header" and kind ~= "subheader" then
            local id = itemID(GetTradeSkillItemLink and GetTradeSkillItemLink(i))
            if (tonumber(want) and id == tonumber(want)) or clean(name, MAX_NAME):lower() == want then
                local own = ownCount(GetTradeSkillNumReagents and GetTradeSkillNumReagents(i),
                    function(r) return GetTradeSkillReagentInfo(i, r) end,
                    GetTradeSkillReagentItemLink and function(r) return GetTradeSkillReagentItemLink(i, r) end)
                return i, name, number(available) or 0, own
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
                local own = ownCount(GetCraftNumReagents and GetCraftNumReagents(i),
                    function(r) return GetCraftReagentInfo(i, r) end,
                    GetCraftReagentItemLink and function(r) return GetCraftReagentItemLink(i, r) end)
                return i, name, number(available) or 0, own
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

function ns.Make(args, again)
    local count, want = (args or ""):match("^(%S+)%s+(.+)$")
    want = want and want:lower():gsub("^%s+", ""):gsub("%s+$", "")
    if not want then return "use: /thor make <count|all> <item id or recipe name>" end
    local all = count == "all"
    local n = all and 0 or math.max(1, math.min(tonumber(count) or 1, 999))
    -- How many to make: the game's count or the bags' (the larger), and when neither can be
    -- read, what was asked for (the game itself says when the materials are missing).
    local function plan(name, gameCount, own, most)
        local available = math.max(gameCount or 0, own or 0)
        local why = " (game says " .. (gameCount or 0) .. ", bags " .. (own and tostring(own) or "?") .. ")"
        if available <= 0 and own ~= nil then
            addResult("Not enough materials for " .. clean(name, MAX_NAME) .. why)
            return nil, "not enough materials for " .. clean(name, MAX_NAME) .. why
        end
        local make = all and available or math.min(n, available)
        if available <= 0 then make = all and 1 or n end
        make = math.max(1, math.min(make, most or make))
        return make, "making " .. make .. " " .. clean(name, MAX_NAME) .. why
    end
    local open = GetTradeSkillLine and select(1, GetTradeSkillLine())
    if GetNumTradeSkills and open and open ~= "UNKNOWN" and (GetNumTradeSkills() or 0) > 0 then
        local i, name, available, own = findTradeSkill(want)
        if i then
            local make, said = plan(name, available, own)
            if make then
                local ok, err = pcall(DoTradeSkill, i, make)
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    if GetNumCrafts and (GetNumCrafts() or 0) > 0 then
        local i, name, available, own = findCraft(want)
        if i then
            local make, said = plan(name, available, own, 1)
            if make then
                local ok, err = pcall(DoCraft, i)
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    if C_TradeSkillUI and C_TradeSkillUI.GetAllRecipeIDs and C_TradeSkillUI.CraftRecipe then
        local okF, id, name, available = pcall(findModern, want)
        if okF and id then
            local make, said = plan(name, available, nil)
            if make then
                local ok, err = pcall(C_TradeSkillUI.CraftRecipe, id, make)
                if not ok then said = "couldn't make it: " .. tostring(err) end
            end
            return said
        end
    end
    if not again then
        -- Its window may be opening right now (the app's open key): made as soon as it is.
        pendingMake = { args = args, before = GetTime() + 5 }
        return "waiting for the profession window to open"
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
