-- Corners.lua
-- With /thor shape corners (the default) the data is split over the four corners of the
-- screen, each square as big as what it carries, so the one that changes all the time
-- (your position) is the smallest:
--   top left      the position (TP2|mapID|x|y|facing)            COLS x 5 cells
--   bottom left   the chat, the chat box and tap answers (TH1, TE1, TU1)  COLS x 10
--   bottom right  the status (TS1)                               COLS x 12
--   top right     everything else (Strip.lua's data square)      COLS x 16
-- Each square is drawn like the colour bar (same sync, calibration and 6 bits a cell),
-- with its own header version, so the app knows which corner it reads. A square is only
-- there while it sends; it goes see-through a moment after.

local _, ns = ...

local COLS = 20
local IDLE = 1.5          -- seconds after the last part before a square goes away
local STEP = 0.45         -- each further part of a longer message
local HOLD = 0.6          -- a message (or its first part) stays at least this long

-- Where each corner goes: its version, rows and anchor (x, y: the direction in from the corner).
local KINDS = {
    position = { version = 6, rows = 5, point = "TOPLEFT", dx = 1, dy = -1 },
    chat = { version = 7, rows = 10, point = "BOTTOMLEFT", dx = 1, dy = 1 },
    status = { version = 8, rows = 12, point = "BOTTOMRIGHT", dx = -1, dy = 1 },
}

local function partBytes(rows) return ns.ColourPartBytes(rows, ns.Dense()) end

local squares = {}

local function cellSize()
    local cell = ThorCompanionDB and ThorCompanionDB.cell or 3
    return cell < 3 and 3 or cell   -- the colours blur into each other below 3 (as for the bar)
end

local function place(sq)
    local cell = cellSize()
    local _, h = GetPhysicalScreenSize()
    sq.frame:SetScale(768 / h)   -- 1 unit = 1 physical pixel, whatever the UI scale
    sq.frame:SetSize(COLS * cell, sq.kind.rows * cell)
    sq.frame:ClearAllPoints()
    -- Two pixels in from the edges: the app finds a square by the edges between its cells.
    local inset = ThorCompanionDB and ThorCompanionDB.cornerInset or 2
    sq.frame:SetPoint(sq.kind.point, UIParent, sq.kind.point, sq.kind.dx * inset, sq.kind.dy * inset)
    for i = 0, COLS * sq.kind.rows - 1 do
        local t = sq.cells[i]
        t:SetSize(cell, cell)
        t:ClearAllPoints()
        t:SetPoint("TOPLEFT", sq.frame, "TOPLEFT", (i % COLS) * cell, -math.floor(i / COLS) * cell)
    end
    sq.rows = nil
end

local function square(name)
    local sq = squares[name]
    if sq then return sq end
    local kind = KINDS[name]
    local frame = CreateFrame("Frame", "ThorCompanionCorner" .. name, UIParent)
    frame:SetIgnoreParentScale(true)
    frame:SetFrameStrata("TOOLTIP")
    frame:SetFrameLevel(9000)
    frame:SetAlpha(0)
    sq = { kind = kind, frame = frame, cells = {}, seq = 0, parts = {}, part = 1, shownAt = -100, drawnAt = -100, nextAt = 0 }
    for i = 0, COLS * kind.rows - 1 do
        local t = frame:CreateTexture(nil, "OVERLAY")
        t:SetSnapToPixelGrid(false)
        t:SetTexelSnappingBias(0)
        t:SetColorTexture(0, 0, 0, 1)
        sq.cells[i] = t
    end
    place(sq)
    frame:SetShown(not (ThorCompanionDB and ThorCompanionDB.hidden))
    squares[name] = sq
    return sq
end

local function draw(sq)
    local cells = sq.cells
    local rows = ns.ColourCells(sq.kind.version, sq.seq, (sq.part - 1) * 16 + (#sq.parts - 1), sq.parts[sq.part], ns.Dense(),
        function(i, r, g, b) cells[i]:SetColorTexture(r, g, b) end)
    -- Only the rows this part needs: a short message, a small square.
    if rows ~= sq.rows then
        sq.rows = rows
        sq.frame:SetHeight(rows * cellSize())
        for i = 0, COLS * sq.kind.rows - 1 do cells[i]:SetShown(i < rows * COLS) end
    end
    sq.drawnAt = GetTime()
    sq.frame:SetAlpha(1)
end

-- The longest message a corner takes (split over up to 4 parts).
function ns.CornerCapacity(name)
    return partBytes(KINDS[name].rows) * 4
end

-- True while the corner's message has not been up for its time, or has parts still to show.
function ns.CornerBusy(name)
    local sq = squares[name]
    if not sq then return false end
    return GetTime() - sq.shownAt < (sq.hold or HOLD) or sq.part < #sq.parts
end

-- Shows payload in the corner (hold: how long it stays at least).
function ns.CornerWrite(name, payload, hold)
    local sq = square(name)
    if #payload > ns.CornerCapacity(name) then payload = payload:sub(1, ns.CornerCapacity(name)) end
    sq.seq = (sq.seq + 1) % 256
    local size = partBytes(sq.kind.rows)
    sq.parts = {}
    for p = 1, math.max(1, math.ceil(#payload / size)) do sq.parts[p] = payload:sub((p - 1) * size + 1, p * size) end
    sq.part, sq.hold = 1, hold
    local now = GetTime()
    sq.shownAt, sq.nextAt = now, now + HOLD - 0.02
    draw(sq)
end

-- The next parts, and the squares going away once quiet.
local function step()
    local now = GetTime()
    for _, sq in pairs(squares) do
        if sq.part < #sq.parts and now >= sq.nextAt then
            sq.part = sq.part + 1
            sq.nextAt = now + STEP - 0.02
            draw(sq)
        elseif sq.part >= #sq.parts and sq.frame:GetAlpha() > 0 and now - sq.drawnAt > IDLE
            and now - sq.shownAt > (sq.hold or HOLD) + IDLE then
            sq.frame:SetAlpha(0)
        end
    end
end
C_Timer.NewTicker(0.05, function() ns.Measured("corners: next part", step) end)

-- /thor cell, /thor hide and show, and a change of shape.
function ns.CornersRefresh()
    for _, sq in pairs(squares) do
        place(sq)
        sq.frame:SetShown(ns.CornersOn() and not (ThorCompanionDB and ThorCompanionDB.hidden))
    end
end

-- For /thor info.
function ns.CornersInfo()
    local out = {}
    for name, sq in pairs(squares) do
        local l, b, w, h = sq.frame:GetRect()
        out[#out + 1] = string.format("%s %s,%s %sx%s alpha %.0f", name, tostring(l), tostring(b), tostring(w), tostring(h), sq.frame:GetAlpha())
    end
    return #out > 0 and table.concat(out, "; ") or "no corner squares drawn yet"
end
