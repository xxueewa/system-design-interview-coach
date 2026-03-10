package com.example.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the full WebSocket lifecycle for a simple chat-style use case.
 *
 * Lifecycle:
 *   afterConnectionEstablished  — client connects via ws://localhost:8080/ws
 *   handleTextMessage           — server receives a message from the client (bidirectional)
 *   afterConnectionClosed       — client disconnects
 *
 * Behavior:
 *   - On connect:   welcome message sent to the new client
 *   - On message:   echoes the message back AND broadcasts to all other clients
 *   - On close:     notifies remaining clients that a user left
 *
 * This demonstrates the key difference from SSE: the client can SEND messages to the server,
 * not just receive them.
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    // Thread-safe map of sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        System.out.println("[WS] Client connected: " + session.getId()
                + " | Total: " + sessions.size());

        sendToSession(session, "system", "Welcome! Your session ID: " + session.getId());
        broadcast(session, "system", "A new user joined. Online: " + sessions.size());
    }

    /**
     * Called when the server receives a text message from a client.
     * Echoes back to the sender and broadcasts to all others.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        System.out.println("[WS] Message from " + session.getId() + ": " + payload);

        // Echo back to sender with confirmation
        sendToSession(session, "echo", "You said: " + payload);

        // Broadcast to all other connected clients
        broadcast(session, "message", "[" + session.getId().substring(0, 6) + "]: " + payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());
        System.out.println("[WS] Client disconnected: " + session.getId()
                + " | Remaining: " + sessions.size());

        broadcast(session, "system", "A user left. Online: " + sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        sessions.remove(session.getId());
        session.close(CloseStatus.SERVER_ERROR);
        System.out.println("[WS] Transport error for " + session.getId() + ": " + exception.getMessage());
    }

    /**
     * Sends a formatted message to a specific session.
     * Format: "type:data" — simple protocol for the client to parse.
     */
    private void sendToSession(WebSocketSession session, String type, String data) {
        if (!session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(type + ":" + data));
        } catch (IOException e) {
            System.out.println("[WS] Failed to send to " + session.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Broadcasts a message to all sessions except the sender.
     */
    private void broadcast(WebSocketSession sender, String type, String data) {
        sessions.forEach((id, session) -> {
            if (!id.equals(sender.getId())) {
                sendToSession(session, type, data);
            }
        });
    }

    /**
     * Push a server-initiated message to all clients (e.g. triggered by a Kafka event).
     * Can be called from any service/component — same as NotificationService.broadcast() for SSE.
     */
    public void pushToAll(String type, String data) {
        sessions.forEach((id, session) -> sendToSession(session, type, data));
    }
}