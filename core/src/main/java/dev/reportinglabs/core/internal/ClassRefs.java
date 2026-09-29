package dev.reportinglabs.core.internal;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The classes a test class refers to, read from its constant pool: the base
 * classes, the factories it calls ({@code DriverFactory.getDriver()}), the
 * page objects it constructs. Add-ons scan the static fields of these for a
 * driver or a page kept outside the test instance (a static ThreadLocal in
 * a factory class is the usual shape). Classes are loaded without
 * initialisation, so looking never runs anyone's static initialiser.
 */
public final class ClassRefs {
    private static final Map<Class<?>, Set<Class<?>>> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CLASSES = 300;

    private ClassRefs() {}

    /** Classes reachable from {@code test} through two hops of references
     *  (test → factory → helper), the test's own hierarchy included.
     *  Only classes the {@code accept} predicate allows are followed. */
    public static Set<Class<?>> reachable(Class<?> test, java.util.function.Predicate<Class<?>> accept) {
        if (test == null) return Collections.emptySet();
        return CACHE.computeIfAbsent(test, t -> {
            Set<Class<?>> out = new LinkedHashSet<>();
            Deque<Object[]> queue = new ArrayDeque<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) if (accept.test(c)) { out.add(c); queue.add(new Object[] { c, 0 }); }
            while (!queue.isEmpty() && out.size() < MAX_CLASSES) {
                Object[] item = queue.poll();
                Class<?> c = (Class<?>) item[0]; int depth = (Integer) item[1];
                if (depth >= 2) continue;
                for (String name : referencedNames(c)) {
                    Class<?> r;
                    try { r = Class.forName(name, false, c.getClassLoader()); } catch (Throwable e) { continue; }
                    if (r.isArray() || r.isPrimitive() || !accept.test(r) || !out.add(r)) continue;
                    for (Class<?> s = r.getSuperclass(); s != null && s != Object.class; s = s.getSuperclass()) if (accept.test(s)) out.add(s);
                    queue.add(new Object[] { r, depth + 1 });
                    if (out.size() >= MAX_CLASSES) break;
                }
            }
            return out;
        });
    }

    /** CONSTANT_Class entries of the class file, as binary names. */
    static Set<String> referencedNames(Class<?> c) {
        Set<String> names = new LinkedHashSet<>();
        String res = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        try (InputStream raw = cl != null ? cl.getResourceAsStream(res) : ClassLoader.getSystemResourceAsStream(res)) {
            if (raw == null) return names;
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
            if (in.readInt() != 0xCAFEBABE) return names;
            in.readUnsignedShort(); in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf = new String[count];
            int[] classIdx = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: utf[i] = in.readUTF(); break;
                    case 3: case 4: in.readInt(); break;
                    case 5: case 6: in.readLong(); i++; break;
                    case 7: classIdx[i] = in.readUnsignedShort(); break;
                    case 8: case 16: case 19: case 20: in.readUnsignedShort(); break;
                    case 9: case 10: case 11: case 12: case 17: case 18: in.readInt(); break;
                    case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                    default: return names;   // unknown tag: stop rather than guess
                }
            }
            for (int i = 1; i < count; i++) {
                if (classIdx[i] == 0 || classIdx[i] >= count || utf[classIdx[i]] == null) continue;
                String n = utf[classIdx[i]];
                if (n.startsWith("[")) continue;
                names.add(n.replace('/', '.'));
            }
        } catch (Throwable ignore) { /* not a readable class file: nothing to follow */ }
        return names;
    }
}
