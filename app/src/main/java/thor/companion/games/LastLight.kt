package thor.companion.games

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Last Light's rules (a survivor game, Jesse 2026-10-27): you only steer, your hero fights on
 * their own. Foes come from the dark and get stronger; beaten foes drop gems, and a full gem bar
 * stops the game for a choice of 1 of 3 upgrades. Survive [NIGHT] seconds until dawn.
 *
 * The world has no edges: the hero is always in the middle of the screen ([W] x [H]) and
 * everything is placed around them. Gold from each run buys small lasting boosts ([shop],
 * levels 0..5 of [Boost]) before the next night. There are three [Night]s, each one longer and
 * harder; surviving one opens the next. The game moves in steps of 1/60 s.
 */
class LastLight(
    val shop: IntArray = IntArray(Boost.entries.size),
    private val random: Random = Random.Default,
    val hero: Hero = Hero.PALADIN,
    val night: Night = Night.MEADOW,
) {

    /**
     * The nights, in the order they open (Jesse 2026-10-27: "a few levels, so progression is a
     * thing"): how long until dawn, when the Necromancer and the swarms come, how tough foes are
     * from the start, and the gold for reaching dawn.
     */
    enum class Night(val title: String, val length: Float, val bosses: FloatArray, val swarms: FloatArray, val tough: Float, val dawnGold: Int, val story: String) {
        MEADOW("Gravewood Meadow", 300f, floatArrayOf(230f), floatArrayOf(90f, 190f), 0.7f, 15, "Five minutes. The dead are slow here."),
        GRAVEYARD("Old Graveyard", 450f, floatArrayOf(180f, 380f), floatArrayOf(100f, 240f, 330f), 1f, 30, "Seven and a half minutes. Two Necromancers."),
        RUINS("Cursed Ruins", 600f, floatArrayOf(180f, 360f, 510f), floatArrayOf(100f, 240f, 400f, 540f), 1.2f, 50, "Ten minutes. The hardest night of all.");

        /** The night after this one, or null after the last. */
        val next get() = entries.getOrNull(ordinal + 1)
    }

    /** The three classes (Jesse 2026-10-27, mockups/survivors-v4): health, speed, spell speed, healing, first weapon. */
    enum class Hero(val title: String, val health: Float, val speed: Float, val cool: Float, val regen: Float, val first: Upgrade, val traits: List<String>) {
        PALADIN("Paladin", 130f, 0.92f, 1f, 0.5f, Upgrade.HAMMER, listOf("Starts with Hammer of Light", "+30 health", "Heals a little over time")),
        MAGE("Mage", 90f, 1f, 0.9f, 0f, Upgrade.BOLT, listOf("Starts with Arcane Bolt", "Spells come 10% sooner", "Less health")),
        HUNTER("Hunter", 100f, 1.1f, 1f, 0f, Upgrade.ARROWS, listOf("Starts with Multi-Shot", "A wolf fights beside you", "Walks 10% faster")),
    }

    enum class Kind(val hp: Float, val speed: Float, val dps: Float, val radius: Float, val gem: Int, val from: Float) {
        GHOUL(10f, 62f, 12f, 14f, 1, 0f),
        BAT(6f, 118f, 8f, 12f, 1, 40f),
        SKELETON(24f, 72f, 16f, 15f, 3, 140f),
        BANSHEE(40f, 88f, 20f, 15f, 5, 290f),
        NECROMANCER(650f, 58f, 34f, 40f, 25, Float.MAX_VALUE),
    }

    /** What can be picked on a level up: weapons (they fight) and helpers. */
    enum class Upgrade(val title: String, val weapon: Boolean, val only: String? = null) {
        HAMMER("Hammer of Light", true, "PALADIN"), ARROWS("Multi-Shot", true, "HUNTER"), WOLF("Wolf Companion", true, "HUNTER"),
        BOLT("Arcane Bolt", true), BLADES("Whirling Blades", true), RING("Holy Ring", true),
        FROST("Frost Nova", true), FIRE("Firebomb", true),
        BOOTS("Swift Boots", false), MAGNET("Gem Magnet", false), STEW("Hearty Stew", false),
        EDGE("Sharp Edge", false), HOURGLASS("Hourglass", false),
        HERBS("Healing Herbs", false), POUCH("Gold Pouch", false);

        /** What the next level does, for the card. */
        fun detail(next: Int): String = when (this) {
            HAMMER -> when (next) { 1 -> "Holy hammers strike the nearest foe and burst with light"; 4 -> "Two hammers each time"; 5 -> "Three hammers each time"; else -> "Bigger, brighter bursts" }
            ARROWS -> when (next) { 1 -> "Shoots a fan of arrows the way you walk"; 4 -> "Arrows fly through foes"; else -> "Shoots one more arrow in each volley" }
            WOLF -> when (next) { 1 -> "A wolf runs to your foes and bites"; 5 -> "Your wolf bites twice as hard and howls, scaring foes away"; else -> "Your wolf runs faster and bites harder" }
            BOLT -> when (next) { 1 -> "Shoots a bolt at the nearest foe"; 3, 4 -> "One more bolt each volley"; 5 -> "Bolts fly through foes"; else -> "Bolts hit harder" }
            BLADES -> when (next) { 1 -> "Two blades circle you"; 3 -> "Blades hit harder and reach further"; else -> "One more blade" }
            RING -> if (next == 1) "A ring of light hurts every foe near you" else "The ring grows and burns brighter"
            FROST -> if (next == 1) "Every few seconds a ring of frost slows everything near you" else "Bigger, colder and more often"
            FIRE -> if (next == 1) "Throws a bomb that bursts into flames" else "Bigger blasts that hurt more"
            BOOTS -> "Walk 10% faster"
            MAGNET -> "Gems fly to you from further away"
            STEW -> "More health, and heal slowly"
            EDGE -> "All weapons hit 10% harder"
            HOURGLASS -> "All weapons fire 8% more often"
            HERBS -> "Heal 40 health now"
            POUCH -> "10 gold for the next night"
        }
    }

    /** Lasting boosts bought with gold between nights. */
    enum class Boost(val title: String, val detail: String, val price: Int) {
        TOUGH("Tough", "+10 health at the start", 40),
        QUICK("Quick feet", "Walk 5% faster", 30),
        REACH("Long reach", "Gems fly to you from further away", 25),
        SHARP("Sharp mind", "All weapons hit 5% harder", 50);

        /** The price of the next level after [level]. */
        fun cost(level: Int) = price * (level + 1)
    }

    class Foe(val kind: Kind, var x: Float, var y: Float, var hp: Float) {
        val maxHp = hp
        /** Seconds left of the white flash, of the frost slow, and of being pushed back. */
        var flash = 0f; var slow = 0f; var push = 0f
        var px = 0f; var py = 0f
        /** A bat's own wobble. */
        val phase = (x * 7 + y * 13) % 6.28f
        /** Per-blade wait before the blades may hit again. */
        var bladeWait = 0f
    }

    class Gem(var x: Float, var y: Float, val value: Int) { var pulled = false }
    class Bolt(var x: Float, var y: Float, val vx: Float, val vy: Float, val damage: Float, var life: Float, val pierce: Boolean, val arrow: Boolean = false) {
        val hit = HashSet<Foe>()
    }
    class Bomb(val fromX: Float, val fromY: Float, val x: Float, val y: Float, val radius: Float, val damage: Float, val holy: Boolean = false) { var t = 0f }

    /** Things that happened since the board last looked (it empties these). */
    class Hit(val x: Float, val y: Float, val damage: Int, val big: Boolean)
    class Burst(val x: Float, val y: Float, val kind: Kind)
    enum class Wave { FROST, FIRE, HOLY, HOWL }
    class Ring(val x: Float, val y: Float, val radius: Float, val wave: Wave)
    val hits = mutableListOf<Hit>()
    val deaths = mutableListOf<Burst>()
    val rings = mutableListOf<Ring>()

    val foes = mutableListOf<Foe>()
    val gems = mutableListOf<Gem>()
    val bolts = mutableListOf<Bolt>()
    val bombs = mutableListOf<Bomb>()

    /** Where the hero is, and where they look (-1 left, 1 right). */
    var x = 0f; var y = 0f
    var facing = 1f
        private set
    /** The way the thumb points (length 0..1). */
    var steerX = 0f; var steerY = 0f
    /** Seconds since nightfall. */
    var time = 0f
        private set
    var hp = 0f
        private set
    val maxHp get() = hero.health + shop[Boost.TOUGH.ordinal] * 10f + have(Upgrade.STEW) * 20f
    /** Seconds left of the red flash after being hurt. */
    var hurt = 0f
        private set
    var level = 1
        private set
    var xp = 0
        private set
    val need get() = 4 + (level - 1) * 3 + maxOf(0, level - 15) * 4
    var kills = 0
        private set
    var goldFound = 0
        private set
    /** The night has begun (the shop is behind us). */
    var started = false
        private set
    var over = false
        private set
    val won get() = over && time >= night.length
    /** The 3 cards to pick from now, or null while playing. */
    var choices: List<Upgrade>? = null
        private set
    private var pending = 0
    val levels = IntArray(Upgrade.entries.size)
    fun have(u: Upgrade) = levels[u.ordinal]
    /** The weapons and helpers taken, in the order they came. */
    val taken = mutableListOf<Upgrade>()

    /** Where the blades are now (their angle). */
    var bladeAngle = 0f
        private set
    private var spawnClock = 0f
    private var boltClock = 0.3f
    private var ringClock = 0f
    private var frostClock = 2f
    private var fireClock = 1.5f
    private var regenClock = 0f
    private var hammerClock = 0.6f
    private var arrowClock = 0.4f
    private var biteClock = 0f
    private var howlClock = 6f
    /** The hunter's wolf: where it is and which way it looks. */
    var wolfX = 0f
        private set
    var wolfY = 0f
        private set
    var wolfFacing = 1f
        private set
    /** The last way the hero walked (arrows fly this way). */
    private var aimX = 1f
    private var aimY = 0f
    /** Bosses and swarms sent so far (the board announces new ones). */
    var bossesSent = 0
        private set
    var swarmsSent = 0
        private set
    private var acc = 0f

    init {
        levels[hero.first.ordinal] = 1
        taken += hero.first
        if (hero == Hero.HUNTER) { levels[Upgrade.WOLF.ordinal] = 1; taken += Upgrade.WOLF }
        hp = maxHp
        wolfX = x - 40f; wolfY = y + 20f
    }

    fun start() { started = true }

    /** Gold earned this night: foes, minutes, dawn, and pouches. */
    val gold get() = kills / 40 + (time / 60f).toInt() * 3 + (if (won) night.dawnGold else 0) + goldFound

    // ---- Numbers that come from upgrades ----

    private val damageScale get() = (1f + have(Upgrade.EDGE) * 0.10f + shop[Boost.SHARP.ordinal] * 0.05f)
    private val coolScale get() = (1f - have(Upgrade.HOURGLASS) * 0.08f) * hero.cool
    val speed get() = 135f * hero.speed * (1f + have(Upgrade.BOOTS) * 0.10f + shop[Boost.QUICK.ordinal] * 0.05f)
    val reach get() = 55f + have(Upgrade.MAGNET) * 30f + shop[Boost.REACH.ordinal] * 15f
    val bladeCount get() = when (have(Upgrade.BLADES)) { 0 -> 0; 1 -> 2; 2, 3 -> 3; 4 -> 4; else -> 5 }
    val bladeRadius get() = 78f + (if (have(Upgrade.BLADES) >= 3) 16f else 0f)
    val ringRadius get() = if (have(Upgrade.RING) == 0) 0f else 90f + have(Upgrade.RING) * 14f
    private val boltCount get() = when (have(Upgrade.BOLT)) { 0 -> 0; 1, 2 -> 1; 3 -> 2; else -> 3 }

    // ---- Playing ----

    /** Moves the night on by [dt] seconds (in steps of 1/60 s). Waits while a choice is open. */
    fun step(dt: Float) {
        if (!started || over || choices != null) return
        acc += dt
        while (acc >= STEP && !over && choices == null) { acc -= STEP; tick(STEP) }
        if (choices != null) acc = 0f
    }

    private fun tick(dt: Float) {
        time += dt
        if (time >= night.length) { time = night.length; over = true; foes.forEach { deaths += Burst(it.x, it.y, it.kind) }; foes.clear(); return }
        // The hero walks where the thumb points.
        val len = hypot(steerX, steerY)
        if (len > 0.05f) {
            val k = minOf(1f, len) / len
            x += steerX * k * speed * dt; y += steerY * k * speed * dt
            if (steerX > 0.1f) facing = 1f else if (steerX < -0.1f) facing = -1f
            aimX = steerX / len; aimY = steerY / len
        }
        if (hurt > 0) hurt -= dt
        regenClock += dt
        if (regenClock >= 1f) { regenClock -= 1f; hp = minOf(maxHp, hp + have(Upgrade.STEW) * 0.4f + hero.regen) }
        spawn(dt)
        moveFoes(dt)
        weapons(dt)
        pickGems(dt)
        if (hp <= 0f) { hp = 0f; over = true }
    }

    private fun spawn(dt: Float) {
        // The Necromancer comes at set times each night.
        val bossAt = night.bosses
        if (bossesSent < bossAt.size && time >= bossAt[bossesSent]) {
            bossesSent++
            place(Kind.NECROMANCER, Kind.NECROMANCER.hp * (1f + (bossesSent - 1) * 0.8f) * night.tough)
        }
        // Now and then a swarm closes in from all around.
        if (swarmsSent < night.swarms.size && time >= night.swarms[swarmsSent]) {
            swarmsSent++
            val kind = if (swarmsSent % 2 == 1) Kind.BAT else Kind.GHOUL
            val n = 22 + swarmsSent * 4
            for (k in 0 until n) {
                val a = k * 6.2832f / n
                place(kind, kind.hp * strength(), x + cos(a) * SPAWN_DISTANCE * 0.8f, y + sin(a) * SPAWN_DISTANCE * depth(a) * 0.85f)
            }
        }
        spawnClock += dt * (1.1f + time / 45f)
        while (spawnClock >= 1f) {
            spawnClock -= 1f
            if (foes.size >= MAX_FOES) continue
            val open = Kind.entries.filter { it != Kind.NECROMANCER && time >= it.from * night.length / 600f }
            // Newer kinds come a little more often than the first ones.
            val kind = open[minOf(open.size - 1, (random.nextFloat() * open.size * 1.15f).toInt())]
            val strength = strength()
            // Now and then a group comes in a bunch.
            val group = if (random.nextFloat() < 0.08f + time / 3000f) 5 else 1
            val (gx, gy) = edge()
            repeat(group) { place(kind, kind.hp * strength, gx + random.nextFloat() * 40 - 20, gy + random.nextFloat() * 40 - 20) }
        }
    }

    /** How much tougher foes are now: slowly at first, quicker towards dawn; later nights start tougher. */
    private fun strength() = night.tough + time / 220f + (time / 320f) * (time / 320f)

    /**
     * A spot just outside the screen, all around. The ground tilts away from the eye (the board
     * shows it in 2.5D), so much more of it can be seen ahead (up) than behind: foes ahead start
     * further out, in the fog.
     */
    private fun edge(): Pair<Float, Float> {
        val a = random.nextFloat() * 6.2832f
        val d = SPAWN_DISTANCE + random.nextFloat() * 60f
        return (x + cos(a) * d) to (y + sin(a) * d * depth(a))
    }

    /** How far up or down the spawn ring reaches, at angle [a]. */
    private fun depth(a: Float) = if (sin(a) < 0f) 1.45f else 0.75f

    private fun place(kind: Kind, hp: Float, at: Pair<Float, Float> = edge()) = place(kind, hp, at.first, at.second)
    private fun place(kind: Kind, hp: Float, fx: Float, fy: Float) { foes += Foe(kind, fx, fy, hp) }

    private val grid = HashMap<Long, MutableList<Foe>>()
    private fun cell(v: Float) = kotlin.math.floor(v / CELL).toLong()
    private fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)

    private fun moveFoes(dt: Float) {
        grid.values.forEach { it.clear() }
        for (f in foes) grid.getOrPut(key(cell(f.x), cell(f.y))) { mutableListOf() } += f
        val it = foes.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if (f.flash > 0) f.flash -= dt
            if (f.slow > 0) f.slow -= dt
            if (f.bladeWait > 0) f.bladeWait -= dt
            val dx = x - f.x; val dy = y - f.y
            val dist = hypot(dx, dy)
            // Foes left far behind come back in on the other side.
            if (dist > FORGET && f.kind != Kind.NECROMANCER) { val (nx, ny) = edge(); f.x = nx; f.y = ny; continue }
            var sp = f.kind.speed * (if (f.slow > 0) 0.5f else 1f)
            var mx = 0f; var my = 0f
            if (dist > 0.01f) { mx = dx / dist; my = dy / dist }
            if (f.kind == Kind.BAT) { val w = sin(time * 6f + f.phase) * 0.6f; val ox = -my * w; val oy = mx * w; mx += ox; my += oy }
            if (f.push > 0) { f.push -= dt; f.x += f.px * dt; f.y += f.py * dt; sp *= 0.2f }
            f.x += mx * sp * dt; f.y += my * sp * dt
            // Keep apart from neighbours.
            val cx = cell(f.x); val cy = cell(f.y)
            for (ax in cx - 1..cx + 1) for (ay in cy - 1..cy + 1) {
                val list = grid[key(ax, ay)] ?: continue
                for (o in list) {
                    if (o === f) continue
                    val ex = f.x - o.x; val ey = f.y - o.y
                    val min = f.kind.radius + o.kind.radius - 4
                    val d2 = ex * ex + ey * ey
                    if (d2 < min * min && d2 > 0.0001f) {
                        val d = sqrt(d2); val p = (min - d) * 0.5f
                        val weight = if (o.kind == Kind.NECROMANCER) 1f else if (f.kind == Kind.NECROMANCER) 0.1f else 0.5f
                        f.x += ex / d * p * weight * 2; f.y += ey / d * p * weight * 2
                    }
                }
            }
            // Touching the hero hurts.
            if (dist < f.kind.radius + HERO_R) { hp -= f.kind.dps * dt; hurt = 0.25f }
        }
    }

    private fun nearest(maxDist: Float, fromX: Float = x, fromY: Float = y): Foe? {
        var best: Foe? = null; var bd = maxDist * maxDist
        for (f in foes) { val d = (f.x - fromX) * (f.x - fromX) + (f.y - fromY) * (f.y - fromY); if (d < bd && f.hp > 0) { bd = d; best = f } }
        return best
    }

    private fun weapons(dt: Float) {
        // Hammer of Light (Paladin): hammers fall on the nearest foes and burst.
        if (have(Upgrade.HAMMER) > 0) {
            hammerClock -= dt
            if (hammerClock <= 0f) {
                hammerClock = 1.5f * coolScale
                val lv = have(Upgrade.HAMMER)
                val count = when (lv) { 4 -> 2; 5 -> 3; else -> 1 }
                val near = foes.filter { hypot(it.x - x, it.y - y) < 340f }.sortedBy { hypot(it.x - x, it.y - y) }
                for (k in 0 until minOf(count, near.size)) {
                    val t = near[k * (near.size / count).coerceAtLeast(1) % near.size]
                    bombs += Bomb(x, y - 20, t.x, t.y, 52f + lv * 8f, (18f + lv * 7f) * damageScale, holy = true)
                }
            }
        }
        // Multi-Shot (Hunter): a fan of arrows the way the hero walks (or at the nearest foe when standing).
        if (have(Upgrade.ARROWS) > 0) {
            arrowClock -= dt
            if (arrowClock <= 0f) {
                arrowClock = 0.9f * coolScale
                val lv = have(Upgrade.ARROWS)
                val n = intArrayOf(3, 3, 4, 5, 6)[lv - 1]
                var ax = aimX; var ay = aimY
                if (hypot(steerX, steerY) < 0.05f) nearest(420f)?.let { f -> val d = hypot(f.x - x, f.y - y).coerceAtLeast(1f); ax = (f.x - x) / d; ay = (f.y - y) / d }
                val base = atan2(ay, ax)
                val dmg = (8f + lv * 3f) * damageScale
                for (k in 0 until n) {
                    val a = base + (k - (n - 1) / 2f) * 0.16f
                    bolts += Bolt(x, y - 4, cos(a) * ARROW_SPEED, sin(a) * ARROW_SPEED, dmg, 0.9f, lv >= 4, arrow = true)
                }
            }
        }
        // The wolf (Hunter): runs to the nearest foe and bites; at rank 5 it howls now and then.
        if (have(Upgrade.WOLF) > 0) {
            val lv = have(Upgrade.WOLF)
            val target = nearest(360f)
            val gx: Float; val gy: Float
            if (target != null) { gx = target.x; gy = target.y } else { gx = x - 40f * facing; gy = y + 22f }
            val dx = gx - wolfX; val dy = gy - wolfY; val d = hypot(dx, dy)
            val sp = (230f + lv * 20f) * dt
            if (d > (target?.kind?.radius ?: 0f) + 10f) { val k = minOf(sp, d) / d.coerceAtLeast(0.01f); wolfX += dx * k; wolfY += dy * k }
            if (kotlin.math.abs(dx) > 2f) wolfFacing = if (dx > 0) 1f else -1f
            // A wolf far behind catches up at once.
            if (hypot(wolfX - x, wolfY - y) > 500f) { wolfX = x - 40f; wolfY = y + 22f }
            biteClock -= dt
            if (target != null && d < target.kind.radius + 18f && biteClock <= 0f) {
                biteClock = 0.5f
                damage(target, (10f + lv * 5f) * (if (lv >= 5) 2f else 1f) * damageScale, dx / d.coerceAtLeast(1f), dy / d.coerceAtLeast(1f))
            }
            if (lv >= 5) {
                howlClock -= dt
                if (howlClock <= 0f) {
                    howlClock = 6f
                    rings += Ring(wolfX, wolfY, 160f, Wave.HOWL)
                    for (f in foes) {
                        val fd = hypot(f.x - wolfX, f.y - wolfY)
                        if (fd < 160f && f.kind != Kind.NECROMANCER) { f.push = 0.35f; f.px = (f.x - wolfX) / fd.coerceAtLeast(1f) * 320f; f.py = (f.y - wolfY) / fd.coerceAtLeast(1f) * 320f }
                    }
                }
            }
        }
        // Arcane Bolt: at the nearest foes.
        if (have(Upgrade.BOLT) > 0) {
            boltClock -= dt
            if (boltClock <= 0f) {
                boltClock = 1.1f * coolScale
                val near = foes.sortedBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }.take(boltCount)
                val dmg = (10f + have(Upgrade.BOLT) * 3f) * damageScale
                for ((i, f) in near.withIndex()) {
                    val a = atan2(f.y - y, f.x - x) + (if (near.size == 1) 0f else (i - (near.size - 1) / 2f) * 0.05f)
                    bolts += Bolt(x, y - 6, cos(a) * BOLT_SPEED, sin(a) * BOLT_SPEED, dmg, 1.3f, have(Upgrade.BOLT) >= 5)
                }
            }
        }
        val bi = bolts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.x += b.vx * dt; b.y += b.vy * dt; b.life -= dt
            if (b.life <= 0) { bi.remove(); continue }
            for (f in foes) {
                if (f in b.hit || f.hp <= 0) continue
                val r = f.kind.radius + 7
                if ((f.x - b.x) * (f.x - b.x) + (f.y - b.y) * (f.y - b.y) < r * r) {
                    val sp = if (b.arrow) ARROW_SPEED else BOLT_SPEED
                    damage(f, b.damage, b.vx / sp, b.vy / sp)
                    b.hit += f
                    if (!b.pierce) { b.life = 0f; break }
                }
            }
        }
        // Whirling Blades.
        val n = bladeCount
        if (n > 0) {
            bladeAngle = (bladeAngle + dt * 3.2f) % 6.2832f
            val dmg = (8f + have(Upgrade.BLADES) * 3f) * damageScale
            for (k in 0 until n) {
                val a = bladeAngle + k * 6.2832f / n
                val bx = x + cos(a) * bladeRadius; val by = y + sin(a) * bladeRadius
                for (f in foes) {
                    if (f.bladeWait > 0 || f.hp <= 0) continue
                    val r = f.kind.radius + 16
                    if ((f.x - bx) * (f.x - bx) + (f.y - by) * (f.y - by) < r * r) {
                        f.bladeWait = 0.45f
                        val d = hypot(f.x - x, f.y - y).coerceAtLeast(1f)
                        damage(f, dmg, (f.x - x) / d, (f.y - y) / d)
                    }
                }
            }
        }
        // Holy Ring: everything inside burns a little, twice a second.
        if (have(Upgrade.RING) > 0) {
            ringClock -= dt
            if (ringClock <= 0f) {
                ringClock = 0.5f
                val rr = ringRadius
                val dmg = (4f + have(Upgrade.RING) * 2f) * damageScale
                for (f in foes.toList()) if (hypot(f.x - x, f.y - y) < rr + f.kind.radius) damage(f, dmg, 0f, 0f, small = true)
            }
        }
        // Frost Nova.
        if (have(Upgrade.FROST) > 0) {
            frostClock -= dt
            if (frostClock <= 0f) {
                frostClock = (4.6f - have(Upgrade.FROST) * 0.4f) * coolScale
                val rr = 140f + have(Upgrade.FROST) * 20f
                val dmg = (8f + have(Upgrade.FROST) * 4f) * damageScale
                rings += Ring(x, y, rr, Wave.FROST)
                for (f in foes.toList()) if (hypot(f.x - x, f.y - y) < rr + f.kind.radius) { f.slow = 2f + have(Upgrade.FROST) * 0.3f; damage(f, dmg, 0f, 0f) }
            }
        }
        // Firebomb: thrown at a foe, bursts when it lands.
        if (have(Upgrade.FIRE) > 0) {
            fireClock -= dt
            if (fireClock <= 0f) {
                fireClock = 2.8f * coolScale
                val near = foes.filter { hypot(it.x - x, it.y - y) < 360f }
                if (near.isNotEmpty()) {
                    val t = near[random.nextInt(near.size)]
                    bombs += Bomb(x, y, t.x, t.y, 60f + have(Upgrade.FIRE) * 10f, (20f + have(Upgrade.FIRE) * 8f) * damageScale)
                }
            }
        }
        val mi = bombs.iterator()
        while (mi.hasNext()) {
            val m = mi.next()
            m.t += dt / (if (m.holy) HAMMER_FLIGHT else BOMB_FLIGHT)
            if (m.t >= 1f) {
                mi.remove()
                rings += Ring(m.x, m.y, m.radius, if (m.holy) Wave.HOLY else Wave.FIRE)
                for (f in foes.toList()) {
                    val d = hypot(f.x - m.x, f.y - m.y)
                    if (d < m.radius + f.kind.radius) damage(f, m.damage, (f.x - m.x) / d.coerceAtLeast(1f), (f.y - m.y) / d.coerceAtLeast(1f))
                }
            }
        }
        foes.removeAll { f ->
            if (f.hp > 0) false else {
                kills++
                deaths += Burst(f.x, f.y, f.kind)
                if (f.kind == Kind.NECROMANCER) {
                    for (k in 0 until 8) gems += Gem(f.x + cos(k * 0.785f) * 30, f.y + sin(k * 0.785f) * 30, 25)
                    goldFound += 5
                } else gems += Gem(f.x, f.y, f.kind.gem)
                true
            }
        }
        if (gems.size > MAX_GEMS) {
            // Too many lying around: the furthest go.
            gems.sortBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
            while (gems.size > MAX_GEMS) gems.removeAt(gems.size - 1)
        }
    }

    private fun damage(f: Foe, amount: Float, kx: Float, ky: Float, small: Boolean = false) {
        f.hp -= amount
        f.flash = 0.12f
        if (f.kind != Kind.NECROMANCER && (kx != 0f || ky != 0f)) { f.push = 0.12f; f.px = kx * 260f; f.py = ky * 260f }
        if (!small || hits.size < 8) hits += Hit(f.x, f.y - f.kind.radius, amount.toInt().coerceAtLeast(1), f.kind == Kind.NECROMANCER)
        if (hits.size > 40) hits.removeAt(0)
    }

    private fun pickGems(dt: Float) {
        val gi = gems.iterator()
        while (gi.hasNext()) {
            val g = gi.next()
            val dx = x - g.x; val dy = y - g.y
            val d = hypot(dx, dy)
            if (d < reach) g.pulled = true
            if (g.pulled) {
                val sp = 420f * dt
                if (d <= sp + HERO_R) { gi.remove(); gain(g.value); continue }
                g.x += dx / d * sp; g.y += dy / d * sp
            }
        }
    }

    private fun gain(v: Int) {
        xp += v
        while (xp >= need) { xp -= need; level++; pending++ }
        if (pending > 0 && choices == null) offer()
    }

    private fun offer() {
        val weapons = Upgrade.entries.count { it.weapon && have(it) > 0 }
        val open = Upgrade.entries.filter {
            it != Upgrade.HERBS && it != Upgrade.POUCH && have(it) < MAX_LEVEL && (it.only == null || it.only == hero.name) && (!it.weapon || have(it) > 0 || weapons < 4) &&
                (it.weapon || have(it) > 0 || taken.count { t -> !t.weapon } < 4)
        }.shuffled(random)
        val pick = open.take(3).toMutableList()
        if (pick.size < 3) pick += Upgrade.HERBS
        if (pick.size < 3) pick += Upgrade.POUCH
        choices = pick
    }

    /** Takes card [i] of [choices]. */
    fun choose(i: Int) {
        val c = choices?.getOrNull(i) ?: return
        when (c) {
            Upgrade.HERBS -> hp = minOf(maxHp, hp + 40f)
            Upgrade.POUCH -> goldFound += 10
            else -> {
                val before = maxHp
                if (levels[c.ordinal] == 0) taken += c
                levels[c.ordinal]++
                hp += maxHp - before
            }
        }
        pending--
        choices = null
        if (pending > 0) offer()
    }

    companion object {
        const val W = 1000f
        const val H = 620f
        const val MAX_LEVEL = 5
        const val HERO_R = 14f
        const val STEP = 1f / 60f
        const val MAX_FOES = 200
        const val MAX_GEMS = 220
        const val SPAWN_DISTANCE = 600f
        const val FORGET = 1150f
        const val CELL = 48f
        const val BOLT_SPEED = 520f
        const val BOMB_FLIGHT = 0.6f
        const val HAMMER_FLIGHT = 0.3f
        const val ARROW_SPEED = 640f
    }
}
