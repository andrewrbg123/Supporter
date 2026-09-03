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
 * Finds what a compiled plugin jar would fail to link against a server jar.
 *
 * <p>Recompiling only catches breakage in code you have the source for. Third-party plugins
 * arrive as jars built against some other server version, and the failure mode is either a
 * refusal to boot or a {@code NoSuchMethodError} at the exact moment a player uses the feature.
 * This reads the constant pool directly and resolves every reference ahead of time instead.
 *
 * <p>The question asked is ABSOLUTE: does this reference resolve against the server you are
 * about to run? An earlier version asked a differential question — what resolved against the
 * old server and no longer does — which is correct only for jars built against that old server.
 * A plugin built against a NEWER server than the old one can reference something that exists in
 * neither, and the differential form silently passes it. Each finding is still LABELLED using
 * the old server, because "the update removed this" and "this was never here" point at
 * different fixes.
 *
 * <p>Members resolve through the superclass and interface chain, as the JVM does, over three
 * answers rather than two. A chain that leaves the indexed jars into the JDK or a shaded
 * library gives UNKNOWN, which is never reported — without it, every hytale class extending a
 * JDK class would produce false positives. A chain that leaves into a MISSING hytale class
 * gives NOT_FOUND, because that class really is gone.
 *
 * <p>Usage: {@code java tools/LinkageCheck.java <old-server.jar> <target-server.jar> <plugin.jar>...}
 */
public final class LinkageCheck {

    /** Only references into this package are checked; everything else is someone else's problem. */
    private static final String WATCHED = "com/hypixel/hytale/";

    private static final int NOT_FOUND = 0;
    private static final int FOUND = 1;
    /** The chain left the indexed jars, so this tool has no opinion. Never reported. */
    private static final int UNKNOWN = 2;

    /** One class as the index knows it: what it declares, and who it inherits from. */
    private record Indexed(String superName, List<String> interfaces, Set<String> members) {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: LinkageCheck <old-server.jar> <target-server.jar> <plugin.jar>...");
            System.exit(2);
        }
        // The server jars carry ~36,000 classes each, so they are indexed ONCE and every plugin
        // is checked against the same pair. Re-running the tool per plugin costs a minute of
        // indexing each time and makes checking a whole server impractical.
        Map<String, Indexed> oldIndex = new HashMap<>();
        Map<String, Indexed> targetIndex = new HashMap<>();
        index(args[0], oldIndex);
        index(args[1], targetIndex);
        System.out.println("old server:    " + args[0] + " (" + oldIndex.size() + " classes)");
        System.out.println("TARGET server: " + args[1] + " (" + targetIndex.size() + " classes)");
        System.out.println();

