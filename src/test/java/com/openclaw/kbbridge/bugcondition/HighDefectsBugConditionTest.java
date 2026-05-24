package com.openclaw.kbbridge.bugcondition;

import com.openclaw.kbbridge.client.MinioStorageClient;
import com.openclaw.kbbridge.config.AsyncConfig;
import com.openclaw.kbbridge.config.KbProperties;
import com.openclaw.kbbridge.exception.ErrorResponse;
import com.openclaw.kbbridge.exception.GlobalExceptionHandler;
import com.openclaw.kbbridge.router.LlmRouteStrategy;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import net.jqwik.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bug Condition Exploration Tests for High Priority Defects (5, 6, 7, 9).
 * <p>
 * These tests encode the EXPECTED (correct) behavior. On unfixed code, they are
 * EXPECTED TO FAIL, confirming the bugs exist.
 * </p>
 *
 * <b>Validates: Requirements 1.5, 1.6, 1.7, 1.9</b>
 */
class HighDefectsBugConditionTest {

    // ── Defect 5: MinIO InputStream Leak ──

    /**
     * Defect 5 - MinIO InputStream Leak:
     * Test that MinioStorageClient.getObject() closes the GetObjectResponse
     * InputStream after reading.
     * <p>
     * On unfixed code, the GetObjectResponse is never closed after readAllBytes(),
     * leaking an HTTP connection on every call.
     * Expected counterexample: verify(response).close() fails because close() was
     * never called.
     * </p>
     *
     * <b>Validates: Requirements 2.5</b>
     */
    @Example
    void getObjectShouldCloseInputStreamAfterReading() throws Exception {
        // Create a mock MinioClient
        MinioClient minioClient = mock(MinioClient.class);
        KbProperties kbProperties = createKbProperties();

        // Create a spy GetObjectResponse that wraps real content bytes
        byte[] contentBytes = "test content from minio".getBytes(StandardCharsets.UTF_8);

        // GetObjectResponse extends FilterInputStream, so we need to create a real-ish
        // one
        // and spy on it. GetObjectResponse constructor requires Headers and specific
        // params.
        // We'll use a different approach: create a mock GetObjectResponse that behaves
        // like
        // a real InputStream.
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.readAllBytes()).thenReturn(contentBytes);

        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        MinioStorageClient storageClient = new MinioStorageClient(minioClient, kbProperties);

        // Call getObject
        String result = storageClient.getObject("test-bucket", "test-key");

        // Verify the content was read correctly
        assertEquals("test content from minio", result);

