package replayroom;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class TestRunner {

    public static void run(Class<?> testClass) {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || !method.getName().startsWith("test")) continue;
            try {
                method.setAccessible(true);
                method.invoke(null);
                passed++;
                System.out.println("  PASS " + testClass.getSimpleName() + "." + method.getName());
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                failures.add(testClass.getSimpleName() + "." + method.getName() + " -> " + cause);
                System.out.println("  FAIL " + testClass.getSimpleName() + "." + method.getName()
                        + " : " + cause);
            }
        }
        if (!failures.isEmpty()) {
            System.out.println();
            for (String failure : failures) System.out.println("FAILURE " + failure);
            System.out.println(failures.size() + " test(s) failed, " + passed + " passed in "
                    + testClass.getSimpleName());
            System.exit(1);
        }
        System.out.println("  (" + passed + " passed in " + testClass.getSimpleName() + ")");
    }
}
