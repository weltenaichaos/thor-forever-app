-- Chat.lua
-- Keeps the recent chat lines so the app's Chat tab can show them. Sent as
--   TH1|<session><tab><tab><channels><newline><id><tab><kind><tab><sender><tab><channel><tab><text>[<tab><class>]<newline>...
-- with only the lines the app has not been sent yet (after a refresh, the last
-- few again). <session> changes with every login or /reload, so the app knows
-- when the line numbers start over. <sender> is Name-Realm as the game gives it.
-- <class> is the sender's class number (1 warrior ... 8 mage, 9 warlock, 11 druid),
-- so the app colours names like the chat frame does; left out when the game doesn't say.
--
-- The field between the tabs is empty (it held the last people who wrote, which the app
-- no longer uses). Colour codes, links and icons are reduced to
-- plain text. During chat lockdown (boss fights) the game hides lines from
-- addons; those arrive as "(hidden by the game during combat)".
-- <channels> are the public channels you are in, like "1 General,2 Trade", so the
-- app can offer to write in each. Their lines are sent too, unless /thor chat channels off.
-- <channels> (and the tab before it) are only there when they changed, after a refresh
-- and every CHANNELS_AGAIN seconds; the app keeps the last ones meanwhile.
--
-- In a crowd there are more lines than the square carries. Whispers, your group's and
-- guild's lines and your own go first, each twice; then the newest say, yell, channel and
-- system lines, once each. Those not sent within STALE seconds are left out, so the app
-- shows what is being said now.
--
-- Answering: CTRL-SHIFT-F8 is the game's own Open Chat key (like Enter). The app
-- presses it, waits for TE1 to say the box is open, then types "/w Name ", "/r ",
-- "/s ", "/g ", "/1 " (a channel) or your group's command with your message and presses Enter.

local _, ns = ...

local KEEP = 60          -- lines remembered
local KEEP_CHANNEL = 20  -- of those, at most this many from public channels (Trade can be busy)
local RESEND = 10        -- lines sent again on a refresh
local MAX_TEXT = 200     -- bytes per line
local SENDS = 2          -- whispers, group, guild and your own lines go out twice, in case the app missed one
local STALE = 20         -- seconds: a crowd's line not sent by then is left out
local CHANNELS_AGAIN = 30 -- seconds: the channels go along again after this long
local PARTS = 2          -- a chat message takes at most this many parts of the square (a long line alone more)

local KINDS = {
    CHAT_MSG_SAY = "say", CHAT_MSG_YELL = "yell", CHAT_MSG_EMOTE = "emote", CHAT_MSG_TEXT_EMOTE = "emote",
    CHAT_MSG_WHISPER = "whisper", CHAT_MSG_WHISPER_INFORM = "whisper_to",
    CHAT_MSG_BN_WHISPER = "whisper", CHAT_MSG_BN_WHISPER_INFORM = "whisper_to",
    CHAT_MSG_PARTY = "party", CHAT_MSG_PARTY_LEADER = "party",
    CHAT_MSG_RAID = "raid", CHAT_MSG_RAID_LEADER = "raid", CHAT_MSG_RAID_WARNING = "raid",
    CHAT_MSG_INSTANCE_CHAT = "instance", CHAT_MSG_INSTANCE_CHAT_LEADER = "instance",
    CHAT_MSG_GUILD = "guild", CHAT_MSG_OFFICER = "guild",
    CHAT_MSG_SYSTEM = "system", CHAT_MSG_CHANNEL = "channel",
}

-- The lines that go first, and twice (the rest: say, yell, emote, system, public channels).
local IMPORTANT = {
    whisper = true, whisper_to = true, bnwhisper = true, bnwhisper_to = true,
    party = true, raid = true, instance = true, guild = true,
}

local lines = {}         -- { id, kind, sender, channel, text[, class] }
local sends = {}         -- id -> how often it went out
local mine = {}          -- id -> true for your own lines (sent twice, like whispers)
local writtenAt = {}     -- id -> GetTime() when it was written
local nextId = 1
local session = tostring(time())

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

