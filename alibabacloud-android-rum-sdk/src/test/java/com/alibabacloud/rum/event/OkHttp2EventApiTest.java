package com.alibabacloud.rum.event;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Verifies the public adapter without adding an optional HTTP dependency to its API. */
public class OkHttp2EventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void nativeResourceBorrowsObjectsAndCommitsThroughPublicWrapper() {
        Object request = new Object();
        Object response = new Object();
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("request", request);
        nativeObjects.put("response", response);
        EventContext[] retained = new EventContext[1];
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained[0] = context;
            assertEquals("resource.okhttp2", context.getSource());
            assertSame(request, context.getNativeObject("request", Object.class));
            assertSame(response, context.getNativeObject("response", Object.class));
            assertNull(context.getNativeObject("request", String.class));
            assertNull(context.getNativeObject("error", IOException.class));
            assertNull(context.getNativeObject("originalParameters", Map.class));
            ResourceEvent resource = (ResourceEvent) event;
            assertTrue(resource.setName("/orders/:id"));
            assertTrue(resource.setResourceResult(1001L, ResourceSuccess.FAILED));
            return resource;
        });

        Map<String, Object> original = new LinkedHashMap<>();
        original.put("event_type", "resource");
        original.put("resource.name", "/orders/123");
        original.put("resource.url", "https://example.invalid/orders/123");
        original.put("resource.status_code", 200L);
        original.put("resource.success", 1);
        Map<String, Object> result = EventInterceptorRegistry.processResource(
                original, "resource.okhttp2", nativeObjects).getAttributes();

        assertEquals(1, callbacks.get());
        assertEquals("/orders/:id", result.get("resource.name"));
        assertEquals(1001L, result.get("resource.status_code"));
        assertEquals(0, result.get("resource.success"));
        assertEquals("https://example.invalid/orders/123", result.get("resource.url"));
        assertEquals("/orders/123", original.get("resource.name"));
        assertNull(retained[0].getNativeObject("request", Object.class));
        assertNull(retained[0].getNativeObject("response", Object.class));
    }

    @Test
    public void failedRequestBorrowsOriginalErrorAndFallbackDoesNotExportNativeObjects() {
        IOException failure = new IOException("controlled network failure");
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("request", new Object());
        nativeObjects.put("error", failure);
        nativeObjects.put("body", "must not become a public slot");
        EventContext[] retained = new EventContext[1];
        boolean[] validNativeContext = new boolean[1];
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained[0] = context;
            validNativeContext[0] = failure == context.getNativeObject("error", IOException.class)
                    && context.getNativeObject("response", Object.class) == null
                    && context.getNativeObject("body", String.class) == null;
            ((ResourceEvent) event).setName("must roll back");
            throw new IllegalStateException("controlled rule failure");
        });
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("event_type", "resource");
        original.put("resource.name", "/orders/123");
        original.put("resource.status_code", 0L);
        original.put("resource.success", 0);

        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.okhttp2", nativeObjects);

        assertEquals(1, callbacks.get());
        assertTrue("The borrowed native context must be checked independently of fallback", validNativeContext[0]);
        assertEquals("fallback", processed.getOutcome());
        assertEquals(original, processed.getAttributes());
        assertNull(retained[0].getNativeObject("error", IOException.class));
        assertNull(retained[0].getNativeObject("request", Object.class));
    }
}
