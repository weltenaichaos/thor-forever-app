-- Areas.lua
-- The real shapes of the quest objective areas, for the app's Map tab. The game
-- tells addons only one point per quest (C_QuestLog.GetQuestsOnMap); the shapes are
-- drawn by a special frame of the game's own (QuestPOIFrame, the one the world map
-- uses), which addons can't read either. So like the zone picture the app takes them
-- off the screen: the addon draws each quest's areas in a small copy of the zone map,
-- white on black, in a grid under the data square for a few seconds, while
--   TA1|mapID|left|top|width|height|stepX|stepY|cols|cell<newline>title<newline>...
-- says where the first copy is (game pixels from the top-left corner of the data
-- square), how big each copy is, the distance from one to the next across and down,
-- how many there are per row, the square's cell size, and which quest each copy shows
-- (row by row, named like the q places in Map.lua's TM1).
-- Shown after the zone picture (its key, and its first time in a zone) for all the
-- zone's quests, and by itself, out of combat, for a quest whose areas weren't shown
-- yet or changed (one of its objectives got done). `/thor auto off` stops the second.

local _, ns = ...

local SECONDS = 5
local SHORT_SECONDS = 3.5   -- a message that fits in one part: the app reads it at once
local AUTO_GAP = 20         -- seconds between two showings by itself
local CHECK_EVERY = 1       -- seconds between two looks for new or changed quests
local WIDTH, GAP, COLS, MAX = 192, 8, 5, 15
local frame, blobs = nil, {}
local shownUntil, shownMap, shownTitles, height = 0, nil, nil, 128
local note = "no quest areas shown yet"
local wantAll = nil         -- a map whose quests all come next (after its zone picture)

local function physical(f)
    local l, b, w, h = f:GetRect()
    if not l then return end
    local k = f:GetEffectiveScale() * select(2, GetPhysicalScreenSize()) / 768
    return l * k, (b + h) * k, w * k, h * k, k
end

-- Which objectives are done: the areas change when one is.
local function progress(questID)
    local ok, objectives = pcall(C_QuestLog.GetQuestObjectives, questID)
    if not ok or type(objectives) ~= "table" then return "" end
    local s = {}
    for i, o in ipairs(objectives) do s[i] = o.finished and "1" or "0" end
    return table.concat(s)
end

-- The quests with areas on the map (not those ready to turn in, they have none).
local function questsOn(mapID)
    local out = {}
    local ok, list = pcall(C_QuestLog.GetQuestsOnMap, mapID)
    if not ok or type(list) ~= "table" then return out end
    for _, q in ipairs(list) do
        local id = q.questID
        if id and not (C_QuestLog.ReadyForTurnIn and C_QuestLog.ReadyForTurnIn(id)) then
            local title = ns.MapLabel(C_QuestLog.GetTitleForQuestID(id))
            if title ~= "" then out[#out + 1] = { id = id, title = title } end
        end
    end
    return out
end

-- A setting the game may lack does not stop the drawing.
local function try(blob, method, ...)
    if blob[method] then pcall(blob[method], blob, ...) end
end

local function newBlob()
    local b = CreateFrame("QuestPOIFrame", nil, frame)
    -- Solid white inside and the world map's own edge: plain to tell from the black.
    try(b, "SetFillTexture", "Interface\\Buttons\\WHITE8X8")
    try(b, "SetFillAlpha", 255)
    try(b, "SetBorderTexture", "Interface\\WorldMap\\UI-QuestBlob-Outside")
    try(b, "SetBorderAlpha", 255)
    try(b, "SetBorderScalar", 1.0)
    return b
end

local function redraw()
    if not shownMap or not frame or not frame:IsShown() then return end
    for i, q in ipairs(shownTitles.quests) do
        local b = blobs[i]
        b:SetMapID(shownMap)
        b:DrawNone()
        b:DrawBlob(q.id, true)
    end
end

local function draw(mapID, list)
    if not frame then
        frame = CreateFrame("Frame", nil, UIParent)
        frame:SetFrameStrata("FULLSCREEN_DIALOG")
        -- Like the zone picture: 1 unit = 1 game pixel, whatever the UI scale.
        frame:SetIgnoreParentScale(true)
        local bg = frame:CreateTexture(nil, "BACKGROUND")
        bg:SetAllPoints()
        bg:SetColorTexture(0, 0, 0, 1)
    end
    for _, b in ipairs(blobs) do b:DrawNone() b:Hide() end
    -- The copies have the zone map's shape (3:2 for most zones).
    local layer = (C_Map.GetMapArtLayers(mapID) or {})[1]
    height = layer and layer.layerWidth > 0 and math.floor(WIDTH * layer.layerHeight / layer.layerWidth + 0.5) or math.floor(WIDTH / 1.5 + 0.5)
    local rows = math.ceil(#list / COLS)
    frame:SetScale(768 / select(2, GetPhysicalScreenSize()))
    frame:SetSize(GAP + math.min(#list, COLS) * (WIDTH + GAP), GAP + rows * (height + GAP))
    frame:ClearAllPoints()
    -- Under the data square, like the item icons, out of the way of the middle of the screen.
    frame:SetPoint("TOPRIGHT", ns.StripFrame(), "BOTTOMRIGHT", 0, -4)
    frame:Show()
    for i, q in ipairs(list) do
        local b = blobs[i] or newBlob()
        blobs[i] = b
        b:SetSize(WIDTH, height)
        b:ClearAllPoints()
        b:SetPoint("TOPLEFT", frame, "TOPLEFT", GAP + ((i - 1) % COLS) * (WIDTH + GAP), -(GAP + math.floor((i - 1) / COLS) * (height + GAP)))
        b:Show()
        b:SetMapID(mapID)
        b:DrawNone()
        b:DrawBlob(q.id, true)
    end
end

-- Where the copies are and which quest each shows (see the top of this file).
local function message()
    local sl, st = physical(ns.StripFrame())
    local cl, ct, _, _, k = physical(frame)
    if not sl or not cl then return nil end
    local r = function(v) return math.floor(v + 0.5) end
    local head = string.format("TA1|%d|%d|%d|%d|%d|%d|%d|%d|%d", shownMap, r(cl - sl + GAP * k), r(st - ct + GAP * k),
        r(WIDTH * k), r(height * k), r((WIDTH + GAP) * k), r((height + GAP) * k), COLS, ns.StripCellSize())
    return head .. "\n" .. table.concat(shownTitles, "\n")
end

local function show(mapID, list, how)
    local ok, err = pcall(draw, mapID, list)
    if not ok then
        note = "could not draw the quest areas: " .. tostring(err)
        if frame then frame:Hide() end
        return false
    end
    local titles = { quests = list }
    for i, q in ipairs(list) do titles[i] = q.title end
    shownMap, shownTitles = mapID, titles
    ThorCompanionDB.areas = ThorCompanionDB.areas or {}
    local seen = {}
    for _, q in ipairs(questsOn(mapID)) do seen[q.id] = (ThorCompanionDB.areas[mapID] or {})[q.id] end
    for _, q in ipairs(list) do seen[q.id] = q.progress end
    -- Only the quests still on the map are remembered, so the list doesn't grow.
    ThorCompanionDB.areas[mapID] = seen
    local text = message()
    shownUntil = GetTime() + ((text and #text <= ns.StripPartBytes()) and SHORT_SECONDS or SECONDS)
    -- Drawn again a moment later too: a copy made this frame may not have its size yet.
    C_Timer.After(0.2, function() pcall(redraw) end)
    note = string.format("showed the areas of %d quest%s on map %d %s at %s", #list, #list == 1 and "" or "s", mapID, how, date("%H:%M:%S"))
    return true
end

-- After the zone picture: all of that zone's quests.
function ns.AreasWithPicture(mapID)
    wantAll = mapID
end

-- Shows what is due, out of combat: all quests after a zone picture, else (by itself)
-- the quests whose areas were not shown yet or changed. True when it showed some.
local autoAt, checkedAt = -100, -100
function ns.AreasAuto()
    if InCombatLockdown() or GetTime() <= shownUntil then return false end
    local mapID = C_Map.GetBestMapForUnit("player")
    if not mapID then return false end
    local all = wantAll == mapID
    if not all and (ThorCompanionDB.auto == false or GetTime() - autoAt < AUTO_GAP or GetTime() - checkedAt < CHECK_EVERY) then return false end
    checkedAt = GetTime()
    wantAll = nil
    local seen = ThorCompanionDB.areas and ThorCompanionDB.areas[mapID] or {}
    local list = {}
    for _, q in ipairs(questsOn(mapID)) do
        q.progress = progress(q.id)
        if (all or seen[q.id] ~= q.progress) and #list < MAX then list[#list + 1] = q end
    end
    if #list == 0 then
        if all then note = "no quest areas on map " .. mapID .. " at " .. date("%H:%M:%S") end
        return false
    end
    autoAt = GetTime()
    return show(mapID, list, all and "after the zone picture" or "by itself")
end

-- TA1 while the areas are up, else nil (and they go away).
function ns.AreasPayload()
    if GetTime() > shownUntil then
        if frame and frame:IsShown() then
            for _, b in ipairs(blobs) do b:DrawNone() end
            frame:Hide()
        end
        return nil
    end
    return message()
end

function ns.AreasInfo()
    return "quest areas: " .. note
end
