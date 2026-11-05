package com.alibabacloud.rum.event;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

import static org.junit.Assert.*;

/** Tests the public Resource adapter with platform network types, without an OkHttp dependency. */
public class HttpURLConnectionEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_HttpNativeSlots_CommitAndReleaseBorrowedReferences() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://example.invalid/orders/123").openConnection();
        InputStream input = new ByteArrayInputStream(new byte[]{7, 8, 9});
        OutputStream output = new ByteArrayOutputStream();
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("connection", connection);
        nativeObjects.put("inputStream", input);
        nativeObjects.put("outputStream", output);
        EventContext[] retained = new EventContext[1];
        boolean[] observed = new boolean[1];
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained[0] = context;
            observed[0] = "resource.urlconnection".equals(context.getSource())
                    && connection == context.getNativeObject("connection", HttpURLConnection.class)
                    && input == context.getNativeObject("inputStream", InputStream.class)
                    && output == context.getNativeObject("outputStream", OutputStream.class)
                    && context.getNativeObject("error", Throwable.class) == null
                    && context.getNativeObject("connection", String.class) == null
                    && context.getNativeObject("request", Object.class) == null
                    && context.getNativeObject("response", Object.class) == null
                    && context.getNativeObject("originalParameters", Map.class) == null;
            ResourceEvent resource = (ResourceEvent) event;
            assertTrue(resource.setName("/orders/:id"));
            assertTrue(resource.setResourceResult(1001L, ResourceSuccess.FAILED));
            return event;
        }));
        Map<String, Object> original = resourceAttributes();

        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.urlconnection", nativeObjects);

        assertEquals(1, callbacks.get());
        assertTrue("The public context must expose only the native borrowed slots", observed[0]);
        assertEquals("accepted", processed.getOutcome());
        assertEquals("/orders/:id", processed.getAttributes().get("resource.name"));
        assertEquals(1001L, processed.getAttributes().get("resource.status_code"));
        assertEquals(0, processed.getAttributes().get("resource.success"));
        assertEquals("http://example.invalid/orders/123", processed.getAttributes().get("resource.url"));
        assertEquals("/orders/123", original.get("resource.name"));
        assertEquals(3, input.available());
        assertNull(retained[0].getNativeObject("connection", HttpURLConnection.class));
        assertNull(retained[0].getNativeObject("inputStream", InputStream.class));
        assertNull(retained[0].getNativeObject("outputStream", OutputStream.class));
        assertNull(retained[0].getNativeObject("error", Throwable.class));
    }

    @Test
    public void testIntercept_HttpsFailure_ReleaseOriginalErrorAndRollbackRuleException() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                "https://example.invalid/orders/123").openConnection();
        IOException error = new IOException("controlled network failure");
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("connection", connection);
        nativeObjects.put("error", error);
        nativeObjects.put("body", "must not become a public slot");
        EventContext[] retained = new EventContext[1];
        boolean[] observed = new boolean[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = context;
            observed[0] = "resource.urlconnection".equals(context.getSource())
                    && connection == context.getNativeObject("connection", HttpsURLConnection.class)
                    && connection == context.getNativeObject("connection", HttpURLConnection.class)
                    && error == context.getNativeObject("error", IOException.class)
                    && context.getNativeObject("inputStream", InputStream.class) == null
                    && context.getNativeObject("outputStream", OutputStream.class) == null
                    && context.getNativeObject("request", Object.class) == null
                    && context.getNativeObject("response", Object.class) == null
                    && context.getNativeObject("originalParameters", Map.class) == null
                    && context.getNativeObject("body", String.class) == null;
            ((ResourceEvent) event).setName("must roll back");
            throw new IllegalStateException("controlled rule failure");
        }));
        Map<String, Object> original = resourceAttributes();
        original.put("resource.url", "https://example.invalid/orders/123");
        original.put("resource.status_code", 0L);
        original.put("resource.success", 0);

        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.urlconnection", nativeObjects);

        assertEquals(1, processed.getCallbackCount());
        assertTrue("The failure must retain the original native error only during the callback", observed[0]);
        assertEquals("fallback", processed.getOutcome());
        assertEquals(original, processed.getAttributes());
        assertNull(retained[0].getNativeObject("connection", HttpsURLConnection.class));
        assertNull(retained[0].getNativeObject("error", Throwable.class));
    }

    @Test
    public void testIntercept_MissingNativeObjects_StillAllowsExplicitDrop() {
        EventContext[] retained = new EventContext[1];
        boolean[] observed = new boolean[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = context;
            observed[0] = "resource.urlconnection".equals(context.getSource())
                    && context.getNativeObject("connection", HttpURLConnection.class) == null
                    && context.getNativeObject("inputStream", InputStream.class) == null
                    && context.getNativeObject("outputStream", OutputStream.class) == null
                    && context.getNativeObject("error", Throwable.class) == null;
            ((ResourceEvent) event).setName("must not export");
            return null;
        }));
        Map<String, Object> original = resourceAttributes();

        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.urlconnection", null);

        assertEquals(1, processed.getCallbackCount());
        assertTrue("Missing native objects must not bypass the supported Resource callback", observed[0]);
        assertEquals("dropped", processed.getOutcome());
        assertNull(processed.getAttributes());
        assertEquals("/orders/123", original.get("resource.name"));
        assertNull(retained[0].getNativeObject("connection", HttpURLConnection.class));
    }

    private static Map<String, Object> resourceAttributes() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event_type", "resource");
        result.put("resource.name", "/orders/123");
        result.put("resource.url", "http://example.invalid/orders/123");
        result.put("resource.status_code", 200L);
        result.put("resource.success", 1);
        return result;
    }
}
