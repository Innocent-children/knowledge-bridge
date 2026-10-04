package com.openclaw.kbbridge.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

class ResponseStatusExceptionTest {
    @Test void preservesUnifiedApiStatusWithoutLeakingInternalCauses() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.CONFLICT,
                HttpStatus.NOT_FOUND, HttpStatus.GONE}) {
            var response = handler.handleResponseStatusException(new ResponseStatusException(status,
                    "Document unavailable", new IllegalStateException("secret database credentials")));
            assertEquals(status.value(), response.getStatusCode().value());
            assertEquals(status.value(), response.getBody().code());
            assertEquals("Document unavailable", response.getBody().message());
            assertFalse(response.getBody().toString().contains("secret database credentials"));
        }
        var unavailable = handler.handleResponseStatusException(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "internal storage hostname", new RuntimeException("secret")));
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("Service unavailable", unavailable.getBody().message());
    }
}
