package com.openclaw.kbbridge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class RequestLimitsTest {
    @Test void limitsAreIndependentAndRecoverInTheNextWindow() {
        var limits=new RequestLimits(mock(BridgeProperties.class));
        assertTrue(limits.allowed("ingest:a",2,1000));assertTrue(limits.allowed("ingest:a",2,2000));assertFalse(limits.allowed("ingest:a",2,3000));
        assertTrue(limits.allowed("query:a",2,3000));assertTrue(limits.allowed("ingest:b",2,3000));assertTrue(limits.allowed("ingest:a",2,61000));
    }
}
