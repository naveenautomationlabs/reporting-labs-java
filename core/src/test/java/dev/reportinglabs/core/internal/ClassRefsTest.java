package dev.reportinglabs.core.internal;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClassRefsTest {
    static final class Holder { static final ThreadLocal<String> TL = new ThreadLocal<>(); static String get() { return TL.get(); } }
    static final class Helper { static String hello() { return Holder.get(); } }
    static class Base { }
    static final class Fake extends Base { String run() { return Helper.hello(); } }

    @Test void followsReferencesTwoHops() {
        Set<Class<?>> r = ClassRefs.reachable(Fake.class, c -> c.getName().startsWith("dev.reportinglabs."));
        assertTrue(r.contains(Fake.class));
        assertTrue(r.contains(Base.class), "superclass");
        assertTrue(r.contains(Helper.class), "called directly");
        assertTrue(r.contains(Holder.class), "called by the helper (second hop)");
        assertFalse(r.stream().anyMatch(c -> c.getName().startsWith("java.")), "predicate filters the JDK out");
    }

    @Test void readsConstantPool() {
        Set<String> names = ClassRefs.referencedNames(Fake.class);
        assertTrue(names.contains(Helper.class.getName()), names.toString());
        assertTrue(names.contains(Base.class.getName()), names.toString());
    }
}
