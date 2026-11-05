package com.alibabacloud.rum.event;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

import org.junit.After;
import org.junit.Test;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Public wrapper behavior; real network completion and body ownership are verified in the SDK and Demo. */
public class OkHttp3EventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testHeaderRule_PublicCandidate_CommitsWithoutChangingHttpResponse() {
        Request request = request();
        Response response = response(request);
        EventContext[] retainedContext = new EventContext[1];
        ResourceEvent[] retainedCandidate = new ResourceEvent[1];
        boolean[] observed = new boolean[2];
        AtomicInteger callbacks = new AtomicInteger();
        Thread caller = Thread.currentThread();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retainedContext[0] = context;
            ResourceEvent resource = (ResourceEvent) event;
            retainedCandidate[0] = resource;
            Request borrowedRequest = context.getNativeObject("request", Request.class);
            Response borrowedResponse = context.getNativeObject("response", Response.class);
            observed[0] = Thread.currentThread() == caller && "resource.okhttp3".equals(context.getSource())
                    && borrowedRequest == request && borrowedResponse == response
                    && context.getNativeObject("request", Response.class) == null
                    && context.getNativeObject("error", IOException.class) == null
                    && context.getNativeObject("originalParameters", Map.class) == null
                    && context.getNativeObject("body", Object.class) == null;
            observed[1] = resource.setName("/orders/:id") && resource.setMessage("business declined")
                    && resource.setSnapshots("plain replacement")
                    && resource.setResourceResult(Long.parseLong(borrowedResponse.header("X-Test-Business-Code")),
                            ResourceSuccess.FAILED)
                    && !resource.setSnapshots(null) && !resource.setResourceResult(200L, null);
            return event;
        }));
        Map<String, Object> original = resourceAttributes();
        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.okhttp3", nativeObjects(request, response, null));
        Map<String, Object> fields = processed.getAttributes();

        assertEquals(1, callbacks.get());
        assertTrue("Request/Response must be borrowed on the current callback thread", observed[0]);
        assertTrue("Public setters commit only valid edits and keep earlier edits on local failure", observed[1]);
        assertEquals("accepted", processed.getOutcome());
        assertEquals("/orders/:id", fields.get("resource.name"));
        assertEquals("business declined", fields.get("resource.message"));
        assertEquals("plain replacement", fields.get("resource.snapshots"));
        assertEquals(1001L, fields.get("resource.status_code"));
        assertEquals(0, fields.get("resource.success"));
        assertEquals("http://example.invalid/orders/123", fields.get("resource.url"));
        assertEquals("original-session", fields.get("session.id"));
        assertEquals(23L, fields.get("resource.duration"));
        assertEquals(200, response.code());
        assertEquals("1001", response.header("X-Test-Business-Code"));
        assertEquals("/orders/123", original.get("resource.name"));
        assertEquals(200L, original.get("resource.status_code"));
        assertFalse(retainedCandidate[0].setName("late mutation"));
        assertFalse(retainedCandidate[0].setSnapshots("late replacement"));
        assertEquals("plain replacement", fields.get("resource.snapshots"));
        assertReleased(retainedContext[0]);
    }

    @Test
    public void testFailureRule_OriginalIOException_FallsBackAndReleasesContext() {
        Request request = request();
        IOException failure = new IOException("controlled network failure");
        EventContext[] retained = new EventContext[1];
        boolean[] observed = new boolean[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = context;
            observed[0] = "resource.okhttp3".equals(context.getSource())
                    && request == context.getNativeObject("request", Request.class)
                    && failure == context.getNativeObject("error", IOException.class)
                    && context.getNativeObject("response", Response.class) == null
                    && context.getNativeObject("originalParameters", Map.class) == null;
            ResourceEvent resource = (ResourceEvent) event;
            resource.setName("must roll back");
            resource.setSnapshots("must roll back");
            resource.setResourceResult(1001L, ResourceSuccess.FAILED);
            throw new IllegalStateException("controlled rule failure");
        }));
        Map<String, Object> original = resourceAttributes();
        original.put("resource.status_code", 0L);
        original.put("resource.success", 0);
        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.okhttp3", nativeObjects(request, null, failure));

        assertTrue("The native failure context must be observed before the intentional fallback", observed[0]);
        assertEquals("fallback", processed.getOutcome());
        assertEquals(1, processed.getCallbackCount());
        assertEquals(original, processed.getAttributes());
        assertReleased(retained[0]);
    }

    @Test
    public void testDrop_PublicRule_IsTerminalAndReleasesNativeReferences() {
        Request request = request();
        EventContext[] retained = new EventContext[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = context;
            ((ResourceEvent) event).setName("discarded modification");
            return null;
        }));
        Map<String, Object> original = resourceAttributes();
        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.okhttp3", nativeObjects(request, response(request), null));

        assertEquals("dropped", processed.getOutcome());
        assertEquals(1, processed.getCallbackCount());
        assertNull(processed.getAttributes());
        assertEquals("/orders/123", original.get("resource.name"));
        assertReleased(retained[0]);
    }

    @Test
    public void testWrongCandidate_PublicWrapper_FallsBackToOriginal() {
        Request request = request();
        ResourceEvent[] earlier = new ResourceEvent[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            earlier[0] = (ResourceEvent) event;
            return event;
        }));
        EventInterceptorRegistry.processResource(resourceAttributes(), "resource.okhttp3",
                nativeObjects(request, response(request), null));
        EventContext[] retained = new EventContext[1];
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = context;
            ((ResourceEvent) event).setName("must roll back");
            return earlier[0];
        }));
        Map<String, Object> original = resourceAttributes();
        EventInterceptorRegistry.ProcessingResult processed = EventInterceptorRegistry.processResource(
                original, "resource.okhttp3", nativeObjects(request, response(request), null));

        assertEquals("fallback", processed.getOutcome());
        assertEquals(1, processed.getCallbackCount());
        assertEquals(original, processed.getAttributes());
        assertReleased(retained[0]);
    }

    private static Request request() {
        return new Request.Builder().url("http://example.invalid/orders/123").build();
    }

    private static Response response(Request request) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Test-Business-Code", "1001").build();
    }

    private static Map<String, Object> nativeObjects(Request request, Response response, IOException error) {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("request", request);
        objects.put("response", response);
        objects.put("error", error);
        return objects;
    }

    private static Map<String, Object> resourceAttributes() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event_type", "resource");
        fields.put("resource.name", "/orders/123");
        fields.put("resource.url", "http://example.invalid/orders/123");
        fields.put("resource.message", "");
        fields.put("resource.status_code", 200L);
        fields.put("resource.success", 1);
        fields.put("resource.duration", 23L);
        fields.put("session.id", "original-session");
        return fields;
    }

    private static void assertReleased(EventContext context) {
        assertNull(context.getNativeObject("request", Request.class));
        assertNull(context.getNativeObject("response", Response.class));
        assertNull(context.getNativeObject("error", IOException.class));
        assertNull(context.getNativeObject("originalParameters", Map.class));
    }
}
