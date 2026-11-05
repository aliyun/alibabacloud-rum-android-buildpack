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

public class ExceptionEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ExceptionFields_CommitsOnlyDedicatedEdits() {
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            assertTrue("Exceptions must use their public type", event instanceof ExceptionEvent);
            ExceptionEvent exception = (ExceptionEvent) event;
            assertEquals("original name", exception.getName());
            assertEquals("original message", exception.getMessage());
            assertEquals("original cause", exception.getCausedBy());
            assertEquals("{\"old\":true}", exception.getSnapshots());
            assertTrue("Name must be editable", exception.setName("checkout failure"));
            assertTrue("Message must be editable independently", exception.setMessage("declined"));
            assertEquals("Changing message must not rewrite cause", "original cause", exception.getCausedBy());
            assertTrue("Cause must be editable independently", exception.setCausedBy("business reason"));
            assertTrue("Snapshots must replace the whole string", exception.setSnapshots("{\"new\":true}"));
            return event;
        }));
        Map<String, Object> original = exceptionAttributes();
        Map<String, Object> expected = exceptionAttributes();
        expected.put("exception.name", "checkout failure");
        expected.put("exception.message", "declined");
        expected.put("exception.caused_by", "business reason");
        expected.put("exception.snapshots", "{\"new\":true}");
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                original, "exception.manual", null);
        assertEquals("The current candidate must commit", "accepted", result.getOutcome());
        assertEquals("Only the four dedicated fields may change", expected, result.getAttributes());
        assertEquals("The original snapshot must remain isolated", exceptionAttributes(), original);
    }

    @Test
    public void testIntercept_MissingFields_AcceptsExactTextAndRejectsNullLocally() {
        String largeText = String.join("", java.util.Collections.nCopies(16385, "plain snapshots "));
        String[] values = {"", " \t\n", "not JSON {", largeText};
        AtomicInteger callbacks = new AtomicInteger();
        for (String value : values) {
            AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                ExceptionEvent exception = (ExceptionEvent) event;
                assertNull("Missing name must stay nullable", exception.getName());
                assertNull("Missing message must stay nullable", exception.getMessage());
                assertNull("Missing cause must stay nullable", exception.getCausedBy());
                assertNull("A producer need not collect snapshots", exception.getSnapshots());
                assertTrue("Missing name may be supplied verbatim", exception.setName(value));
                assertTrue("Missing message may be supplied verbatim", exception.setMessage(value));
                assertTrue("Missing cause may be supplied verbatim", exception.setCausedBy(value));
                assertTrue("Missing snapshots may be supplied verbatim", exception.setSnapshots(value));
                assertFalse("Null name must fail locally", exception.setName(null));
                assertFalse("Null message must fail locally", exception.setMessage(null));
                assertFalse("Null cause must fail locally", exception.setCausedBy(null));
                assertFalse("Null snapshots must fail locally", exception.setSnapshots(null));
                assertEquals("Prior name edit must survive null", value, exception.getName());
                assertEquals("Prior message edit must survive null", value, exception.getMessage());
                assertEquals("Prior cause edit must survive null", value, exception.getCausedBy());
                assertEquals("Prior snapshots edit must survive null", value, exception.getSnapshots());
                return event;
            });
            Map<String, Object> original = exceptionAttributes();
            original.remove("exception.name");
            original.remove("exception.message");
            original.remove("exception.caused_by");
            original.remove("exception.snapshots");
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                    original, "exception.native", null);
            assertEquals("Exact text must commit without content or size validation", "accepted", result.getOutcome());
            assertEquals(value, result.getAttributes().get("exception.name"));
            assertEquals(value, result.getAttributes().get("exception.message"));
            assertEquals(value, result.getAttributes().get("exception.caused_by"));
            assertEquals(value, result.getAttributes().get("exception.snapshots"));
            assertEquals("Only the four approved missing fields may be added", original.size() + 4,
                    result.getAttributes().size());
            assertFalse("The original must not gain snapshots", original.containsKey("exception.snapshots"));
        }
        assertEquals("Each snapshot must invoke the rule exactly once", 4, callbacks.get());
    }

    @Test
    public void testIntercept_ModifyThenDrop_TerminatesOnlyThisOutputOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ExceptionEvent exception = (ExceptionEvent) event;
            exception.setName("discarded");
            exception.setMessage("discarded");
            exception.setCausedBy("discarded");
            exception.setSnapshots("discarded");
            return null;
        });
        Map<String, Object> original = exceptionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                original, "exception.manual", null);
        assertEquals("Null must be an intentional terminal drop", "dropped", result.getOutcome());
        assertNull("A dropped snapshot must produce no output attributes", result.getAttributes());
        assertEquals("Drop must not cause a repeated callback", 1, callbacks.get());
        assertEquals("Business drop must preserve the original exception input", exceptionAttributes(), original);
    }

    @Test
    public void testIntercept_CallbackThrows_RestoresAllOriginalFieldsOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ExceptionEvent exception = (ExceptionEvent) event;
            exception.setName("must rollback");
            exception.setMessage("must rollback");
            exception.setCausedBy("must rollback");
            exception.setSnapshots("must rollback");
            throw new AssertionError("host callback failure");
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                exceptionAttributes(), "exception.native", null);
        assertEquals("A catchable callback error must fall back", "fallback", result.getOutcome());
        assertEquals("Fallback must not call the host rule again", 1, callbacks.get());
        assertEquals("No partial edits may escape on callback failure", exceptionAttributes(), result.getAttributes());
    }

    @Test
    public void testIntercept_RetainedWrapper_CannotEditFinalOrReplaceAnotherCandidate() {
        AtomicReference<ExceptionEvent> retained = new AtomicReference<>();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained.set((ExceptionEvent) event);
            retained.get().setMessage("accepted message");
            retained.get().setSnapshots("accepted snapshots");
            assertThrows("The attribute container must remain read-only", UnsupportedOperationException.class,
                    () -> event.getAttributes().put("thread.id", 1L));
            return event;
        });
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processException(
                exceptionAttributes(), "exception.manual", null);
        assertEquals("The current candidate must commit", "accepted", first.getOutcome());
        assertFalse("Late name edits must fail", retained.get().setName("too late"));
        assertFalse("Late message edits must fail", retained.get().setMessage("too late"));
        assertFalse("Late cause edits must fail", retained.get().setCausedBy("too late"));
        assertFalse("Late snapshots edits must fail", retained.get().setSnapshots("too late"));
        assertThrows("Final attributes must be immutable", UnsupportedOperationException.class,
                () -> first.getAttributes().put("exception.message", "rewritten"));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((ExceptionEvent) event).setMessage("must rollback");
            ((ExceptionEvent) event).setSnapshots("must rollback");
            return retained.get();
        });
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processException(
                exceptionAttributes(), "exception.manual", null);
        assertEquals("A different wrapper must be rejected", "fallback", second.getOutcome());
        assertEquals("Illegal returns must restore the full original", exceptionAttributes(), second.getAttributes());
        assertEquals("A later snapshot must not pollute an earlier final", "accepted message",
                first.getAttributes().get("exception.message"));
        assertEquals("A retained candidate must not pollute final snapshots", "accepted snapshots",
                first.getAttributes().get("exception.snapshots"));
    }

    @Test
    public void testIntercept_ThrowableContext_BorrowsOnlyDuringCurrentThreadCallback() {
        Throwable originalException = new IllegalStateException("original throwable");
        Map<String, Object> parameters = java.util.Collections.singletonMap("exception", originalException);
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        nativeObjects.put("exception", originalException);
        nativeObjects.put("originalParameters", parameters);
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            callbackThread.set(Thread.currentThread());
            assertEquals("The callback must identify its actual source", "exception.manual", context.getSource());
            assertSame("Throwable access must borrow the original object", originalException,
                    context.getNativeObject("exception", Throwable.class));
            Map<?, ?> borrowed = context.getNativeObject("originalParameters", Map.class);
            assertNotNull("The immediate producer supplies original parameters", borrowed);
            assertSame("Parameter and native slot must refer to the same original Throwable",
                    originalException, borrowed.get("exception"));
            assertThrows("The original parameter container must be read-only", UnsupportedOperationException.class,
                    borrowed::clear);
            assertNull("Mismatched requested types remain nullable",
                    context.getNativeObject("exception", String.class));
            assertNull("Exception input does not imply an error slot",
                    context.getNativeObject("error", Throwable.class));
            ((ExceptionEvent) event).setMessage("business message");
            assertEquals("Business edits must not alter original attribution", "original throwable",
                    originalException.getMessage());
            return event;
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                exceptionAttributes(), "exception.manual", nativeObjects);
        assertEquals("The event must still commit", "accepted", result.getOutcome());
        assertEquals("The callback must run exactly once", 1, callbacks.get());
        assertSame("The rule must stay on the current processing thread", Thread.currentThread(), callbackThread.get());
        assertNull("Throwable references must expire when the callback returns",
                retained.get().getNativeObject("exception", Throwable.class));
        assertNull("Original parameters must expire when the callback returns",
                retained.get().getNativeObject("originalParameters", Map.class));
        assertFalse("Raw exceptions must not be exported as fields", result.getAttributes().containsKey("exception"));
        assertFalse("Original parameters must not be exported",
                result.getAttributes().containsKey("originalParameters"));
    }

    @Test
    public void testIntercept_MissingNativeContext_DoesNotSkipEitherSource() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            assertTrue("A missing Throwable must not prevent a typed callback", event instanceof ExceptionEvent);
            assertNull("The SDK must not fabricate Throwable objects",
                    context.getNativeObject("exception", Throwable.class));
            assertNull("Missing original parameters remain nullable",
                    context.getNativeObject("originalParameters", Map.class));
            return event;
        });
        for (String source : new String[]{"exception.manual", "exception.native"}) {
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                    exceptionAttributes(), source, null);
            assertEquals("Missing native context must still process the snapshot", "accepted", result.getOutcome());
            assertEquals("Passthrough must preserve all original fields",
                    exceptionAttributes(), result.getAttributes());
        }
        assertEquals("Both ordinary and manual sources must invoke once", 2, callbacks.get());
    }

    private Map<String, Object> exceptionAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "exception");
        attributes.put("exception.type", "custom");
        attributes.put("exception.name", "original name");
        attributes.put("exception.message", "original message");
        attributes.put("exception.caused_by", "original cause");
        attributes.put("exception.snapshots", "{\"old\":true}");
        attributes.put("exception.stack", "original stack");
        attributes.put("thread.id", 42L);
        attributes.put("view.id", "view-id");
        attributes.put("view.name", "checkout");
        attributes.put("session.id", "session-id");
        return attributes;
    }
}
