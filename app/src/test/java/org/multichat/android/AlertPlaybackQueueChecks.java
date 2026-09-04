package org.multichat.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Runs both through JUnit and as a dependency-free JVM check of the actual queue. */
public final class AlertPlaybackQueueChecks {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Queue contract failed");
    }

    private static final class Fixture implements AlertPlaybackQueue.Player {
        final List<AlertPlaybackQueue.Ticket> starts = new ArrayList<>();
        final List<AlertPlaybackQueue.Ticket> stops = new ArrayList<>();
        final AlertPlaybackQueue queue;
        boolean failStart;
        boolean failStop;
        Fixture(int capacity) {
            queue = new AlertPlaybackQueue(this, capacity);
            for (String name : Arrays.asList("se-generation-1", "sl-generation-1", "doneru-generation-1")) {
                queue.register(name);
            }
        }
        public void start(AlertPlaybackQueue.Ticket ticket) {
            starts.add(ticket);
            if (failStart) throw new IllegalStateException("test start failure");
        }
        public void stop(AlertPlaybackQueue.Ticket ticket) {
            stops.add(ticket);
            if (failStop) throw new IllegalStateException("test stop failure");
        }
    }

    public static void runAll() {
        Fixture f = new Fixture(3);
        f.queue.enqueue("se-generation-1", 1);
        AlertPlaybackQueue.Ticket first = f.queue.active();
        f.queue.enqueue("sl-generation-1", 1);
        f.queue.enqueue("doneru-generation-1", 1);
        check(f.starts.size() == 1 && f.queue.waitingCount() == 2);
        check(f.queue.completed(first));
        check(f.queue.active().source.equals("sl-generation-1"));
        check(!f.queue.completed(first));
        check(f.queue.completed(f.queue.active()));
        check(f.queue.active().source.equals("doneru-generation-1"));
        check(f.queue.completed(f.queue.active()) && f.queue.active() == null);
        check(f.queue.enqueue("se-generation-1", 1) == AlertPlaybackQueue.Admission.DUPLICATE);
        check(f.queue.enqueue("unregistered", 1) == AlertPlaybackQueue.Admission.UNKNOWN_SOURCE);

        f = new Fixture(1);
        f.queue.enqueue("se-generation-1", 1);
        first = f.queue.active();
        f.queue.enqueue("sl-generation-1", 1);
        check(f.queue.enqueue("doneru-generation-1", 1) == AlertPlaybackQueue.Admission.FULL);
        f.queue.completed(first);
        check(f.queue.enqueue("doneru-generation-1", 1) == AlertPlaybackQueue.Admission.ACCEPTED);
        check(f.queue.waitingCount() == 1); // Capacity rejection did not consume the sequence.

        f = new Fixture(3);
        f.queue.enqueue("se-generation-1", 1);
        first = f.queue.active();
        f.queue.enqueue("sl-generation-1", 1);
        f.queue.enqueue("doneru-generation-1", 1);
        f.queue.unregister("sl-generation-1");
        f.queue.unregister("se-generation-1");
        check(f.queue.isStopping() && f.starts.size() == 1 && f.stops.size() == 1);
        check(!f.queue.completed(first)); // A stale 'ended' message cannot bypass stop acknowledgement.
        check(f.queue.stopped(first));
        check(f.queue.active().source.equals("doneru-generation-1"));
        check(!f.queue.stopped(first));
        f.queue.register("se-generation-2");
        check(f.queue.enqueue("se-generation-1", 2) == AlertPlaybackQueue.Admission.UNKNOWN_SOURCE);
        f.queue.enqueue("se-generation-2", 1);
        f.queue.clear();
        check(f.queue.waitingCount() == 0 && f.queue.isStopping());
        f.queue.stopped(f.queue.active());
        check(f.queue.active() == null);

        f = new Fixture(3);
        f.failStart = true;
        f.failStop = true;
        f.queue.enqueue("se-generation-1", 1);
        f.queue.enqueue("sl-generation-1", 1);
        check(f.queue.hasPlayerFailure() && f.queue.isStopping() && f.starts.size() == 1);
        f.failStart = false;
        f.failStop = false;
        f.queue.stopped(f.queue.active());
        check(f.queue.active().source.equals("sl-generation-1") && !f.queue.hasPlayerFailure());

        final AlertPlaybackQueue[] immediate = new AlertPlaybackQueue[1];
        final int[] count = {0};
        immediate[0] = new AlertPlaybackQueue(new AlertPlaybackQueue.Player() {
            public void start(AlertPlaybackQueue.Ticket ticket) { count[0]++; immediate[0].completed(ticket); }
            public void stop(AlertPlaybackQueue.Ticket ticket) { immediate[0].stopped(ticket); }
        }, 2);
        immediate[0].register("instant");
        for (int i = 1; i <= 5000; i++) immediate[0].enqueue("instant", i);
        check(count[0] == 5000 && immediate[0].active() == null);
    }

    public static void main(String[] arguments) {
        runAll();
        System.out.println("PASS: FIFO, duplicate rejection, capacity retry, stale completion, stop acknowledgement, cancellation, failure isolation, synchronous completion");
    }
}
