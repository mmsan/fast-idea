package com.mirror.ideafocusbridge;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // The host Logger above remains the fallback when the plugin log cannot be written.
            }
        }
    }

    private static void prune() throws IOException {
        Instant cutoff = Instant.now().minus(RETENTION);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(LOG_DIRECTORY, FILE_PREFIX + "*.log")) {
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
