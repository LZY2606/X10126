package replayroom;

import replayroom.engine.ActionRollbackTest;
import replayroom.engine.CheckpointVersionTest;
import replayroom.engine.DeterminismTest;
import replayroom.engine.ExportReplayTest;
import replayroom.engine.InternalEventQueueTest;
import replayroom.engine.MergeConflictTest;
import replayroom.engine.SameTimeOrderingTest;
import replayroom.web.ApiHttpTest;

public final class AllTests {
    public static void main(String[] args) {
        System.out.println("Running replay room test suite (virtual logical clock, no sleeps)");
        TestRunner.run(SameTimeOrderingTest.class);
        TestRunner.run(InternalEventQueueTest.class);
        TestRunner.run(ActionRollbackTest.class);
        TestRunner.run(CheckpointVersionTest.class);
        TestRunner.run(MergeConflictTest.class);
        TestRunner.run(ExportReplayTest.class);
        TestRunner.run(DeterminismTest.class);
        TestRunner.run(ApiHttpTest.class);
        System.out.println("ALL TESTS PASSED");
    }
}
