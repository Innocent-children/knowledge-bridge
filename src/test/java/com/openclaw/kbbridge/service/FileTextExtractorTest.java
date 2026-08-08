package com.openclaw.kbbridge.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileTextExtractorTest {
    private final FileTextExtractor extractor = new FileTextExtractor();

    @Test
    void extractsAndNormalizesUtf8Text() {
        assertEquals("hello\nworld", extractor.extract("sample.md",
                "hello\r\nworld\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsEmptyAndUnsupportedFiles() {
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("empty.txt", "  ".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract("sheet.xlsx", new byte[]{1, 2, 3}));
    }
}
