package com.motorota.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.motorota.data.Corridor
import com.motorota.data.Hazard
import com.motorota.data.HazardType
import com.motorota.data.KnownRisk
import com.motorota.data.Radar
import com.motorota.routing.GeoPoint
import com.motorota.routing.Mode
import com.motorota.routing.RiskArea
import com.motorota.routing.RouteResult
import com.motorota.routing.Waypoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/** Camadas desenhadas sobre o mapa: rotas, áreas de risco, radares e paradas. */
object MapLayers {
    const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    private const val SRC_ROUTES = "mr-routes"
    private const val SRC_RISKS = "mr-risks"
    private const val SRC_KNOWN = "mr-known"
    private const val SRC_CORRIDORS = "mr-corridors"
    private const val SRC_WPS = "mr-wps"
    private const val SRC_RADARS = "mr-radars"
    private const val SRC_HAZARDS = "mr-hazards"
    private const val SRC_POIS = "mr-pois"
    private const val SRC_SAT = "mr-sat"
    private const val LAYER_SAT = "mr-sat-layer"
    // Esri World Imagery: foto aérea de alta resolução (dá para ver rua, carro e telhado nas cidades)
    private const val SAT_URL = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    /** Liga/desliga a imagem de satélite mantendo as vias legíveis por cima. */
    fun setSatellite(style: Style, on: Boolean) {
        style.getLayer(LAYER_SAT)?.setProperties(
            PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
        )
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("mr-") && id != LAYER_ROAD_LABEL) continue
            if (layer is LineLayer && (id.startsWith("road_") || id.startsWith("bridge_") || id.startsWith("tunnel_"))) {
                if (!on) {
                    layer.setProperties(PropertyFactory.lineOpacity(1f))
                    continue
                }
                // no satélite: ruas em amarelo-claro (rodovia mais forte), com contorno escuro para destacar da foto
                val casing = id.endsWith("_casing")
                val (color, alpha) = when {
                    casing -> "#3A2E05" to 0.55f
                    id.contains("motorway") || id.contains("trunk_primary") -> "#FFC93C" to 0.92f
                    id.contains("secondary_tertiary") -> "#FFDD7A" to 0.85f
                    else -> "#FFF0B8" to 0.72f
                }
                layer.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineOpacity(alpha))
            } else if (layer is SymbolLayer && !id.startsWith("poi") && !id.contains("shield")) {
                if (on) layer.setProperties(
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("#1B1B1B"),
                    PropertyFactory.textHaloWidth(1.8f),
                )
            }
        }
    }

    private const val LAYER_ROAD_LABEL = "mr-road-label"

    /**
     * Rodovias e avenidas principais sempre com o código e o nome juntos: "BR-040 · Rodovia Washington Luiz".
     * Substitui a plaquinha e o nome separados do estilo original.
     */
    private fun addRoadLabels(style: Style) {
        if (style.getLayer(LAYER_ROAD_LABEL) != null) return
        val src = style.sources.firstOrNull { it.id == "openmaptiles" }?.id ?: return
        style.layers.filter { it.id.startsWith("highway-shield") || it.id == "highway-name-major" }
            .forEach { it.setProperties(PropertyFactory.visibility(Property.NONE)) }
        val ref = Expression.get("ref")
        val name = Expression.get("name")
        val hasRef = Expression.all(Expression.has("ref"), Expression.neq(ref, Expression.literal("")))
        val hasName = Expression.all(Expression.has("name"), Expression.neq(name, Expression.literal("")))
        val text = Expression.switchCase(
            Expression.all(hasRef, hasName, Expression.neq(ref, name)), Expression.concat(ref, Expression.literal(" · "), name),
            hasRef, ref,
            name,
        )
        val layer = SymbolLayer(LAYER_ROAD_LABEL, src).withProperties(
            PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            PropertyFactory.textField(text),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8, 10f), Expression.stop(14, 13f), Expression.stop(17, 14.5f))),
            PropertyFactory.textColor("#3A2A00"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.8f),
            PropertyFactory.textMaxAngle(35f),
            PropertyFactory.symbolSpacing(320f),
            PropertyFactory.textLetterSpacing(0.02f),
        )
        layer.sourceLayer = "transportation_name"
        layer.setFilter(Expression.match(Expression.get("class"), Expression.literal(false),
            Expression.stop("motorway", true), Expression.stop("trunk", true), Expression.stop("primary", true)))
        layer.minZoom = 7f
        // por CIMA da linha azul da rota (antes o nome da rodovia ficava escondido embaixo dela),
        // mas abaixo de radares, alertas e pontos da rota
        when {
            style.getLayer("mr-route-arrows") != null -> style.addLayerAbove(layer, "mr-route-arrows")
            else -> {
                val firstOurs = style.layers.firstOrNull { it.id.startsWith("mr-") && it.id != LAYER_SAT }?.id
                if (firstOurs != null) style.addLayerBelow(layer, firstOurs) else style.addLayer(layer)
            }
        }
    }

    private val HIDE = listOf(
        "natural_earth", "landuse_", "landcover_", "park_outline", "aeroway", "rail", "hatching",
        "road_area_pattern", "path_pedestrian", "one_way_arrow", "building", "poi", "airport",
        "label_other", "highway-name-path", "waterway_tunnel", "waterway_other", "boundary_disputed",
        "road_shield_us", "highway-shield-us",
    )

    private class MapPalette(
        val bg: String, val water: String, val park: String,
        val minor: String, val minorCase: String,
        val secondary: String, val secondaryCase: String,
        val primary: String, val primaryCase: String,
        val motorway: String, val motorwayCase: String,
        val text: String, val halo: String,
    )

    private val DAY = MapPalette(
        // ruas brancas com contorno bem marcado sobre fundo cinza: dá para ver as ruas ao redor
        bg = "#E6EAEF", water = "#A6D5FA", park = "#D6EBD2",
        minor = "#FFFFFF", minorCase = "#B9C1CB",
        secondary = "#FFFFFF", secondaryCase = "#A3ADB9",
        primary = "#FFF6DC", primaryCase = "#C9A95A",
        motorway = "#FFE9B0", motorwayCase = "#C08A2E",
        text = "#4A5056", halo = "#FFFFFF",
    )
    private val NIGHT = MapPalette(
        bg = "#161B22", water = "#0D1620", park = "#16221E",
        minor = "#323C49", minorCase = "#0E1217",
        secondary = "#343E4B", secondaryCase = "#141920",
        primary = "#434F5E", primaryCase = "#141920",
        motorway = "#566475", motorwayCase = "#141920",
        text = "#9AA4B2", halo = "#12161C",
    )

    const val ROUTE_BLUE = "#1A73E8"
    private const val ROUTE_BLUE_NIGHT = "#4C8DF6"

    /** Deixa o mapa limpo, só com as estradas e referências essenciais em destaque. */
    fun applyTheme(style: Style, night: Boolean) {
        addRoadLabels(style)
        val t = if (night) NIGHT else DAY
        for (layer in style.layers) {
            val id = layer.id
            if (id.startsWith("mr-")) continue
            if (HIDE.any { id.contains(it) }) {
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
                continue
            }
            when (layer) {
                is BackgroundLayer ->
                    layer.setProperties(PropertyFactory.backgroundColor(t.bg))
                is FillLayer -> when (id) {
                    "water" -> layer.setProperties(PropertyFactory.fillColor(t.water))
                    "park" -> layer.setProperties(PropertyFactory.fillColor(t.park), PropertyFactory.fillOpacity(0.65f))
                }
                is LineLayer -> {
                    val casing = id.endsWith("_casing")
                    val c = when {
                        id.contains("waterway") -> t.water
                        id.contains("boundary") -> if (night) "#3E4752" else "#B5BCC4"
                        id.contains("motorway") -> if (casing) t.motorwayCase else t.motorway
                        id.contains("trunk_primary") -> if (casing) t.primaryCase else t.primary
                        id.contains("secondary_tertiary") -> if (casing) t.secondaryCase else t.secondary
                        else -> if (casing) t.minorCase else t.minor
                    }
                    layer.setProperties(PropertyFactory.lineColor(c))
                }
                is SymbolLayer -> if (id.startsWith("poi")) {
                    // estabelecimentos (posto, restaurante, farmácia...): aparecem de perto, sem poluir
                    layer.setProperties(
                        PropertyFactory.textColor(if (night) "#C9D3E0" else "#3D4650"),
                        PropertyFactory.textHaloColor(t.halo),
                        PropertyFactory.textHaloWidth(1.4f),
                        PropertyFactory.textSize(11.5f),
                    )
                    layer.minZoom = 14.5f
                } else if (!id.contains("shield")) layer.setProperties(
                    PropertyFactory.textColor(t.text),
                    PropertyFactory.textHaloColor(t.halo),
                    PropertyFactory.textHaloWidth(1.6f),
                )
            }
        }
        style.getLayer("mr-route-main")?.setProperties(
            PropertyFactory.lineColor(if (night) ROUTE_BLUE_NIGHT else ROUTE_BLUE)
        )
        style.getLayer("mr-route-casing")?.setProperties(
            PropertyFactory.lineColor(if (night) "#0B3574" else "#0D47A1")
        )
        style.getLayer("mr-route-alt")?.setProperties(
            PropertyFactory.lineColor(if (night) "#636E7B" else "#8E96A0")
        )
        style.getLayer(LAYER_ROAD_LABEL)?.setProperties(
            PropertyFactory.textColor(if (night) "#F3D58A" else "#3A2A00"),
            PropertyFactory.textHaloColor(if (night) "#12161C" else "#FFFFFF"),
        )
        style.getLayer("mr-known-label")?.setProperties(
            PropertyFactory.textHaloColor(if (night) "#12161C" else "#FFFFFF")
        )
    }

    private fun arrowBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply {
            moveTo(14f, 10f); lineTo(34f, 24f); lineTo(14f, 38f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 7f
            color = android.graphics.Color.WHITE
        }
        c.drawPath(path, paint)
        return bmp
    }

    fun setup(style: Style) {
        style.addSource(GeoJsonSource(SRC_RISKS))
        style.addSource(GeoJsonSource(SRC_KNOWN))
        style.addSource(GeoJsonSource(SRC_CORRIDORS))
        style.addSource(GeoJsonSource(SRC_ROUTES))
        style.addSource(GeoJsonSource(SRC_WPS))
        style.addSource(GeoJsonSource(SRC_RADARS))
        style.addSource(GeoJsonSource(SRC_HAZARDS))
        style.addSource(GeoJsonSource(SRC_POIS))

        val labelAnchor = style.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("mr-") }?.id
        fun below(layer: Layer) {
            if (labelAnchor != null) style.addLayerBelow(layer, labelAnchor) else style.addLayer(layer)
        }

        // a Esri devolve uma imagem CINZA ("sem dados") quando não tem foto naquele zoom (comum acima
        // de 17 fora do centro das cidades). Pedindo no máximo zoom 17, o MapLibre amplia essa foto
        // em vez de mostrar cinza.
        val satTiles = TileSet("2.1.0", SAT_URL).apply {
            maxZoom = 17f
            attribution = "Imagens © Esri, Maxar, Earthstar Geographics"
        }
        style.addSource(RasterSource(SRC_SAT, satTiles, 256))
        val satLayer = RasterLayer(LAYER_SAT, SRC_SAT).withProperties(
            PropertyFactory.visibility(Property.NONE),
        )
        val firstRoad = style.layers.firstOrNull {
            it is LineLayer && (it.id.startsWith("tunnel_") || it.id.startsWith("road_"))
        }?.id
        if (firstRoad != null) style.addLayerBelow(satLayer, firstRoad) else below(satLayer)

        // Áreas bloqueadas pelo usuário
        below(
            FillLayer("mr-risk-fill", SRC_RISKS).withProperties(
                PropertyFactory.fillColor("#E53935"),
                PropertyFactory.fillOpacity(0.24f),
            )
        )
        below(
            LineLayer("mr-risk-line", SRC_RISKS).withProperties(
                PropertyFactory.lineColor("#EF5350"),
                PropertyFactory.lineWidth(2f),
            )
        )

        // Corredores de atenção: realce discreto sob a rodovia (sem poluir o mapa)
        below(
            LineLayer("mr-corridor", SRC_CORRIDORS).withProperties(
                PropertyFactory.lineColor("#D32F2F"),
                PropertyFactory.lineOpacity(0.18f),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(9, 2f), Expression.stop(13, 6f), Expression.stop(16, 12f)
                    )
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ).apply { minZoom = 9.5f }
        )

        // Áreas de risco conhecidas: sutis no mapa, rótulos apenas em zoom próximo sem sobrepor ruas
        below(
            FillLayer("mr-known-fill", SRC_KNOWN).withProperties(
                PropertyFactory.fillColor(Expression.get("cor")),
                PropertyFactory.fillOpacity(0.11f),
            ).apply { minZoom = 10f }
        )
        below(
            LineLayer("mr-known-line", SRC_KNOWN).withProperties(
                PropertyFactory.lineColor(Expression.get("cor")),
                PropertyFactory.lineWidth(1.2f),
                PropertyFactory.lineDasharray(arrayOf(3f, 2f)),
                PropertyFactory.lineOpacity(0.55f),
            ).apply { minZoom = 10.5f }
        )
        style.addLayer(
            SymbolLayer("mr-known-label", SRC_KNOWN).withProperties(
                PropertyFactory.textField(Expression.get("nome")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(Expression.get("cor")),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_POINT),
                PropertyFactory.textAllowOverlap(false),
            ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
                .apply { minZoom = 13.5f }
        )

        // Rotas (alternativas em cinza + selecionada em azul com setas de sentido)
        style.addImage("mr-arrow", arrowBitmap())
        val selected = Expression.eq(Expression.get("sel"), Expression.literal(true))
        below(
            LineLayer("mr-route-alt", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor("#8E96A0"),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.82f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(Expression.not(selected))
        )
        below(
            LineLayer("mr-route-casing", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor("#0D47A1"),
                PropertyFactory.lineWidth(12.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(selected)
        )
        below(
            LineLayer("mr-route-main", SRC_ROUTES).withProperties(
                PropertyFactory.lineColor(ROUTE_BLUE),
                PropertyFactory.lineWidth(9.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(selected)
        )
        below(
            SymbolLayer("mr-route-arrows", SRC_ROUTES).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.symbolSpacing(95f),
                PropertyFactory.iconImage("mr-arrow"),
                PropertyFactory.iconSize(0.62f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            ).withFilter(selected).apply { minZoom = 11.5f }
        )

        // Radares: só aparecem em zoom urbano/rodoviário próximo para manter o mapa limpo
        style.addLayer(
            CircleLayer("mr-radar", SRC_RADARS).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(), Expression.zoom(),
                        Expression.stop(13, 4.5f), Expression.stop(16, 10.5f)
                    )
                ),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleStrokeColor("#E53935"),
                PropertyFactory.circleStrokeWidth(2.6f),
            ).apply { minZoom = 13.2f }
        )
        style.addLayer(
            SymbolLayer("mr-radar-label", SRC_RADARS).withProperties(
                PropertyFactory.textField(Expression.get("lim")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(10.5f),
                PropertyFactory.textColor("#111111"),
                PropertyFactory.textAllowOverlap(false),
            ).apply { minZoom = 14.2f }
        )

        // Alertas marcados pelo piloto
        style.addLayer(
            CircleLayer("mr-hazard", SRC_HAZARDS).withProperties(
                PropertyFactory.circleRadius(9.5f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
        style.addLayer(
            SymbolLayer("mr-hazard-label", SRC_HAZARDS).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(11.5f),
                PropertyFactory.textOffset(arrayOf(0f, 1.5f)),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#101214"),
                PropertyFactory.textHaloWidth(1.8f),
                PropertyFactory.textAllowOverlap(false),
            ).apply { minZoom = 11.5f }
        )

        // Pontos do roteiro (Partida, Paradas, Destino)
        style.addLayer(
            CircleLayer("mr-wp", SRC_WPS).withProperties(
                PropertyFactory.circleRadius(8.5f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#1F242B"),
                PropertyFactory.circleStrokeWidth(2.8f),
            )
        )
        style.addLayer(
            SymbolLayer("mr-wp-label", SRC_WPS).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(12.5f),
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

    /** Em navegação: desenha apenas o trecho restante a partir da posição projetada da moto. */
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
        src.setGeoJson(
            FeatureCollection.fromFeatures(risks.map { Feature.fromGeometry(circle(it.point, it.radiusM.toDouble())) })
        )
    }

    fun updateCorridors(style: Style, list: List<Corridor>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_CORRIDORS) ?: return
        val feats = list.flatMap { c ->
            c.lines.map { l -> Feature.fromGeometry(LineString.fromLngLats(l.map { Point.fromLngLat(it.lon, it.lat) })) }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    fun updateKnownRisks(style: Style, list: List<KnownRisk>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_KNOWN) ?: return
        val feats = ArrayList<Feature>()
        for (k in list) {
            val cor = if (k.avoid) "#D32F2F" else "#EF6C00"
            feats.add(Feature.fromGeometry(circle(k.point, k.radiusM.toDouble())).apply { addStringProperty("cor", cor) })
            feats.add(Feature.fromGeometry(Point.fromLngLat(k.point.lon, k.point.lat)).apply {
                addStringProperty("cor", cor)
                addStringProperty("nome", k.name.substringBefore(" ("))
            })
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
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
        origin?.let { add(it.point, "#10B981", "Partida") }
        stops.forEachIndexed { i, w -> add(w.point, "#FFFFFF", "${i + 1}") }
        dest?.let { add(it.point, "#EF4444", "Destino") }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    fun updateRadars(style: Style, radars: List<Radar>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_RADARS) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(radars.map { r ->
                Feature.fromGeometry(Point.fromLngLat(r.point.lon, r.point.lat)).apply {
                    addStringProperty("lim", if (r.limitKmh > 0) r.limitKmh.toString() else "")
                }
            })
        )
    }

    /** Camadas dos estabelecimentos (TomTom): bolinha colorida + nome, só de perto. */
    private fun addPoiLayers(style: Style) {
        if (style.getLayer("mr-poi-dot") != null) return
        style.addLayer(
            CircleLayer("mr-poi-dot", SRC_POIS).withProperties(
                PropertyFactory.circleColor(Expression.get("cor")),
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(1.5f),
            ).apply { minZoom = 14f },
        )
        style.addLayer(
            SymbolLayer("mr-poi-label", SRC_POIS).withProperties(
                PropertyFactory.textField(Expression.get("nome")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#374151"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.4f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.7f)),
                PropertyFactory.textOptional(true),
            ).apply { minZoom = 16f },
        )
    }

    fun updatePois(style: Style, pois: List<com.motorota.data.Pois.Poi>) {
        addPoiLayers(style)
        val src = style.getSourceAs<GeoJsonSource>(SRC_POIS) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(pois.map { p ->
                Feature.fromGeometry(Point.fromLngLat(p.point.lon, p.point.lat)).apply {
                    addStringProperty("nome", p.name)
                    addStringProperty("cor", p.kind.color)
                }
            })
        )
    }

    fun updateHazards(style: Style, hazards: List<Hazard>) {
        val src = style.getSourceAs<GeoJsonSource>(SRC_HAZARDS) ?: return
        src.setGeoJson(
            FeatureCollection.fromFeatures(hazards.map { h ->
                Feature.fromGeometry(Point.fromLngLat(h.point.lon, h.point.lat)).apply {
                    addStringProperty("label", h.type.label)
                    addStringProperty(
                        "color",
                        when (h.type) {
                            HazardType.POLICIA -> "#1E88E5"
                            HazardType.ACIDENTE -> "#E53935"
                            HazardType.RADAR_MOVEL -> "#8E24AA"
                            HazardType.OBRA -> "#E65100"
                            else -> "#F57C00"
                        }
                    )
                }
            })
        )
    }

    private fun circle(c: GeoPoint, radiusM: Double): Polygon {
        val pts = ArrayList<Point>(49)
        val dLat = radiusM / 110540.0
        val dLon = radiusM / (111320.0 * cos(Math.toRadians(c.lat)))
        for (i in 0..48) {
            val a = 2 * Math.PI * i / 48
            pts.add(Point.fromLngLat(c.lon + dLon * cos(a), c.lat + dLat * sin(a)))
        }
        return Polygon.fromLngLats(listOf(pts))
    }
}
