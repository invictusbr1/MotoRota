package com.motorota.ui

import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
import com.motorota.routing.RiskArea
import com.motorota.routing.RouteResult
import com.motorota.routing.Waypoint
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** Camadas desenhadas por cima do mapa: rotas, areas de risco e paradas. */
object MapLayers {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    private const val SRC_ROUTES = "mr-routes"
    private const val SRC_RISKS = "mr-risks"
    private const val SRC_WPS = "mr-wps"
    private const val SRC_RADARS = "mr-radars"
    private const val SRC_HAZARDS = "mr-hazards"
    private const val SRC_SAT = "mr-sat"
    private const val LAYER_SAT = "mr-sat-layer"
    private const val SAT_URL = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg"

    /** liga/desliga a imagem de satelite (estradas e nomes continuam por cima) */
    fun setSatellite(style: Style, on: Boolean) {
        style.getLayer(LAYER_SAT)?.setProperties(PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE))
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("mr-")) continue
            if (layer is LineLayer && (id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_"))) {
                // no satelite as ruas ficam meio transparentes para ver a foto
                layer.setProperties(PropertyFactory.lineOpacity(if (on) 0.55f else 1f))
            }
        }
    }

    private val HIDE = listOf(
        "natural_earth", "landuse_", "landcover_", "park_outline", "aeroway", "rail", "hatching",
        "road_area_pattern", "path_pedestrian", "one_way_arrow", "building", "poi_", "airport",
        "label_other", "highway-name-path", "waterway_tunnel", "waterway_other", "boundary_disputed",
        "road_shield_us", "highway-shield-us",
    )

    private class Theme(
        val bg: String, val water: String, val park: String,
        val minor: String, val minorCase: String,
        val secondary: String, val secondaryCase: String,
        val primary: String, val primaryCase: String,
        val motorway: String, val motorwayCase: String,
        val text: String, val halo: String,
    )

    // Paletas simples, no estilo do Google Maps: estradas brancas/cinza, sem amarelo.
    private val DAY = Theme(
        bg = "#F1F3F4", water = "#AADAFF", park = "#DCEFD9",
        minor = "#FFFFFF", minorCase = "#E1E3E6",
        secondary = "#FFFFFF", secondaryCase = "#CDD1D5",
        primary = "#FFFFFF", primaryCase = "#BDC1C6",
        motorway = "#FFFFFF", motorwayCase = "#9AA0A6",
        text = "#5F6368", halo = "#FFFFFF",
    )
    private val NIGHT = Theme(
        bg = "#1F2630", water = "#0F1A25", park = "#1D2A26",
        minor = "#353E4A", minorCase = "#1A2029",
        secondary = "#434D5A", secondaryCase = "#1A2029",
        primary = "#525D6B", primaryCase = "#1A2029",
        motorway = "#66727F", motorwayCase = "#1A2029",
        text = "#9AA4B1", halo = "#161B22",
    )

    const val ROUTE_BLUE = "#1A73E8"
    private const val ROUTE_BLUE_NIGHT = "#4C8DF6"

    /** Deixa o mapa "limpo", so com as estradas em destaque (estilo navegador). */
    fun applyTheme(style: Style, night: Boolean) {
        val t = if (night) NIGHT else DAY
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("mr-")) continue
            if (HIDE.any { id.contains(it) }) {
                layer.setProperties(PropertyFactory.visibility(Property.NONE)); continue
            }
            when (layer) {
                is org.maplibre.android.style.layers.BackgroundLayer ->
                    layer.setProperties(PropertyFactory.backgroundColor(t.bg))
                is FillLayer -> when (id) {
                    "water" -> layer.setProperties(PropertyFactory.fillColor(t.water))
                    "park" -> layer.setProperties(PropertyFactory.fillColor(t.park), PropertyFactory.fillOpacity(0.7f))
                }
                is LineLayer -> {
                    val casing = id.endsWith("_casing")
                    val c = when {
                        id.contains("waterway") -> t.water
                        id.contains("boundary") -> if (night) "#4A535E" else "#B0B6BD"
                        id.contains("motorway") -> if (casing) t.motorwayCase else t.motorway
                        id.contains("trunk_primary") -> if (casing) t.primaryCase else t.primary
                        id.contains("secondary_tertiary") -> if (casing) t.secondaryCase else t.secondary
                        else -> if (casing) t.minorCase else t.minor
                    }
                    layer.setProperties(PropertyFactory.lineColor(c))
                }
                is SymbolLayer -> if (!id.contains("shield")) layer.setProperties(
                    PropertyFactory.textColor(t.text),
                    PropertyFactory.textHaloColor(t.halo),
                    PropertyFactory.textHaloWidth(1.5f),
                )
            }
        }
        // rota: azul forte; alternativas em cinza
        style.getLayer("mr-route-main")?.setProperties(PropertyFactory.lineColor(if (night) ROUTE_BLUE_NIGHT else ROUTE_BLUE))
        style.getLayer("mr-route-casing")?.setProperties(PropertyFactory.lineColor(if (night) "#0B3A80" else "#0D47A1"))
        style.getLayer("mr-route-alt")?.setProperties(PropertyFactory.lineColor(if (night) "#6B7480" else "#9AA0A6"))
    }

    /** seta branca (">") desenhada ao longo da rota, apontando o sentido */
    private fun arrowBitmap(): android.graphics.Bitmap {
        val size = 48
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val path = android.graphics.Path().apply {
            moveTo(14f, 10f); lineTo(34f, 24f); lineTo(14f, 38f)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = 7f
            color = android.graphics.Color.WHITE
        }
        c.drawPath(path, paint)
        return bmp
    }

    fun setup(style: Style) {
        style.addSource(GeoJsonSource(SRC_RISKS))
        style.addSource(GeoJsonSource(SRC_ROUTES))
        style.addSource(GeoJsonSource(SRC_WPS))
        style.addSource(GeoJsonSource(SRC_RADARS))
        style.addSource(GeoJsonSource(SRC_HAZARDS))

        // tudo que e "chao" (areas, rota) fica ABAIXO dos nomes e placas das rodovias
        val labelAnchor = style.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("mr-") }?.id
        fun below(layer: org.maplibre.android.style.layers.Layer) {
            if (labelAnchor != null) style.addLayerBelow(layer, labelAnchor) else style.addLayer(layer)
        }

        // imagem de satelite (desligada ate o usuario pedir): fica por cima do fundo e abaixo das estradas
        val satTiles = org.maplibre.android.style.sources.TileSet("2.1.0", SAT_URL).apply {
            maxZoom = 15f
            attribution = "Sentinel-2 cloudless – s2maps.eu by EOX IT Services GmbH (Copernicus Sentinel data)"
        }
        style.addSource(org.maplibre.android.style.sources.RasterSource(SRC_SAT, satTiles, 256))
        val satLayer = org.maplibre.android.style.layers.RasterLayer(LAYER_SAT, SRC_SAT).withProperties(
            PropertyFactory.visibility(Property.NONE),
        )
        val firstRoad = style.layers.firstOrNull { it is LineLayer && (it.id.startsWith("tunnel_") || it.id.startsWith("road_")) }?.id
        if (firstRoad != null) style.addLayerBelow(satLayer, firstRoad) else below(satLayer)

        below(
            FillLayer("mr-risk-fill", SRC_RISKS).withProperties(
                PropertyFactory.fillColor("#E53935"),
                PropertyFactory.fillOpacity(0.28f),
            )
        )
        below(
            LineLayer("mr-risk-line", SRC_RISKS).withProperties(
                PropertyFactory.lineColor("#E53935"),
                PropertyFactory.lineWidth(2f),
            )
        )

        style.addImage("mr-arrow", arrowBitmap())
        val selected = Expression.eq(Expression.get("sel"), Expression.literal(true))
        below(
            LineLayer("mr-route-alt", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor("#9AA0A6"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(Expression.not(selected))
        )
        below(
            LineLayer("mr-route-casing", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor("#0D47A1"),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(selected)
        )
        below(
            LineLayer("mr-route-main", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor(ROUTE_BLUE),
                PropertyFactory.lineWidth(10f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(selected)
        )
        below(
            SymbolLayer("mr-route-arrows", SRC_ROUTES).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(80f),
                PropertyFactory.iconImage("mr-arrow"),
                PropertyFactory.iconSize(0.65f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            ).withFilter(selected).apply { minZoom = 11f }
        )

        // radares: plaquinha branca com borda vermelha e o limite
        style.addLayer(
            CircleLayer("mr-radar", SRC_RADARS).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(10, 4f), Expression.stop(15, 11f)
                    )
                ),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#E53935"),
                PropertyFactory.circleStrokeWidth(3f),
            ).apply { minZoom = 12.5f }
        )
        style.addLayer(
            SymbolLayer("mr-radar-label", SRC_RADARS).withProperties(
                PropertyFactory.textField(Expression.get("lim")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#111111"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ).apply { minZoom = 13f }
        )
        // alertas (buraco, policia...)
        style.addLayer(
            CircleLayer("mr-hazard", SRC_HAZARDS).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            SymbolLayer("mr-hazard-label", SRC_HAZARDS).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#101214"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
            ).apply { minZoom = 11f }
        )

        style.addLayer(
            CircleLayer("mr-wp", SRC_WPS).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#3C4043"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        style.addLayer(
            SymbolLayer("mr-wp-label", SRC_WPS).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#101214"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
            )
        )
    }

    fun updateRoutes(style: Style, routes: Map<Mode, RouteResult>, selected: Mode, navigating: Boolean) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_ROUTES) ?: return
        val feats = routes.values
            .filter { !navigating || it.mode == selected }
            .sortedBy { it.mode == selected }
            .map { r ->
                Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                    addStringProperty("color", r.mode.colorHex)
                    addBooleanProperty("sel", r.mode == selected)
                }
            }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    /** Navegando: desenha so o que falta da rota (o trecho percorrido some). */
    fun updateRemaining(style: Style, route: RouteResult, fromIndex: Int, start: GeoPoint) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_ROUTES) ?: return
        val pts = ArrayList<Point>(route.points.size - fromIndex + 1)
        pts.add(Point.fromLngLat(start.lon, start.lat))
        for (i in (fromIndex + 1) until route.points.size) {
            val p = route.points[i]
            pts.add(Point.fromLngLat(p.lon, p.lat))
        }
        if (pts.size < 2) return
        val f = Feature.fromGeometry(LineString.fromLngLats(pts)).apply { addBooleanProperty("sel", true) }
        src.setGeoJson(FeatureCollection.fromFeatures(listOf(f)))
    }

    fun updateRisks(style: Style, risks: List<RiskArea>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_RISKS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(risks.map { Feature.fromGeometry(circle(it.point, it.radiusM.toDouble())) }))
    }

    fun updateWaypoints(style: Style, origin: Waypoint?, stops: List<Waypoint>, dest: Waypoint?) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_WPS) ?: return
        val feats = ArrayList<Feature>()
        fun add(p: GeoPoint, color: String, label: String) {
            feats.add(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addStringProperty("color", color)
                addStringProperty("label", label)
            })
        }
        origin?.let { add(it.point, "#5F6368", "Partida") }
        stops.forEachIndexed { i, w -> add(w.point, "#FFFFFF", "${i + 1}") }
        dest?.let { add(it.point, "#EA4335", "Destino") }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    fun updateRadars(style: Style, radars: List<com.motorota.data.Radar>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_RADARS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(radars.map { r ->
            Feature.fromGeometry(Point.fromLngLat(r.point.lon, r.point.lat)).apply {
                addStringProperty("lim", if (r.limitKmh > 0) r.limitKmh.toString() else "")
            }
        }))
    }

    fun updateHazards(style: Style, hazards: List<com.motorota.data.Hazard>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_HAZARDS) ?: return
        src.setGeoJson(FeatureCollection.fromFeatures(hazards.map { h ->
            Feature.fromGeometry(Point.fromLngLat(h.point.lon, h.point.lat)).apply {
                addStringProperty("label", h.type.label)
                addStringProperty(
                    "color", when (h.type) {
                        com.motorota.data.HazardType.POLICIA -> "#1E88E5"
                        com.motorota.data.HazardType.ACIDENTE -> "#E53935"
                        com.motorota.data.HazardType.RADAR_MOVEL -> "#8E24AA"
                        com.motorota.data.HazardType.OBRA -> "#E65100"
                        else -> "#F57C00"
                    }
                )
            }
        }))
    }

    private fun circle(c: GeoPoint, radiusM: Double): Polygon {
        val pts = ArrayList<Point>()
        val dLat = radiusM / 110540.0
        val dLon = radiusM / (111320.0 * cos(Math.toRadians(c.lat)))
        for (i in 0..48) {
            val a = 2 * Math.PI * i / 48
            pts.add(Point.fromLngLat(c.lon + dLon * cos(a), c.lat + dLat * sin(a)))
        }
        return Polygon.fromLngLats(listOf(pts))
    }
}
