package com.mirror.ideafocusbridge;

import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
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
    private volatile SocketListener connection;
    private volatile ScheduledFuture<?> pendingSnapshot;
    private volatile ScheduledFuture<?> pendingStatus;
    private volatile String lastStatusSignature;
    private volatile int reconnectAttempt;
    private volatile long reconnectStartedAt;
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
        SocketListener old = connection;
        if (old != null) old.close();
        if (FocusBridgeSettings.getInstance().data().autoConnect) connect();
    }

    /** Acquires Windows admission in the background before opening the authenticated local socket. */
    private void connect() {
        long expectedGeneration = generation.get();
        runBackground(() -> {
            if (expectedGeneration != generation.get()) return;
            BridgeAuthentication authentication;
            try {
                authentication = BridgeAuthentication.bootstrap();
            } catch (IOException | RuntimeException | LinkageError error) {
                if (expectedGeneration == generation.get()) {
                    recordConnectionFailure("local bridge admission unavailable", null);
                    scheduleReconnect(expectedGeneration);
                }
                return;
            }
            SocketListener candidate = new SocketListener(expectedGeneration, authentication);
            SocketListener previous = connection;
            if (previous != null) previous.close();
            connection = candidate;
            if (expectedGeneration != generation.get()) {
                candidate.close();
                return;
            }
            URI uri = URI.create("ws://127.0.0.1:" + authentication.port());
            httpClient.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(1))
                    .header("X-Idea-Bridge-Version", "2").buildAsync(uri, candidate)
                    .whenComplete((ws, error) -> {
                        if (error != null) candidate.fail("authenticated connection failed");
                    });
        });
    }

    private void recordConnectionFailure(String message, Throwable error) {
        int count = connectionFailures.incrementAndGet();
        if (message.equals("local bridge admission unavailable") || message.equals("authenticated connection failed") || message.equals("socket error")) {
            if (count == 1) BridgeLog.warn(message + "; attempt=" + count, error);
        } else {
            logConnectionEvent(message, message + "; attempt=" + count, error, true);
        }
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
            SocketListener current = connection;
            if (current != null && current.webSocket == ws) current.fail("heartbeat timeout; reconnecting");
            return;
        }
        ByteBuffer ping = ByteBuffer.allocate(Long.BYTES).putLong(now).flip();
        ws.sendPing(ping).exceptionally(error -> {
            SocketListener current = connection;
            if (current != null && current.webSocket == ws) current.fail("ping failed; reconnecting");
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
        SocketListener current = connection;
        if (current != null && current.webSocket == ws) current.sendApplication(message);
    }

    private final class SocketListener implements WebSocket.Listener {
        private static final int MAX_PENDING_MESSAGES = 16;
        private static final int MAX_PENDING_BYTES = 4 * 1024 * 1024;
        private final long expectedGeneration;
        private final BridgeAuthentication authentication;
        private final StringBuilder incoming = new StringBuilder();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicInteger pendingMessages = new AtomicInteger();
        private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
        private volatile WebSocket webSocket;
        private volatile boolean authenticated;
        private volatile ScheduledFuture<?> authenticationTimeout;
        private volatile long authenticationDeadlineNanos;
        private int pendingBytes;

        /** Associates one fresh bootstrap with one reconnect generation. */
        SocketListener(long expectedGeneration, BridgeAuthentication authentication) {
            this.expectedGeneration = expectedGeneration;
            this.authentication = authentication;
        }

        /** Starts a bounded mutual proof without publishing an application-ready socket. */
        @Override public void onOpen(WebSocket webSocket) {
            this.webSocket = webSocket;
            if (closed.get() || expectedGeneration != generation.get() || connection != this) {
                webSocket.abort();
                close();
                return;
            }
            try {
                authenticationDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                authenticationTimeout = executor.schedule(() -> fail("bridge authentication timed out"),
                        5, TimeUnit.SECONDS);
                sendControl(authentication.hello());
                webSocket.request(1);
            } catch (IOException | RuntimeException error) {
                fail("bridge authentication rejected");
            }
        }

        /** Publishes editor identity and a complete state only after encrypted readiness. */
        private synchronized void authenticated() {
            if (System.nanoTime() >= authenticationDeadlineNanos) {
                fail("bridge authentication timed out");
                return;
            }
            if (closed.get() || expectedGeneration != generation.get() || connection != this) {
                close();
                return;
            }
            authenticated = true;
            ScheduledFuture<?> timeout = authenticationTimeout;
            if (timeout != null) timeout.cancel(false);
            connectionId = UUID.randomUUID().toString();
            socket = webSocket;
            reconnectAttempt = 0;
            reconnectScheduled.set(false);
            reconnectStartedAt = 0;
            lastStatusSignature = null;
            int failures = connectionFailures.getAndSet(0);
            boolean firstBridgeHandshake = startupFullscreenHandshakeSent.compareAndSet(false, true);
            BridgeLog.info("authenticated connection" + (failures > 0 ? "; recoveredAfterFailures=" + failures : ""));
            lastPongMillis = System.currentTimeMillis();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (closed.get() || socket != webSocket) return;
                Map<String, Object> identity = BridgeCommands.identity();
                if (firstBridgeHandshake) {
                    identity.put("startupFullScreenUntil", System.currentTimeMillis() + STARTUP_FULLSCREEN_WINDOW_MS);
                }
                send("bridge.hello", identity, null);
                scheduleSnapshot("editor.state", 0);
                scheduleStatus(0);
            });
        }

        /** Serializes the two small proof messages with any following encrypted sends. */
        private synchronized void sendControl(String raw) {
            sendTail = sendTail.thenCompose(ignored -> {
                if (closed.get()) return CompletableFuture.failedFuture(new IOException("Bridge closed"));
                return webSocket.sendText(raw, true).thenApply(ws -> (Void) null);
            }).whenComplete((ignored, error) -> {
                if (error != null) fail("bridge authentication send failed");
            });
        }

        /** Bounds queued application work before serializing it outside the EDT. */
        private void sendApplication(Map<String, Object> message) {
            if (!authenticated || closed.get()) return;
            if (pendingMessages.incrementAndGet() > MAX_PENDING_MESSAGES) {
                pendingMessages.decrementAndGet();
                fail("bridge send queue exceeded");
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        enqueue(gson.toJson(message));
                    } catch (IOException | RuntimeException error) {
                        pendingMessages.decrementAndGet();
                        fail("bridge message send rejected");
                    }
                });
            } catch (RejectedExecutionException error) {
                pendingMessages.decrementAndGet();
                close();
            }
        }

        /** Preserves frame order and bounds aggregate plaintext awaiting socket writes. */
        private synchronized void enqueue(String raw) throws IOException {
            if (closed.get()) {
                pendingMessages.decrementAndGet();
                return;
            }
            int bytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes > BridgeAuthentication.MAX_PLAINTEXT || pendingBytes + bytes > MAX_PENDING_BYTES) {
                throw new IOException("Bridge send queue exceeded");
            }
            pendingBytes += bytes;
            sendTail = sendTail.thenCompose(ignored -> {
                if (closed.get()) return CompletableFuture.failedFuture(new IOException("Bridge closed"));
                try {
                    return webSocket.sendText(authentication.encrypt(raw), true).thenApply(ws -> (Void) null);
                } catch (IOException error) {
                    return CompletableFuture.failedFuture(error);
                }
            }).whenComplete((ignored, error) -> {
                synchronized (this) {
                    pendingBytes -= bytes;
                }
                pendingMessages.decrementAndGet();
                if (error != null) fail("bridge message send failed");
            });
        }

        /** Authenticates each bounded message before dispatching any command. */
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (closed.get()) return CompletableFuture.completedFuture(null);
            if (!authenticated && System.nanoTime() >= authenticationDeadlineNanos) {
                fail("bridge authentication timed out");
                return CompletableFuture.completedFuture(null);
            }
            int limit = authenticated ? BridgeAuthentication.MAX_FRAME_CHARACTERS : 4_096;
            if (incoming.length() + (long) data.length() > limit) {
                incoming.setLength(0);
                fail("bridge message too large");
                return CompletableFuture.completedFuture(null);
            }
            incoming.append(data);
            if (last) {
                String message = incoming.toString();
                incoming.setLength(0);
                try {
                    if (!authentication.hasServerProof()) {
                        sendControl(authentication.proveServer(message));
                    } else {
                        String plaintext = authentication.decrypt(message);
                        if (!authenticated) {
                            authentication.acceptReady(plaintext);
                            authenticated();
                        } else {
                            BridgeCommands.handle(plaintext, FocusBridgeService.this);
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    fail("bridge message authentication rejected");
                }
            }
            if (!closed.get()) webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        /** Rejects binary traffic because the authenticated wire format is JSON text. */
        @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer message, boolean last) {
            fail("unsupported bridge frame");
            return CompletableFuture.completedFuture(null);
        }

        /** Retains the existing transport ping behavior without application data. */
        @Override public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (authenticated && socket == webSocket) lastPongMillis = System.currentTimeMillis();
            webSocket.request(1);
            return webSocket.sendPong(message);
        }

        /** Retains the existing pong freshness check after authentication. */
        @Override public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (authenticated && socket == webSocket) lastPongMillis = System.currentTimeMillis();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        /** Drops credentials and reconnects without recording peer-controlled close text. */
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (close() && expectedGeneration == generation.get()) {
                logConnectionEvent("closed", "connection closed; code=" + statusCode, null, false);
                scheduleReconnect(expectedGeneration);
            }
            return CompletableFuture.completedFuture(null);
        }

        /** Treats transport errors as connection failure without logging protocol payloads. */
        @Override public void onError(WebSocket webSocket, Throwable error) {
            fail("socket error");
        }

        /** Fails closed once and uses the unchanged reconnect schedule. */
        private void fail(String message) {
            if (close() && expectedGeneration == generation.get()) {
                recordConnectionFailure(message, null);
                scheduleReconnect(expectedGeneration);
            }
        }

        /** Erases ephemeral keys and aborts pending or established transport on disposal. */
        private synchronized boolean close() {
            if (!closed.compareAndSet(false, true)) return false;
            authenticated = false;
            ScheduledFuture<?> timeout = authenticationTimeout;
            if (timeout != null) timeout.cancel(false);
            authentication.close();
            WebSocket ws = webSocket;
            if (socket == ws) socket = null;
            if (connection == this) connection = null;
            if (ws != null) ws.abort();
            return true;
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
        SocketListener current = connection;
        if (current != null) current.close();
        BridgeLog.info("service disposed");
        localPluginUpdateChecker.dispose();
        executor.shutdownNow();
    }
}
