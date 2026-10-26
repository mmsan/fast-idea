package com.mirror.ideafocusbridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Windows admission and the authenticated protocol-2 transport; credentials remain in memory. */
final class BridgeAuthentication implements AutoCloseable {
    static final int MAX_PLAINTEXT = 2 * 1024 * 1024;
    static final int MAX_FRAME_CHARACTERS = 2_796_544;
    private static final int MAX_BOOTSTRAP_BYTES = 16 * 1024;
    private static final long MAX_SEQUENCE = 9_007_199_254_740_991L;
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).disableHtmlEscaping().create();
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String ticket;
    private final long expires;
    private final long peerPid;
    private final String clientNonce;
    private final int port;
    private byte[] bootstrapKey;
    private byte[] sendKey;
    private byte[] receiveKey;
    private long sendSequence;
    private long receiveSequence;
    private boolean ready;
    private boolean closed;

    /** Retains only the one-use bootstrap needed for this connection. */
    private BridgeAuthentication(JsonObject response, String nonce) throws IOException {
        long now = System.currentTimeMillis();
        if (response.size() != 9 || number(response, "version") != 2
                || !response.has("ok") || !response.get("ok").isJsonPrimitive()
                || !response.getAsJsonPrimitive("ok").isBoolean() || !response.get("ok").getAsBoolean()
                || !"idea".equals(string(response, "client")) || !nonce.equals(string(response, "clientNonce"))) {
            throw rejected();
        }
        peerPid = number(response, "peerPid");
        expires = number(response, "expires");
        long admittedPort = number(response, "port");
        if (peerPid != ProcessHandle.current().pid() || expires <= now || expires - now > 30_000
                || admittedPort < 1 || admittedPort > 65535) throw rejected();
        ticket = hex(response, "ticket");
        bootstrapKey = HEX.parseHex(hex(response, "key"));
        clientNonce = nonce;
        port = (int) admittedPort;
    }

    /** Obtains fresh admission off the EDT, with one five-second native-I/O deadline. */
    static BridgeAuthentication bootstrap() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) throw rejected();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        WindowsApi api = WindowsApi.INSTANCE;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) throw rejected();
        Path descriptorPath = localPath(Path.of(localAppData, "FastGoTo", "BrowserBridge", "Native",
                "bridge-host-v2.json").toString());
        JsonObject descriptor;
        Pointer file = openFile(api, descriptorPath, 0x80000000, 1, 0x40200000);
        try {
            requireOrdinaryFile(api, file);
            long[] size = new long[1];
            if (!api.GetFileSizeEx(file, size) || size[0] < 1 || size[0] > MAX_BOOTSTRAP_BYTES) throw rejected();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size[0]);
            while (bytes.size() < size[0]) {
                byte[] chunk = transfer(api, file, null, (int) size[0] - bytes.size(), deadline);
                if (chunk.length == 0) throw rejected();
                bytes.writeBytes(chunk);
            }
            requireNoReparse(api, descriptorPath);
            descriptor = object(utf8(bytes.toByteArray()));
        } finally {
            api.CloseHandle(file);
        }
        if (descriptor.size() != 4 || number(descriptor, "version") != 2) throw rejected();
        long expectedPid = number(descriptor, "pid");
        if (expectedPid < 1 || expectedPid > 0xffff_ffffL) throw rejected();
        String expectedImage = string(descriptor, "executable");
        Path executable = localPath(expectedImage);
        if (!"FastGoTo.exe".equalsIgnoreCase(executable.getFileName().toString())) throw rejected();
        String name = string(descriptor, "pipe");
        if (!name.matches("FastGoTo\\.Bridge\\.v2\\.S-1-(?:[0-9]+-)+[0-9]+\\.[0-9]+")) throw rejected();
        String pipeName = "\\\\.\\pipe\\" + name;
        Pointer pipe;
        while (true) {
            remainingMillis(deadline);
            pipe = api.CreateFileW(new WString(pipeName), 0xc0000000, 0, null, 3, 0x40000000, null);
            if (valid(pipe)) break;
            if (Native.getLastError() != 231) throw rejected();
            api.WaitNamedPipeW(new WString(pipeName), Math.min(100, remainingMillis(deadline)));
        }
        try {
            int[] actualPid = new int[1];
            int[] currentSession = new int[1];
            int[] serverSession = new int[1];
            if (!api.GetNamedPipeServerProcessId(pipe, actualPid)
                    || Integer.toUnsignedLong(actualPid[0]) != expectedPid
                    || !api.ProcessIdToSessionId((int) ProcessHandle.current().pid(), currentSession)
                    || !api.ProcessIdToSessionId(actualPid[0], serverSession)
                    || currentSession[0] != serverSession[0]
                    || !name.endsWith("." + Integer.toUnsignedString(currentSession[0]))) throw rejected();
            Pointer process = api.OpenProcess(0x1000, false, actualPid[0]);
            if (!valid(process)) throw rejected();
            try {
                char[] image = new char[32768];
                int[] length = {image.length};
                int[] exitCode = new int[1];
                if (!api.QueryFullProcessImageNameW(process, 0, image, length) || length[0] < 1
                        || !canonicalImage(api, new String(image, 0, length[0]))
                        .equalsIgnoreCase(canonicalImage(api, expectedImage))
                        || !api.GetExitCodeProcess(process, exitCode) || exitCode[0] != 259
                        || !api.GetNamedPipeServerProcessId(pipe, actualPid)
                        || Integer.toUnsignedLong(actualPid[0]) != expectedPid) throw rejected();
            } finally {
                api.CloseHandle(process);
            }
            String nonce = randomNonce();
            byte[] request = (JSON.toJson(java.util.Map.of("version", 2, "type", "connect",
                    "client", "idea", "clientNonce", nonce)) + "\n").getBytes(StandardCharsets.UTF_8);
            int written = 0;
            while (written < request.length) {
                byte[] chunk = Arrays.copyOfRange(request, written, request.length);
                int count = transfer(api, pipe, chunk, chunk.length, deadline).length;
                if (count == 0) throw rejected();
                written += count;
            }
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            while (response.size() < MAX_BOOTSTRAP_BYTES) {
                byte[] chunk = transfer(api, pipe, null, Math.min(1024, MAX_BOOTSTRAP_BYTES - response.size()), deadline);
                if (chunk.length == 0) throw rejected();
                for (int index = 0; index < chunk.length; index++) {
                    if (chunk[index] == '\n') {
                        if (index != chunk.length - 1) throw rejected();
                        response.write(chunk, 0, index);
                        return new BridgeAuthentication(object(utf8(response.toByteArray())), nonce);
                    }
                }
                response.writeBytes(chunk);
            }
            throw rejected();
        } finally {
            api.CloseHandle(pipe);
        }
    }

    /** Uses only the authenticated runtime port, irrespective of saved legacy settings. */
    int port() {
        return port;
    }

    /** Sends no editor identity or state in the unauthenticated greeting. */
    synchronized String hello() throws IOException {
        if (closed || System.currentTimeMillis() >= expires) throw rejected();
        return JSON.toJson(java.util.Map.of("type", "bridgeHello", "protocol", 2, "client", "idea",
                "peerPid", peerPid, "ticket", ticket, "expires", expires, "clientNonce", clientNonce));
    }

    /** Reports whether the server has proved possession of the bootstrap key. */
    synchronized boolean hasServerProof() {
        return receiveKey != null && !closed;
    }

    /** Verifies the server before deriving directional keys and releasing the client proof. */
    synchronized String proveServer(String raw) throws IOException {
        if (closed || receiveKey != null || bootstrapKey == null || System.currentTimeMillis() >= expires) throw rejected();
        JsonObject challenge = object(raw);
        if (challenge.size() != 4 || !"bridgeChallenge".equals(string(challenge, "type"))
                || number(challenge, "protocol") != 2) throw rejected();
        String serverNonce = hex(challenge, "serverNonce");
        byte[] expected = derive("server", serverNonce);
        byte[] actual = HEX.parseHex(hex(challenge, "proof"));
        if (!MessageDigest.isEqual(expected, actual)) throw rejected();
        String proof = HEX.formatHex(derive("client", serverNonce));
        sendKey = derive("clientToServer", serverNonce);
        receiveKey = derive("serverToClient", serverNonce);
        Arrays.fill(bootstrapKey, (byte) 0);
        bootstrapKey = null;
        return JSON.toJson(java.util.Map.of("type", "bridgeProof", "proof", proof));
    }

    /** Accepts only the encrypted readiness marker before normal bridge traffic. */
    synchronized void acceptReady(String raw) throws IOException {
        JsonObject message = object(raw);
        if (closed || ready || receiveSequence != 1 || message.size() != 1
                || !"bridgeReady".equals(string(message, "type"))) throw rejected();
        ready = true;
    }

    /** Authenticates the next exact server sequence before exposing its plaintext. */
    synchronized String decrypt(String raw) throws IOException {
        if (closed || receiveKey == null) throw rejected();
        JsonObject frame = object(raw);
        long sequence = number(frame, "sequence");
        if (frame.size() != 3 || !"bridgeFrame".equals(string(frame, "type"))
                || sequence != receiveSequence + 1 || sequence > MAX_SEQUENCE) throw rejected();
        String encoded = string(frame, "ciphertext");
        if (encoded.length() > ((MAX_PLAINTEXT + 18) / 3) * 4) throw rejected();
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw rejected();
        }
        if (ciphertext.length < 16 || ciphertext.length > MAX_PLAINTEXT + 16
                || !Base64.getEncoder().encodeToString(ciphertext).equals(encoded)) throw rejected();
        byte[] plaintext = crypt(Cipher.DECRYPT_MODE, receiveKey, "serverToClient", sequence, ciphertext);
        String result = utf8(plaintext);
        receiveSequence = sequence;
        return result;
    }

    /** Encrypts application traffic with a distinct key and monotonically increasing IV. */
    synchronized String encrypt(String raw) throws IOException {
        if (closed || !ready || sendKey == null || sendSequence >= MAX_SEQUENCE) throw rejected();
        byte[] plaintext = raw.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_PLAINTEXT) throw rejected();
        long sequence = ++sendSequence;
        String ciphertext = Base64.getEncoder().encodeToString(
                crypt(Cipher.ENCRYPT_MODE, sendKey, "clientToServer", sequence, plaintext));
        return JSON.toJson(java.util.Map.of("type", "bridgeFrame", "sequence", sequence, "ciphertext", ciphertext));
    }

    /** Derives a proof or a directional key from the exact compact JSON transcript. */
    private byte[] derive(String purpose, String serverNonce) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bootstrapKey, "HmacSHA256"));
            return mac.doFinal(JSON.toJson(List.of(purpose, "idea", peerPid, ticket, expires,
                    clientNonce, serverNonce)).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw rejected();
        }
    }

    /** Applies the protocol IV and AAD without reusing a nonce in either direction. */
    private static byte[] crypt(int mode, byte[] key, String direction, long sequence, byte[] input) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(JSON.toJson(List.of("fast-local-bridge-v2", direction, sequence))
                    .getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException error) {
            throw rejected();
        }
    }

    /** Clears credentials when this connection is replaced, closed, or rejected. */
    @Override public synchronized void close() {
        closed = true;
        ready = false;
        for (byte[] key : new byte[][] {bootstrapKey, sendKey, receiveKey}) {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
        bootstrapKey = null;
        sendKey = null;
        receiveKey = null;
    }

    /** Parses flat strict protocol envelopes and rejects duplicate or unexpected structured values. */
    private static JsonObject object(String raw) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(raw))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject result = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (result.has(name)) throw rejected();
                switch (reader.peek()) {
                    case STRING -> result.addProperty(name, reader.nextString());
                    case NUMBER -> {
                        String value = reader.nextString();
                        if (!value.matches("0|[1-9][0-9]{0,15}")) throw rejected();
                        long number = Long.parseLong(value);
                        if (number > MAX_SEQUENCE) throw rejected();
                        result.addProperty(name, number);
                    }
                    case BOOLEAN -> result.addProperty(name, reader.nextBoolean());
                    default -> throw rejected();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw rejected();
            return result;
        } catch (IOException | RuntimeException error) {
            throw rejected();
        }
    }

    /** Requires a JSON string without implicit primitive conversions. */
    private static String string(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw rejected();
        return value.getAsString();
    }

    /** Requires an exact nonnegative JSON integer in the interoperable numeric range. */
    private static long number(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("0|[1-9][0-9]{0,15}")) throw rejected();
        try {
            long result = Long.parseLong(value.getAsString());
            if (result > MAX_SEQUENCE) throw rejected();
            return result;
        } catch (NumberFormatException error) {
            throw rejected();
        }
    }

    /** Requires canonical lowercase 32-byte hexadecimal protocol values. */
    private static String hex(JsonObject object, String name) throws IOException {
        String value = string(object, name);
        if (!value.matches("[0-9a-f]{64}")) throw rejected();
        return value;
    }

    /** Decodes strict UTF-8 instead of replacing malformed bytes. */
    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw rejected();
        }
    }

    /** Creates an independent cryptographic client nonce for every bootstrap. */
    private static String randomNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    /** Rejects device, UNC, alternate-stream, and noncanonical local paths. */
    private static Path localPath(String value) throws IOException {
        if (value == null || value.length() > 32767 || !value.matches("[A-Za-z]:\\\\[^:]*")
                || value.indexOf('\0') >= 0) throw rejected();
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !path.normalize().equals(path)) throw rejected();
            return path;
        } catch (RuntimeException error) {
            throw rejected();
        }
    }

    /** Checks every existing path component rather than following a junction or symbolic link. */
    private static void requireNoReparse(WindowsApi api, Path path) throws IOException {
        Path current = path.getRoot();
        int attributes = api.GetFileAttributesW(new WString(current.toString()));
        if (attributes == -1 || (attributes & 0x400) != 0) throw rejected();
        for (Path component : path) {
            current = current.resolve(component);
            attributes = api.GetFileAttributesW(new WString(current.toString()));
            if (attributes == -1 || (attributes & 0x400) != 0) throw rejected();
        }
    }

    /** Opens a checked local file without following a final-component reparse point. */
    private static Pointer openFile(WindowsApi api, Path path, int access, int sharing, int flags) throws IOException {
        requireNoReparse(api, path);
        Pointer file = api.CreateFileW(new WString(path.toString()), access, sharing, null, 3, flags, null);
        if (!valid(file)) throw rejected();
        return file;
    }

    /** Checks the actual opened file's attributes, including reparse tags. */
    private static void requireOrdinaryFile(WindowsApi api, Pointer file) throws IOException {
        Memory attributes = new Memory(8);
        attributes.clear();
        if (!api.GetFileInformationByHandleEx(file, 9, attributes, 8)
                || (attributes.getInt(0) & (0x400 | 0x10)) != 0) throw rejected();
    }

    /** Canonicalizes the executable through its opened file handle. */
    private static String canonicalImage(WindowsApi api, String value) throws IOException {
        Path path = localPath(value);
        Pointer file = openFile(api, path, 0x80, 7, 0x00200000);
        try {
            requireOrdinaryFile(api, file);
            char[] result = new char[32768];
            int count = api.GetFinalPathNameByHandleW(file, result, result.length, 0);
            if (count < 1 || count >= result.length) throw rejected();
            String canonical = new String(result, 0, count);
            if (!canonical.startsWith("\\\\?\\")) throw rejected();
            canonical = canonical.substring(4);
            requireNoReparse(api, localPath(canonical));
            return canonical;
        } finally {
            api.CloseHandle(file);
        }
    }

    /** Performs one overlapped operation and cancels it before releasing native memory on timeout. */
    private static byte[] transfer(WindowsApi api, Pointer file, byte[] output, int count, long deadline) throws IOException {
        int waitMillis = remainingMillis(deadline);
        Memory buffer = new Memory(count);
        if (output != null) buffer.write(0, output, 0, count);
        Memory overlapped = new Memory(Native.POINTER_SIZE * 3L + 8);
        overlapped.clear();
        Pointer event = api.CreateEventW(null, true, false, null);
        if (!valid(event)) throw rejected();
        overlapped.setPointer(Native.POINTER_SIZE * 2L + 8, event);
        int[] transferred = new int[1];
        try {
            boolean complete = output == null ? api.ReadFile(file, buffer, count, transferred, overlapped)
                    : api.WriteFile(file, buffer, count, transferred, overlapped);
            int error = complete ? 0 : Native.getLastError();
            if (!complete && error == 997) {
                int wait = api.WaitForSingleObject(event, waitMillis);
                if (wait != 0) {
                    api.CancelIoEx(file, overlapped);
                    api.GetOverlappedResult(file, overlapped, transferred, true);
                    throw rejected();
                }
                complete = api.GetOverlappedResult(file, overlapped, transferred, false);
                error = complete ? 0 : Native.getLastError();
            }
            if ((!complete && (output != null || error != 234)) || transferred[0] < 0 || transferred[0] > count) {
                throw rejected();
            }
            return output == null ? buffer.getByteArray(0, transferred[0]) : new byte[transferred[0]];
        } finally {
            api.CloseHandle(event);
        }
    }

    /** Returns the remaining shared bootstrap budget without granting a fresh per-operation timeout. */
    private static int remainingMillis(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw rejected();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    /** Recognizes both NULL and INVALID_HANDLE_VALUE. */
    private static boolean valid(Pointer handle) {
        return handle != null && Pointer.nativeValue(handle) != -1;
    }

    /** Uses a fixed diagnostic so credentials and attacker-controlled text never reach logs. */
    private static IOException rejected() {
        return new IOException("Local bridge authentication rejected");
    }

    /** Uses only IntelliJ's existing bundled JNA base library. */
    interface WindowsApi extends StdCallLibrary {
        WindowsApi INSTANCE = Native.load("kernel32", WindowsApi.class);
        /** Opens the named local file or pipe. */
        Pointer CreateFileW(WString name, int access, int sharing, Pointer security, int disposition, int flags, Pointer template);
        /** Reads the opened descriptor length. */
        boolean GetFileSizeEx(Pointer file, long[] size);
        /** Reads attributes from the actual opened object. */
        boolean GetFileInformationByHandleEx(Pointer file, int informationClass, Pointer information, int size);
        /** Checks each path component for reparse points. */
        int GetFileAttributesW(WString path);
        /** Obtains the canonical name of an opened image file. */
        int GetFinalPathNameByHandleW(Pointer file, char[] path, int length, int flags);
        /** Obtains the actual server identity from this pipe handle. */
        boolean GetNamedPipeServerProcessId(Pointer pipe, int[] pid);
        /** Requires the Host and plugin to share a Windows session. */
        boolean ProcessIdToSessionId(int pid, int[] session);
        /** Opens the actual pipe server for limited image inspection. */
        Pointer OpenProcess(int access, boolean inherit, int pid);
        /** Reads the opened process image path. */
        boolean QueryFullProcessImageNameW(Pointer process, int flags, char[] path, int[] length);
        /** Rejects a process that exited during inspection. */
        boolean GetExitCodeProcess(Pointer process, int[] exitCode);
        /** Waits only within the shared admission deadline. */
        boolean WaitNamedPipeW(WString name, int timeout);
        /** Creates the event owned by one overlapped operation. */
        Pointer CreateEventW(Pointer security, boolean manualReset, boolean initialState, WString name);
        /** Starts a bounded native read. */
        boolean ReadFile(Pointer file, Pointer buffer, int count, int[] transferred, Pointer overlapped);
        /** Starts a bounded native write. */
        boolean WriteFile(Pointer file, Pointer buffer, int count, int[] transferred, Pointer overlapped);
        /** Waits for overlapped completion within the remaining budget. */
        int WaitForSingleObject(Pointer handle, int timeout);
        /** Obtains completion before releasing native buffers. */
        boolean GetOverlappedResult(Pointer file, Pointer overlapped, int[] transferred, boolean wait);
        /** Cancels pending I/O when its deadline expires. */
        boolean CancelIoEx(Pointer file, Pointer overlapped);
        /** Releases the native handle. */
        boolean CloseHandle(Pointer handle);
    }
}
