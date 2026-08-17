import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * Lists vanilla particle systems that are safe to use as a movement trail.
 *
 * A trail effect must expire on its own. A particlesystem with no LifeSpan is designed to be
 * attached to a persistent entity (a projectile, a torch) and, spawned free-standing, never
 * goes away — which is exactly what went wrong on the live server.
 */
public class ScanParticles {
    public static void main(String[] a) throws Exception {
        List<String[]> ok = new ArrayList<>();
        int total = 0, noLifespan = 0;

        try (ZipFile z = new ZipFile(a[0])) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry ze = e.nextElement();
                if (!ze.getName().endsWith(".particlesystem")) continue;
                total++;
                String body;
                try (InputStream in = z.getInputStream(ze)) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String flat = body.replaceAll("\\s+", "");
                Double life = grabDouble(flat, "\"LifeSpan\":");
                if (life == null) { noLifespan++; continue; }

                int spawners = countOccurrences(flat, "\"SpawnerId\":");
                Double cull = grabDouble(flat, "\"CullDistance\":");
                String name = ze.getName();
                name = name.substring(name.lastIndexOf('/') + 1).replace(".particlesystem", "");

                // A trail wants: short life, one spawner, nothing dramatic.
                if (life <= 6.0) {
                    ok.add(new String[]{name, String.valueOf(life), String.valueOf(spawners),
                            String.valueOf(cull), ze.getName()});
                }
            }
        }

        ok.sort(Comparator.comparingDouble(r -> Double.parseDouble(r[1])));
        System.out.println("total particlesystems : " + total);
        System.out.println("no LifeSpan (unsafe)  : " + noLifespan);
        System.out.println("short-lived candidates: " + ok.size());
        System.out.println();
        System.out.printf("%-38s %6s %4s  %s%n", "NAME", "LIFE", "SPWN", "PATH");
        for (String[] r : ok) {
            System.out.printf("%-38s %6s %4s  %s%n", r[0], r[1], r[2], r[4]);
        }
    }

    private static Double grabDouble(String flat, String key) {
        int i = flat.indexOf(key);
        if (i < 0) return null;
        int s = i + key.length();
        int e = s;
        while (e < flat.length() && (Character.isDigit(flat.charAt(e)) || flat.charAt(e) == '.'
                || flat.charAt(e) == '-')) e++;
        try { return Double.parseDouble(flat.substring(s, e)); } catch (Exception ex) { return null; }
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); }
        return n;
    }
}
