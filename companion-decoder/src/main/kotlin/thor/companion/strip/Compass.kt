package thor.companion.strip

import kotlin.math.atan2
import kotlin.math.hypot

/** Which way and how far a quest is from you, for the quest arrow. */
object Compass {
    /** Direction clockwise from north (radians), distance in yards (null when the zone's size is unknown). */
    data class Heading(val place: MapPlace, val bearing: Double, val yards: Int?)

    /** Whether a map place's label (maybe cut short with "…") belongs to the quest [title]. */
    fun matches(label: String, title: String) = label.isNotEmpty() && title.startsWith(label.removeSuffix("…"))

    /**
     * The nearest place of quest [title] on [zone] from [x], [y]: where to turn it in
     * when it is ready (Q), else its objective areas (q) or where it starts (a). With
     * [waypoint] (where the game's own arrow points), that place is used instead when
     * it is in this zone, since it also knows turn-ins the map doesn't show.
     */
    fun toQuest(zone: ZoneMap, title: String, x: Double, y: Double, waypoint: Waypoint? = null): Heading? {
        val here = waypoint?.takeIf { it.mapId == zone.mapId }
        val mine = if (here != null) listOf(MapPlace('Q', here.x, here.y, title))
            else zone.places.filter { it.kind in "qQa" && matches(it.label, title) }
        val pick = mine.filter { it.kind == 'Q' }.ifEmpty { mine }
        // Without the size, the zone maps are about 3:2.
        val w = if (zone.width > 0) zone.width.toDouble() else 1.5
        val h = if (zone.height > 0) zone.height.toDouble() else 1.0
        val near = pick.minByOrNull { hypot((it.x - x) * w, (it.y - y) * h) } ?: return null
        return toPlace(zone, near, x, y)
    }

    /** Which way and how far [place] on [zone] is from [x], [y]. */
    fun toPlace(zone: ZoneMap, place: MapPlace, x: Double, y: Double): Heading {
        // Without the size, the zone maps are about 3:2.
        val w = if (zone.width > 0) zone.width.toDouble() else 1.5
        val h = if (zone.height > 0) zone.height.toDouble() else 1.0
        val dx = (place.x - x) * w
        val dy = (place.y - y) * h
        val yards = if (zone.width > 0 && zone.height > 0) hypot(dx, dy).toInt() else null
        return Heading(place, atan2(dx, -dy), yards)
    }

    private val WAYS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

    /** The [bearing] (clockwise from north, radians) as one of eight words, like "north-east". */
    fun direction(bearing: Double): String {
        val eighth = Math.floorMod(Math.round(bearing / (Math.PI / 4)).toInt(), 8)
        return WAYS[eighth]
    }
}
