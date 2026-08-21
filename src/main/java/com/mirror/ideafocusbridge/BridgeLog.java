package com.mirror.ideafocusbridge;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

final class BridgeLog {
    private static final Logger IDE_LOG = Logger.getInstance(BridgeLog.class);
    private static final String FILE_PREFIX = "fast-idea-bridge-";
    private static final Path LOG_DIRECTORY = Path.of(PathManager.getLogPath(), "fast-idea-bridge");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Duration RETENTION = Duration.ofDays(3);
    private static final long MAX_LOG_FILE_BYTES = 5L * 1024 * 1024;
    private static final int LOG_BACKUP_COUNT = 2;
    private static final Object LOCK = new Object();

    private BridgeLog() {}

    static void info(String message) {
        write("INFO", message, null);
    }

    static void warn(String message, Throwable error) {
        IDE_LOG.warn(message, error);
        write("WARN", message, error);
    }

    static void error(String message, Throwable error) {
        IDE_LOG.error(message, error);
        write("ERROR", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        String text = compact(message);
        if (error != null) text += " (" + error.getClass().getSimpleName() + ": " + compact(error.getMessage()) + ")";
        String line = Instant.now() + " [" + level + "] " + text + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Files.createDirectories(LOG_DIRECTORY);
                prune();
                Path file = LOG_DIRECTORY.resolve(FILE_PREFIX + LocalDate.now().format(DATE_FORMAT) + ".log");
                rotateIfNeeded(file, line);
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The host Logger above remains the fallback when the plugin log cannot be written.
            }
        }
    }

    private static void rotateIfNeeded(Path file, String line) throws IOException {
        if (!Files.exists(file) || Files.size(file) + line.getBytes(StandardCharsets.UTF_8).length <= MAX_LOG_FILE_BYTES) return;
        Path oldest = Path.of(file + "." + LOG_BACKUP_COUNT);
        Files.deleteIfExists(oldest);
        for (int index = LOG_BACKUP_COUNT - 1; index >= 1; index--) {
            Path source = Path.of(file + "." + index);
            if (Files.exists(source)) Files.move(source, Path.of(file + "." + (index + 1)), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(file, Path.of(file + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void prune() throws IOException {
        Instant cutoff = Instant.now().minus(RETENTION);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(LOG_DIRECTORY, FILE_PREFIX + "*.log*")) {
            for (Path file : files) {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
            }
        }
    }

    private static String compact(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.replace('\r', ' ').replace('\n', ' ').trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