        // On FIXED code: close() should have been called on the response
        // (try-with-resources)
        // On UNFIXED code: close() is never called, so this verification fails
        verify(mockResponse).close();
    }

    // ── Defect 6: Wrong Jackson Imports ──

    /**
     * Defect 6 - Wrong Jackson Imports:
     * Verify that LlmRouteStrategy uses tools.jackson.databind.ObjectMapper
     * (Jackson 3.x), not com.fasterxml.jackson.databind.ObjectMapper (Jackson 2.x).
     * <p>
     * On unfixed code, the static OBJECT_MAPPER field is a Jackson 2.x instance
     * (com.fasterxml.jackson.databind.ObjectMapper).
     * Expected counterexample: OBJECT_MAPPER is
     * com.fasterxml.jackson.databind.ObjectMapper
     * </p>
     *
     * <b>Validates: Requirements 2.6</b>
     */
    @Example
    void llmRouteStrategyShouldUseJackson3xObjectMapper() throws Exception {
        // Use reflection to check the OBJECT_MAPPER field type in LlmRouteStrategy.
        // On unfixed code, it's com.fasterxml.jackson.databind.ObjectMapper (Jackson
        // 2.x).
        // On fixed code, it should be tools.jackson.databind.ObjectMapper (Jackson 3.x)
        // or injected as an instance field.

        Class<?> clazz = LlmRouteStrategy.class;

        // First, check if there's a static OBJECT_MAPPER field (unfixed code pattern)
        Field objectMapperField = null;
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getName().equals("OBJECT_MAPPER") || field.getName().equals("objectMapper")) {
                objectMapperField = field;
                break;
            }
        }

        assertNotNull(objectMapperField,
                "LlmRouteStrategy should have an ObjectMapper field (OBJECT_MAPPER or objectMapper)");

        // Check the field type - it should be from tools.jackson package (Jackson 3.x)
        String fieldTypeName = objectMapperField.getType().getName();

        // On FIXED code: field type should be tools.jackson.databind.ObjectMapper
        // On UNFIXED code: field type is com.fasterxml.jackson.databind.ObjectMapper
        assertTrue(fieldTypeName.startsWith("tools.jackson"),
                "ObjectMapper field should be from tools.jackson.* (Jackson 3.x), "
                        + "but found: " + fieldTypeName
                        + " (Jackson 2.x com.fasterxml.jackson.* is incorrect)");
    }

    // ── Defect 7: Missing Fallback Exception Handler ──

    /**
     * Defect 7 - Missing Fallback Exception Handler:
     * Throw a RuntimeException (not one of the 5 known types) and verify
     * GlobalExceptionHandler returns JSON ErrorResponse with HTTP 500.
     * <p>
     * On unfixed code, no @ExceptionHandler(Exception.class) exists, so Spring
     * returns an HTML error page instead of JSON ErrorResponse.
     * Expected counterexample: no handler method found for generic Exception.
     * </p>
     *
     * <b>Validates: Requirements 2.7</b>
     */
    @Property(tries = 10)
    void unexpectedExceptionShouldReturnJsonErrorResponseWith500(
            @ForAll("unexpectedExceptions") Exception exception) {

        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // Check if GlobalExceptionHandler has a method that can handle generic
        // Exception
        // On unfixed code, there's no @ExceptionHandler(Exception.class) method,
        // so we need to find and invoke it.
        Method fallbackMethod = null;
        for (Method method : handler.getClass().getDeclaredMethods()) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1 && paramTypes[0] == Exception.class) {
                fallbackMethod = method;
                break;
            }
        }

        // On FIXED code: a fallback handler method for Exception.class should exist
        // On UNFIXED code: no such method exists
        assertNotNull(fallbackMethod,
                "GlobalExceptionHandler should have a fallback @ExceptionHandler(Exception.class) method, "
                        + "but none was found. Unexpected exceptions will get Spring's default HTML error page "
                        + "instead of JSON ErrorResponse. Exception tested: " + exception.getClass().getSimpleName());

        // If the method exists, verify it returns the correct response
        try {
            fallbackMethod.setAccessible(true);
            @SuppressWarnings("unchecked")
            ResponseEntity<ErrorResponse> response = (ResponseEntity<ErrorResponse>) fallbackMethod.invoke(handler,
                    exception);

            assertNotNull(response, "Fallback handler should return a ResponseEntity");
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                    "Fallback handler should return HTTP 500");
            assertNotNull(response.getBody(), "Response body should not be null");
            assertEquals(500, response.getBody().code(),
                    "ErrorResponse code should be 500");
        } catch (Exception e) {
            fail("Fallback handler invocation failed: " + e.getMessage());
        }
    }

    // ── Defect 9: Ungraceful Shutdown ──

    /**
     * Defect 9 - Ungraceful Shutdown:
     * Verify that AsyncConfig.ingestTaskExecutor() creates an executor with
     * waitForTasksToCompleteOnShutdown=true and awaitTerminationSeconds > 0.
     * <p>
     * On unfixed code, neither is set, so in-flight async tasks are interrupted
     * immediately on shutdown.
     * Expected counterexample: waitForTasksToCompleteOnShutdown is false.
     * </p>
     *
     * <b>Validates: Requirements 2.9</b>
     */
    @Example
    void ingestTaskExecutorShouldBeConfiguredForGracefulShutdown() throws Exception {
        KbProperties kbProperties = createKbProperties();
        AsyncConfig asyncConfig = new AsyncConfig();

        Executor executor = asyncConfig.ingestTaskExecutor(kbProperties);

        // The executor should be a ThreadPoolTaskExecutor
        assertInstanceOf(ThreadPoolTaskExecutor.class, executor,
                "ingestTaskExecutor should return a ThreadPoolTaskExecutor");

        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;

        // Use reflection to check the waitForTasksToCompleteOnShutdown field
        // ThreadPoolTaskExecutor inherits from ExecutorConfigurationSupport
        Field waitField = findField(taskExecutor.getClass(), "waitForTasksToCompleteOnShutdown");
        assertNotNull(waitField,
                "Should be able to find waitForTasksToCompleteOnShutdown field");
        waitField.setAccessible(true);
        boolean waitForCompletion = (boolean) waitField.get(taskExecutor);

        // On FIXED code: waitForTasksToCompleteOnShutdown should be true
        // On UNFIXED code: it defaults to false
        assertTrue(waitForCompletion,
                "ingestTaskExecutor should have waitForTasksToCompleteOnShutdown=true "
                        + "for graceful shutdown, but it is false. In-flight async tasks "
                        + "will be interrupted immediately on application shutdown.");

        // Also check awaitTerminationSeconds (or awaitTerminationMillis)
        Field awaitField = findField(taskExecutor.getClass(), "awaitTerminationMillis");
        if (awaitField == null) {
            // Try older field name
            awaitField = findField(taskExecutor.getClass(), "awaitTerminationSeconds");
        }
        assertNotNull(awaitField,
                "Should be able to find awaitTerminationMillis or awaitTerminationSeconds field");
        awaitField.setAccessible(true);
        long awaitValue = ((Number) awaitField.get(taskExecutor)).longValue();

        // On FIXED code: awaitTermination should be > 0
        // On UNFIXED code: it defaults to 0
        assertTrue(awaitValue > 0,
                "ingestTaskExecutor should have awaitTerminationSeconds > 0 "
                        + "for graceful shutdown, but it is " + awaitValue);

        // Clean up the executor
        taskExecutor.shutdown();
    }

    // ── Providers ──

    @Provide
    Arbitrary<Exception> unexpectedExceptions() {
        return Arbitraries.of(
                new RuntimeException("Unexpected runtime error"),
                new IllegalStateException("Illegal state encountered"),
                new IllegalArgumentException("Bad argument"),
                new NullPointerException("Null reference"),
                new UnsupportedOperationException("Not supported"),
                new ArithmeticException("Division by zero"),
                new IndexOutOfBoundsException("Index out of range"),
                new ClassCastException("Invalid cast"),
                new StackOverflowError("Stack overflow") == null
                        ? new RuntimeException("fallback") // avoid actual StackOverflowError
                        : new RuntimeException("Generic unexpected error"),
                new Exception("Checked exception wrapped as runtime"));
    }

    // ── Helper Methods ──

    /**
     * Find a field by name, searching up the class hierarchy.
     */
    private Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private KbProperties createKbProperties() {
        KbProperties props = new KbProperties();

        KbProperties.Ragflow ragflow = new KbProperties.Ragflow();
        ragflow.setBaseUrl("http://localhost:9380");
        ragflow.setApiKey("test-api-key");
        props.setRagflow(ragflow);

        KbProperties.Query query = new KbProperties.Query();
        query.setMaxSources(5);
        query.setMaxContentLength(2000);
        query.setMaxTotalLength(8000);
        props.setQuery(query);

        KbProperties.Ingest ingest = new KbProperties.Ingest();
        ingest.setAsyncPoolSize(4);
        props.setIngest(ingest);

        KbProperties.Minio minio = new KbProperties.Minio();
        minio.setRawBucket("kb-raw");
        minio.setProcessedBucket("kb-processed");
        props.setMinio(minio);

        KbProperties.Processor processor = new KbProperties.Processor();
        processor.setMinQaCount(3);
        props.setProcessor(processor);

        return props;
    }
}
