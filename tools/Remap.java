import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Rewrites class and descriptor names inside a compiled jar, so a plugin whose author is not
 * around can be pointed at a renamed server API.
 *
 * <p>Every class name, field type and method descriptor a class file mentions lives in a CONSTANT_Utf8
 * entry of its constant pool. Nothing in the format refers to those entries by byte offset — they
 * are addressed by index — so an entry can be replaced with a longer or shorter string as long as
 * its length prefix is rewritten with it. That makes a rename a local edit rather than a
 * recompile, which is the only option when there is no source.
 *
 * <p>Replacements are applied to WHOLE Utf8 entries and to substrings within them, so a rule can
 * be either a bare internal class name — {@code a/b/Old=a/b/New}, which catches the class entry
 * and every descriptor mentioning it — or a complete descriptor, when only one specific overload
 * should move. Prefer whole descriptors when a type is also used in ways that must NOT change:
 * widening a parameter from {@code Vector3d} to the read-only {@code Vector3dc} is right in a
 * method signature and catastrophic in a {@code new} instruction, because an interface cannot be
 * instantiated.
 *
 * <p>This does not verify the result. Run LinkageCheck over the output, and let the server's own
 * verifier be the second opinion.
 *
 * <p>Usage: {@code java tools/Remap.java <in.jar> <out.jar> <from=to>...}
 */
public final class Remap {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: Remap <in.jar> <out.jar> <from=to>...");
            System.exit(2);
        }
        Map<String, String> rules = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq < 0) {
                System.err.println("bad rule (expected from=to): " + args[i]);
                System.exit(2);
            }
            rules.put(args[i].substring(0, eq), args[i].substring(eq + 1));
        }

        int classes = 0;
        int rewritten = 0;
        Map<String, Integer> hits = new LinkedHashMap<>();
        try (JarFile in = new JarFile(args[0]);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (JarEntry entry : Collections.list(in.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data;
                try (InputStream is = in.getInputStream(entry)) {
                    data = is.readAllBytes();
                }
                // Signed jars break the moment a class changes, and the signature files would
                // make the server reject every rewritten class. Dropping them is the same thing
                // every repackaging tool does.
                String name = entry.getName();
                if (name.startsWith("META-INF/") && (name.endsWith(".SF")
                        || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
                    continue;
                }
                if (name.endsWith(".class")) {
                    classes++;
                    byte[] patched = rewriteConstantPool(data, rules, hits);
                    if (patched != null) {
                        data = patched;
                        rewritten++;
                    }
                }
                JarEntry copy = new JarEntry(name);
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("rewrote " + rewritten + " of " + classes + " classes -> " + args[1]);
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            System.out.println("  " + hits.getOrDefault(rule.getKey(), 0) + "x  "
                    + rule.getKey() + "  ->  " + rule.getValue());
        }
        // A rule that never fires is usually a typo in the rule, and silently doing nothing is
        // exactly how a patch gets shipped believing it applied.
        for (Map.Entry<String, String> rule : rules.entrySet()) {
            if (hits.getOrDefault(rule.getKey(), 0) == 0) {
                System.out.println("WARNING: rule never matched: " + rule.getKey());
            }
        }
    }

    /** Returns rewritten class bytes, or null when no rule applied. */
    private static byte[] rewriteConstantPool(
            byte[] data, Map<String, String> rules, Map<String, Integer> hits) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream hd = new DataOutputStream(head);

        int magic = in.readInt();
        if (magic != 0xCAFEBABE) {
            return null;
        }
        hd.writeInt(magic);
        hd.writeShort(in.readUnsignedShort()); // minor
        hd.writeShort(in.readUnsignedShort()); // major
        int count = in.readUnsignedShort();
        hd.writeShort(count);

        boolean changed = false;
        List<byte[]> entries = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            ByteArrayOutputStream e = new ByteArrayOutputStream();
            DataOutputStream ed = new DataOutputStream(e);
            ed.writeByte(tag);
            switch (tag) {
                case 1 -> { // Utf8
                    int len = in.readUnsignedShort();
                    byte[] raw = new byte[len];
                    in.readFully(raw);
                    String s = new String(raw, StandardCharsets.UTF_8);
                    String t = s;
                    for (Map.Entry<String, String> rule : rules.entrySet()) {
                        if (t.contains(rule.getKey())) {
                            hits.merge(rule.getKey(), 1, Integer::sum);
                            t = t.replace(rule.getKey(), rule.getValue());
                        }
                    }
                    if (!t.equals(s)) {
                        changed = true;
                    }
                    byte[] outRaw = t.getBytes(StandardCharsets.UTF_8);
                    ed.writeShort(outRaw.length);
                    ed.write(outRaw);
                }
                case 7, 8, 16, 19, 20 -> ed.writeShort(in.readUnsignedShort());
                case 15 -> {
                    ed.writeByte(in.readUnsignedByte());
                    ed.writeShort(in.readUnsignedShort());
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> ed.writeInt(in.readInt());
                case 5, 6 -> { // Long, Double occupy two pool slots
                    ed.writeLong(in.readLong());
                    entries.add(e.toByteArray());
                    entries.add(new byte[0]);
                    i++;
                    continue;
                }
                default -> {
                    return null; // an unknown tag means this parser is out of date; do not guess
                }
            }
            entries.add(e.toByteArray());
        }
        if (!changed) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head.toByteArray());
        for (byte[] e : entries) {
            out.write(e);
        }
        // Everything after the constant pool is addressed by pool INDEX, never by byte offset,
        // so it survives the entries changing length and is copied through untouched.
        in.transferTo(out);
        return out.toByteArray();
    }
}
