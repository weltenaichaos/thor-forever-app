-- Quests.lua
-- The app's Quests tab and its level checker on the Character tab:
--   TL1|lastKill|readyCount|readyXP|pages<newline><questID><tab><state><tab><xp><tab><title><tab><objective>;<objective>...<tab><level><tab><waypoint><tab><place><newline>...
-- lastKill is the experience the last kill gave without its rested bonus (0 until
-- there was one), readyCount and readyXP count all quests ready to turn in and the
-- experience they give. <state> is r (ready to turn in), z (in this zone) or o
-- (elsewhere); quests in this zone come first. A log too long for one square goes
-- out in pages: the rest as TL2 (see below), <pages> says how many there are. Objectives are the game's own text, like "4/8 Boar Ribs".
-- <level> is the quest's level (0 when unknown), for the colour of its title.
-- <waypoint> is where to go next, "mapID:x:y:zone" (empty when the game doesn't say):
-- for a quest ready to turn in, whoever takes it (the "?" the world map shows for it,
-- also in another zone), else where the game's own arrow points. <place> is the town
-- there (empty when none is near), for the app's errands; without a spot, the town
-- the quest's text names for the turn-in, or at least the zone it is handed in in.

local _, ns = ...

local MAX_TITLE = 40
local MAX_OBJECTIVE = 40

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function number(v)
    if v == nil or secret(v) then return nil end
    return tonumber(v)
end

local function clean(s, max)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("[\t\r\n;|]", " ")
    if #s > max then s = s:sub(1, max - 1) .. "…" end
    return s
end

-- The last kills' experience: each time the experience can be read, what was gained
-- since the last reading, less the rested bonus (what the rested pool lost), is
-- shared out over the kills the game reported in between. Readings with a quest
-- turned in, or without kills, are left out. (In combat the game may hide the
-- experience from addons; then the next reading covers all kills of the fight.)
ThorCompanionKill = ThorCompanionKill  -- saved per character (see the .toc)
local lastXP, lastMax, lastRested, lastLevel
local kills, questsDone = 0, 0

local function measure()
    local xp, max = number(UnitXP("player")), number(UnitXPMax("player"))
    local rested = number(GetXPExhaustion()) or 0
    local level = number(UnitLevel("player"))
    if not xp or not max or not level then return end
    if lastXP and lastLevel and kills > 0 and questsDone == 0 then
        local gained = 0
        if level == lastLevel then gained = xp - lastXP
        elseif level == lastLevel + 1 then gained = lastMax - lastXP + xp end
        local base = gained - math.max(0, (lastRested or 0) - rested)
        if base > 0 then ThorCompanionKill = math.floor(base / kills + 0.5) end
    end
    lastXP, lastMax, lastRested, lastLevel = xp, max, rested, level
    kills, questsDone = 0, 0
end

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("PLAYER_XP_UPDATE")
events:RegisterEvent("PLAYER_LEVEL_UP")
events:RegisterEvent("PLAYER_REGEN_ENABLED")
events:RegisterEvent("CHAT_MSG_COMBAT_XP_GAIN")
events:RegisterEvent("QUEST_TURNED_IN")
events:SetScript("OnEvent", function(_, event)
    if event == "CHAT_MSG_COMBAT_XP_GAIN" then kills = kills + 1 return end
    if event == "QUEST_TURNED_IN" then questsDone = questsDone + 1 return end
    pcall(measure)
end)

local function rewardXP(id)
    local ok, xp = pcall(GetQuestLogRewardXP, id)
    return ok and number(xp) or 0
end

local function ready(id)
    local ok, r = pcall(C_QuestLog.ReadyForTurnIn, id)
    if ok and r then return true end
    ok, r = pcall(C_QuestLog.IsComplete, id)
    return ok and r == true
end

-- The town the quest's own text names for handing it in ("Return to Zinge in the
-- Undercity." gives "Undercity", "... at Sentinel Hill." gives "Sentinel Hill"): the
-- last "in" or "at" followed by a name, for when the game gives no spot to go to.
local function namedPlace(id)
    local index = C_QuestLog.GetLogIndexForQuestID and C_QuestLog.GetLogIndexForQuestID(id)
    if not index or not GetQuestLogCompletionText then return "" end
    local ok, text = pcall(GetQuestLogCompletionText, index)
    if not ok or type(text) ~= "string" or secret(text) then return "" end
    local place, last = nil, 0
    for _, word in ipairs({ "in", "at" }) do
        for pos, p in text:gmatch("() " .. word .. " ([^%.,;!]+)") do
            p = p:gsub("^[Tt]he ", ""):gsub("%s+$", "")
            -- A name, not "at once" or "in time".
            if pos > last and p:match("^%u") then place, last = p, pos end
        end
    end
    return place or ""
end

-- Where a quest is handed in, from the game's own quest marks, like the world map
-- finds it when you click the quest in its log: the map it opens for the quest
-- (GetQuestUiMapID, not following the arrow) and the quest's "?" on that map. Also
-- when that is in another zone. The map alone when it has no mark for the quest.
local function turnInSpot(id)
    if not GetQuestUiMapID then return end
    local ok, mapID = pcall(GetQuestUiMapID, id, true)
    mapID = ok and number(mapID)
    if not mapID or mapID <= 0 then return end
    local okList, list = pcall(C_QuestLog.GetQuestsOnMap, mapID)
    if okList and type(list) == "table" then
        for _, q in ipairs(list) do
            local x, y = number(q.x), number(q.y)
            if q.questID == id and x and y and x > 0 and y > 0 then
                -- A mark on a continent map: on the zone map there instead, so the zone has its name.
                local info = C_Map.GetMapInfo(mapID)
                if info and Enum.UIMapType and info.mapType and info.mapType < Enum.UIMapType.Zone then
                    local okZone, zone, zx, zy = pcall(function()
                        local z = C_Map.GetMapInfoAtPosition(mapID, x, y)
                        if not z or z.mapID == mapID then return end
                        local continent, world = C_Map.GetWorldPosFromMapPos(mapID, CreateVector2D(x, y))
                        local _, pos = C_Map.GetMapPosFromWorldPos(continent, world, z.mapID)
                        if pos then return z.mapID, pos:GetXY() end
                    end)
                    if okZone and zone and zx and zy and zx > 0 and zy > 0 and zx < 1 and zy < 1 then mapID, x, y = zone, zx, zy end
                end
                return mapID, x, y
            end
        end
    end
    return mapID
end

local function spot(mapID, x, y)
    local info = C_Map.GetMapInfo(mapID)
    local okPlace, place = pcall(ns.PlaceName, mapID, x, y)
    return string.format("%d:%.3f:%.3f:%s", mapID, x, y, clean(info and info.name, 24)), okPlace and clean(place, 24) or ""
end

-- Where to go next for a quest, and the town there: for a quest ready to turn in its
-- "?" (turnInSpot), else (and for the others) where the game's own arrow points, which
-- may also be in another zone. Without either, for a quest ready to turn in, the town
-- its text names, or at least the zone of its "?" (the arrow has no spot for many
-- turn-ins in another zone, often one you haven't been to).
local function waypoint(id, isReady)
    local turnInMap
    if isReady then
        local ok, mapID, x, y = pcall(turnInSpot, id)
        if ok and mapID and x then return spot(mapID, x, y) end
        if ok then turnInMap = mapID end
    end
    local ok, mapID, x, y = pcall(C_QuestLog.GetNextWaypoint, id)
    if ok and mapID and not secret(mapID) and x and y then
        mapID, x, y = number(mapID), number(x), number(y)
        if mapID and x and y then return spot(mapID, x, y) end
    end
    if not isReady then return "", "" end
    local okNamed, named = pcall(namedPlace, id)
    named = okNamed and clean(named, 24) or ""
    if named == "" and turnInMap then
        local info = C_Map.GetMapInfo(turnInMap)
        named = clean(info and info.name, 24)
    end
    return "", named
end

-- The quest log's quests (no headers or hidden ones), each like C_QuestLog.GetInfo's
-- table: questID, title, level, difficultyLevel, isOnMap, plus logIndex and isComplete
-- (1 done, -1 failed) for the Classic games. The current game (and WoW Forever) has
-- C_QuestLog.GetInfo; Classic Era and Anniversary only have GetQuestLogTitle, where a
-- quest counts as "in this zone" when it is under the header named like the zone.
local function logQuests()
    local list = {}
    if C_QuestLog.GetNumQuestLogEntries and C_QuestLog.GetInfo then
        for i = 1, C_QuestLog.GetNumQuestLogEntries() or 0 do
            local info = C_QuestLog.GetInfo(i)
            if info and not info.isHeader and not info.isHidden and info.questID then list[#list + 1] = info end
        end
    elseif GetNumQuestLogEntries and GetQuestLogTitle then
        local zone = GetRealZoneText and GetRealZoneText() or nil
        local header
        for i = 1, (GetNumQuestLogEntries()) or 0 do
            local title, level, _, isHeader, _, isComplete, _, questID = GetQuestLogTitle(i)
            if isHeader then
                header = title
            elseif title and questID and questID > 0 then
                list[#list + 1] = { questID = questID, title = title, level = level, logIndex = i,
                    isComplete = isComplete, isOnMap = zone ~= nil and header == zone }
            end
        end
    end
    return list
end

-- A quest's objectives as { text, finished }: C_QuestLog.GetQuestObjectives, else (in
-- the Classic games, when that has nothing) the quest log's own lines.
local function objectivesOf(info)
    local ok, list = pcall(C_QuestLog.GetQuestObjectives, info.questID)
    if ok and type(list) == "table" and #list > 0 then return list end
    list = {}
    if info.logIndex and GetNumQuestLeaderBoards and GetQuestLogLeaderBoard then
        for j = 1, (GetNumQuestLeaderBoards(info.logIndex)) or 0 do
            local text, _, finished = GetQuestLogLeaderBoard(j, info.logIndex)
            list[#list + 1] = { text = text, finished = finished }
        end
    end
    return list
end

-- The whole log, cut into pages that fit the square: page 1 goes out as TL1 (with the
-- level checker's numbers), the others as
--   TL2|<page>|<pages><newline><quest rows like TL1's>...
-- Quests in this zone come first.
local function buildLog()
    local here, elsewhere = {}, {}
    local readyCount, readyXP = 0, 0
    for _, info in ipairs(logQuests()) do
        do
            local id = info.questID
            local xp = rewardXP(id)
            local isReady = ready(id) or info.isComplete == 1 or info.isComplete == true
            if isReady then
                readyCount = readyCount + 1
                readyXP = readyXP + xp
            end
            local objectives = {}
            for _, o in ipairs(objectivesOf(info)) do
                if o.text and not secret(o.text) and o.text ~= "" then
                    objectives[#objectives + 1] = (o.finished and "+" or "") .. clean(o.text, MAX_OBJECTIVE)
                end
            end
            local state = isReady and "r" or (info.isOnMap and "z" or "o")
            local level = number(info.difficultyLevel) or 0
            if level <= 0 then level = number(info.level) or 0 end
            local where, place = waypoint(id, isReady)
            local entry = table.concat({ id, state, xp, clean(info.title, MAX_TITLE), table.concat(objectives, ";"), level, where, place }, "\t")
            if state == "o" then elsewhere[#elsewhere + 1] = entry else here[#here + 1] = entry end
        end
    end
    local pages, page, used = {}, {}, 0
    local room = ns.StripCapacity() - 40   -- room for either head
    for _, list in ipairs({ here, elsewhere }) do
        for _, e in ipairs(list) do
            if #page > 0 and used + #e + 1 > room then
                pages[#pages + 1] = page
                page, used = {}, 0
            end
            if #e + 1 <= room then
                page[#page + 1] = e
                used = used + #e + 1
            end
        end
    end
    pages[#pages + 1] = page
    return string.format("TL1|%d|%d|%d|%d", ThorCompanionKill or 0, readyCount, readyXP, #pages), pages
end

-- Built once for both TL1 and TL2, and again only when the quest log, the zone or your
-- experience changes (see ns.Remember).
local build = ns.Remember(buildLog, { "QUEST_LOG_UPDATE", "QUEST_WATCH_UPDATE", "UNIT_QUEST_LOG_CHANGED",
    "QUEST_ACCEPTED", "QUEST_REMOVED", "QUEST_TURNED_IN", "ZONE_CHANGED", "ZONE_CHANGED_NEW_AREA",
    "ZONE_CHANGED_INDOORS", "PLAYER_XP_UPDATE", "PLAYER_LEVEL_UP", "QUEST_POI_UPDATE" }, 5)

-- The pages after the first the app hasn't had twice since they changed, and whether
-- one of them hasn't gone out once.
local sentPage, sentCount = {}, {}
local pending = true

function ns.QuestsPayload()
    local head, pages = build()
    if #pages <= 1 then pending = false end
    return head .. (#pages[1] > 0 and ("\n" .. table.concat(pages[1], "\n")) or "")
end

local pagesSum = 0

-- The checksum (as TS1's) over the pages after the first, one after another, as last
-- worked out: 0 while there is only one page.
function ns.QuestPagesSum()
    return pagesSum
end

function ns.QuestPagesPayload()
    local _, pages = build()
    local all = {}
    for n = 2, #pages do all[#all + 1] = "TL2|" .. n .. "|" .. #pages .. "\n" .. table.concat(pages[n], "\n") end
    local h = 0
    local joined = table.concat(all)
    for i = 1, #joined do h = (h * 31 + joined:byte(i)) % 65536 end
    pagesSum = h
    for n = 2, #pages do
        local p = all[n - 1]
        if p ~= sentPage[n] then sentPage[n], sentCount[n] = p, 0 end
    end
    -- Every page once before any goes the second time.
    local pick
    for times = 0, 1 do
        for n = 2, #pages do
            if not pick and sentCount[n] == times then pick = n end
        end
    end
    if pick then sentCount[pick] = sentCount[pick] + 1 end
    pending = false
    for n = 2, #pages do
        if sentCount[n] == 0 then pending = true end
    end
    return pick and all[pick - 1] or nil
end

-- True while a page (new or changed) has not gone out once: the pages then go before
-- the rest of the turns, or a long quest log took a minute behind the item names.
function ns.QuestPagesPending()
    return pending
end

-- Every page again (a refresh, or an app that just started).
function ns.QuestPagesAgain()
    sentPage, sentCount, pending = {}, {}, true
end
