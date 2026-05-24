package com.openclaw.kbbridge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DotenvPropertyLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_missingEnvFile_returnsEmptyProperties() {
        assertTrue(DotenvPropertyLoader.load(tempDir).isEmpty());
    }

    @Test
    void load_existingEnvFile_returnsProperties() throws Exception {
        Files.writeString(tempDir.resolve(".env"), """
                # local secrets
                DB_PASSWORD=test-password
                KB_SHARED_SECRET=test-secret
                RAGFLOW_DATASET_ID=dataset-001
                """);

        Map<String, Object> properties = DotenvPropertyLoader.load(tempDir);

        assertEquals("test-password", properties.get("DB_PASSWORD"));
        assertEquals("test-secret", properties.get("KB_SHARED_SECRET"));
        assertEquals("dataset-001", properties.get("RAGFLOW_DATASET_ID"));
        assertFalse(properties.containsKey("PATH"));
    }
}
