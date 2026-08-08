package me.river.pulse.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * A bespoke 24dp stroke icon set drawn for Nightbell.
 *
 * Hand-authored rather than pulled from `material-icons-extended` so the whole
 * app shares one optical weight (1.7px strokes, round caps, 24-unit grid) and so
 * the APK carries exactly the glyphs it uses.
 */
object NightbellIcons {

    private fun stroke(name: String, path: String, width: Float = 1.7f): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    private fun filled(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        }.build()

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} $cy A$r $r 0 1 0 ${cx + r} $cy A$r $r 0 1 0 ${cx - r} $cy"

    val Plus = stroke("Plus", "M12 5 L12 19 M5 12 L19 12", width = 2f)
    val Close = stroke("Close", "M6.5 6.5 L17.5 17.5 M17.5 6.5 L6.5 17.5", width = 1.9f)
    val Check = stroke("Check", "M4.5 12.6 L9.5 17.6 L19.5 6.6", width = 2.1f)
    val ChevronRight = stroke("ChevronRight", "M9.5 5.5 L16 12 L9.5 18.5", width = 1.9f)
    val ChevronLeft = stroke("ChevronLeft", "M14.5 5.5 L8 12 L14.5 18.5", width = 1.9f)
    val ChevronDown = stroke("ChevronDown", "M5.5 9 L12 15.5 L18.5 9", width = 1.9f)
    val ChevronUp = stroke("ChevronUp", "M5.5 15.5 L12 9 L18.5 15.5", width = 1.9f)

    /** Drag grip: two rows of three, the platform-conventional reorder affordance. */
    val Grip = stroke(
        "Grip",
        "M8 9 L8 9 M12 9 L12 9 M16 9 L16 9 M8 15 L8 15 M12 15 L12 15 M16 15 L16 15",
        width = 2.6f,
    )
    val ArrowLeft = stroke("ArrowLeft", "M20 12 L4 12 M10 6 L4 12 L10 18", width = 1.9f)
    val ArrowRight = stroke("ArrowRight", "M4 12 L20 12 M14 6 L20 12 L14 18", width = 1.9f)

    val Refresh = stroke(
        "Refresh",
        "M20.2 12 A8.2 8.2 0 1 1 17.4 5.9 M20.4 3 L20.4 8 L15.4 8",
    )
    val History = stroke(
        "History",
        "M3.8 12 A8.2 8.2 0 1 0 6.6 5.9 M3.6 3 L3.6 8 L8.6 8 M12 7.6 L12 12.3 L15.4 14.2",
    )

    val Sliders = stroke(
        "Sliders",
        "M4 7.5 L13 7.5 M17.5 7.5 L20 7.5 M4 16.5 L7 16.5 M11.5 16.5 L20 16.5 " +
            "M15.2 4.7 L15.2 10.3 M9.2 13.7 L9.2 19.3",
    )
    val Trash = stroke(
        "Trash",
        "M4.2 6.8 L19.8 6.8 M9.4 6.8 L9.4 4.6 L14.6 4.6 L14.6 6.8 " +
            "M6.6 6.8 L7.6 19.6 L16.4 19.6 L17.4 6.8 M10.2 10.4 L10.2 16 M13.8 10.4 L13.8 16",
    )
    val Pencil = stroke(
        "Pencil",
        "M4 20 L4.9 15.6 L15.9 4.6 A2.2 2.2 0 0 1 19.4 8.1 L8.4 19.1 Z M14.2 6.3 L17.7 9.8",
    )
    val Play = filled("Play", "M7.4 5.2 L18.6 12 L7.4 18.8 Z")
    val Pause = stroke("Pause", "M9.2 5.4 L9.2 18.6 M14.8 5.4 L14.8 18.6", width = 2.2f)

    val Globe = stroke(
        "Globe",
        circle(12f, 12f, 8.6f) +
            " M3.4 12 L20.6 12 M12 3.4 C15.2 6.8 15.2 17.2 12 20.6 M12 3.4 C8.8 6.8 8.8 17.2 12 20.6",
    )
    val Pointer = stroke(
        "Pointer",
        "M6 3.2 L18.6 11.6 L12.9 12.8 L15.9 18.8 L13.3 20.1 L10.3 14.1 L6 17.6 Z",
    )
    val Braces = stroke(
        "Braces",
        "M9.4 4 C7 4 7.6 8 7.6 9.6 C7.6 11.1 5.8 12 5.8 12 C5.8 12 7.6 12.9 7.6 14.4 " +
            "C7.6 16 7 20 9.4 20 M14.6 4 C17 4 16.4 8 16.4 9.6 C16.4 11.1 18.2 12 18.2 12 " +
            "C18.2 12 16.4 12.9 16.4 14.4 C16.4 16 17 20 14.6 20",
    )
    val Bell = stroke(
        "Bell",
        "M6.2 10.6 A5.8 5.8 0 0 1 17.8 10.6 L17.8 15.2 L19.8 18 L4.2 18 L6.2 15.2 Z " +
            "M9.9 18 A2.3 2.3 0 0 0 14.1 18",
    )
    val BellOff = stroke(
        "BellOff",
        "M6.2 10.6 A5.8 5.8 0 0 1 17.8 10.6 L17.8 15.2 L19.8 18 L4.2 18 L6.2 15.2 Z " +
            "M9.9 18 A2.3 2.3 0 0 0 14.1 18 M3.6 3.6 L20.4 20.4",
    )
    val Vibrate = stroke(
        "Vibrate",
        "M8.4 6.2 L15.6 6.2 L15.6 17.8 L8.4 17.8 Z M5.2 9.4 L5.2 14.6 M2.6 10.8 L2.6 13.2 " +
            "M18.8 9.4 L18.8 14.6 M21.4 10.8 L21.4 13.2",
    )
    val Volume = stroke(
        "Volume",
        "M4 9.4 L7.6 9.4 L12 5.4 L12 18.6 L7.6 14.6 L4 14.6 Z " +
            "M15.4 9.2 A4.2 4.2 0 0 1 15.4 14.8 M18 6.4 A7.8 7.8 0 0 1 18 17.6",
    )
    val VolumeOff = stroke(
        "VolumeOff",
        "M4 9.4 L7.6 9.4 L12 5.4 L12 18.6 L7.6 14.6 L4 14.6 Z M15.8 10 L20.8 15 M20.8 10 L15.8 15",
    )
    val Clock = stroke("Clock", circle(12f, 12f, 8.6f) + " M12 6.9 L12 12.4 L15.8 14.6")
    val Moon = stroke("Moon", "M20.4 14.6 A8.6 8.6 0 1 1 9.6 3.8 A7 7 0 0 0 20.4 14.6 Z")
    val Activity = stroke(
        "Activity",
        "M2.4 12.6 L6.8 12.6 L9.4 6.2 L14.2 17.8 L16.8 12.6 L21.6 12.6",
        width = 1.9f,
    )
    val Shield = stroke(
        "Shield",
        "M12 3.2 L20 6.2 L20 11.8 C20 16.8 16.4 20 12 21.2 C7.6 20 4 16.8 4 11.8 L4 6.2 Z",
    )
    val Search = stroke("Search", circle(11f, 11f, 6.9f) + " M16.1 16.1 L21 21")

    /** Generic sort glyph: descending rule lengths. Used where the key is a name. */
    val SortLines = stroke(
        "SortLines",
        "M4 6.6 L16 6.6 M4 12 L13 12 M4 17.4 L10 17.4 M19 6.6 L19 17.4 M16.6 15 L19 17.4 L21.4 15",
    )

    /** Filter funnel — the entry point to the dashboard's show/order panel. */
    val Funnel = stroke(
        "Funnel",
        "M3.6 5.2 L20.4 5.2 L13.8 13 L13.8 19.6 L10.2 17.6 L10.2 13 Z",
    )
    val Copy = stroke("Copy", "M9 8.8 L19.2 8.8 L19.2 19.4 L9 19.4 Z M5.6 15.2 L5.6 4.6 L15.8 4.6")
    val Info = stroke("Info", circle(12f, 12f, 8.6f) + " M12 11 L12 16.4 M12 7.4 L12 8.1")
    val Warning = stroke(
        "Warning",
        "M12 3.4 L21.6 20 L2.4 20 Z M12 9.4 L12 14 M12 16.6 L12 17.3",
    )
    val Sparkle = stroke(
        "Sparkle",
        "M11 2.8 L12.7 8.4 L18.3 10.1 L12.7 11.8 L11 17.4 L9.3 11.8 L3.7 10.1 L9.3 8.4 Z " +
            "M18 16 L18.9 18.4 L21.3 19.3 L18.9 20.2 L18 22.6 L17.1 20.2 L14.7 19.3 L17.1 18.4 Z",
        width = 1.5f,
    )
    val Chart = stroke("Chart", "M4 19.6 L20 19.6 M7.4 19.6 L7.4 13 M12 19.6 L12 6.6 M16.6 19.6 L16.6 10")
    val Radar = stroke(
        "Radar",
        "M12 20.6 A8.6 8.6 0 1 0 3.4 12 M12 16.8 A4.8 4.8 0 1 0 7.2 12 M12 12 L18.2 5.8",
    )
    val Link = stroke(
        "Link",
        "M10.4 13.6 A4.2 4.2 0 0 0 16.3 13.9 L18.7 11.5 A4.2 4.2 0 0 0 12.8 5.6 L11.4 7 " +
            "M13.6 10.4 A4.2 4.2 0 0 0 7.7 10.1 L5.3 12.5 A4.2 4.2 0 0 0 11.2 18.4 L12.6 17",
    )
    val Eye = stroke(
        "Eye",
        "M2.6 12 C5.2 7.4 8.5 5.4 12 5.4 C15.5 5.4 18.8 7.4 21.4 12 " +
            "C18.8 16.6 15.5 18.6 12 18.6 C8.5 18.6 5.2 16.6 2.6 12 Z " + circle(12f, 12f, 2.9f),
    )
    val Target = stroke(
        "Target",
        circle(12f, 12f, 8.6f) + " " + circle(12f, 12f, 4.4f) + " " + circle(12f, 12f, 1.1f),
    )
    val Server = stroke(
        "Server",
        "M4 4.4 L20 4.4 L20 9.6 L4 9.6 Z M4 14.4 L20 14.4 L20 19.6 L4 19.6 Z " +
            "M7.4 7 L7.5 7 M7.4 17 L7.5 17",
    )
    val Layers = stroke(
        "Layers",
        "M12 3.2 L20.8 8 L12 12.8 L3.2 8 Z M3.2 12.4 L12 17.2 L20.8 12.4 M3.2 16.4 L12 21.2 L20.8 16.4",
    )
    val More = stroke("More", "M6 12 L6.1 12 M12 12 L12.1 12 M18 12 L18.1 12", width = 2.4f)
    val Zap = stroke("Zap", "M13.6 2.6 L5.2 13.4 L11.2 13.4 L10.4 21.4 L18.8 10.6 L12.8 10.6 Z")
    val Filter = stroke("Filter", "M3.6 5.4 L20.4 5.4 L14 13 L14 19.4 L10 21 L10 13 Z")
    val Wifi = stroke(
        "Wifi",
        "M2.6 9.4 A13.4 13.4 0 0 1 21.4 9.4 M6 12.9 A8.6 8.6 0 0 1 18 12.9 " +
            "M9.5 16.4 A4.3 4.3 0 0 1 14.5 16.4 M12 19.9 L12.1 19.9",
    )
    /**
     * The wifi arcs, struck through.
     *
     * Same arcs as [Wifi] rather than the truncated-arc treatment other sets
     * use: at the 19dp this renders at, broken arcs plus a slash turn into
     * confetti, while one clean diagonal reads instantly.
     */
    val WifiOff = stroke(
        "WifiOff",
        "M2.6 9.4 A13.4 13.4 0 0 1 21.4 9.4 M6 12.9 A8.6 8.6 0 0 1 18 12.9 " +
            "M9.5 16.4 A4.3 4.3 0 0 1 14.5 16.4 M12 19.9 L12.1 19.9 M4.4 4.4 L19.6 19.6",
    )
    val Gauge = stroke("Gauge", "M12 19.6 A8.6 8.6 0 1 1 20.6 11 M12 12 L16.8 8.2")
    val Power = stroke("Power", "M12 3 L12 11.4 M7.3 6.1 A7.2 7.2 0 1 0 16.7 6.1")
    val Save = stroke(
        "Save",
        "M5 5.4 L15.6 5.4 L19 8.8 L19 18.6 L5 18.6 Z M8.4 5.4 L8.4 10 L15 10 L15 5.4 " +
            "M8 18.6 L8 13.6 L16 13.6 L16 18.6",
    )
    val Drag = stroke("Drag", "M9 7 L9.1 7 M15 7 L15.1 7 M9 12 L9.1 12 M15 12 L15.1 12 M9 17 L9.1 17 M15 17 L15.1 17", width = 2.2f)

    // Export and import share the tray so the pair reads as one operation in two
    // directions; only the arrow differs.
    private const val TRAY = "M4.4 15.4 L4.4 19.4 L19.6 19.4 L19.6 15.4"
    val Export = stroke("Export", "$TRAY M12 3.6 L12 14.2 M7.8 9.8 L12 14.2 L16.2 9.8")
    val Import = stroke("Import", "$TRAY M12 14.2 L12 3.6 M7.8 8 L12 3.6 L16.2 8")
}
