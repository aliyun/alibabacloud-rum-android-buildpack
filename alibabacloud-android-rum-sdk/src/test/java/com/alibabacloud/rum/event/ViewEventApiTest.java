package com.alibabacloud.rum.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

public class ViewEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ViewCandidate_CommitsOnlyDedicatedFields() {
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue("View registration should succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            assertTrue("Native View should use the public ViewEvent wrapper", event instanceof ViewEvent);
            ViewEvent view = (ViewEvent) event;
            assertEquals("View type should retain its wire value", "view", view.getEventType());
            assertEquals("Name getter should read the original candidate", "checkout", view.getName());
            assertEquals("Snapshots getter should read the original candidate", "{\"old\":true}", view.getSnapshots());
            assertEquals("Context should identify the native View source", "view.native", context.getSource());
            assertNull("Native View should not invent original parameters",
                    context.getNativeObject("originalParameters", Map.class));
            assertNull("Native View should not retain an Activity", context.getNativeObject("activity", Object.class));
            assertTrue("Name should accept a replacement", view.setName("checkout.confirmed"));
            assertTrue("Snapshots should accept non-JSON text", view.setSnapshots("not JSON"));
            assertFalse("Null name should fail locally", view.setName(null));
            assertFalse("Null snapshots should fail locally", view.setSnapshots(null));
            assertEquals("Failed setter should preserve the accepted name", "checkout.confirmed", view.getName());
            assertEquals("Failed setter should preserve accepted snapshots", "not JSON", view.getSnapshots());
            return event;
        }));

        Map<String, Object> original = viewAttributes();
        Map<String, Object> result = EventInterceptorRegistry.processView(
                original, "view.native", null).getAttributes();
        assertEquals("A View snapshot should invoke the callback once", 1, callbacks.get());
        assertEquals("Explicit return should commit the candidate name", "checkout.confirmed", result.get("view.name"));
        assertEquals("Explicit return should commit candidate snapshots", "not JSON", result.get("view.snapshots"));
        assertEquals("Editing should preserve View identity", "view-id", result.get("view.id"));
        assertEquals("Editing should preserve session association", "original-session", result.get("session.id"));
        assertEquals("Editing should preserve View timing", 120L, result.get("view.duration"));
        assertEquals("Candidate changes must not alter the original name", "checkout", original.get("view.name"));
        assertEquals("Candidate changes must not alter original snapshots",
                "{\"old\":true}", original.get("view.snapshots"));
    }

    @Test
    public void testIntercept_ModifyThenReturnNull_PreservesOriginalViewOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ViewEvent view = (ViewEvent) event;
            assertTrue("Candidate name edit should succeed before null return", view.setName("must rollback"));
            assertTrue("Candidate snapshots edit should succeed before null return",
                    view.setSnapshots("must rollback"));
            return null;
        });

        Map<String, Object> result = EventInterceptorRegistry.processView(
                viewAttributes(), "view.native", null).getAttributes();
        assertEquals("Null return must not trigger a second callback", 1, callbacks.get());
        assertNotNull("View cannot be dropped", result);
        assertEquals("Null return should restore the original name", "checkout", result.get("view.name"));
        assertEquals("Null return should restore original snapshots", "{\"old\":true}", result.get("view.snapshots"));
    }

    @Test
    public void testIntercept_MissingFields_AcceptsEmptyAndWhitespaceWithoutDeletion() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ViewEvent view = (ViewEvent) event;
            assertNull("Missing View name should remain nullable", view.getName());
            assertNull("Missing snapshots should remain nullable", view.getSnapshots());
            assertTrue("An empty name should be accepted", view.setName(""));
            assertTrue("Whitespace snapshots should be accepted verbatim", view.setSnapshots(" \t"));
            assertFalse("Null name should not delete the empty name", view.setName(null));
            assertFalse("Null snapshots should not delete accepted whitespace", view.setSnapshots(null));
            assertEquals("Name getter should retain the accepted empty string", "", view.getName());
            assertEquals("Snapshots getter should retain accepted whitespace", " \t", view.getSnapshots());
            return event;
        });
        Map<String, Object> original = viewAttributes();
        original.remove("view.name");
        original.remove("view.snapshots");
        Map<String, Object> result = EventInterceptorRegistry.processView(
                original, "view.manual", null).getAttributes();
        assertEquals("An internal manual View snapshot should invoke the callback once", 1, callbacks.get());
        assertTrue("An empty name must remain a present field", result.containsKey("view.name"));
        assertTrue("Whitespace snapshots must remain a present field", result.containsKey("view.snapshots"));
        assertEquals("Final name should preserve the empty string", "", result.get("view.name"));
        assertEquals("Final snapshots should preserve whitespace", " \t", result.get("view.snapshots"));
        assertFalse("Adding the candidate name must not change the original", original.containsKey("view.name"));
        assertFalse("Adding candidate snapshots must not change the original", original.containsKey("view.snapshots"));
    }

    @Test
    public void testIntercept_RetainedCandidate_CannotMutateFinalOrReplaceNextCandidate() {
        ViewEvent[] retained = new ViewEvent[1];
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = (ViewEvent) event;
            assertThrows("Attribute containers must not expose a writable View identity",
                    UnsupportedOperationException.class,
                    () -> event.getAttributes().put("view.id", "rewritten"));
            assertTrue("The current candidate should accept a name", retained[0].setName("accepted"));
            assertTrue("The current candidate should accept empty snapshots", retained[0].setSnapshots(""));
            return event;
        });
        Map<String, Object> first = EventInterceptorRegistry.processView(
                viewAttributes(), "view.native", null).getAttributes();
        assertEquals("The first explicit return should commit its name", "accepted", first.get("view.name"));
        assertEquals("The first explicit return should commit empty snapshots", "", first.get("view.snapshots"));
        assertFalse("A retained candidate must reject late name edits", retained[0].setName("too late"));
        assertFalse("A retained candidate must reject late snapshots edits", retained[0].setSnapshots("too late"));

        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ViewEvent) event).setName("must rollback");
            ((ViewEvent) event).setSnapshots("must rollback");
            return retained[0];
        });
        Map<String, Object> second = EventInterceptorRegistry.processView(
                viewAttributes(), "view.native", null).getAttributes();
        assertEquals("Returning another candidate must not repeat the callback", 1, callbacks.get());
        assertEquals("Another candidate should cause original-name fallback", "checkout", second.get("view.name"));
        assertEquals("Another candidate should cause original-snapshots fallback",
                "{\"old\":true}", second.get("view.snapshots"));
        assertEquals("The first final name must remain frozen", "accepted", first.get("view.name"));
        assertEquals("The first final snapshots must remain frozen", "", first.get("view.snapshots"));
    }

    @Test
    public void testIntercept_CallbackThrows_RollsBackAllViewChangesOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ViewEvent view = (ViewEvent) event;
            view.setName("must rollback");
            view.setSnapshots("must rollback");
            throw new AssertionError("host callback failure");
        });
        Map<String, Object> result = EventInterceptorRegistry.processView(
                viewAttributes(), "view.native", null).getAttributes();
        assertEquals("A callback failure must not trigger another callback", 1, callbacks.get());
        assertNotNull("A callback failure must retain the View", result);
        assertEquals("A callback failure should restore the original name", "checkout", result.get("view.name"));
        assertEquals("A callback failure should restore original snapshots",
                "{\"old\":true}", result.get("view.snapshots"));
    }

    private Map<String, Object> viewAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "view");
        attributes.put("view.name", "checkout");
        attributes.put("view.snapshots", "{\"old\":true}");
        attributes.put("view.id", "view-id");
        attributes.put("view.duration", 120L);
        attributes.put("session.id", "original-session");
        return attributes;
    }
}