-- The game's class numbers (as in its class list), by the names GetPlayerInfoByGUID gives.
local CLASS_IDS = {
    WARRIOR = 1, PALADIN = 2, HUNTER = 3, ROGUE = 4, PRIEST = 5, DEATHKNIGHT = 6, SHAMAN = 7,
    MAGE = 8, WARLOCK = 9, MONK = 10, DRUID = 11, DEMONHUNTER = 12, EVOKER = 13,
}

-- The class number of whoever wrote a line, from the line's GUID; nil when the game doesn't say.
local function classOf(guid)
    if guid == nil or secret(guid) or guid == "" or not GetPlayerInfoByGUID then return nil end
    local ok, _, file = pcall(GetPlayerInfoByGUID, guid)
    if not ok or file == nil or secret(file) then return nil end
    return CLASS_IDS[file]
end

-- The first n bytes of s or fewer, never half a letter (ä, é take two bytes): a cut
-- letter is no text, and the app couldn't read the rest of the message after it.
local function cut(s, n)
    if n <= 0 then return "" end
    if n >= #s then return s end
    while n > 0 do
        local b = s:byte(n + 1)
        if b < 128 or b >= 192 then break end   -- the next byte starts a letter: cut before it
        n = n - 1
    end
    return s:sub(1, n)
end
ns.ChatCut = cut   -- for the tests

-- Colour codes, links and icons as plain text; one line, no tabs.
local function plain(s)
    s = s:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")
    s = s:gsub("|H.-|h(.-)|h", "%1")
    s = s:gsub("|T.-|t", ""):gsub("|A.-|a", "")
    s = s:gsub("||", "|"):gsub("[\t\r\n]", " ")
    if #s > MAX_TEXT then s = cut(s, MAX_TEXT - 3) .. "…" end
    return s
end

local chatOwner = CreateFrame("Frame")
local chatPending = false

local function isMe(name)
    local me = UnitName("player")
    local realm = GetNormalizedRealmName and GetNormalizedRealmName()
    return name == me or (realm ~= nil and name == me .. "-" .. realm)
end

-- Whether the chat box opened, for the app:
--   TE1|n|open|<group>|<length>   or   TE1|n|closed|<group>|<length>
-- n counts the times the box got the cursor; <group> is the command for your
-- group right now ("/p ", "/raid " or "/i "). The app types your message only
-- after "open", so no letter ever reaches the game as a key binding (W would walk).
-- <length> is how long the box's text is (in bytes): said again when it changes in
-- the first seconds after the box opened, so the app sees whether its paste arrived.
local openN, openPending, openedAt = 0, false, -100
local reports = 0

local WATCH = 8   -- seconds after the box opened in which text changes are reported

local function editBox()
    local get = (ChatFrameUtil and ChatFrameUtil.GetActiveWindow) or ChatEdit_GetActiveWindow
    return get and get()
end

local function activeEditBox()
    local eb = editBox()
    return eb and eb:IsShown() and eb:HasFocus()
end

local function textLength()
    local eb = editBox()
    local text = eb and eb.GetText and eb:GetText()
    if type(text) ~= "string" or (issecretvalue and issecretvalue(text)) then return 0 end
    return #text
end

local function groupCommand()
    if IsInGroup(LE_PARTY_CATEGORY_INSTANCE) then return "/i " end
    if IsInRaid() then return "/raid " end
    return "/p "
end

function ns.ChatOpenPayload()
    if not openPending or GetTime() - openedAt < 0.1 then return nil end
    openPending = false
    -- The last field only counts the reports, so the same state said again is a new message.
    reports = reports + 1
    return "TE1|" .. openN .. "|" .. (activeEditBox() and "open" or "closed") .. "|" .. groupCommand() .. "|" .. textLength() .. "|" .. reports
end

-- Only watched, never called: an addon that opens the chat box itself makes the
-- game block its controller code (the "blocked from an action" popup).
local hooked = {}
local function hookEditBoxes()
    for i = 1, (NUM_CHAT_WINDOWS or 10) do
        local eb = _G["ChatFrame" .. i .. "EditBox"]
        if eb and not hooked[eb] then
            hooked[eb] = true
            eb:HookScript("OnEditFocusGained", function()
                openN, openPending, openedAt = openN + 1, true, GetTime()
            end)
            eb:HookScript("OnTextChanged", function()
                if GetTime() - openedAt < WATCH then openPending = true end
            end)
            -- Closed (sent or cancelled): said at once, so the app knows its Enter took.
            eb:HookScript("OnEditFocusLost", function() openPending = true end)
        end
    end