        List<String> broken = new ArrayList<>();
        List<String> clean = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            if (check(args[i], oldIndex, targetIndex)) {
                clean.add(args[i]);
            } else {
                broken.add(args[i]);
            }
        }

        System.out.println("=".repeat(78));
        System.out.println("SUMMARY: " + broken.size() + " of " + (args.length - 2)
                + " jar(s) reference server API missing from " + args[1]);
        for (String b : broken) {
            System.out.println("  BREAKS   " + b);
        }
        for (String c : clean) {
            System.out.println("  ok       " + c);
        }
    }

    /** Checks one plugin jar. Returns true when every reference resolves. */
    private static boolean check(
            String path, Map<String, Indexed> oldIndex, Map<String, Indexed> targetIndex)
            throws IOException {
        Set<String> missingClasses = new TreeSet<>();
        Set<String> missingMembers = new TreeSet<>();
        int scanned = 0;
        // Counted and printed so a clean result can be trusted. "No problems" is worth nothing
        // on its own — it reads identically whether the jar is fine or the scan found nothing
        // to look at, which is a mistake this tool has already made once.
        int watchedRefs = 0;

        // The plugin's OWN classes are indexed alongside the server's, and references are then
        // checked whatever their owner. Restricting the check to owners inside the watched
        // package misses a whole category, and it missed a live one: HyGuns failed to boot on
        // a NoSuchFieldError for `cancelOnItemChange` on ITS OWN class, a field inherited from
        // a server base class that 0.6.3 dropped. The owner in the constant pool is the plugin
        // class, so a watched-owner-only scan called that jar's six findings non-fatal and
        // never saw the one that stopped the server. Owners that are in neither index resolve
        // to UNKNOWN and stay unreported, so references into other plugins cost nothing.
        Map<String, Indexed> pluginClasses = new HashMap<>();
        List<String[]> memberRefs = new ArrayList<>();
        List<String[]> classRefs = new ArrayList<>();

        try (JarFile jar = new JarFile(path)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                // META-INF/versions/N/ holds a multi-release jar's per-JDK overlays: the SAME
                // class name compiled against a different JDK. Indexing one variant and then
                // checking the other's references makes them contradict each other, which is
                // not a server incompatibility at all — a shaded snakeyaml inside Shop produced
                // four such findings before this skip, every one of them false.
                if (!entry.getName().endsWith(".class")
                        || entry.getName().startsWith("META-INF/")) {
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
                pluginClasses.put(from, indexOf(cm));
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
                        memberRefs.add(new String[] {
                                ref.owner().asInternalName(),
                                ref.nameAndType().name().stringValue(),
                                ref.nameAndType().type().stringValue(),
                                from});
                    } else if (pe instanceof ClassEntry ce) {
                        classRefs.add(new String[] {ce.asInternalName(), from});
                    }
                }
            }
        }

        Map<String, Indexed> target = new HashMap<>(targetIndex);
        target.putAll(pluginClasses);
        Map<String, Indexed> old = new HashMap<>(oldIndex);
        old.putAll(pluginClasses);

        for (String[] r : memberRefs) {
            String owner = r[0];
            String member = r[1] + r[2];
            if (!target.containsKey(owner)) {
                continue;
            }
            watchedRefs++;
            if (resolve(target, owner, member) == NOT_FOUND) {
                missingMembers.add(owner + "#" + member
                        + "\n        " + label(resolve(old, owner, member))
                        + ", used by " + r[3]
                        + "\n        " + suggest(target, owner, r[1]));
            }
        }
        for (String[] r : classRefs) {
            String owner = r[0];
            if (!owner.startsWith(WATCHED) || target.containsKey(owner)) {
                continue;
            }
            missingClasses.add(owner + "\n        "
                    + label(old.containsKey(owner) ? FOUND : NOT_FOUND)
                    + ", used by " + r[1]);
        }

        boolean ok = missingClasses.isEmpty() && missingMembers.isEmpty();
        System.out.println((ok ? "ok    " : "BREAKS") + "  " + path
                + "  (" + scanned + " classes, " + watchedRefs + " server refs)");
        report("CLASSES MISSING", missingClasses);
        report("MEMBERS MISSING", missingMembers);
        return ok;
    }

    /**
     * Lists what the target server still offers under the same member name.
     *
     * <p>This is the difference between a finding you can patch and one you cannot. A signature
     * that merely widened — {@code Vector3d} to the read-only {@code Vector3dc}, say — leaves a
     * same-name replacement whose descriptor is the only thing that changed, and rewriting the
     * descriptor in the constant pool is enough, because the object being passed already
     * implements the wider type. A member with NO same-name survivor was genuinely deleted, and
     * no amount of rewriting invents it: that one needs source changes or a new build from the
     * author.
     */
    private static String suggest(Map<String, Indexed> index, String owner, String name) {
        Set<String> candidates = new TreeSet<>();
        collectByName(index, owner, name, candidates, new HashSet<>());
        if (candidates.isEmpty()) {
            return "NO same-name replacement — deleted outright, cannot be patched";
        }
        return "same-name candidate(s), likely a signature change: " + candidates;
    }

    private static void collectByName(Map<String, Indexed> index, String owner, String name,
            Set<String> out, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return;
        }
        Indexed cls = index.get(owner);
        if (cls == null) {
            return;
        }
        for (String m : cls.members()) {
            int cut = m.indexOf('(');
            String memberName = cut < 0 ? m.substring(0, Math.max(0, m.length() - 1)) : m.substring(0, cut);
            if (cut >= 0 && memberName.equals(name)) {
                out.add(m.substring(cut));
            }
        }
        collectByName(index, cls.superName(), name, out, seen);
        for (String iface : cls.interfaces()) {
            collectByName(index, iface, name, out, seen);
        }
    }

    /** Says which fix a finding points at: something the update took away, or something else. */
    private static String label(int oldState) {
        return oldState == FOUND
                ? "removed by the update"
                : "absent from the old server too — jar likely built against a different version";
    }

    private static void report(String title, Set<String> found) {
        if (found.isEmpty()) {
            return;
        }
        System.out.println("  " + title + " (" + found.size() + ")");
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
                    into.put(cm.thisClass().asInternalName(), indexOf(cm));
                } catch (Throwable t) {
                    // A class the parser cannot read is one this check has no opinion about.
                }
            }
        }
    }

    /** What one parsed class declares and inherits from. */
    private static Indexed indexOf(ClassModel cm) {
        Set<String> members = new HashSet<>();
        cm.methods().forEach(m -> members.add(
                m.methodName().stringValue() + m.methodType().stringValue()));
        cm.fields().forEach(f -> members.add(
                f.fieldName().stringValue() + f.fieldType().stringValue()));
        List<String> interfaces = new ArrayList<>();
        cm.interfaces().forEach(ce -> interfaces.add(ce.asInternalName()));
        return new Indexed(cm.superclass().map(ClassEntry::asInternalName).orElse(null),
                interfaces, members);
    }

    private static int resolve(Map<String, Indexed> index, String owner, String member) {
        return resolve(index, owner, member, new HashSet<>());
    }

    /** Indexes a class this JVM can load, so JDK supertypes are known rather than guessed at. */
    private static Indexed reflect(String internalName) {
        try {
            Class<?> c = Class.forName(internalName.replace('/', '.'), false,
                    LinkageCheck.class.getClassLoader());
            Set<String> members = new HashSet<>();
            for (var m : c.getDeclaredMethods()) {
                members.add(m.getName() + descriptor(m.getParameterTypes(), m.getReturnType()));
            }
            for (var ctor : c.getDeclaredConstructors()) {
                members.add("<init>" + descriptor(ctor.getParameterTypes(), void.class));
            }
            for (var f : c.getDeclaredFields()) {
                members.add(f.getName() + descriptor(f.getType()));
            }
            List<String> interfaces = new ArrayList<>();
            for (Class<?> i : c.getInterfaces()) {
                interfaces.add(i.getName().replace('.', '/'));
            }
            String superName = c.getSuperclass() == null
                    ? null
                    : c.getSuperclass().getName().replace('.', '/');
            return new Indexed(superName, interfaces, members);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String descriptor(Class<?>[] params, Class<?> returnType) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            sb.append(descriptor(p));
        }
        return sb.append(')').append(descriptor(returnType)).toString();
    }

    private static String descriptor(Class<?> t) {
        if (t.isArray()) {
            return "[" + descriptor(t.getComponentType());
        }
        if (!t.isPrimitive()) {
            return "L" + t.getName().replace('.', '/') + ";";
        }
        return switch (t.getName()) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            default -> "D";
        };
    }

    /**
     * Resolves a member the way the JVM does — the class, then its superclasses, then its
     * interfaces — over three answers. See the class comment for why UNKNOWN has to exist.
     */
    private static int resolve(
            Map<String, Indexed> index, String owner, String member, Set<String> seen) {
        if (owner == null || !seen.add(owner)) {
            return NOT_FOUND;
        }
        Indexed cls = index.get(owner);
        if (cls == null) {
            // A missing class INSIDE the watched package is genuinely missing, and is reported
            // in its own right.
            if (owner.startsWith(WATCHED)) {
                return NOT_FOUND;
            }
            // Outside it, the chain has reached the JDK or a library. Neither blanket answer
            // works here, and both were tried: "not found" flags every inherited toString() and
            // every enum ordinal(), while "unknown" suppresses real breakage, because almost
            // every chain ends at java/lang/Object and one UNKNOWN parent taints the result.
            // So the class is loaded and indexed for real. Only when it cannot be loaded — a
            // library absent from this tool's own classpath — is the answer UNKNOWN.
            cls = reflect(owner);
            if (cls == null) {
                return UNKNOWN;
            }
            index.put(owner, cls);
        }
        if (cls.members().contains(member)) {
            return FOUND;
        }
        boolean unknown = false;
        List<String> parents = new ArrayList<>();
        parents.add(cls.superName());
        parents.addAll(cls.interfaces());
        for (String parent : parents) {
            int state = resolve(index, parent, member, seen);
            if (state == FOUND) {
                return FOUND;
            }
            unknown |= state == UNKNOWN;
        }
        return unknown ? UNKNOWN : NOT_FOUND;
    }
}
