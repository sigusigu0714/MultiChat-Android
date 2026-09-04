package org.multichat.android;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Cross-widget FIFO. Call only from the UI thread.
 * Contains opaque request identifiers, never widget URLs or notification payloads.
 * Provider adapters must acknowledge actual media completion, not merely DOM hiding.
 */
public final class AlertPlaybackQueue {
    public enum Admission { ACCEPTED, DUPLICATE, UNKNOWN_SOURCE, FULL }

    public static final class Ticket {
        public final String source;
        public final long sequence;
        private Ticket(String source, long sequence) {
            this.source = source;
            this.sequence = sequence;
        }
    }

    public interface Player {
        void start(Ticket ticket);
        /** Stop media; then acknowledge through stopped(ticket). This may be asynchronous. */
        void stop(Ticket ticket);
    }

    private final Player player;
    private final int capacity;
    private final Map<String, Long> sources = new HashMap<>();
    private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();
    private Ticket active;
    private boolean stopping;
    private boolean draining;
    private boolean playerFailed;

    public AlertPlaybackQueue(Player player, int capacity) {
        if (player == null || capacity < 1) throw new IllegalArgumentException();
        this.player = player;
        this.capacity = capacity;
    }

    /** A source identifier belongs to one WebView document generation; never reuse it. */
    public void register(String source) {
        if (source == null || source.isEmpty() || sources.containsKey(source)
                || (active != null && active.source.equals(source))) {
            throw new IllegalArgumentException("A fresh source identifier is required");
        }
        sources.put(source, 0L);
    }

    /** FULL rejects admission only: the adapter must retain the payload and retry later. */
    public Admission enqueue(String source, long sequence) {
        Long previous = sources.get(source);
        if (previous == null) return Admission.UNKNOWN_SOURCE;
        if (sequence <= previous) return Admission.DUPLICATE;
        if (waiting.size() >= capacity) return Admission.FULL;
        sources.put(source, sequence);
        waiting.addLast(new Ticket(source, sequence));
        drain();
        return Admission.ACCEPTED;
    }

    public Ticket active() { return active; }
    public int waitingCount() { return waiting.size(); }
    public boolean isStopping() { return stopping; }
    public boolean hasPlayerFailure() { return playerFailed; }

    /** Late completion from a removed/reloaded page cannot release another notification. */
    public boolean completed(Ticket ticket) {
        if (ticket == null || ticket != active || stopping) return false;
        active = null;
        playerFailed = false;
        drain();
        return true;
    }

    /** Advance after stopping only when the player confirms that its audio has stopped. */
    public boolean stopped(Ticket ticket) {
        if (ticket == null || ticket != active || !stopping) return false;
        active = null;
        stopping = false;
        playerFailed = false;
        drain();
        return true;
    }

    public void skipActive() { requestStop(); }

    public void unregister(String source) {
        sources.remove(source);
        waiting.removeIf(ticket -> ticket.source.equals(source));
        if (active != null && active.source.equals(source)) requestStop();
    }

    /** Explicit user cancellation or app shutdown; do not use to implement timeouts. */
    public void clear() {
        waiting.clear();
        requestStop();
    }

    private void requestStop() {
        if (active == null || stopping) return;
        stopping = true;
        try {
            player.stop(active);
        } catch (RuntimeException exception) {
            // Fail closed: audio may still be playing. Keep the next item waiting.
            playerFailed = true;
        }
    }

    private void drain() {
        if (draining) return;
        draining = true;
        try {
            while (active == null && !waiting.isEmpty()) {
                active = waiting.removeFirst();
                stopping = false;
                try {
                    player.start(active);
                } catch (RuntimeException exception) {
                    playerFailed = true;
                    requestStop();
                }
            }
        } finally {
            draining = false;
        }
    }
}