end

-- The chat key (CTRL-SHIFT-F8) is bound to the game's own "Open Chat" command,
-- exactly like Enter; the app then types "/g ", "/w Name " and so on with your text.
function ns.BindWhispers()
    if InCombatLockdown() then chatPending = true return end
    chatPending = false
    ClearOverrideBindings(chatOwner)
    if not ns.TapsEnabled() then return end
    SetOverrideBinding(chatOwner, true, ns.ActionKeys[ns.ChatKeys[8]], "OPENCHAT")
end

chatOwner:RegisterEvent("PLAYER_LOGIN")
chatOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
chatOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" then
        hookEditBoxes()
        -- 0.20.1 to 0.21.5 switched on the game's chat log (Logs/WoWChatLog.txt) for the
        -- app to read; the game writes it only every few minutes, so the chat is back on
        -- the square. Switched off again, once (a /chatlog of your own after that stays).
        if ThorCompanionDB and not ThorCompanionDB.chatLogOff then
            pcall(LoggingChat, false)
            ThorCompanionDB.chatLogOff = true
        end
    end
    if event == "PLAYER_LOGIN" or chatPending then ns.BindWhispers() end
end)

local function add(kind, sender, channel, text, class)
    if kind == "channel" and ThorCompanionDB and ThorCompanionDB.chatChannels == false then return end
    if secret(text) then text = "(hidden by the game during combat)" else text = plain(tostring(text or "")) end
    if secret(sender) then sender = "?" else sender = plain(tostring(sender or "")) end
    -- "2. Trade - City" is shown as "Trade", like the chat frame does.
    channel = (not secret(channel) and channel) and plain(tostring(channel)):gsub("^%d+%.%s*", ""):gsub("%s+%-%s+.*$", "") or ""
    -- The class goes last, so an older app shows the line as before.
    lines[#lines + 1] = { nextId, kind, sender, channel, text, kind ~= "system" and class or nil }
    if kind ~= "system" and sender ~= "" and isMe(sender) then mine[nextId] = true end
    writtenAt[nextId] = GetTime()
    nextId = nextId + 1
    -- Busy public channels make room among themselves, never by pushing out your
    -- group's, guild's or whisper lines.
    local channelLines, oldestChannel = 0, nil
    for i, l in ipairs(lines) do
        if l[2] == "channel" then
            channelLines = channelLines + 1
            oldestChannel = oldestChannel or i
        end
    end
    local drop = (channelLines > KEEP_CHANNEL or (#lines > KEEP and oldestChannel)) and oldestChannel
        or (#lines > KEEP and 1) or nil
    if drop then
        local id = lines[drop][1]
        sends[id], mine[id], writtenAt[id] = nil, nil, nil
        table.remove(lines, drop)
    end
    if kind ~= "channel" and kind ~= "system" and ns.ChatUrgent then ns.ChatUrgent() end
end

local f = CreateFrame("Frame")
for event in pairs(KINDS) do f:RegisterEvent(event) end
f:SetScript("OnEvent", function(_, event, text, sender, _, channelName, _, _, _, _, _, _, _, guid)
    local kind = KINDS[event]
    -- Battle.net whispers carry a protected name that /w cannot use.
    if event == "CHAT_MSG_BN_WHISPER" or event == "CHAT_MSG_BN_WHISPER_INFORM" then
        kind = kind == "whisper" and "bnwhisper" or "bnwhisper_to"
        sender, guid = "", nil
    end
    ns.Measured("chat lines", add, kind, sender, kind == "channel" and channelName or "", text, classOf(guid))
end)

-- The public channels you are in: "1 General,2 Trade,...". A mistake in reading them
-- (a value the game hides) gives none, never an error that stops the chat.
local function channels()
    local ok, text = pcall(function()
        local list = { GetChannelList() }
        local out = {}
        for i = 1, #list - 1, 3 do
            local id, name, disabled = list[i], list[i + 1], list[i + 2]
            if not secret(id) and not secret(name) and not secret(disabled) and tonumber(id) and name and not disabled then
                out[#out + 1] = tonumber(id) .. " " .. plain(tostring(name)):gsub("[,\t]", " ")
            end
        end
        return table.concat(out, ",")
    end)
    return ok and text or ""
end

-- How often line l goes out, and whether it still should now.
local function wanted(l)
    return (IMPORTANT[l[2]] or mine[l[1]]) and SENDS or 1
end
local function due(l, now)
    local n = sends[l[1]] or 0
    if n >= wanted(l) then return false end
    -- A crowd's line that waited too long is old news (whispers and the like always go).
    return n > 0 or IMPORTANT[l[2]] or mine[l[1]] or now - (writtenAt[l[1]] or now) <= STALE
end

local sentChannels, channelsAt = nil, -100

-- The lines still to send (as many as fit in PARTS parts of the square), or nil when
-- there are none (and the channels didn't change): whispers, your group's, guild's and
-- your own lines first (each twice, oldest first), then the newest of the others, once.
-- The app sorts them by number. With again, the last RESEND lines (for an app that
-- started late). Each line is whole text: a cut never splits a letter.
function ns.ChatPayload(again, peek)
    local now = GetTime()
    local chans = channels()
    local withChannels = again or chans ~= sentChannels or now - channelsAt > CHANNELS_AGAIN
    local head = "TH1|" .. session .. "\t" .. (withChannels and "\t" .. chans or "")
    local corners = ns.CornersOn()
    local capacity = corners and ns.CornerCapacity("chat") or ns.StripCapacity()
    local part = corners and ns.CornerPartBytes("chat") or ns.StripPartBytes()
    -- A long line may fill the whole square on its own; together, lines stay within PARTS
    -- parts, so each message is quick and a missed part loses less.
    local whole = capacity - #head - 1
    local room = math.min(whole, PARTS * part - #head - 1)
    local picked, used = {}, 0
    local function take(l)
        local limit = used == 0 and whole or room
        local entry = table.concat(l, "\t")
        -- A line too long for the square on its own is cut, or it would hold up the rest.
        if used == 0 and #entry + 1 > limit then
            l[5] = cut(l[5], #l[5] - (#entry + 1 - limit) - 3) .. "…"
            entry = table.concat(l, "\t")
            -- Still too long (the name alone, say): left out, it would never fit.
            if #entry + 1 > limit then
                if not peek then sends[l[1]] = wanted(l) end
                return
            end
        end
        if used + #entry + 1 > limit then return end
        picked[#picked + 1] = l
        used = used + #entry + 1
    end
    if again then
        for _, l in ipairs(lines) do
            if l[1] >= nextId - RESEND then take(l) end
        end
    else
        for _, l in ipairs(lines) do
            if (IMPORTANT[l[2]] or mine[l[1]]) and due(l, now) then take(l) end
        end
        for i = #lines, 1, -1 do
            local l = lines[i]
            if not (IMPORTANT[l[2]] or mine[l[1]]) and due(l, now) then take(l) end
        end
    end
    if #picked == 0 and chans == sentChannels and not again then return nil end
    table.sort(picked, function(a, b) return a[1] < b[1] end)
    local out = {}
    for i, l in ipairs(picked) do
        out[i] = table.concat(l, "\t")
        if not peek then sends[l[1]] = (sends[l[1]] or 0) + 1 end
    end
    if not peek and withChannels then sentChannels, channelsAt = chans, now end
    return head .. "\n" .. table.concat(out, "\n")
end

-- Whether there are lines the app hasn't been sent yet.
function ns.ChatPending()
    local now = GetTime()
    for _, l in ipairs(lines) do
        if due(l, now) then return true end
    end
    return false
end

-- For /thor chat channels on|off.
function ns.ChatChannels(on)
    ThorCompanionDB.chatChannels = on
end

-- In the first seconds after the chat box opened, while it stays open, its state is
-- said again every second: an Enter that got lost leaves no event behind, and the app
-- tries it once more. (Not longer: each report cuts into the square's other messages.)
C_Timer.NewTicker(1, function()
    if GetTime() - openedAt < 15 and activeEditBox() then openPending = true end
end)
