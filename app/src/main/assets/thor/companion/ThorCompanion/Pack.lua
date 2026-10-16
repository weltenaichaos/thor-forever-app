-- Pack.lua
-- Every message the squares carry is made smaller first, the app writes it out again
-- (Compact.kt): fewer bytes, fewer parts, the data is there sooner (after a login above all).
--   Z<packed>   instead of the message, when that is shorter. In <packed>:
--     byte 1, then 3 digits    the next 5 to 68 bytes are the same as some bytes before:
--                              2 digits how far back (1 to 4095), 1 digit the length less 5
--     byte w (2 to 6), then w digits   a number (4 or more decimal digits, no leading 0)
--     byte 7, then 3 digits    0.dddd (a map place, as 0 to 9999)
--     byte 8, then 2 digits    0.ddd (as 0 to 999)
--     any other byte           itself
-- Digits are of 64 (0-9 A-Z a-z + /), as in the short forms. A repeat never starts or ends
-- inside a letter of more than one byte (ä, é), so the packed message is good text too.
-- Messages that hold bytes 1 to 8 themselves go out as they are.

local _, ns = ...

local DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+/"
local digit = {}
for i = 1, 64 do digit[i - 1] = DIGITS:sub(i, i) end

local MIN, MAX, FAR = 5, 68, 4095
local BUCKETS = 4093
local DEPTH = 24          -- earlier places looked at for each repeat

-- Reused for every message (no new tables, nothing for the garbage collector).
local head, prev = {}, {}
for i = 0, BUCKETS - 1 do head[i] = 0 end
local base = 0            -- positions are stored as base + i: older ones are below base
local out = {}

local byte, char, sub = string.byte, string.char, string.sub

local function isDigit(b) return b and b >= 48 and b <= 57 end
local function isTail(b) return b and b >= 128 and b < 192 end   -- the 2nd, 3rd, ... byte of a letter

local function key(s, i)
    local a, b, c, d = byte(s, i, i + 3)
    return ((a * 31 + b) * 31 + c) * 31 % BUCKETS + d   -- d < 256, so below BUCKETS + 256
end

function ns.Pack(s)
    local n = #s
    if n < 8 or s:find("[\1-\8]") then return s end
    if base > 1e9 then
        base = 0
        for i = 0, BUCKETS + 255 do head[i] = 0 end
    end
    local b0 = base
    base = base + n + 1
    local o, i = 0, 1
    local function remember(at)
        if at + 3 <= n then
            local k = key(s, at)
            prev[at] = head[k] or 0
            head[k] = b0 + at
        end
    end
    while i <= n do
        local c = byte(s, i)
        local done = false
        -- A number: four or more digits, not starting with 0, not the end of a longer number.
        if c >= 49 and c <= 57 and not isDigit(byte(s, i - 1)) then
            local j = i
            while j < n and isDigit(byte(s, j + 1)) do j = j + 1 end
            local len = j - i + 1
            if len >= 4 and len <= 10 then
                local v = tonumber(sub(s, i, j))
                local w = 2
                while v >= 64 ^ w do w = w + 1 end
                if 1 + w < len then
                    o = o + 1; out[o] = char(w)
                    for p = w - 1, 0, -1 do
                        o = o + 1; out[o] = digit[math.floor(v / 64 ^ p) % 64]
                    end
                    for p = i, j do remember(p) end
                    i = j + 1
                    done = true
                end
            end
        end
        -- 0.dddd and 0.ddd, as the places on the map and the quests' waypoints are written.
        if not done and c == 48 and byte(s, i + 1) == 46 and not isDigit(byte(s, i - 1)) then
            local j = i + 1
            while j < n and isDigit(byte(s, j + 1)) do j = j + 1 end
            local decimals = j - i - 1
            if decimals == 4 or decimals == 3 then
                local v = tonumber(sub(s, i + 2, j))
                local w = decimals == 4 and 3 or 2
                o = o + 1; out[o] = char(decimals == 4 and 7 or 8)
                for p = w - 1, 0, -1 do
                    o = o + 1; out[o] = digit[math.floor(v / 64 ^ p) % 64]
                end
                for p = i, j do remember(p) end
                i = j + 1
                done = true
            end
        end
        if not done and i + MIN - 1 <= n and not isTail(c) then
            -- The longest earlier repeat of what starts here.
            local bestLen, bestAt = 0, 0
            local at = head[key(s, i)] or 0
            local tries = 0
            while at > b0 and tries < DEPTH do
                local from = at - b0
                if i - from > FAR then break end
                local len = 0
                while len < MAX and i + len <= n and byte(s, from + len) == byte(s, i + len) do len = len + 1 end
                -- Not ending inside a letter.
                while len >= MIN and isTail(byte(s, i + len)) do len = len - 1 end
                if len > bestLen then bestLen, bestAt = len, from end
                if len == MAX then break end
                at = prev[from] or 0
                tries = tries + 1
            end
            if bestLen >= MIN then
                local dist, l = i - bestAt, bestLen - MIN
                o = o + 1; out[o] = "\1"
                o = o + 1; out[o] = digit[math.floor(dist / 64)]
                o = o + 1; out[o] = digit[dist % 64]
                o = o + 1; out[o] = digit[l]
                for p = i, i + bestLen - 1 do remember(p) end
                i = i + bestLen
                done = true
            end
        end
        if not done then
            o = o + 1; out[o] = sub(s, i, i)
            remember(i)
            i = i + 1
        end
    end
    local packed = "Z" .. table.concat(out, "", 1, o)
    for k = 1, o do out[k] = nil end
    if #packed < n then return packed end
    return s
end
