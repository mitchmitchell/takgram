package org.takgram.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.takgram.core.Caption
import org.takgram.core.CotEvent
import org.takgram.core.CotTypes
import org.takgram.core.Geo
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/**
 * Draws an object's card picture: its symbol and name on a map thumbnail.
 *
 * Map tiles come from OpenStreetMap's public tile server by default. Its usage policy asks for an
 * identifying User-Agent, local caching and visible attribution, and allows only light use; all
 * three are done here, and a tile that cannot be fetched is drawn as a plain grid square instead.
 */
class CardRenderer(ctx: Context) {
    private val tileDir = File(ctx.filesDir, "tiles")
    private val cardDir = File(ctx.cacheDir, "cards").apply { mkdirs() }

    /** Empty turns map tiles off: cards are then drawn on a plain grid. */
    @Volatile var tileUrl: String = OSM_TILES

    /** Renders [ev] to a JPEG file. Does network and disk work, so never call it on the main thread. */
    fun render(ev: CotEvent): File {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(226, 226, 220))

        val (cx, cy) = Geo.worldPx(ev.lat, ev.lon, ZOOM, TILE)
        val x0 = cx - W / 2.0
        val y0 = cy - H / 2.0
        val grid = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.rgb(200, 200, 194)
        }
        var haveMap = false
        val n = 1 shl ZOOM
        for (tx in floor(x0 / TILE).toInt()..floor((x0 + W) / TILE).toInt()) {
            for (ty in floor(y0 / TILE).toInt()..floor((y0 + H) / TILE).toInt()) {
                val px = (tx * TILE - x0).toFloat()
                val py = (ty * TILE - y0).toFloat()
                val tile = if (ty in 0 until n) tile(ZOOM, tx.mod(n), ty) else null
                if (tile != null) {
                    c.drawBitmap(tile, px, py, null)
                    tile.recycle()
                    haveMap = true
                } else {
                    c.drawRect(px, py, px + TILE, py + TILE, grid)
                }
            }
        }
        drawSymbol(c, ev)
        drawLabel(c, Caption.title(ev))
        if (haveMap) drawAttribution(c)

        val out = File(cardDir, "card-${System.nanoTime()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        // Keep the folder small: only the newest few files are ever needed.
        cardDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
        return out
    }

    private fun drawSymbol(c: Canvas, ev: CotEvent) {
        val x = W / 2f
        val y = H / 2f
        val r = 30f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.BLACK
        }
        val shape = Path()
        when {
            CotTypes.isAlert(ev.type) -> {
                fill.color = Color.rgb(230, 40, 40)
                shape.moveTo(x, y - r * 1.2f); shape.lineTo(x + r * 1.2f, y + r); shape.lineTo(x - r * 1.2f, y + r); shape.close()
            }
            !ev.type.startsWith("a-") -> {
                fill.color = Color.rgb(255, 160, 40)
                shape.addCircle(x, y, r * 0.8f, Path.Direction.CW)
            }
            else -> when (CotTypes.frame(ev.type)) {
                'f' -> {
                    fill.color = Color.rgb(128, 224, 255)
                    shape.addRoundRect(RectF(x - r * 1.3f, y - r * 0.9f, x + r * 1.3f, y + r * 0.9f), 8f, 8f, Path.Direction.CW)
                }
                'h' -> {
                    fill.color = Color.rgb(255, 128, 128)
                    shape.moveTo(x, y - r * 1.2f); shape.lineTo(x + r * 1.2f, y); shape.lineTo(x, y + r * 1.2f); shape.lineTo(x - r * 1.2f, y); shape.close()
                }
                'n' -> {
                    fill.color = Color.rgb(170, 255, 170)
                    shape.addRect(x - r, y - r, x + r, y + r, Path.Direction.CW)
                }
                else -> {
                    fill.color = Color.rgb(255, 255, 128)
                    // The "unknown" frame is a quatrefoil: four overlapping lobes.
                    for ((dx, dy) in listOf(0f to -0.55f, 0.55f to 0f, 0f to 0.55f, -0.55f to 0f)) {
                        shape.addCircle(x + dx * r, y + dy * r, r * 0.62f, Path.Direction.CW)
                    }
                }
            }
        }
        c.drawPath(shape, line)
        c.drawPath(shape, fill)
        if (CotTypes.isAlert(ev.type)) {
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText("!", x, y + r * 0.75f, t)
        }
    }

    private fun drawLabel(c: Canvas, name: String) {
        val text = if (name.length > 34) name.take(33) + "…" else name
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val w = p.measureText(text)
        val y = H / 2f + 78f
        val box = Paint().apply { color = Color.argb(190, 0, 0, 0) }
        c.drawRoundRect(RectF(W / 2f - w / 2 - 10, y - 28, W / 2f + w / 2 + 10, y + 10), 6f, 6f, box)
        c.drawText(text, W / 2f, y, p)
    }

    private fun drawAttribution(c: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 15f
            textAlign = Paint.Align.RIGHT
        }
        val w = p.measureText(ATTRIBUTION)
        c.drawRect(W - w - 12, H - 24f, W.toFloat(), H.toFloat(), Paint().apply { color = Color.argb(200, 255, 255, 255) })
        c.drawText(ATTRIBUTION, W - 6f, H - 7f, p)
    }

    private fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val template = tileUrl
        if (template.isEmpty()) return null
        val file = File(tileDir, "$z/$x/$y.png")
        try {
            if (file.isFile && System.currentTimeMillis() - file.lastModified() < CACHE_MS) {
                BitmapFactory.decodeFile(file.path)?.let { return it }
            }
            val url = URL(template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString()))
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                if (conn.responseCode != 200) return null
                val bytes = conn.inputStream.use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_TILE_BYTES) return null
                    }
                    buf.toByteArray()
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                return bmp
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Offline or blocked: fall back to whatever is cached, however old, else to the grid.
            return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
        }
    }

    companion object {
        const val W = 640
        const val H = 400
        const val OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val TILE = 256
        private const val ZOOM = 15
        private const val ATTRIBUTION = "© OpenStreetMap contributors"
        private const val USER_AGENT = "TAKgram/${BuildConfig.VERSION_NAME} (+https://github.com/mitchmitchell/takgram)"
        private const val MAX_TILE_BYTES = 1_000_000
        private const val CACHE_MS = 30L * 24 * 3600 * 1000
        private const val KEEP_FILES = 20
    }
}
