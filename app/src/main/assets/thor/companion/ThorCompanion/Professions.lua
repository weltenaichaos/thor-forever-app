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
--   <kind><tab><itemID><tab><made min><tab><made max><tab><ready at><tab><materials><tab><name>[<tab><fits>]
--                                                      the recipes, in the profession window's order
--                                                      and under its headings
--   TK1|<read at>|itemID:count,...                     the materials of these recipes in your bank
-- <kind>: o, m, e or t (the game's orange, yellow, green and grey: how sure a skill point
-- is), h for a heading. <itemID> is what the recipe makes (0 for an enchantment).
-- <ready at> is when a recipe with a cooldown can be made again (0: now). <materials> is
-- "itemID:count;itemID:count", an item the game hasn't loaded yet as "0:count:name".
-- <fits> only comes with an enchantment: the items it can go on, as the game's own enchanting
-- window lists them (C_TradeSkillUI.GetEnchantItems), "place:itemID,place:itemID" (empty: none
-- now). <place> is a worn item's slot (1 to 19) or 1000 + bag * 100 + slot for one in the bags.
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

-- Where an item is, as one number (see <fits> above): a worn item's slot, or 1000 + bag * 100 + slot.
local function placeOf(bag, slot)
    if bag then return 1000 + bag * 100 + slot end
    return slot
end

local function locationOf(place)
    if not (ItemLocation and place) then return nil end
    if place >= 1000 then
        return ItemLocation:CreateFromBagAndSlot(math.floor((place - 1000) / 100), (place - 1000) % 100)
    end
    return ItemLocation:CreateFromEquipmentSlot(place)
end

-- Everything you wear and carry, by the game's item GUID: { place, id }.
local function itemsByGUID()
    local out = {}
    if not (ItemLocation and C_Item and C_Item.DoesItemExist and C_Item.GetItemGUID) then return out end
    local function note(loc, place)
        local okE, exists = pcall(C_Item.DoesItemExist, loc)
        if not okE or not exists then return end
        local okG, guid = pcall(C_Item.GetItemGUID, loc)
        if okG and guid and not secret(guid) then
            out[guid] = { place = place, id = number(C_Item.GetItemID and C_Item.GetItemID(loc)) or 0 }
        end
    end
    for slot = 1, 19 do note(ItemLocation:CreateFromEquipmentSlot(slot), slot) end
    local bags = (NUM_TOTAL_EQUIPPED_BAG_SLOTS or NUM_BAG_SLOTS or 4)
    for bag = 0, bags do
        local n = C_Container and C_Container.GetContainerNumSlots and C_Container.GetContainerNumSlots(bag) or 0
        for slot = 1, math.min(n or 0, 99) do note(ItemLocation:CreateFromBagAndSlot(bag, slot), placeOf(bag, slot)) end
    end
    return out
end

