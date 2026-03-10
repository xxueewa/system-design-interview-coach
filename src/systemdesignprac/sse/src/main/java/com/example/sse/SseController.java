package com.example.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes two endpoints:
 *
 *   GET  /stream          — clients connect here to receive SSE events
 *   POST /notify?msg=...  — manually trigger a broadcast (simulates a follow event)
 */
@RestController
public class SseController {

    private final NotificationService notificationService;

    public SseController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * SSE subscription endpoint.
     *
     * The response Content-Type is text/event-stream, which tells the browser
     * (or any SSE client) to keep the connection open and parse incoming events.
     *
     * Timeout is set to 60s here. For production you'd tune this based on
     * proxy/load balancer idle-connection limits.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return notificationService.register(60000L);
    }

    /**
     * Manual broadcast trigger — useful for testing without waiting for the scheduler.
     * In a real system this would be replaced by a Kafka consumer or domain event listener.
     *
     * Example: POST /notify?msg=UserA+followed+you
     */
    @PostMapping("/notify")
    public String notify(@RequestParam String msg) {
        notificationService.broadcast("new_follower", msg);
        return "Broadcasted: " + msg;
    }
}