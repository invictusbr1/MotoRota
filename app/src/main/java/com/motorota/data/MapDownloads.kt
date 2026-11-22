package com.motorota.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

data class Country(val name: String, val flag: String, val sizeMb: Int, val tiles: List<String>)

/**
 * Mapas de rota (formato BRouter .rd5, blocos de 5x5 graus), baixados de brouter.de.
 * Cada pais lista os blocos que o cobrem.
 */
object MapPacks {
    val countries = listOf(
        Country(
            "Brasil", "🇧🇷", 571, listOf(
                "W75_S35", "W75_S30", "W75_S25", "W75_S20", "W75_S15", "W75_S10", "W75_S5", "W75_N0", "W75_N5",
                "W70_S35", "W70_S30", "W70_S25", "W70_S20", "W70_S15", "W70_S10", "W70_S5", "W70_N0", "W70_N5",
                "W65_S35", "W65_S30", "W65_S25", "W65_S20", "W65_S15", "W65_S10", "W65_S5", "W65_N0", "W65_N5",
                "W60_S35", "W60_S30", "W60_S25", "W60_S20", "W60_S15", "W60_S10", "W60_S5", "W60_N0", "W60_N5",
                "W55_S35", "W55_S30", "W55_S25", "W55_S20", "W55_S15", "W55_S10", "W55_S5", "W55_N0", "W55_N5",
                "W50_S30", "W50_S25", "W50_S20", "W50_S15", "W50_S10", "W50_S5",
                "W45_S25", "W45_S20", "W45_S15", "W45_S10", "W45_S5",
                "W40_S20", "W40_S15", "W40_S10", "W40_S5", "W35_S10",
            )
        ),
        Country(
            "Argentina", "🇦🇷", 204, listOf(
                "W75_S55", "W75_S50", "W75_S45", "W75_S40", "W75_S35", "W75_S30", "W75_S25",
                "W70_S55", "W70_S50", "W70_S45", "W70_S40", "W70_S35", "W70_S30", "W70_S25",
                "W65_S45", "W65_S40", "W65_S35", "W65_S30", "W65_S25",
                "W60_S55", "W60_S40", "W60_S35", "W60_S30", "W60_S25",
                "W55_S35", "W55_S30", "W55_S25",
            )
        ),
        Country(
            "Chile", "🇨🇱", 117, listOf(
                "W75_S55", "W75_S50", "W75_S45", "W75_S40", "W75_S35", "W75_S30", "W75_S25", "W75_S20",
                "W70_S60", "W70_S55", "W70_S50", "W70_S45", "W70_S40", "W70_S35", "W70_S30", "W70_S25", "W70_S20",
            )
        ),
        Country("Uruguai", "🇺🇾", 20, listOf("W60_S35", "W55_S35")),
    )

    fun tileFor(lat: Double, lon: Double): String {
        val lo = Math.floorDiv(Math.floor(lon).toInt(), 5) * 5
        val la = Math.floorDiv(Math.floor(lat).toInt(), 5) * 5
        val ew = if (lo < 0) "W${-lo}" else "E$lo"
        val ns = if (la < 0) "S${-la}" else "N$la"
        return "${ew}_$ns"
    }
}

class MapDownloader(private val ctx: Context, private val segmentDir: File) {
    private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = ctx.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    fun isInstalled(tile: String) = File(segmentDir, "$tile.rd5").let { it.exists() && it.length() > 0 }

    fun installedCount(c: Country) = c.tiles.count { isInstalled(it) }

    /** Inicia o download (em segundo plano, pelo sistema) dos blocos que faltam. */
    fun download(tiles: Collection<String>) {
        for (tile in tiles.distinct()) {
            if (isInstalled(tile) || prefs.contains(tile)) continue
            val part = File(segmentDir, "$tile.rd5.part")
            part.delete()
            val req = DownloadManager.Request(Uri.parse("$BASE_URL$tile.rd5"))
                .setTitle("MotoRota: mapa $tile")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, null, "brouter/segments4/$tile.rd5.part")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            val id = dm.enqueue(req)
            prefs.edit().putLong(tile, id).apply()
        }
    }

    data class Progress(val pending: Int, val bytesDone: Long, val bytesTotal: Long, val failed: Int)

    /**
     * Confere os downloads em andamento. Os concluidos sao renomeados de .part para .rd5,
     * e so entao o motor de rotas passa a usa-los.
     */
    fun poll(): Progress {
        var pending = 0
        var done = 0L
        var total = 0L
        var failed = 0
        val editor = prefs.edit()
        for ((tile, value) in prefs.all) {
            val id = value as? Long ?: continue
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    editor.remove(tile)
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val size = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val part = File(segmentDir, "$tile.rd5.part")
                        val dest = File(segmentDir, "$tile.rd5")
                        if (part.exists()) {
                            dest.delete()
                            part.renameTo(dest)
                        }
                        editor.remove(tile)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        failed++
                        editor.remove(tile)
                        dm.remove(id)
                    }
                    else -> {
                        pending++
                        done += soFar.coerceAtLeast(0)
                        if (size > 0) total += size
                    }
                }
            }
        }
        editor.apply()
        return Progress(pending, done, total, failed)
    }

    fun deleteCountry(c: Country, keep: Set<String>) {
        c.tiles.filter { it !in keep }.forEach { File(segmentDir, "$it.rd5").delete() }
    }

    companion object {
        const val BASE_URL = "https://brouter.de/brouter/segments4/"
    }
}
