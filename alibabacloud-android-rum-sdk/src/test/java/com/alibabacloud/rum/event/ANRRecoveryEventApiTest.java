package com.alibabacloud.rum.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

/** Public binding for an ANR snapshot rebuilt at the next launch's safe recovery point. */
public class ANRRecoveryEventApiTest {
    private static final String SOURCE = "exception.anr.recovered";

    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ANRRecord_BorrowsOriginalDiagnosticAndPreservesIdentity() {
        Map<String, Object> record = recoveredRecord();
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread recoveryThread = Thread.currentThread();
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            assertTrue("ANR recovery uses the existing public Exception type", event instanceof ExceptionEvent);
            assertEquals("The source identifies safe ANR recovery", SOURCE, context.getSource());
            assertSame("The callback stays on the safe processing thread", recoveryThread, Thread.currentThread());
            Map<?, ?> borrowed = context.getNativeObject("recoveredRecord", Map.class);
            assertSame("Borrow the record already present at recovery", record, borrowed);
            assertEquals("The raw ANR name remains available", "ApplicationNotResponding", borrowed.get("name"));
            assertEquals("The raw detector message is independent of edits", "input dispatch timed out",
                    borrowed.get("message"));
            assertEquals("The raw thread dump remains available", "original ANR thread dump",
                    borrowed.get("threadDump"));
            assertThrows("Recovery fields are read-only", UnsupportedOperationException.class, borrowed::clear);
            assertNull("The old process has no live Throwable", context.getNativeObject("exception", Throwable.class));
            assertNull("Recovery does not fabricate API parameters",
                    context.getNativeObject("originalParameters", Map.class));
            assertNull("A mismatched type is absent", context.getNativeObject("recoveredRecord", Throwable.class));
            ExceptionEvent exception = (ExceptionEvent) event;
            assertTrue("Name may be edited", exception.setName("checkout.anr"));
            assertTrue("Message may be edited", exception.setMessage("checkout timeout"));
            assertTrue("Cause may be edited independently", exception.setCausedBy("checkout blocked"));
            assertTrue("Snapshots may be supplied as exact text", exception.setSnapshots("not JSON"));
            assertFalse("Null does not delete accepted snapshots", exception.setSnapshots(null));
            assertEquals("Local failure preserves the edit", "not JSON", exception.getSnapshots());
            return event;
        }));

        Map<String, Object> original = exceptionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                original, SOURCE, Collections.singletonMap("recoveredRecord", record));

        assertEquals("ANR recovery must opt into public processing", "accepted", result.getOutcome());
        assertEquals("One recovered snapshot invokes the rule once", 1, callbacks.get());
        Map<String, Object> expected = exceptionAttributes();
        expected.put("exception.name", "checkout.anr");
        expected.put("exception.message", "checkout timeout");
        expected.put("exception.caused_by", "checkout blocked");
        expected.put("exception.snapshots", "not JSON");
        assertEquals("Identity, associations, ANR type, timing and stack remain protected",
                expected, result.getAttributes());
        assertEquals("The original event remains isolated", exceptionAttributes(), original);
        assertEquals("Business edits preserve the original diagnostic", "input dispatch timed out",
                record.get("message"));
        assertNull("The record borrow expires after the callback",
                retained.get().getNativeObject("recoveredRecord", Map.class));
        assertFalse("The record does not become an exported event field",
                result.getAttributes().containsKey("recoveredRecord"));
    }

    @Test
    public void testIntercept_PreparedANR_UsesCurrentReplacementAndExplicitClear() {
        Map<String, Object> nativeObjects = Collections.singletonMap("recoveredRecord", recoveredRecord());
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicInteger currentCallbacks = new AtomicInteger();
        assertTrue("An initial rule can be registered", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            oldCallbacks.incrementAndGet();
            return event;
        }));
        assertTrue("A prepared record must not prevent replacement",
                AlibabaCloudRum.setEventInterceptor((event, context) -> {
                    currentCallbacks.incrementAndGet();
                    ((ExceptionEvent) event).setMessage("current ANR rule");
                    return event;
                }));

        EventInterceptorRegistry.ProcessingResult replaced = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);

        assertEquals("Only the current rule processes recovery", "accepted", replaced.getOutcome());
        assertEquals("Preparing a record cannot bind the initial rule", 0, oldCallbacks.get());
        assertEquals("The replacement processes one snapshot", 1, currentCallbacks.get());
        assertEquals("The replacement's edit is committed", "current ANR rule",
                replaced.getAttributes().get("exception.message"));

        assertTrue("Null explicitly clears registration", AlibabaCloudRum.setEventInterceptor(null));
        EventInterceptorRegistry.ProcessingResult cleared = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);
        assertEquals("Clear restores the default recovery output", "unregistered", cleared.getOutcome());
        assertEquals("A cleared rule is not called", 1, currentCallbacks.get());
        assertEquals("Clear preserves the original ANR", exceptionAttributes(), cleared.getAttributes());
    }

    @Test
    public void testIntercept_DroppedANR_ReleasesBorrowAndAllowsNextSnapshot() {
        Map<String, Object> nativeObjects = Collections.singletonMap("recoveredRecord", recoveredRecord());
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue("The drop rule is registered", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            ((ExceptionEvent) event).setName("discarded ANR edit");
            return null;
        }));
        Map<String, Object> original = exceptionAttributes();
        EventInterceptorRegistry.ProcessingResult dropped = EventInterceptorRegistry.processException(
                original, SOURCE, nativeObjects);
        assertEquals("ANR is an allowed terminal drop", "dropped", dropped.getOutcome());
        assertNull("No candidate is exported after drop", dropped.getAttributes());
        assertEquals("The discarded candidate cannot alter the input", exceptionAttributes(), original);
        assertNull("Drop releases the record borrow",
                retained.get().getNativeObject("recoveredRecord", Map.class));

        assertTrue("A subsequent rule may retain events", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            return event;
        }));
        EventInterceptorRegistry.ProcessingResult control = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);
        assertEquals("The next snapshot is retained", "accepted", control.getOutcome());
        assertEquals("Each independent snapshot is called once", 2, callbacks.get());
        assertEquals("The discarded candidate does not contaminate a later snapshot",
                exceptionAttributes(), control.getAttributes());
    }

    @Test
    public void testIntercept_ANRRuleThrows_RollsBackAndReleasesBorrow() {
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue("The failing rule is registered", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            ExceptionEvent exception = (ExceptionEvent) event;
            exception.setName("must rollback");
            exception.setMessage("must rollback");
            exception.setCausedBy("must rollback");
            exception.setSnapshots("must rollback");
            throw new AssertionError("intentional ANR rule failure");
        }));

        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));

        assertEquals("A catchable callback failure falls back", "fallback", result.getOutcome());
        assertEquals("Failure does not repeat the callback", 1, callbacks.get());
        assertEquals("All four candidate edits are discarded together", exceptionAttributes(), result.getAttributes());
        assertNull("Failure also releases the record borrow",
                retained.get().getNativeObject("recoveredRecord", Map.class));
    }

    @Test
    public void testIntercept_ANRCandidate_CannotMutateFinalOrReplaceAnotherSnapshot() {
        AtomicReference<ExceptionEvent> retained = new AtomicReference<>();
        assertTrue("The current candidate may be registered", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained.set((ExceptionEvent) event);
            retained.get().setMessage("accepted ANR message");
            retained.get().setSnapshots("accepted ANR snapshots");
            return event;
        }));
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
        assertEquals("The current candidate is accepted", "accepted", first.getOutcome());
        assertFalse("Late edits cannot alter the final", retained.get().setMessage("too late"));
        assertFalse("Late snapshots cannot alter the final", retained.get().setSnapshots("too late"));
        assertThrows("Final attributes are read-only", UnsupportedOperationException.class,
                () -> first.getAttributes().put("exception.message", "too late"));

        assertTrue("A second rule may replace registration", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((ExceptionEvent) event).setMessage("must rollback");
            return retained.get();
        }));
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
        assertEquals("A prior candidate cannot submit a different ANR", "fallback", second.getOutcome());
        assertEquals("An invalid return preserves the new original", exceptionAttributes(), second.getAttributes());
        assertEquals("The previous final remains isolated", "accepted ANR message",
                first.getAttributes().get("exception.message"));
        assertEquals("The previous final snapshots remain isolated", "accepted ANR snapshots",
                first.getAttributes().get("exception.snapshots"));
    }

    private Map<String, Object> recoveredRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", "ApplicationNotResponding");
        record.put("message", "input dispatch timed out");
        record.put("cause", "main thread blocked");
        record.put("threadDump", "original ANR thread dump");
        record.put("timestamp", 1720000000123L);
        record.put("pid", 321);
        record.put("processName", "com.example.checkout");
        record.put("sessionId", "previous-session-01");
        record.put("viewId", "previous-view");
        record.put("viewName", "checkout");
        record.put("viewSampled", true);
        record.put("moduleSampled", true);
        return Collections.unmodifiableMap(record);
    }

    private Map<String, Object> exceptionAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "exception");
        attributes.put("event_id", "recovered-anr-event");
        attributes.put("exception.type", "anr");
        attributes.put("exception.name", "ApplicationNotResponding");
        attributes.put("exception.message", "input dispatch timed out");
        attributes.put("exception.caused_by", "main thread blocked");
        attributes.put("exception.stack", "original ANR thread dump");
        attributes.put("view.id", "previous-view");
        attributes.put("view.name", "checkout");
        attributes.put("session.id", "previous-session-01");
        attributes.put("timestamp", 1720000000123L);
        return attributes;
    }
}
