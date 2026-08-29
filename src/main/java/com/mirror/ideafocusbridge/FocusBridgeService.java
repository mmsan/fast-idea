package com.mirror.ideafocusbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.util.Disposer;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.APP)
public final class FocusBridgeService implements Disposable {
    private static final long SLOW_RECONNECT_AFTER_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long SLOW_RECONNECT_DELAY_MS = TimeUnit.SECONDS.toMillis(6);
    private static final long CONNECTION_LOG_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long STARTUP_FULLSCREEN_WINDOW_MS = TimeUnit.SECONDS.toMillis(8);
    private final Gson gson = new Gson();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fast-idea-bridge"); t.setDaemon(true); return t;
    });
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).executor(executor).build();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicInteger connectionFailures = new AtomicInteger();
    private final AtomicBoolean startupFullscreenHandshakeSent = new AtomicBoolean();
    private final Map<String, ConnectionLogState> connectionLogStates = new ConcurrentHashMap<>();
    private final LocalPluginUpdateChecker localPluginUpdateChecker = new LocalPluginUpdateChecker();
    private final String instanceId = UUID.randomUUID().toString();
    private volatile String connectionId = UUID.randomUUID().toString();
    private volatile WebSocket socket;
    private volatile ScheduledFuture<?> pendingSnapshot;
    private volatile ScheduledFuture<?> pendingStatus;
    private volatile String lastStatusSignature;
    private volatile int reconnectAttempt;
    private volatile long reconnectStartedAt;
    private volatile boolean slowReconnectLogged;
    private volatile long lastPongMillis;
    private PropertyChangeListener focusListener;
    private AWTEventListener inputListener;

    public static FocusBridgeService getInstance() {
        return ApplicationManager.getApplication().getService(FocusBridgeService.class);
    }

    String instanceId() { return instanceId; }
    String connectionId() { return connectionId; }

    void runBackground(Runnable task) {
        if (task != null && !executor.isShutdown()) executor.execute(task);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) return;
        BridgeLog.info("service started; autoConnect=" + FocusBridgeSettings.getInstance().data().autoConnect);
        focusListener = event -> {
            scheduleSnapshot("focus.changed", 0);
            scheduleStatus(0);
        };
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addPropertyChangeListener("permanentFocusOwner", focusListener);
        inputListener = event -> {
            if (event instanceof KeyEvent key && key.getID() == KeyEvent.KEY_RELEASED) {
                scheduleSnapshot("focus.changed", 80);
                scheduleStatus(80);
            } else if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_RELEASED) {
                scheduleSnapshot("focus.changed", 40);
                scheduleStatus(40);
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(inputListener, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        EditorFactory.getInstance().getEventMulticaster().addCaretListener(new CaretListener() {
            @Override public void caretPositionChanged(CaretEvent event) { scheduleStatus(40); }
        }, this);
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) { scheduleStatus(100); }
        }, this);
        executor.scheduleWithFixedDelay(this::heartbeat, 2, 2, TimeUnit.SECONDS);
        localPluginUpdateChecker.start();
        if (FocusBridgeSettings.getInstance().data().autoConnect) connect();
    }

    public void settingsChanged() {
        BridgeLog.info("settings changed; reconnecting");
        long ignored = generation.incrementAndGet();
        reconnectScheduled.set(false);
        reconnectAttempt = 0;
        reconnectStartedAt = 0;
        slowReconnectLogged = false;
        WebSocket old = socket; socket = null;
        if (old != null) old.sendClose(WebSocket.NORMAL_CLOSURE, "settings changed");
        if (FocusBridgeSettings.getInstance().data().autoConnect) connect();
    }

    private void connect() {
        long expectedGeneration = generation.get();
        URI uri;
        try { uri = URI.create(FocusBridgeSettings.getInstance().websocketUrl()); }
        catch (RuntimeException e) { BridgeLog.warn("invalid WebSocket URL", e); return; }

        WebSocket.Builder builder = httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(1));
        String token = FocusBridgeSettings.getInstance().data().token;
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        builder.header("X-Idea-Bridge-Version", "1");
        builder.buildAsync(uri, new SocketListener(expectedGeneration)).whenComplete((ws, error) -> {
            if (error != null && expectedGeneration == generation.get()) {
                recordConnectionFailure("connection failed", error);
                scheduleReconnect(expectedGeneration);
            }
        });
    }

    private void recordConnectionFailure(String message, Throwable error) {
        int count = connectionFailures.incrementAndGet();
        logConnectionEvent("failure", message + "; attempt=" + count, error, true);
    }

    private synchronized void logConnectionEvent(String kind, String message, Throwable error, boolean warning) {
        long now = System.currentTimeMillis();
        ConnectionLogState state = connectionLogStates.computeIfAbsent(kind, ignored -> new ConnectionLogState());
        state.count++;
        if (state.count <= 3 || now - state.lastLoggedAt >= CONNECTION_LOG_INTERVAL_MS) {
            String suffix = state.suppressed > 0 ? "; suppressed=" + state.suppressed : "";
            state.lastLoggedAt = now;
            state.suppressed = 0;
            if (warning) BridgeLog.warn(message + suffix, error);
            else BridgeLog.info(message + suffix);
        } else {
            state.suppressed++;
        }
    }

    private void scheduleReconnect(long expectedGeneration) {
        if (!FocusBridgeSettings.getInstance().data().autoConnect || expectedGeneration != generation.get()) return;
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        int[] delays = {200, 500, 1_000, 2_000};
        if (reconnectStartedAt == 0) reconnectStartedAt = System.currentTimeMillis();
        long retryingForMs = System.currentTimeMillis() - reconnectStartedAt;
        long delay = retryingForMs >= SLOW_RECONNECT_AFTER_MS
                ? SLOW_RECONNECT_DELAY_MS
                : delays[Math.min(reconnectAttempt++, delays.length - 1)];
        if (delay == SLOW_RECONNECT_DELAY_MS && !slowReconnectLogged) {
            slowReconnectLogged = true;
            BridgeLog.info("connection remains unavailable for " + retryingForMs / 1_000
                    + "s; retry delay increased to " + SLOW_RECONNECT_DELAY_MS + "ms");
        }
        executor.schedule(() -> {
            reconnectScheduled.set(false);
            if (expectedGeneration == generation.get()) connect();
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) return;
        long now = System.currentTimeMillis();
        if (lastPongMillis > 0 && now - lastPongMillis > 6_000) {
            if (socket == ws) socket = null;
            ws.abort();
            recordConnectionFailure("heartbeat timeout; reconnecting", null);
            scheduleReconnect(generation.get());
            return;
        }
        ByteBuffer ping = ByteBuffer.allocate(Long.BYTES).putLong(now).flip();
        ws.sendPing(ping).exceptionally(error -> {
            if (socket == ws) socket = null;
            ws.abort();
            recordConnectionFailure("ping failed; reconnecting", error);
            scheduleReconnect(generation.get());
            return null;
        });
    }

    private void scheduleSnapshot(String type, long delayMs) {
        ScheduledFuture<?> old = pendingSnapshot;
        if (old != null) old.cancel(false);
        pendingSnapshot = executor.schedule(() -> ApplicationManager.getApplication().invokeLater(() -> {
            Map<String, Object> snapshot = FocusSnapshotCollector.collect();
            send(type, snapshot, null);
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private void scheduleStatus(long delayMs) {
        ScheduledFuture<?> old = pendingStatus;
        if (old != null) old.cancel(false);
        pendingStatus = executor.schedule(() -> ApplicationManager.getApplication().invokeLater(() -> {
            Map<String, Object> status = FocusSnapshotCollector.collectStatus();
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("inputable", status.get("inputable"));
            key.put("objectInfo", status.get("objectInfo"));
            String signature = gson.toJson(key);
            if (!signature.equals(lastStatusSignature)) {
                lastStatusSignature = signature;
                send("editor.statusChanged", status, null);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    void send(String type, Object payload, String replyTo) {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) return;
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("version", 1); message.put("id", UUID.randomUUID().toString());
        if (replyTo != null) message.put("replyTo", replyTo);
        message.put("type", type); message.put("timestamp", System.currentTimeMillis());
        message.put("sequence", sequence.incrementAndGet()); message.put("payload", payload);
        message.put("source", BridgeCommands.source(instanceId, connectionId, payload));
        ws.sendText(gson.toJson(message), true).exceptionally(error -> {
            logConnectionEvent("send:" + type, "message send failed; type=" + type, error, true);
            return null;
        });
    }

    private final class SocketListener implements WebSocket.Listener {
        private final long expectedGeneration;
        private final StringBuilder incoming = new StringBuilder();
        SocketListener(long expectedGeneration) { this.expectedGeneration = expectedGeneration; }

        @Override public void onOpen(WebSocket webSocket) {
            if (expectedGeneration != generation.get()) { webSocket.abort(); return; }
            connectionId = UUID.randomUUID().toString();
            socket = webSocket; reconnectAttempt = 0; reconnectScheduled.set(false);
            reconnectStartedAt = 0; slowReconnectLogged = false;
            lastStatusSignature = null;
            int failures = connectionFailures.getAndSet(0);
            boolean firstBridgeHandshake = startupFullscreenHandshakeSent.compareAndSet(false, true);
            BridgeLog.info("connected" + (failures > 0 ? "; recoveredAfterFailures=" + failures : ""));
            lastPongMillis = System.currentTimeMillis();
            ApplicationManager.getApplication().invokeLater(() -> {
                Map<String, Object> identity = BridgeCommands.identity();
                if (firstBridgeHandshake) {
                    identity.put("startupFullScreenUntil", System.currentTimeMillis() + STARTUP_FULLSCREEN_WINDOW_MS);
                }
                send("bridge.hello", identity, null);
                scheduleSnapshot("editor.state", 0);
                scheduleStatus(0);
            });
            webSocket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (incoming.length() + data.length() > 1_048_576) {
                incoming.setLength(0);
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "message too large");
                return CompletableFuture.completedFuture(null);
            }
            incoming.append(data);
            if (last) {
                String message = incoming.toString(); incoming.setLength(0);
                BridgeCommands.handle(message, FocusBridgeService.this);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onPing(WebSocket webSocket, java.nio.ByteBuffer message) {
            lastPongMillis = System.currentTimeMillis();
            webSocket.request(1); return webSocket.sendPong(message);
        }

        @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            lastPongMillis = System.currentTimeMillis();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) socket = null;
            logConnectionEvent("closed", "connection closed; code=" + statusCode + "; reason=" + reason, null, false);
            scheduleReconnect(expectedGeneration);
            return CompletableFuture.completedFuture(null);
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) socket = null;
            recordConnectionFailure("socket error", error);
            scheduleReconnect(expectedGeneration);
        }
    }

    private static final class ConnectionLogState {
        int count;
        long lastLoggedAt;
        int suppressed;
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (focusListener != null) KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("permanentFocusOwner", focusListener);
        if (inputListener != null) Toolkit.getDefaultToolkit().removeAWTEventListener(inputListener);
        WebSocket ws = socket; socket = null;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disposed");
        BridgeLog.info("service disposed");
        localPluginUpdateChecker.dispose();
        executor.shutdownNow();
    }
}
