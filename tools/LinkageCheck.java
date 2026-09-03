import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds what a compiled plugin jar would fail to link against a new server jar.
 *
 * <p>Recompiling only catches breakage in code you have the source for. Third-party plugins
 * arrive as jars built against an older server, and the failure mode is a {@code
 * NoSuchMethodError} at the exact moment a player opens a menu — which is why this reads the
 * constant pool directly and resolves every reference ahead of time instead.
 *
 * <p>It is a DIFFERENTIAL check, and that is the part that makes the output usable. Reporting
 * everything that fails to resolve against the new server buries the answer in references to
 * optional plugins that were never on the classpath to begin with. So each reference is
 * resolved against BOTH servers and only the ones that resolved against the old and do not
 * resolve against the new are reported: those, and only those, are things the update broke.
 *
 * <p>Members are resolved through the superclass and interface chain, as the JVM does. A chain
 * that leaves the indexed jars — into the JDK, or a shaded library — is treated as resolvable
 * rather than reported, because the alternative is a page of false positives.
 *
 * <p>Usage: {@code java tools/LinkageCheck.java <plugin.jar> <old-server.jar> <new-server.jar>}
 */
public final class LinkageCheck {

    /** Only references into this package are checked; everything else is someone else's problem. */
    private static final String WATCHED = "com/hypixel/hytale/";

