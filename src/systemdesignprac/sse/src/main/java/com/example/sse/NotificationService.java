package com.example.sse;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages active SSE client connections and broadcasts events to them.
 *
 * Design notes:
 * - Each connected client is tracked by a unique ID mapped to its SseEmitter.
 * - A @Scheduled task simulates periodic server-side events (e.g. notifications).
 * - Dead emitters (timed out or errored) are removed automatically via callbacks.
 */
@Service
public class NotificationService {

    // Thread-safe map of clientId -> SseEmitter
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(0);

    /**
     * Registers a new SSE client. Returns the emitter for the controller to hand off to Spring.
     *
     * @param timeoutMillis how long to keep the connection open (0 = indefinite)
     */
    public SseEmitter register(long timeoutMillis) {
        Long clientId = idCounter.incrementAndGet();
        SseEmitter emitter = new SseEmitter(timeoutMillis);

        // Clean up on completion, timeout, or error so we don't hold stale emitters
        emitter.onCompletion(() -> {
            emitters.remove(clientId);
            System.out.println("[SSE] Client " + clientId + " disconnected");
        });
        emitter.onTimeout(() -> {
            emitters.remove(clientId);
            System.out.println("[SSE] Client " + clientId + " timed out");
        });
        emitter.onError(e -> {
            emitters.remove(clientId);
            System.out.println("[SSE] Client " + clientId + " error: " + e.getMessage());
        });

        emitters.put(clientId, emitter);
        System.out.println("[SSE] Client " + clientId + " connected. Total: " + emitters.size());

        // Send an initial connection-established event
        sendToClient(clientId, emitter, "connected", "Client ID: " + clientId);

        return emitter;
    }

    /**
     * Broadcasts a notification event to all connected clients.
     * Called externally (e.g. from a controller endpoint or a follow-event handler).
     */
    public void broadcast(String eventName, String data) {
        emitters.forEach((clientId, emitter) ->
                sendToClient(clientId, emitter, eventName, data));
    }

    /**
     * Simulates a periodic server-side event every 5 seconds.
     * In a real system this would be triggered by an actual domain event
     * (e.g. a Kafka consumer receiving a follow event).
     */
    @Scheduled(fixedDelay = 5000)
    public void simulateNotification() {
        if (emitters.isEmpty()) return;

        String payload = "New follower at " + LocalTime.now();
        System.out.println("[SSE] Broadcasting: " + payload);
        broadcast("new_follower", payload);
    }

    private void sendToClient(Long clientId, SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(
                SseEmitter.event()
                    .id(String.valueOf(clientId))
                    .name(eventName)
                    .data(data)
            );
        } catch (IOException e) {
            // Client is gone — remove and complete the emitter
            emitters.remove(clientId);
            emitter.completeWithError(e);
        }
    }
}
