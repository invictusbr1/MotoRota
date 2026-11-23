package btools.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * MotoRota: "trechos perigosos" (rodovias com muitos assaltos) indexados numa grade.
 * Para cada pedaco de rua avaliado pelo roteador, diz quanto custo extra ele paga:
 * - na propria rodovia: metros dentro da faixa (meia-largura em volta do eixo) x peso;
 * - na redondeza (~1,5 km): metros x peso da redondeza (o OsmPath multiplica pelo tipo de via,
 *   entao rodovia/avenida grande quase nao paga e as ruas locais pagam tudo).
 *
 * Feito para ser rapido no celular: depois de montado, as consultas nao criam objetos
 * (tabelas de numeros com enderecamento aberto) e quem esta longe de tudo sai na primeira conta.
 */
public final class CorridorIndex {
  private static final double CELL_DEG = 0.002;        // ~220 m (faixa da rodovia)
  private static final double REGION_CELL_DEG = 0.01;  // ~1,1 km (redondeza)

  private final double halfWidthM;

  // ---- montagem (so usado enquanto adiciona linhas) ----
  private final HashMap<Long, List<double[]>> build = new HashMap<>();
  private final HashMap<Long, Double> buildRegion = new HashMap<>();
  // volatile: o indice pode ser consultado por mais de uma thread depois de montado
  private volatile boolean frozen = false;

  // ---- consulta ----
  private long[] gKeys;          // celula da faixa
  private double[][] gSegs;      // segmentos da celula, achatados: lat1,lon1,lat2,lon2,peso,...
  private long[] rKeys;          // celula da redondeza
  private double[] rVals;
  private int gMask, rMask;
  // caixa que contem tudo (com a redondeza): fora dela, custo zero sem procurar
  private double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;

  public CorridorIndex(double halfWidthM) {
    this.halfWidthM = halfWidthM;
  }

  public boolean isEmpty() {
    return build.isEmpty() && buildRegion.isEmpty() && gKeys == null && rKeys == null;
  }

  /** acrescenta uma linha (eixo de uma pista) com o peso dado */
  public void addLine(double[] lats, double[] lons, double weight) {
    if (frozen) throw new IllegalStateException("indice ja montado");
    if (weight <= 0) return;
    double padLat = halfWidthM / 111320.0;
    for (int i = 0; i + 1 < lats.length; i++) {
      double[] seg = {lats[i], lons[i], lats[i + 1], lons[i + 1], weight};
      double padLon = padLat / Math.cos(Math.toRadians(lats[i]));
      grow(Math.min(lats[i], lats[i + 1]) - padLat, Math.min(lons[i], lons[i + 1]) - padLon);
      grow(Math.max(lats[i], lats[i + 1]) + padLat, Math.max(lons[i], lons[i + 1]) + padLon);
      for (int a = cell(Math.min(lats[i], lats[i + 1]) - padLat); a <= cell(Math.max(lats[i], lats[i + 1]) + padLat); a++) {
        for (int o = cell(Math.min(lons[i], lons[i + 1]) - padLon); o <= cell(Math.max(lons[i], lons[i + 1]) + padLon); o++) {
          build.computeIfAbsent(key(a, o), k -> new ArrayList<>()).add(seg);
        }
      }
    }
  }

  /** marca a redondeza (raio aproximado em metros) da linha com o peso dado */
  public void addRegion(double[] lats, double[] lons, double radiusM, double weight) {
    if (frozen) throw new IllegalStateException("indice ja montado");
    if (weight <= 0) return;
    for (int i = 0; i + 1 < lats.length; i++) {
      double len = Math.hypot((lats[i + 1] - lats[i]) * 111320, (lons[i + 1] - lons[i]) * 111320 * Math.cos(Math.toRadians(lats[i])));
      int steps = Math.max(1, (int) (len / 300));
      for (int s = 0; s <= steps; s++) {
        double la = lats[i] + (lats[i + 1] - lats[i]) * s / steps;
        double lo = lons[i] + (lons[i + 1] - lons[i]) * s / steps;
        double dLa = radiusM / 111320.0;
        double dLo = dLa / Math.cos(Math.toRadians(la));
        grow(la - dLa - REGION_CELL_DEG, lo - dLo - REGION_CELL_DEG);
        grow(la + dLa + REGION_CELL_DEG, lo + dLo + REGION_CELL_DEG);
        for (int a = rcell(la - dLa); a <= rcell(la + dLa); a++) {
          for (int o = rcell(lo - dLo); o <= rcell(lo + dLo); o++) {
            buildRegion.merge(key(a, o), weight, Math::max);
          }
        }
      }
    }
  }

  private void grow(double lat, double lon) {
    minLat = Math.min(minLat, lat);
    maxLat = Math.max(maxLat, lat);
    minLon = Math.min(minLon, lon);
    maxLon = Math.max(maxLon, lon);
  }