    /** One class as the index knows it: what it declares, and who it inherits from. */
    private record Indexed(String superName, List<String> interfaces, Set<String> members) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: LinkageCheck <old-server.jar> <new-server.jar> <plugin.jar>...");
            System.exit(2);
        }
        // The two server jars carry ~36,000 classes each, so they are indexed ONCE and every
        // plugin is checked against the same pair. Re-running the whole tool per plugin costs
        // a minute of indexing each time and makes checking a whole server impractical.
        Map<String, Indexed> oldIndex = new HashMap<>();
        Map<String, Indexed> newIndex = new HashMap<>();
        index(args[0], oldIndex);
        index(args[1], newIndex);
        System.out.println("old server: " + args[0] + " (" + oldIndex.size() + " classes)");
        System.out.println("new server: " + args[1] + " (" + newIndex.size() + " classes)");
        System.out.println();

        List<String> broken = new ArrayList<>();
        List<String> clean = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (check(args[i], oldIndex, newIndex)) {
                clean.add(args[i]);
            } else {
                broken.add(args[i]);
            }
        }

        System.out.println("=".repeat(78));
        System.out.println("SUMMARY: " + broken.size() + " of " + (args.length - 2)
                + " jar(s) reference server API the update removed or changed.");
        for (String b : broken) {
            System.out.println("  BREAKS   " + b);
        }
        for (String c : clean) {
            System.out.println("  ok       " + c);
        }
    }

    /** Checks one plugin jar. Returns true when nothing regressed. */
    private static boolean check(
            String path, Map<String, Indexed> oldIndex, Map<String, Indexed> newIndex)
            throws IOException {
        Set<String> missingClasses = new TreeSet<>();
        Set<String> missingMembers = new TreeSet<>();
        int scanned = 0;
        // Counted and printed so a clean result can be trusted. "No regressions" is worth
        // nothing on its own — it reads identically whether the jar is fine or the scan found
        // nothing to look at, which is a mistake this tool has already made once.
        int watchedRefs = 0;

        try (JarFile jar = new JarFile(path)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ClassModel cm;
                try {
                    cm = ClassFile.of().parse(jar.getInputStream(entry).readAllBytes());
                } catch (Throwable t) {
                    continue;
                }
                scanned++;
                String from = cm.thisClass().asInternalName();
                ConstantPool cp = cm.constantPool();
                for (int i = 1; i < cp.size(); i++) {
                    PoolEntry pe;
                    try {
                        pe = cp.entryByIndex(i);
                    } catch (Throwable t) {
                        // Long and double entries occupy two slots; the second is not an entry.
                        continue;
                    }
                    if (pe instanceof MemberRefEntry ref) {
                        String owner = ref.owner().asInternalName();
                        if (!owner.startsWith(WATCHED)) {
                            continue;
                        }
                        String member = ref.nameAndType().name().stringValue()
                                + ref.nameAndType().type().stringValue();
                        watchedRefs++;
                        if (hasMember(oldIndex, owner, member)
                                && !hasMember(newIndex, owner, member)) {
                            missingMembers.add(owner + "#" + member + "\n        used by " + from);
                        }
                    } else if (pe instanceof ClassEntry ce) {
                        String owner = ce.asInternalName();
                        if (!owner.startsWith(WATCHED)) {
                            continue;
                        }
                        if (oldIndex.containsKey(owner) && !newIndex.containsKey(owner)) {
                            missingClasses.add(owner + "\n        used by " + from);
                        }
                    }
                }
            }
        }

        boolean ok = missingClasses.isEmpty() && missingMembers.isEmpty();
        System.out.println((ok ? "ok    " : "BREAKS") + "  " + path
                + "  (" + scanned + " classes, " + watchedRefs + " server refs)");
        report("CLASSES REMOVED", missingClasses);
        report("MEMBERS REMOVED OR CHANGED", missingMembers);
        return ok;
    }

    private static void report(String title, Set<String> found) {
        if (found.isEmpty()) {
            return;
        }
        System.out.println(title + " (" + found.size() + ")");
        for (String line : found) {
            System.out.println("    " + line);
        }
        System.out.println();
    }

    /** Reads every class in a jar into the index. */
    private static void index(String path, Map<String, Indexed> into) throws IOException {
        try (JarFile jar = new JarFile(path)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try {
                    ClassModel cm = ClassFile.of().parse(jar.getInputStream(entry).readAllBytes());
                    Set<String> members = new HashSet<>();
                    cm.methods().forEach(m -> members.add(
                            m.methodName().stringValue() + m.methodType().stringValue()));
                    cm.fields().forEach(f -> members.add(
                            f.fieldName().stringValue() + f.fieldType().stringValue()));
                    List<String> interfaces = new ArrayList<>();
                    cm.interfaces().forEach(ce -> interfaces.add(ce.asInternalName()));
                    String superName =
                            cm.superclass().map(ClassEntry::asInternalName).orElse(null);
                    into.put(cm.thisClass().asInternalName(),
                            new Indexed(superName, interfaces, members));
                } catch (Throwable t) {
                    // A class the parser cannot read is one this check has no opinion about.
                }
            }
        }
    }

    /**
     * Resolves a member the way the JVM does — the class, then its superclasses, then its
     * interfaces. Returns true when the chain leaves the index, since an unknown answer must
     * not be reported as a break.
     */
    private static boolean hasMember(Map<String, Indexed> index, String owner, String member) {
        return hasMember(index, owner, member, new HashSet<>());
    }

    private static boolean hasMember(
            Map<String, Indexed> index, String owner, String member, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return false;
        }
        Indexed cls = index.get(owner);
        if (cls == null) {
            // Outside the indexed jars: the JDK, a shaded library, an array type. Answering
            // "resolvable" here looks safe and is in fact the bug that made the first version
            // of this tool useless — nearly every class ends its super chain at
            // java/lang/Object, so every lookup fell through to that answer and NOTHING was
            // ever reported, including breakage known to be present.
            //
            // Answering "not found" is safe because the check is DIFFERENTIAL. The same
            // conservatism runs against both servers, so a member that is invisible for this
            // reason is invisible on both sides and is never reported. What survives is only
            // the asymmetric case: resolvable against the old server, not against the new.
            return false;
        }
        if (cls.members().contains(member)) {
            return true;
        }
        if (hasMember(index, cls.superName(), member, seen)) {
            return true;
        }
        for (String iface : cls.interfaces()) {
            if (hasMember(index, iface, member, seen)) {
                return true;
            }
        }
        return false;
    }
}
