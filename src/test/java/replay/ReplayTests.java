package replay;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-dependency deterministic test runner. Discovers test classes on the
 * classpath under the replay package and invokes methods annotated with
 * {@link Test}. Every test uses virtual logical time - never sleep().
 */
public final class ReplayTests {

    private ReplayTests() {
    }

    public static void main(String[] args) {
        List<Class<?>> testClasses = List.of(
                replay.SameTimeOrderingTest.class,
                replay.InternalQueueTest.class,
                replay.ActionRollbackTest.class,
                replay.CheckpointVersionTest.class,
                replay.BranchMergeConflictTest.class,
                replay.ExportReplayTest.class,
                replay.DeterminismTest.class,
                replay.WebApiTest.class);

        int passed = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();
        for (Class<?> c : testClasses) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    String label = c.getSimpleName() + "." + m.getName();
                    try {
                        m.setAccessible(true);
                        Object instance = c.getDeclaredConstructor().newInstance();
                        m.invoke(instance);
                        System.out.println("PASS " + label);
                        passed++;
                    } catch (Exception e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        System.out.println("FAIL " + label + " -> " + cause);
                        cause.printStackTrace(System.out);
                        failures.add(label + ": " + cause);
                        failed++;
                    }
                }
            }
        }
        System.out.println("---");
        System.out.println("tests: " + (passed + failed) + ", passed: " + passed + ", failed: " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