-- The items an enchantment can go on, as the game's enchanting window offers them: { place, id }, worn first.
local MAX_FITS = 8   -- (a recipe row has to fit a page: see build)
local function enchantItems(recipeID, byGUID)
    local ui = C_TradeSkillUI
    local out = {}
    if not (ui and ui.GetEnchantItems) then return out end
    local ok, guids = pcall(ui.GetEnchantItems, recipeID)
    if not ok or type(guids) ~= "table" then return out end
    for _, guid in ipairs(guids) do
        local at = not secret(guid) and byGUID[guid]
        if at then out[#out + 1] = at end
    end
    table.sort(out, function(x, y) return x.place < y.place end)
    return out
end

local function isEnchant(info, schematic)
    if info and info.isEnchantingRecipe then return true end
    local kinds = Enum and Enum.TradeskillRecipeType
    return schematic ~= nil and kinds ~= nil and kinds.Enchant ~= nil and schematic.recipeType == kinds.Enchant
end

-- The newer profession window, for a game that has it instead (this one, WoW Forever): its
-- recipes under its own headings (Bracer, Chest, ... for enchanting), in its order.
local function readModern()
    local ui = C_TradeSkillUI
    if not (ui and ui.GetAllRecipeIDs and ui.GetRecipeInfo and ui.GetBaseProfessionInfo) then return nil end
    local info = ui.GetBaseProfessionInfo()
    if not info or not info.professionName or secret(info.professionName) then return nil end
    local entries = {}
    local now = time()
    local byGUID  -- read once, when the first enchantment comes
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
            local enchant = isEnchant(r, okS and s or nil)
            local text = row(MODERN_KINDS[r.relativeDifficulty] or "t", enchant and 0 or out, low, high,
                wait and wait > 0 and math.floor(now + wait) or 0, materials, r.name)
            if enchant then
                byGUID = byGUID or itemsByGUID()
                local fits = {}
                for i, at in ipairs(enchantItems(id, byGUID)) do
                    if i > MAX_FITS then break end
                    fits[#fits + 1] = at.place .. ":" .. at.id
                end
                text = text .. "\t" .. table.concat(fits, ",")
            end
            entries[#entries + 1] = { category = number(r.categoryID) or 0, text = text }
        end
    end
    -- Under the window's headings: its category order, then any other in the order its recipes came.
    local order, seen = {}, {}
    local okC, cats = pcall(function() return { ui.GetCategories() } end)
    if okC and type(cats) == "table" then
        if type(cats[1]) == "table" then cats = cats[1] end
        for _, c in ipairs(cats) do
            c = number(c)
            if c and not seen[c] then seen[c] = true order[#order + 1] = c end
        end
    end
    for _, e in ipairs(entries) do
        if not seen[e.category] then seen[e.category] = true order[#order + 1] = e.category end
    end
    local byCategory = {}
    for _, e in ipairs(entries) do
        byCategory[e.category] = byCategory[e.category] or {}
        table.insert(byCategory[e.category], e.text)
    end
    local rows = {}
    for _, c in ipairs(order) do
        local list = byCategory[c]
        if list then
            local okI, cat = pcall(function() return ui.GetCategoryInfo and ui.GetCategoryInfo(c) end)
            local title = okI and cat and cat.name
            if title and not secret(title) and title ~= "" then rows[#rows + 1] = row("h", 0, 0, 0, 0, nil, title) end
            for _, t in ipairs(list) do rows[#rows + 1] = t end
        end
    end
    return clean(info.professionName, MAX_NAME), number(info.skillLevel) or 0, number(info.maxSkillLevel) or 0, rows
end

local bindOpenKeys  -- the open keys (below), bound again when a new profession comes

local function store(read)
    local ok, profession, rank, max, rows = pcall(read)
    if not ok or not profession or profession == "" or not rows or #rows == 0 then return end
    local m = mine()
    local old = m.recipes[profession]
    local new = old == nil
    -- The window sends updates all the time while it is open (every craft, every bag
    -- change): the same recipes again keep their time, so the app keeps its copy.
    if old and old.rank == rank and old.max == max and #old.rows == #rows then
        local same = true
        for i = 1, #rows do if old.rows[i] ~= rows[i] then same = false break end end
        if same then return end
    end
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

-- What is being made now (a Make): how many of how many, and whether the game stopped.
-- Sent as c\t<seq>\t<made>\t<wanted>\t<status>\t<reason>\t<name>; status making, done or stopped,
-- or asking while the game asks something about an enchantment (<reason>: its question, as
-- "Do you want to replace ...?"); the app's Yes and No answer it (the answer keys below).
local run
local runSeq = 0
local function runChanged()
    changed = true
    if ns.ProfessionsUrgent then ns.ProfessionsUrgent() end
end
local function startRun(name, itemId, want, spell)
    runSeq = runSeq + 1
    run = { seq = runSeq, name = clean(name, MAX_NAME), id = itemId, want = want, made = 0, status = "making", reason = "", at = GetTime(), spell = spell }
    runChanged()
end
local function busy() return run and (run.status == "making" or run.status == "asking") end
local function stopRun(reason)
    if not busy() then return end
    run.status, run.reason, run.asking = "stopped", clean(reason or "", 60, true), nil
    runChanged()
end

-- The game asks before an enchantment replaces another one or binds the item to you
-- (its own dialog on the top screen). Answered there or with the app's Yes and No.
local function ask(which, text)
    run.status, run.reason, run.asking = "asking", clean(text, 90, true), which
    runChanged()
    if not (StaticPopup_Visible and C_Timer) then return end
    local seq = run.seq
    local function check()
        if not run or run.seq ~= seq or run.status ~= "asking" then return end
        if StaticPopup_Visible(which) then C_Timer.After(0.5, check) return end
        -- Answered in the game: a yes starts the enchantment (UNIT_SPELLCAST_START), a no doesn't.
        C_Timer.After(1.5, function()
            if run and run.seq == seq and run.status == "asking" then stopRun("nothing enchanted: the game's question got a no") end
        end)
    end
    C_Timer.After(1, check)
end

local ANSWERS = { REPLACE_ENCHANT = "ReplaceEnchant", REPLACE_TRADESKILL_ENCHANT = "ReplaceTradeskillEnchant", BIND_ENCHANT = "BindEnchant" }
local function answer(yes)
    if not run or run.status ~= "asking" then return end
    local which = run.asking
    if yes then
        local name = ANSWERS[which]
        local f = name and ((C_Item and C_Item[name]) or _G[name])
        if f then pcall(f) end
        run.status, run.reason, run.asking = "making", "", nil
        runChanged()
    else
        stopRun("you said no: nothing enchanted")
    end
    if StaticPopup_Hide and which then pcall(StaticPopup_Hide, which) end
end
local function runMade(text)
    if not run or run.status ~= "making" then return end
    local id = tonumber(text:match("item:(%d+)"))
    if (run.id and run.id > 0 and id == run.id) or text:find("[" .. run.name .. "]", 1, true) then
        run.made = run.made + 1
        if run.made >= run.want then run.status = "done" end
        runChanged()
    end
end
local runEvents = CreateFrame("Frame")
for _, e in ipairs({ "UNIT_SPELLCAST_INTERRUPTED", "UI_ERROR_MESSAGE", "UNIT_SPELLCAST_START", "UNIT_SPELLCAST_SUCCEEDED",
    "UNIT_SPELLCAST_FAILED", "REPLACE_ENCHANT", "REPLACE_TRADESKILL_ENCHANT", "BIND_ENCHANT" }) do
    pcall(runEvents.RegisterEvent, runEvents, e)
end
local lastError, lastErrorAt = nil, 0
runEvents:SetScript("OnEvent", function(_, event, a, b, c)
    local message
    if event == "UI_ERROR_MESSAGE" then
        -- (message type, message), or just the message in older games
        message = type(b) == "string" and b or a
        if type(message) ~= "string" or secret(message) then return end
        lastError, lastErrorAt = message, GetTime()
    end
    if not busy() then return end
    if event == "UNIT_SPELLCAST_INTERRUPTED" then
        if a == "player" then stopRun("interrupted") end
    elseif event == "UI_ERROR_MESSAGE" then
        if message == ERR_INV_FULL or message:lower():find("full", 1, true) then stopRun(message)
        -- An enchantment the game refuses right away says why ("Requires Runed Copper Rod").
        elseif run.spell and GetTime() - run.at < 3 then stopRun(message) end
    elseif not run.spell then
        return
    elseif event == "REPLACE_ENCHANT" or event == "REPLACE_TRADESKILL_ENCHANT" then
        local okF, text = pcall(string.format, REPLACE_ENCHANT or "Replace \"%s\" with \"%s\"?", tostring(a or ""), tostring(b or ""))
        ask(event, okF and text or "Replace the enchantment that is on it?")
        -- The app's Enchant tap said yes already (its replace switch): answered right here,
        -- still within that key press. Should the game not take it, the question stays, on
        -- its screen and in the app, for a Yes or No as without the switch.
        -- Once per enchant: should the game ask again, the question stays for the app's Yes or
        -- No, so nothing can go on by itself over and over.
        if run.replace and not run.answeredItself then
            run.answeredItself = true
            local f = (C_Item and C_Item[ANSWERS[event]]) or _G[ANSWERS[event]]
            if f then pcall(f) end
        end
    elseif event == "BIND_ENCHANT" then
        ask(event, BIND_ENCHANT or "Enchanting this item will bind it to you.")
    elseif a == "player" and number(c) == run.spell then
        if event == "UNIT_SPELLCAST_START" and run.status == "asking" then
            -- (The game hides its question when the cast starts; this makes sure.)
            if StaticPopup_Hide and run.asking then pcall(StaticPopup_Hide, run.asking) end
            run.status, run.reason, run.asking = "making", "", nil
            runChanged()
        elseif event == "UNIT_SPELLCAST_SUCCEEDED" then
            if run.replace and StaticPopup_Hide then
                for which in pairs(ANSWERS) do if which ~= "BIND_ENCHANT" then pcall(StaticPopup_Hide, which) end end
            end
            run.made, run.status, run.reason, run.asking = 1, "done", "", nil
            addResult("Enchanted " .. (run.on or "it") .. ": " .. run.name)
            runChanged()
        elseif event == "UNIT_SPELLCAST_FAILED" then
            stopRun(GetTime() - lastErrorAt < 1 and lastError or "the game didn't do it")
        end
    end
end)

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
-- The app waits for it (Make), so it goes out next, not in its turn.
local syncOpenKeys  -- (below, with the open keys)
local function urgent()
    if ns.ProfessionsUrgent then ns.ProfessionsUrgent() end
    if syncOpenKeys then pcall(syncOpenKeys) end
end
for _, e in ipairs({ "TRADE_SKILL_CLOSE", "CRAFT_CLOSE" }) do pcall(events.RegisterEvent, events, e) end
events:SetScript("OnEvent", function(_, event, text)
    if event == "TRADE_SKILL_SHOW" or event == "TRADE_SKILL_UPDATE" or event == "TRADE_SKILL_LIST_UPDATE" then
        readSoon(function()
            local r = { readTradeSkills() }
            if not r[1] then r = { readModern() } end
            if r[1] and r[1] ~= openWindow then openWindow, changed = r[1], true urgent() end
            return unpack(r)
        end)
    elseif event == "CRAFT_SHOW" or event == "CRAFT_UPDATE" then
        readSoon(function()
            local r = { readCrafts() }
            if r[1] and r[1] ~= openWindow then openWindow, changed = r[1], true urgent() end
            return unpack(r)
        end)
    elseif event == "TRADE_SKILL_CLOSE" or event == "CRAFT_CLOSE" then
        if openWindow then openWindow, changed = nil, true urgent() end
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
                if text:match(p) then addResult(text) runMade(text) break end
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
        for name in pairs(mine().recipes) do if name ~= "" then names[#names + 1] = name end end
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
            b:SetAttribute("type", name == openWindow and "" or "spell")  -- only opens (the spell would close it)
            b.profession = name
            SetOverrideBindingClick(openOwner, true, ns.ActionKeys[OPEN_KEYS[n]], b:GetName())
            openKeys[name] = OPEN_KEYS[n]
        end
    end
    for k, v in pairs(openKeys) do if was[k] ~= v then changed = true end end
    for k in pairs(was) do if not openKeys[k] then changed = true end end
end
ns.BindProfessionKeys = bindOpenKeys

-- When a window opens or closes: its open key does nothing while it is open, so a press
-- from the app (which may not know yet) never closes it.
syncOpenKeys = function()
    if InCombatLockdown() then openPending = true return end
    for _, b in pairs(openButtons) do
        if b.profession then b:SetAttribute("type", b.profession == openWindow and "" or "spell") end
    end
end

-- The Make keys: the app's Make without typing in the chat. It presses, one after another,
--   <profession n> SEP <row> SEP <count> [SEP <place> SEP <itemID>] MAKE
-- each number as digit keys: ALT-CTRL-NUMPAD0 to 9, SEP is ALT-CTRL-F1 and MAKE ALT-CTRL-F2.
-- <profession n> is that profession's open key (ALT-SHIFT-F<n>), <row> the recipe's place in
-- its TR1 rows (1 first, headings counted). MAKE is the key press that crafts (the game wants
-- one); the digits before it only say what. One tap in the app, one craft, as with typing.
-- An enchantment also says which item it goes on: its <place> (see <fits> above) and itemID,
-- and a 1 after them when the same tap also says yes to replacing the item's enchantment
-- (the app's "Replace an old enchantment without asking"; binding an item is always asked).
-- ALT-CTRL-F3 and ALT-CTRL-F5 answer the game's question about an enchantment: yes and no
-- (not F4: ALT-F4 closes the game window, so no key near it is used).
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
    local nums = {}
    for v in text:gmatch("[^*]+") do nums[#nums + 1] = tonumber(v) end
    if not text:match("^[%d*]+$") or (#nums ~= 3 and #nums ~= 5 and #nums ~= 6) then nums = {} end
    local n, row, count, place, item, replace = nums[1], nums[2], nums[3], nums[4], nums[5], nums[6] == 1
    local names = {}
    for name in pairs(mine().recipes) do if name ~= "" then names[#names + 1] = name end end
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
    local on = place and (" @" .. place .. ":" .. (item or 0) .. (replace and ":1" or "")) or ""
    print("|cff66ccffForever Companion|r " .. ns.Make(math.max(1, count or 1) .. " " .. what .. on))
end
local makeButtons = {}
for d = 0, 9 do makeButtons[#makeButtons + 1] = { "ALT-CTRL-NUMPAD" .. d, keyButton("ThorCompanionDigit" .. d, function() addDigit(tostring(d)) end) } end
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F1", keyButton("ThorCompanionMakeSep", function() addDigit("*") end) }
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F2", keyButton("ThorCompanionMake", makeNow) }
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F3", keyButton("ThorCompanionYes", function() answer(true) end) }
makeButtons[#makeButtons + 1] = { "ALT-CTRL-F5", keyButton("ThorCompanionNo", function() answer(false) end) }
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
    m.recipes[""] = nil  -- a window without a name (kept by 0.28.1 and older)
    local now = time()
    local list = levels()
    -- (Recipes are kept under the name the profession window gives, which may not be the
    -- skill's: the app shows those as professions of their own.)
    local out = { "TF1|" .. now }
    -- The window and the keys first: the app's Make goes by them.
    if openWindow then out[#out + 1] = "w\t" .. openWindow end
    for name, key in pairs(openKeys) do out[#out + 1] = "k\t" .. name .. "\t" .. key end
    if makeBound then out[#out + 1] = "f\t1" end
    if run then out[#out + 1] = table.concat({ "c", run.seq, run.made, run.want, run.status, run.reason, run.name }, "\t") end
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

-- An enchantment on the item at <place> (see <fits> above), the way the game's own enchanting
-- window does it: the item has to be one the game offers for it (GetEnchantItems), and when it
-- moved in the bags since the app saw it, the same item is found where it is now.
local function enchant(recipeID, name, place, itemID, replace)
    local ui = C_TradeSkillUI
    if not (ui and ui.CraftEnchant and ui.GetEnchantItems) then return "this game can't enchant from the app" end
    local fits = enchantItems(recipeID, itemsByGUID())
    local pick
    for _, at in ipairs(fits) do
        if at.place == place and (itemID == 0 or at.id == itemID) then pick = at break end
    end
    if not pick and itemID > 0 then
        for _, at in ipairs(fits) do if at.id == itemID then pick = at break end end
    end
    if not pick then
        local said = #fits == 0 and "no item of yours takes " .. clean(name, MAX_NAME) .. " now"
            or "that item isn't there any more or doesn't take " .. clean(name, MAX_NAME)
        addResult(said:sub(1, 1):upper() .. said:sub(2))
        return said
    end
    local loc = locationOf(pick.place)
    local okN, item = pcall(function() return C_Item.GetItemName and C_Item.GetItemName(loc) end)
    item = okN and type(item) == "string" and not secret(item) and item or ("item " .. pick.id)
    startRun(name, 0, 1, recipeID)
    run.on, run.replace = clean(item, MAX_NAME), replace
    local ok, err = pcall(ui.CraftEnchant, recipeID, 1, nil, loc)
    if not ok then
        stopRun("couldn't enchant: " .. tostring(err))
        return "couldn't enchant: " .. tostring(err)
    end
    return "enchanting " .. item .. " with " .. clean(name, MAX_NAME)
end

function ns.Make(args, again)
    local count, want = (args or ""):match("^(%S+)%s+(.+)$")
    want = want and want:lower():gsub("^%s+", ""):gsub("%s+$", "")
    if not want then return "use: /thor make <count|all> <item id or recipe name> [@place:itemID]" end
    -- An enchantment's item: @<place>:<itemID> at the end.
    local place, onItem, replace = want:match("%s*@(%d+):?(%d*):?(%d*)$")
    if place then want = want:gsub("%s*@%d+:?%d*:?%d*$", "") end
    place, onItem, replace = tonumber(place), tonumber(onItem) or 0, replace == "1"
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
                if not ok then said = "couldn't make it: " .. tostring(err)
                else startRun(name, GetTradeSkillItemLink and itemID(GetTradeSkillItemLink(i)), make) end
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
                if not ok then said = "couldn't make it: " .. tostring(err)
                else startRun(name, GetCraftItemLink and itemID(GetCraftItemLink(i)), 1) end
            end
            return said
        end
    end
    if C_TradeSkillUI and C_TradeSkillUI.GetAllRecipeIDs and C_TradeSkillUI.CraftRecipe then
        local okF, id, name, available = pcall(findModern, want)
        if okF and id and place then
            return enchant(id, name, place, onItem, replace)
        end
        if okF and id then
            local make, said = plan(name, available, nil)
            if make then
                local ok, err = pcall(C_TradeSkillUI.CraftRecipe, id, make)
                if not ok then said = "couldn't make it: " .. tostring(err)
                else startRun(name, nil, make) end
            end
            return said
        end
    end
    if not again then
        -- Its window may be opening right now (the app's open key): made as soon as it is.
        pendingMake = { args = args, before = GetTime() + 10 }
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
        "; bank items kept: " .. (function() local n = 0 for _ in pairs(m.bank.counts or {}) do n = n + 1 end return n end)() ..
        "; open keys: " .. (function()
            local k = {}
            for name, i in pairs(openKeys) do k[#k + 1] = name .. " " .. tostring(ns.ActionKeys[i]) end
            return #k > 0 and table.concat(k, ", ") or "none"
        end)() ..
        "; Make keys: " .. (makeBound and "on" or "off") ..
        "; enchantments: " .. (function()
            local n, worn, carried = 0, {}, {}
            for _, p in pairs(m.recipes) do
                for _, r in ipairs(p.rows) do
                    local fits = select(8, strsplit("\t", r))
                    if fits then
                        n = n + 1
                        for at in fits:gmatch("(%d+):") do
                            if tonumber(at) < 1000 then worn[at] = true else carried[at] = true end
                        end
                    end
                end
            end
            local function count(t) local c = 0 for _ in pairs(t) do c = c + 1 end return c end
            return n .. " (fit " .. count(worn) .. " worn, " .. count(carried) .. " in the bags)"
        end)() ..
        "; window open: " .. (openWindow or "none")
end
