package com.openclaw.kbbridge.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads local .env values as Spring default properties.
 * <p>
 * Real environment variables and command line arguments keep higher precedence.
 * Changes to .env are applied on the next application restart.
 */
public final class DotenvPropertyLoader {

    private DotenvPropertyLoader() {
    }

    public static Map<String, Object> load() {
        return load(Path.of("."));
    }

    static Map<String, Object> load(Path directory) {
        Path envFile = directory.resolve(".env");
        if (!Files.exists(envFile)) {
            return Map.of();
        }

        Dotenv dotenv = Dotenv.configure()
                .directory(directory.toString())
                .filename(".env")
                .ignoreIfMissing()
                .ignoreIfMalformed()
                .load();

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : readKeys(envFile)) {
            String value = dotenv.get(key);
            if (value != null) {
                properties.put(key, value);
            }
        }
        return properties;
    }

    private static Iterable<String> readKeys(Path envFile) {
        try (Stream<String> lines = Files.lines(envFile)) {
            return lines.map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .map(DotenvPropertyLoader::extractKey)
                    .filter(key -> !key.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read .env file: " + envFile, e);
        }
    }

    private static String extractKey(String line) {
        int separator = line.indexOf('=');
        return separator < 0 ? "" : line.substring(0, separator).trim();
    }
}
