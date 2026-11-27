import btools.router.*;
import java.io.File;
import java.util.*;

/** Calcula uma rota com o motor do app (BRouter + perfil moto) e imprime o JSON. */
public class RouteTest {
  public static void main(String[] a) throws Exception {
    String segDir = a[0], profile = a[1], lonlats = a[2], modo = a[3];
    RoutingParamCollector c = new RoutingParamCollector();
    List<OsmNodeNamed> wps = c.getWayPointList(lonlats);
    RoutingContext rc = new RoutingContext();
    rc.localFunction = new File(profile).getAbsolutePath();
    rc.memoryclass = 512;
    Map<String, String> p = new HashMap<>();
    p.put("profile:modo", modo);
    c.setParams(rc, wps, p);
    RoutingEngine e = new RoutingEngine(null, null, new File(segDir), wps, rc);
    e.quite = true;
    e.doRun(600000);
    if (e.getErrorMessage() != null) { System.err.println("ERRO: " + e.getErrorMessage()); System.exit(1); }
    System.out.println(new FormatJson(rc).format(e.getFoundTrack()));
  }
}
