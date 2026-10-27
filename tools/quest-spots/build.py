"""Builds the companion's quest spots for Classic Era and Anniversary from pfQuest.

Those games have no quest areas on their own map, so the app draws circles from this
list instead. pfQuest (https://github.com/shagu/pfQuest, MIT licence, see NOTICE) has
where every quest's mobs, objects and items are, from the open VMaNGOS/CMaNGOS data.

    python3 build.py <path to a pfQuest checkout> <assets dir>

The pfQuest files are only read as data (a small parser for their table literals);
nothing from them is run.

Writes quest-spots-1.txt (Classic Era: pfQuest's vanilla data) and quest-spots-2.txt
(Anniversary: vanilla patched with pfQuest's -tbc files), one quest per line:
    questID|o area x y,area x y,...|t area x y,...
o: middles of the groups where the objectives are, t: where the quest is handed in.
area is the zone's AreaTable ID; x and y are 0 to 1000 on that zone's map.
"""
import os
import re
import sys

GROUP = 8.0           # map percent: spawns closer than this are one circle
PER_TARGET = 3        # circles per mob, object or item
PER_QUEST = 8         # circles per quest
DROPS = 4             # mobs or objects per item, the likeliest first

TOKEN = re.compile(r'\s*(?:(--[^\n]*)|(\[)|(\])|(\{)|(\})|(=)|(,)|("(?:[^"\\]|\\.)*")|(-?\d+(?:\.\d+)?(?:e-?\d+)?)|(true|false|nil))', re.S)


def parse_value(s, i):
    """A Lua table literal, string, number or boolean at s[i:]; returns (value, next index)."""
    m = TOKEN.match(s, i)
    if not m:
        raise ValueError(f"unexpected text at {i}: {s[i:i + 40]!r}")
    if m.group(4):
        return parse_table(s, m.end())
    if m.group(8):
        return m.group(8)[1:-1].replace('\\"', '"').replace("\\\\", "\\"), m.end()
    if m.group(9):
        v = m.group(9)
        return (float(v) if any(c in v for c in ".e") else int(v)), m.end()
    if m.group(10):
        return {"true": True, "false": False, "nil": None}[m.group(10)], m.end()
    if m.group(1):
        return parse_value(s, m.end())
    raise ValueError(f"unexpected token at {i}: {m.group(0)!r}")


def parse_table(s, i):
    out, n = {}, 1
    while True:
        m = TOKEN.match(s, i)
        if not m:
            raise ValueError(f"unexpected text at {i}: {s[i:i + 40]!r}")
        if m.group(1) or m.group(7):
            i = m.end()
            continue
        if m.group(5):
            return out, m.end()
        if m.group(2):
            key, i = parse_value(s, m.end())
            m2 = TOKEN.match(s, i)
            assert m2 and m2.group(3), f"expected ] at {i}"
            m3 = TOKEN.match(s, m2.end())
            assert m3 and m3.group(6), f"expected = at {m2.end()}"
            out[key], i = parse_value(s, m3.end())
        else:
            out[n], i = parse_value(s, i)
            n += 1


def read(db, name, suffix):
    """pfDB["name"]["data<suffix>"] from db/<name><suffix>.lua."""
    s = open(os.path.join(db, f"{name}{suffix}.lua"), encoding="utf-8").read()
    head = f'pfDB["{name}"]["data{suffix}"] = '
    at = s.index(head) + len(head)
    value, _ = parse_value(s, at)
    return value


def merged(db, name, tbc):
    base = read(db, name, "")
    if tbc:
        for k, v in read(db, name, "-tbc").items():
            if v == "_":
                base.pop(k, None)
            else:
                base[k] = v
    return base


def ids_of(v):
    return list(v.values()) if isinstance(v, dict) else list(v or [])


def groups(coords):
    """Middles of the groups of spawns, biggest first: [(area, x, y, count)]."""
    out = []
    for c in ids_of(coords):
        c = ids_of(c)
        if len(c) < 3:
            continue
        x, y, area = c[0], c[1], c[2]
        if not area or x <= 0 or y <= 0:
            continue
        for g in out:
            if g[0] == area and abs(g[1] / g[3] - x) < GROUP and abs(g[2] / g[3] - y) < GROUP:
                g[1] += x
                g[2] += y
                g[3] += 1
                break
        else:
            out.append([area, x, y, 1])
    out.sort(key=lambda g: -g[3])
    return [(g[0], g[1] / g[3], g[2] / g[3], g[3]) for g in out]


def coords_of(table, ident):
    entry = table.get(ident)
    return entry.get("coords") or {} if isinstance(entry, dict) else {}


def build(db, tbc):
    quests = merged(db, "quests", tbc)
    units = merged(db, "units", tbc)
    objects = merged(db, "objects", tbc)
    items = merged(db, "items", tbc)
    triggers = merged(db, "areatrigger", tbc)

    def spots(kind, ident):
        if kind == "U":
            return groups(coords_of(units, ident))[:PER_TARGET]
        if kind == "O":
            return groups(coords_of(objects, ident))[:PER_TARGET]
        if kind == "A":
            return groups(coords_of(triggers, ident))[:PER_TARGET]
        if kind == "I":
            item = items.get(ident)
            if not isinstance(item, dict):
                return []
            found = []
            for source, table in (("U", units), ("O", objects)):
                drops = item.get(source) or {}
                for who in sorted(drops, key=lambda w: -(drops[w] or 0))[:DROPS]:
                    found += groups(coords_of(table, who))[:1]
            if not found:
                for who in list(item.get("V") or {})[:DROPS]:
                    found += groups(coords_of(units, who))[:1]
            return found[:PER_TARGET]
        return []

    def fmt(found):
        return ",".join(f"{a} {round(x * 10)} {round(y * 10)}" for a, x, y, _ in found)

    lines = []
    for qid in sorted(k for k in quests if isinstance(k, int)):
        q = quests[qid]
        if not isinstance(q, dict):
            continue
        obj = []
        for kind, ids in (q.get("obj") or {}).items():
            for ident in ids_of(ids):
                obj += spots(kind, ident)
        obj = sorted(obj, key=lambda s: -s[3])[:PER_QUEST]
        end = []
        for kind, ids in (q.get("end") or {}).items():
            for ident in ids_of(ids):
                end += spots(kind, ident)[:1]
        if obj or end:
            lines.append(f"{qid}|o {fmt(obj)}|t {fmt(end[:2])}")
    return lines


def main():
    db, assets = os.path.join(sys.argv[1], "db"), sys.argv[2]
    for name, tbc in (("quest-spots-1.txt", False), ("quest-spots-2.txt", True)):
        lines = build(db, tbc)
        with open(os.path.join(assets, name), "w", encoding="utf-8", newline="\n") as f:
            f.write("# Quest spots for the companion's map, built by tools/quest-spots/build.py from pfQuest (MIT).\n")
            f.write("\n".join(lines) + "\n")
        print(name, len(lines), "quests")


if __name__ == "__main__":
    main()
