package dev.appliedcausality.test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Test-only allocation helper for constructor-free Minecraft test doubles. */
public final class UnsafeAllocator {
    private UnsafeAllocator() {
    }

    public static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            return type.cast(allocateInstance.invoke(unsafe, type));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to allocate test double " + type.getName(), exception);
        }
    }
}
