package com.alibabacloud.rum.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

public class LongTaskEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_LongTask_CommitsOnlyMessageAndSnapshots() {
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            assertTrue("Watchdog events must use their public type", event instanceof LongTaskEvent);
            LongTaskEvent longTask = (LongTaskEvent) event;
            assertEquals("longTask", longTask.getEventType());
            assertEquals("main_thread", longTask.getMessage());
            assertEquals("{\"old\":true}", longTask.getSnapshots());
            assertTrue("Message must be editable", longTask.setMessage("checkout blocked"));
            assertTrue("Snapshots replace the entire string", longTask.setSnapshots("{\"new\":true}"));
            return event;
        }));
        Map<String, Object> original = longTaskAttributes();
        Map<String, Object> expected = longTaskAttributes();
        expected.put("longtask.message", "checkout blocked");
        expected.put("longtask.snapshots", "{\"new\":true}");
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processLongTask(
                original, "longtask.watchdog", null);
        assertEquals("The current candidate must commit", "accepted", result.getOutcome());
        assertEquals("Only the two dedicated fields may change", expected, result.getAttributes());
        assertEquals("The original must remain isolated", longTaskAttributes(), original);
    }

    @Test
    public void testIntercept_MissingFields_AcceptsExactTextAndRejectsNullLocally() {
        String largeText = String.join("", java.util.Collections.nCopies(16385, "plain snapshots "));
        String[] values = {"", " \t\n", "not JSON {", largeText};
        AtomicInteger callbacks = new AtomicInteger();
        for (String value : values) {
            assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                LongTaskEvent longTask = (LongTaskEvent) event;
                assertNull("Missing message stays nullable", longTask.getMessage());
                assertNull("Missing snapshots stay nullable", longTask.getSnapshots());
                assertTrue("Missing message may be supplied verbatim", longTask.setMessage(value));
                assertTrue("Missing snapshots may be supplied verbatim", longTask.setSnapshots(value));
                assertFalse("Null message fails locally", longTask.setMessage(null));
                assertFalse("Null snapshots fail locally", longTask.setSnapshots(null));
                assertEquals("Successful message edit survives null", value, longTask.getMessage());
                assertEquals("Successful snapshots edit survives null", value, longTask.getSnapshots());
                return event;
            }));
            Map<String, Object> original = longTaskAttributes();
            original.remove("longtask.message");
            original.remove("longtask.snapshots");
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processLongTask(
                    original, "longtask.watchdog", null);
            assertEquals("Exact text must commit without content or size validation", "accepted", result.getOutcome());
            assertEquals(value, result.getAttributes().get("longtask.message"));
            assertEquals(value, result.getAttributes().get("longtask.snapshots"));
            assertEquals("Only two approved fields may be added", original.size() + 2, result.getAttributes().size());
            assertFalse("The original must not gain snapshots", original.containsKey("longtask.snapshots"));
        }
        assertEquals("Each snapshot invokes the rule once", values.length, callbacks.get());
    }

    @Test
    public void testIntercept_DropAndCallbackFailure_PreserveTheOriginalInput() {
        AtomicInteger callbacks = new AtomicInteger();
        for (boolean drop : new boolean[]{true, false}) {
            assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                LongTaskEvent longTask = (LongTaskEvent) event;
                assertTrue(longTask.setMessage("must not escape"));
                assertTrue(longTask.setSnapshots("must not escape"));
                if (drop) {
                    return null;
                }
                throw new AssertionError("host callback failure");
            }));
            Map<String, Object> original = longTaskAttributes();
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processLongTask(
                    original, "longtask.watchdog", null);
            assertEquals("Null drops; catchable failures fall back", drop ? "dropped" : "fallback",
                    result.getOutcome());
            if (drop) {
                assertNull("Dropped output has no exportable attributes", result.getAttributes());
            } else {
                assertEquals("No partial edit escapes callback failure", longTaskAttributes(), result.getAttributes());
            }
            assertEquals("Output decisions must not mutate the input", longTaskAttributes(), original);
        }
        assertEquals("Each snapshot invokes once, including drop and fallback", 2, callbacks.get());
    }

    @Test
    public void testIntercept_RetainedWrapper_CannotEditFinalOrReplaceAnotherCandidate() {
        AtomicReference<LongTaskEvent> retained = new AtomicReference<>();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            LongTaskEvent longTask = (LongTaskEvent) event;
            retained.set(longTask);
            assertTrue(longTask.setMessage("accepted message"));
            assertTrue(longTask.setSnapshots("accepted snapshots"));
            assertThrows("Read access must not become a write channel", UnsupportedOperationException.class,
                    () -> event.getAttributes().put("longtask.stack", "rewritten"));
            return event;
        }));
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processLongTask(
                longTaskAttributes(), "longtask.watchdog", null);
        assertEquals("The current wrapper must commit", "accepted", first.getOutcome());
        assertFalse("Late message edits fail", retained.get().setMessage("too late"));
        assertFalse("Late snapshots edits fail", retained.get().setSnapshots("too late"));
        assertThrows("Final attributes are immutable", UnsupportedOperationException.class,
                () -> first.getAttributes().put("longtask.message", "rewritten"));
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((LongTaskEvent) event).setMessage("must roll back");
            ((LongTaskEvent) event).setSnapshots("must roll back");
            return retained.get();
        }));
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processLongTask(
                longTaskAttributes(), "longtask.watchdog", null);
        assertEquals("Another wrapper is an invalid final candidate", "fallback", second.getOutcome());
        assertEquals("Invalid returns restore all original attributes", longTaskAttributes(), second.getAttributes());
        assertEquals("Later snapshots cannot pollute a previous final", "accepted message",
                first.getAttributes().get("longtask.message"));
        assertEquals("Retained wrappers cannot pollute final snapshots", "accepted snapshots",
                first.getAttributes().get("longtask.snapshots"));
    }

    @Test
    public void testIntercept_Diagnostics_BorrowsOnCurrentThreadAndExpiresAfterReturn() {
        Object diagnostic = new Object();
        Object[] threadBeans = new Object[]{new Object()};
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("longtaskInfo", diagnostic);
        nativeObjects.put("threadBeans", threadBeans);
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread processingThread = Thread.currentThread();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            assertEquals("The actual source identifies watchdog processing", "longtask.watchdog", context.getSource());
            assertSame("The rule stays on its current processing thread", processingThread, Thread.currentThread());
            assertSame("Diagnostic access borrows the original", diagnostic,
                    context.getNativeObject("longtaskInfo", Object.class));
            assertSame("Thread diagnostics are borrowed without copying", threadBeans,
                    context.getNativeObject("threadBeans", Object[].class));
            assertNull("Wrong requested types remain nullable",
                    context.getNativeObject("longtaskInfo", Throwable.class));
            assertNull("LongTask does not imply exception parameters",
                    context.getNativeObject("originalParameters", Map.class));
            return event;
        }));
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processLongTask(
                longTaskAttributes(), "longtask.watchdog", nativeObjects);
        assertEquals("The diagnostic wrapper does not alter output", "accepted", result.getOutcome());
        assertEquals("One snapshot invokes the rule once", 1, callbacks.get());
        assertEquals("Passthrough preserves every original field", longTaskAttributes(), result.getAttributes());
        assertNull("Diagnostic access expires at callback return",
                retained.get().getNativeObject("longtaskInfo", Object.class));
        assertNull("Thread diagnostic access expires at callback return",
                retained.get().getNativeObject("threadBeans", Object[].class));
        assertFalse("Native diagnostic objects are not event attributes",
                result.getAttributes().containsKey("longtaskInfo"));
        assertFalse("Native thread diagnostic arrays are not exported",
                result.getAttributes().containsKey("threadBeans"));
    }

    private Map<String, Object> longTaskAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "longTask");
        attributes.put("longtask.type", "main_thread");
        attributes.put("longtask.message", "main_thread");
        attributes.put("longtask.source", "EVENT");
        attributes.put("longtask.snapshots", "{\"old\":true}");
        attributes.put("longtask.stack", "original stack");
        attributes.put("timestamp", 123456789L);
        attributes.put("view.id", "view-id");
        attributes.put("view.name", "checkout");
        attributes.put("session.id", "session-id");
        return attributes;
    }
}
