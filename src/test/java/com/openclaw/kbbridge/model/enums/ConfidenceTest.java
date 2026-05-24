package com.openclaw.kbbridge.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfidenceTest {

    @Test
    void fromScore_highConfidence() {
        assertEquals(Confidence.HIGH, Confidence.fromScore(0.8));
        assertEquals(Confidence.HIGH, Confidence.fromScore(0.95));
        assertEquals(Confidence.HIGH, Confidence.fromScore(1.0));
    }

    @Test
    void fromScore_mediumConfidence() {
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.6));
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.7));
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.79));
    }

    @Test
    void fromScore_lowConfidence() {
        assertEquals(Confidence.LOW, Confidence.fromScore(0.59));
        assertEquals(Confidence.LOW, Confidence.fromScore(0.0));
        assertEquals(Confidence.LOW, Confidence.fromScore(-0.1));
    }

    @Test
    void fromScore_boundaryValues() {
        // Exact boundary at 0.8 → HIGH
        assertEquals(Confidence.HIGH, Confidence.fromScore(0.8));
        // Just below 0.8 → MEDIUM
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.7999));
        // Exact boundary at 0.6 → MEDIUM
        assertEquals(Confidence.MEDIUM, Confidence.fromScore(0.6));
        // Just below 0.6 → LOW
        assertEquals(Confidence.LOW, Confidence.fromScore(0.5999));
    }
}