  /** monta as tabelas de consulta (chamado sozinho na primeira consulta, ou antes por quem quiser) */
  public synchronized void freeze() {
    if (frozen) return;
    int gCap = tableSize(build.size());
    gKeys = new long[gCap];
    Arrays.fill(gKeys, Long.MIN_VALUE);
    gSegs = new double[gCap][];
    gMask = gCap - 1;
    for (java.util.Map.Entry<Long, List<double[]>> e : build.entrySet()) {
      int slot = slot(gKeys, gMask, e.getKey());
      gKeys[slot] = e.getKey();
      List<double[]> l = e.getValue();
      double[] flat = new double[l.size() * 5];
      for (int i = 0; i < l.size(); i++) System.arraycopy(l.get(i), 0, flat, i * 5, 5);
      gSegs[slot] = flat;
    }
    int rCap = tableSize(buildRegion.size());
    rKeys = new long[rCap];
    Arrays.fill(rKeys, Long.MIN_VALUE);
    rVals = new double[rCap];
    rMask = rCap - 1;
    for (java.util.Map.Entry<Long, Double> e : buildRegion.entrySet()) {
      int slot = slot(rKeys, rMask, e.getKey());
      rKeys[slot] = e.getKey();
      rVals[slot] = e.getValue();
    }
    build.clear();
    buildRegion.clear();
    frozen = true; // so depois de tudo pronto
  }

  private static int tableSize(int n) {
    int cap = 16;
    while (cap < n * 2) cap <<= 1;
    return cap;
  }

  /** posicao da chave na tabela (ou da primeira vaga) */
  private static int slot(long[] keys, int mask, long k) {
    int i = (int) (mix(k) & mask);
    while (keys[i] != Long.MIN_VALUE && keys[i] != k) i = (i + 1) & mask;
    return i;
  }

  private static long mix(long k) {
    k ^= (k >>> 33);
    k *= 0xff51afd7ed558ccdL;
    k ^= (k >>> 33);
    return k;
  }

  private boolean outside(double lat, double lon) {
    return lat < minLat || lat > maxLat || lon < minLon || lon > maxLon;
  }

  /** custo extra na propria rodovia (coordenadas internas do BRouter: microgrdaus + offset) */
  public double cost(int ilon1, int ilat1, int ilon2, int ilat2, double distM) {
    if (distM <= 0) return 0;
    if (!frozen) freeze();
    double lat1 = ilat1 / 1e6 - 90.0, lon1 = ilon1 / 1e6 - 180.0;
    double lat2 = ilat2 / 1e6 - 90.0, lon2 = ilon2 / 1e6 - 180.0;
    if (outside(lat1, lon1) && outside(lat2, lon2)) return 0;
    double w1 = weightAt(lat1, lon1);
    double w2 = weightAt(lat2, lon2);
    if (w1 == 0 && w2 == 0) {
      // segmento comprido pode ter as pontas fora e o meio dentro
      if (distM < 2 * halfWidthM) return 0;
      return weightAt((lat1 + lat2) / 2, (lon1 + lon2) / 2) * distM * 0.5;
    }
    return distM * (w1 + w2) * 0.5;
  }

  /** custo da redondeza (antes do fator do tipo de via, aplicado no OsmPath) */
  public double regionCost(int ilon1, int ilat1, int ilon2, int ilat2, double distM) {
    if (distM <= 0) return 0;
    if (!frozen) freeze();
    if (rKeys == null || rKeys.length == 0) return 0;
    double lat = (ilat1 + ilat2) / 2e6 - 90.0, lon = (ilon1 + ilon2) / 2e6 - 180.0;
    if (outside(lat, lon)) return 0;
    long k = key(rcell(lat), rcell(lon));
    int i = (int) (mix(k) & rMask);
    while (rKeys[i] != Long.MIN_VALUE) {
      if (rKeys[i] == k) return rVals[i] * distM;
      i = (i + 1) & rMask;
    }
    return 0;
  }

  /** maior peso de um trecho perigoso que passa a menos de halfWidthM do ponto (0 se nenhum) */
  public double weightAt(double lat, double lon) {
    if (!frozen) freeze();
    if (outside(lat, lon)) return 0;
    long k = key(cell(lat), cell(lon));
    int i = (int) (mix(k) & gMask);
    double[] segs = null;
    while (gKeys[i] != Long.MIN_VALUE) {
      if (gKeys[i] == k) {
        segs = gSegs[i];
        break;
      }
      i = (i + 1) & gMask;
    }
    if (segs == null) return 0;
    double kc = Math.cos(Math.toRadians(lat));
    double hw2 = halfWidthM * halfWidthM;
    double best = 0;
    for (int s = 0; s < segs.length; s += 5) {
      double w = segs[s + 4];
      if (w <= best) continue;
      double ax = (segs[s + 1] - lon) * kc * 111320, ay = (segs[s] - lat) * 111320;
      double bx = (segs[s + 3] - lon) * kc * 111320, by = (segs[s + 2] - lat) * 111320;
      double dx = bx - ax, dy = by - ay;
      double l2 = dx * dx + dy * dy;
      double t = l2 > 0 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2)) : 0;
      double x = ax + dx * t, y = ay + dy * t;
      if (x * x + y * y <= hw2) best = w;
    }
    return best;
  }

  private static int cell(double deg) {
    return (int) Math.floor(deg / CELL_DEG);
  }

  private static int rcell(double deg) {
    return (int) Math.floor(deg / REGION_CELL_DEG);
  }

  private static long key(int a, int o) {
    return ((long) a << 32) ^ (o & 0xffffffffL);
  }
}
