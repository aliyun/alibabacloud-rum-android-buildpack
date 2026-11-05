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

/** Public-wrapper behavior at the existing recovered-Exception processing seam. */
public class JavaFatalRecoveryEventApiTest {
    private static final String SOURCE = "exception.java.recovered";

    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_JavaFatalRecord_BorrowsExistingRecordOnlyDuringSynchronousCallback() {
        Map<String, Object> record = recoveredRecord();
        Map<String, Object> nativeObjects = Collections.singletonMap("recoveredRecord", record);
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread processingThread = Thread.currentThread();
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            assertTrue("Recovery must expose the public Exception type", event instanceof ExceptionEvent);
            assertEquals("The source must identify the safe recovery point", SOURCE, context.getSource());
            assertSame("Recovery is synchronous on the current processing thread", processingThread,
                    Thread.currentThread());
            Map<?, ?> borrowed = context.getNativeObject("recoveredRecord", Map.class);
            assertSame("Borrow the record already present at the recovery point", record, borrowed);
            assertEquals("Read the original crash attribution", "java.lang.IllegalStateException",
                    borrowed.get("exceptionType"));
            assertEquals("Read the original crash message", "checkout failed", borrowed.get("exceptionMessage"));
            assertThrows("The recovery record is read-only", UnsupportedOperationException.class, borrowed::clear);
            assertNull("The previous process has no live Throwable",
                    context.getNativeObject("exception", Throwable.class));
            assertNull("Recovery does not fabricate original call arguments",
                    context.getNativeObject("originalParameters", Map.class));
            assertNull("A mismatched record type must remain nullable",
                    context.getNativeObject("recoveredRecord", Throwable.class));
            ExceptionEvent exception = (ExceptionEvent) event;
            assertTrue("Recovery supports the existing dedicated message edit", exception.setMessage("payment failed"));
            assertTrue("Recovery supports adding missing snapshots", exception.setSnapshots("not JSON"));
            assertFalse("Null snapshots fail without deleting a previous edit", exception.setSnapshots(null));
            assertEquals("The successful edit survives a local failure", "not JSON", exception.getSnapshots());
            return event;
        }));

        Map<String, Object> original = exceptionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                original, SOURCE, nativeObjects);

        assertEquals("The current candidate must commit at the recovery seam", "accepted", result.getOutcome());
        assertEquals("Exactly one callback may process this snapshot", 1, callbacks.get());
        Map<String, Object> expected = exceptionAttributes();
        expected.put("exception.message", "payment failed");
        expected.put("exception.snapshots", "not JSON");
        assertEquals("Identity, timing, associations and read-only crash fields stay unchanged",
                expected, result.getAttributes());
        assertEquals("The recovery baseline remains isolated", exceptionAttributes(), original);
        assertEquals("Candidate edits cannot change the original diagnostic record", "checkout failed",
                record.get("exceptionMessage"));
        assertNull("The record borrow expires when the callback ends",
                retained.get().getNativeObject("recoveredRecord", Map.class));
        assertFalse("The native recovery record must not become a wire field",
                result.getAttributes().containsKey("recoveredRecord"));
    }

    @Test
    public void testIntercept_PreparedRecovery_UsesCurrentReplacementAndExplicitClear() {
        Map<String, Object> nativeObjects = Collections.singletonMap("recoveredRecord", recoveredRecord());
        AtomicInteger oldCallbacks = new AtomicInteger();
        AtomicInteger currentCallbacks = new AtomicInteger();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            oldCallbacks.incrementAndGet();
            return event;
        }));
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            currentCallbacks.incrementAndGet();
            ((ExceptionEvent) event).setName("current recovery rule");
            return event;
        }));

        EventInterceptorRegistry.ProcessingResult replaced = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);
        assertEquals("Recovery must use the current registration", "accepted", replaced.getOutcome());
        assertEquals("Preparing a recovery record must not bind the old callback", 0, oldCallbacks.get());
        assertEquals("Only the replacement may run", 1, currentCallbacks.get());
        assertEquals("Only the current rule may edit the result", "current recovery rule",
                replaced.getAttributes().get("exception.name"));

        assertTrue("Null explicitly clears registration", AlibabaCloudRum.setEventInterceptor(null));
        EventInterceptorRegistry.ProcessingResult cleared = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);
        assertEquals("Clearing restores the default recovery output", "unregistered", cleared.getOutcome());
        assertEquals("A cleared rule must not be invoked", 1, currentCallbacks.get());
        assertEquals("Unregistered recovery keeps all original fields", exceptionAttributes(), cleared.getAttributes());
    }

    @Test
    public void testIntercept_RecoveryDrop_DoesNotPolluteTheNextRetainedSnapshot() {
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<EventContext> droppedContext = new AtomicReference<>();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            droppedContext.set(context);
            ((ExceptionEvent) event).setName("discarded edit");
            ((ExceptionEvent) event).setSnapshots("discarded snapshots");
            return null;
        });
        Map<String, Object> original = exceptionAttributes();
        Map<String, Object> nativeObjects = Collections.singletonMap("recoveredRecord", recoveredRecord());
        EventInterceptorRegistry.ProcessingResult dropped = EventInterceptorRegistry.processException(
                original, SOURCE, nativeObjects);
        assertEquals("Null is an intentional terminal decision", "dropped", dropped.getOutcome());
        assertNull("A drop must supply no attributes to export", dropped.getAttributes());
        assertEquals("The drop calls the public rule once", 1, callbacks.get());
        assertEquals("Drop must preserve the raw recovery input", exceptionAttributes(), original);
        assertNull("Drop also releases the recovery record borrow",
                droppedContext.get().getNativeObject("recoveredRecord", Map.class));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            return event;
        });
        EventInterceptorRegistry.ProcessingResult retained = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, nativeObjects);
        assertEquals("A later independent snapshot can still be retained", "accepted", retained.getOutcome());
        assertEquals("The retained control proves processing continues", 2, callbacks.get());
        assertEquals("The discarded candidate must not leak into the retained control",
                exceptionAttributes(), retained.getAttributes());
    }

    @Test
    public void testIntercept_RecoveryCallbackThrows_RollsBackAndReleasesTheRecord() {
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            ExceptionEvent exception = (ExceptionEvent) event;
            exception.setName("must rollback");
            exception.setMessage("must rollback");
            exception.setCausedBy("must rollback");
            exception.setSnapshots("must rollback");
            throw new AssertionError("recovery rule failure");
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
        assertEquals("A catchable public callback error must be isolated", "fallback", result.getOutcome());
        assertEquals("Failure must not trigger a second callback", 1, callbacks.get());
        assertEquals("All candidate edits must roll back together", exceptionAttributes(), result.getAttributes());
        assertNull("Failure must release the record borrow",
                retained.get().getNativeObject("recoveredRecord", Map.class));
    }

    @Test
    public void testIntercept_RecoveryCandidate_CannotRewriteFinalOrReplaceAnotherSnapshot() {
        AtomicReference<ExceptionEvent> retained = new AtomicReference<>();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained.set((ExceptionEvent) event);
            retained.get().setMessage("accepted message");
            retained.get().setSnapshots("accepted snapshots");
            return event;
        });
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
        assertEquals("The current recovery candidate can commit", "accepted", first.getOutcome());
        assertFalse("Late message edits must fail", retained.get().setMessage("too late"));
        assertFalse("Late snapshots edits must fail", retained.get().setSnapshots("too late"));
        assertThrows("The final attributes must be read-only", UnsupportedOperationException.class,
                () -> first.getAttributes().put("exception.message", "too late"));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((ExceptionEvent) event).setMessage("must rollback");
            return retained.get();
        });
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processException(
                exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
        assertEquals("A prior wrapper is not the current candidate", "fallback", second.getOutcome());
        assertEquals("An invalid return preserves the current original", exceptionAttributes(), second.getAttributes());
        assertEquals("A different snapshot cannot contaminate an earlier final", "accepted message",
                first.getAttributes().get("exception.message"));
        assertEquals("Final snapshots cannot be mutated through a retained candidate", "accepted snapshots",
                first.getAttributes().get("exception.snapshots"));
    }

    @Test
    public void testIntercept_RecoverySnapshots_AcceptsExactTextAndRejectsNullLocally() {
        String largeText = String.join("", Collections.nCopies(16385, "plain snapshots "));
        AtomicInteger callbacks = new AtomicInteger();
        for (String value : new String[]{"", " \t\n", "not JSON {", largeText}) {
            AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                ExceptionEvent exception = (ExceptionEvent) event;
                assertNull("Recovery does not need default snapshots", exception.getSnapshots());
                assertTrue("Missing snapshots may be supplied verbatim", exception.setSnapshots(value));
                assertFalse("Null is not deletion", exception.setSnapshots(null));
                assertEquals("A local failure preserves the accepted edit", value, exception.getSnapshots());
                return event;
            });
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                    exceptionAttributes(), SOURCE, Collections.singletonMap("recoveredRecord", recoveredRecord()));
            assertEquals("No interceptor-specific content or capacity gate is introduced", "accepted",
                    result.getOutcome());
            assertEquals("The exact snapshots string must survive finalization at this seam", value,
                    result.getAttributes().get("exception.snapshots"));
        }
        assertEquals("Each independent snapshot runs exactly once", 4, callbacks.get());
    }

    private Map<String, Object> recoveredRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("exceptionType", "java.lang.IllegalStateException");
        record.put("exceptionMessage", "checkout failed");
        record.put("sessionId", "previous-session-01");
        record.put("viewId", "previous-view");
        record.put("viewName", "checkout");
        record.put("timestamp", 1720000000123L);
        record.put("threadId", 42L);
        record.put("rumSampled", true);
        return Collections.unmodifiableMap(record);
    }

    private Map<String, Object> exceptionAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "exception");
        attributes.put("event_id", "recovered-java-event");
        attributes.put("exception.type", "crash");
        attributes.put("exception.sub_type", "java");
        attributes.put("exception.name", "java.lang.IllegalStateException");
        attributes.put("exception.message", "checkout failed");
        attributes.put("exception.caused_by", "checkout failed");
        attributes.put("exception.stack", "original crash stack");
        attributes.put("thread.id", 42L);
        attributes.put("view.id", "previous-view");
        attributes.put("view.name", "checkout");
        attributes.put("session.id", "previous-session-01");
        attributes.put("timestamp", 1720000000123L);
        return attributes;
    }
}
