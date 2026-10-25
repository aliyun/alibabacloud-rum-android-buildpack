package com.alibabacloud.rum.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class ResourceEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void resourceCandidateCommitsDedicatedFieldsAndAtomicSigned64BitResult() {
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            assertTrue(event instanceof ResourceEvent);
            ResourceEvent resource = (ResourceEvent) event;
            assertEquals("resource", resource.getEventType());
            assertEquals("checkout", resource.getName());
            assertEquals("original message", resource.getMessage());
            assertEquals(Long.valueOf(200), resource.getStatusCode());
            assertEquals(Integer.valueOf(7), resource.getSuccess());
            assertEquals(-1, ResourceSuccess.UNKNOWN.getValue());
            assertEquals(0, ResourceSuccess.FAILED.getValue());
            assertEquals(1, ResourceSuccess.SUCCESS.getValue());
            assertTrue(resource.setName("checkout.confirmed"));
            assertTrue(resource.setMessage(""));
            assertTrue(resource.setSnapshots("not JSON"));
            assertTrue(resource.setResourceResult(Long.MIN_VALUE, ResourceSuccess.FAILED));
            assertFalse(resource.setName(null));
            assertFalse(resource.setMessage(null));
            assertFalse(resource.setSnapshots(null));
            assertFalse(resource.setResourceResult(1001, null));
            assertEquals("checkout.confirmed", resource.getName());
            assertEquals("", resource.getMessage());
            assertEquals("not JSON", resource.getSnapshots());
            assertEquals(Long.valueOf(Long.MIN_VALUE), resource.getStatusCode());
            assertEquals(Integer.valueOf(0), resource.getSuccess());
            return event;
        }));

        Map<String, Object> original = resourceAttributes();
        Map<String, Object> result = EventInterceptorRegistry.processResource(
                original, "resource.manual", null).getAttributes();
        assertEquals("checkout.confirmed", result.get("resource.name"));
        assertEquals("", result.get("resource.message"));
        assertEquals("not JSON", result.get("resource.snapshots"));
        assertEquals(Long.MIN_VALUE, result.get("resource.status_code"));
        assertEquals(0, result.get("resource.success"));
        assertEquals("https://example.invalid/checkout", result.get("resource.url"));
        assertEquals("original-session", result.get("session.id"));
        assertEquals("checkout", original.get("resource.name"));
        assertEquals(200L, original.get("resource.status_code"));
        assertEquals(7, original.get("resource.success"));
    }

    @Test
    public void resultDoesNotInferSuccessFromStatusCodeAndAcceptsLargestSignedValue() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ResourceEvent resource = (ResourceEvent) event;
            if (callbacks.incrementAndGet() == 1) {
                assertTrue(resource.setResourceResult(1001, ResourceSuccess.FAILED));
            } else {
                assertTrue(resource.setResourceResult(Long.MAX_VALUE, ResourceSuccess.SUCCESS));
            }
            return event;
        });
        Map<String, Object> first = EventInterceptorRegistry.processResource(
                resourceAttributes(), "resource.manual", null).getAttributes();
        Map<String, Object> second = EventInterceptorRegistry.processResource(
                resourceAttributes(), "resource.manual", null).getAttributes();
        assertEquals(2, callbacks.get());
        assertEquals(1001L, first.get("resource.status_code"));
        assertEquals(0, first.get("resource.success"));
        assertEquals(Long.MAX_VALUE, second.get("resource.status_code"));
        assertEquals(1, second.get("resource.success"));
    }

    @Test
    public void missingFieldsRemainNullableAndCanBeAddedWithoutNormalizingText() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ResourceEvent resource = (ResourceEvent) event;
            assertNull(resource.getMessage());
            assertNull(resource.getSnapshots());
            assertNull(resource.getStatusCode());
            assertNull(resource.getSuccess());
            assertTrue(resource.setName(""));
            assertTrue(resource.setMessage(" \t"));
            assertTrue(resource.setSnapshots(""));
            assertFalse(resource.setSnapshots(null));
            assertTrue(resource.setResourceResult(-1001, ResourceSuccess.UNKNOWN));
            return event;
        });
        Map<String, Object> original = resourceAttributes();
        original.remove("resource.message");
        original.remove("resource.status_code");
        original.remove("resource.success");
        Map<String, Object> result = EventInterceptorRegistry.processResource(
                original, "resource.manual", null).getAttributes();
        assertEquals(1, callbacks.get());
        assertEquals("", result.get("resource.name"));
        assertEquals(" \t", result.get("resource.message"));
        assertEquals("", result.get("resource.snapshots"));
        assertEquals(-1001L, result.get("resource.status_code"));
        assertEquals(-1, result.get("resource.success"));
    }

    @Test
    public void retainedResourceCandidateCannotModifyFinalOutputOrReplaceAnotherCandidate() {
        ResourceEvent[] retained = new ResourceEvent[1];
        EventContext[] retainedContext = new EventContext[1];
        Object provider = new Object();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("provider", provider);
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = (ResourceEvent) event;
            retainedContext[0] = context;
            assertEquals("resource.manual", context.getSource());
            Map<?, ?> borrowed = context.getNativeObject("originalParameters", Map.class);
            assertNotNull(borrowed);
            assertSame(provider, borrowed.get("provider"));
            assertNull(context.getNativeObject("originalParameters", String.class));
            assertNull(context.getNativeObject("request", Object.class));
            assertNull(context.getNativeObject("response", Object.class));
            assertThrows(UnsupportedOperationException.class,
                    () -> event.getAttributes().put("resource.url", "rewritten"));
            assertTrue(retained[0].setSnapshots("replacement"));
            return event;
        });
        Map<String, Object> original = resourceAttributes();
        original.put("resource.snapshots", "{\"old\":true}");
        Map<String, Object> first = EventInterceptorRegistry.processResource(
                original, "resource.manual", parameters).getAttributes();
        assertEquals("replacement", first.get("resource.snapshots"));
        assertFalse(retained[0].setName("too late"));
        assertFalse(retained[0].setMessage("too late"));
        assertFalse(retained[0].setSnapshots("too late"));
        assertFalse(retained[0].setResourceResult(1, ResourceSuccess.SUCCESS));
        assertNull(retainedContext[0].getNativeObject("originalParameters", Map.class));

        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ResourceEvent) event).setName("must rollback");
            return retained[0];
        });
        Map<String, Object> second = EventInterceptorRegistry.processResource(
                original, "resource.manual", null).getAttributes();
        assertEquals(1, callbacks.get());
        assertEquals("checkout", second.get("resource.name"));
        assertEquals("{\"old\":true}", second.get("resource.snapshots"));
        assertEquals("replacement", first.get("resource.snapshots"));
    }

    @Test
    public void nullDropsResourceButCallbackExceptionRollsBackAllChanges() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            return null;
        });
        assertNull(EventInterceptorRegistry.processResource(
                resourceAttributes(), "resource.manual", null).getAttributes());
        assertEquals(1, callbacks.get());

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ResourceEvent resource = (ResourceEvent) event;
            resource.setName("must rollback");
            resource.setResourceResult(Long.MAX_VALUE, ResourceSuccess.SUCCESS);
            throw new AssertionError("host callback failure");
        });
        Map<String, Object> result = EventInterceptorRegistry.processResource(
                resourceAttributes(), "resource.manual", null).getAttributes();
        assertEquals(2, callbacks.get());
        assertEquals("checkout", result.get("resource.name"));
        assertEquals(200L, result.get("resource.status_code"));
        assertEquals(7, result.get("resource.success"));
    }

    private Map<String, Object> resourceAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "resource");
        attributes.put("resource.name", "checkout");
        attributes.put("resource.message", "original message");
        attributes.put("resource.status_code", 200L);
        attributes.put("resource.success", 7);
        attributes.put("resource.url", "https://example.invalid/checkout");
        attributes.put("session.id", "original-session");
        return attributes;
    }
}
