package com.alibabacloud.rum.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Public wrapper behavior at the Replay metadata processing seam; no binary upload is simulated here. */
public class ReplayViewEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ReplayMetadata_BorrowsRecordOnlyDuringCurrentThreadCallback() {
        Map<String, Object> record = Collections.unmodifiableMap(replayRecord());
        Map<String, Object> objects = Collections.singletonMap("recoveredRecord", record);
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread processingThread = Thread.currentThread();
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            assertTrue("Replay metadata uses the existing public View type", event instanceof ViewEvent);
            assertEquals("Replay does not introduce another wire type", "view", event.getEventType());
            assertEquals("Replay subtype remains readable", "replay", event.getAttribute("view.type"));
            assertEquals("The context identifies the upload completion safe point", "view.replay", context.getSource());
            assertSame("The callback remains synchronous on the current thread",
                    processingThread, Thread.currentThread());
            Map<?, ?> borrowed = context.getNativeObject("recoveredRecord", Map.class);
            assertSame("The context borrows metadata without inventing a UI or network object", record, borrowed);
            assertEquals("The record retains its original page name", "checkout", borrowed.get("view.name"));
            assertEquals("The record retains its records timestamp", 123456789L, borrowed.get("timestamp"));
            assertThrows("Borrowed metadata cannot become an event write channel", UnsupportedOperationException.class,
                    borrowed::clear);
            assertNull("The original Activity is not retained", context.getNativeObject("activity", Object.class));
            assertNull("A binary request is not exposed", context.getNativeObject("request", Object.class));
            assertNull("A binary response is not exposed", context.getNativeObject("response", Object.class));
            assertNull("A binary body is not exposed", context.getNativeObject("body", Object.class));
            assertNull("There are no synthetic callback parameters",
                    context.getNativeObject("originalParameters", Map.class));
            assertNull("Mismatched requested types stay nullable",
                    context.getNativeObject("recoveredRecord", Throwable.class));
            ViewEvent view = (ViewEvent) event;
            assertTrue("Replay name uses the same dedicated setter", view.setName("checkout.replay"));
            assertTrue("Replay snapshots do not require a provider", view.setSnapshots("plain replay text"));
            assertEquals("The borrowed original metadata does not follow candidate edits",
                    "checkout", borrowed.get("view.name"));
            return event;
        }));
        Map<String, Object> original = replayAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processView(
                original, "view.replay", objects);
        assertEquals("Replay must reach the public callback with its borrowed metadata",
                "accepted", result.getOutcome());
        assertEquals("Each metadata snapshot invokes the callback once", 1, callbacks.get());
        Map<String, Object> expected = replayAttributes();
        expected.put("view.name", "checkout.replay");
        expected.put("view.snapshots", "plain replay text");
        assertEquals("Only name and snapshots may change", expected, result.getAttributes());
        assertEquals("The original event must remain isolated", replayAttributes(), original);
        assertNotNull("Context access was exercised", retained.get());
        assertNull("Borrowed record access expires at callback return",
                retained.get().getNativeObject("recoveredRecord", Map.class));
        assertFalse("The borrowed record is not an exported attribute",
                result.getAttributes().containsKey("recoveredRecord"));
    }

    @Test
    public void testIntercept_ReplaySnapshots_AcceptsExactTextAndRejectsNullLocally() {
        String largeText = String.join("", Collections.nCopies(16385, "plain snapshots "));
        String[] values = {"", " \t\n", "not JSON {", largeText};
        AtomicInteger callbacks = new AtomicInteger();
        for (String value : values) {
            assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                assertTrue("Replay metadata keeps its public View wrapper", event instanceof ViewEvent);
                ViewEvent replay = (ViewEvent) event;
                assertNull("A missing snapshots field stays nullable", replay.getSnapshots());
                assertTrue("Missing snapshots may be supplied verbatim", replay.setSnapshots(value));
                assertFalse("Null snapshots must fail locally rather than delete", replay.setSnapshots(null));
                assertEquals("A failed setter preserves the accepted string", value, replay.getSnapshots());
                return event;
            }));
            Map<String, Object> original = replayAttributes();
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processView(
                    original, "view.replay", null);
            assertEquals("Exact text commits without a Replay-only content or size gate",
                    "accepted", result.getOutcome());
            Map<String, Object> expected = replayAttributes();
            expected.put("view.snapshots", value);
            assertEquals("All records associations and protected fields remain unchanged",
                    expected, result.getAttributes());
            assertEquals("The source metadata must not gain candidate fields", replayAttributes(), original);
            assertTrue("Empty and whitespace are still present values",
                    result.getAttributes().containsKey("view.snapshots"));
        }
        assertEquals("Each independent metadata snapshot invokes once", values.length, callbacks.get());
    }

    @Test
    public void testIntercept_ReplayInvalidReturns_KeepUnmodifiedMetadataOnce() {
        AtomicReference<ViewEvent> previous = new AtomicReference<>();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            previous.set((ViewEvent) event);
            return event;
        }));
        assertEquals("Prepare an earlier public wrapper without constructing an event",
                "accepted", EventInterceptorRegistry.processView(
                        replayAttributes(), "view.replay", null).getOutcome());

        AtomicInteger callbacks = new AtomicInteger();
        for (String mode : new String[]{"null", "throw", "other-candidate"}) {
            assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
                callbacks.incrementAndGet();
                ViewEvent replay = (ViewEvent) event;
                assertTrue("Candidate name edits may succeed before fallback", replay.setName("must roll back"));
                assertTrue("Candidate snapshots edits may succeed before fallback",
                        replay.setSnapshots("must roll back"));
                if ("throw".equals(mode)) throw new AssertionError("host callback failure");
                return "null".equals(mode) ? null : previous.get();
            }));
            Map<String, Object> original = replayAttributes();
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processView(
                    original, "view.replay", null);
            assertEquals("A View cannot be dropped by " + mode, "fallback", result.getOutcome());
            assertNotNull("Fallback must retain exportable Replay metadata for " + mode, result.getAttributes());
            assertEquals("No candidate edits escape " + mode, replayAttributes(), result.getAttributes());
            assertEquals("The raw metadata remains isolated for " + mode, replayAttributes(), original);
        }
        assertEquals("Invalid returns do not repeat the callback", 3, callbacks.get());
    }

    @Test
    public void testIntercept_ReplayRetainedCandidate_CannotChangeFinalMetadata() {
        AtomicReference<ViewEvent> retained = new AtomicReference<>();
        assertTrue("Registration must succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ViewEvent replay = (ViewEvent) event;
            retained.set(replay);
            assertThrows("Read access cannot rewrite records association", UnsupportedOperationException.class,
                    () -> replay.getAttributes().put("view.records_end", 0L));
            assertTrue("The current candidate may rename this metadata", replay.setName("accepted"));
            assertTrue("The current candidate may replace snapshots", replay.setSnapshots("accepted snapshots"));
            assertFalse("Null name fails without discarding accepted snapshots", replay.setName(null));
            return event;
        }));
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processView(
                replayAttributes(), "view.replay", null);
        assertEquals("The current candidate commits", "accepted", result.getOutcome());
        assertFalse("Late name edits must fail", retained.get().setName("too late"));
        assertFalse("Late snapshots edits must fail", retained.get().setSnapshots("too late"));
        assertThrows("Final metadata is immutable", UnsupportedOperationException.class,
                () -> result.getAttributes().put("view.records_count", 0));
        Map<String, Object> expected = replayAttributes();
        expected.put("view.name", "accepted");
        expected.put("view.snapshots", "accepted snapshots");
        assertEquals("Retained candidates cannot alter final identity, records or output", expected,
                result.getAttributes());
    }

    private Map<String, Object> replayRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", 123456789L);
        record.put("view.records_end", 123456999L);
        record.put("view.records_count", 7);
        record.put("view.has_full_snapshot", 1);
        record.put("session.id", "recorded-session");
        record.put("view.id", "recorded-view");
        record.put("view.name", "checkout");
        return record;
    }

    private Map<String, Object> replayAttributes() {
        Map<String, Object> attributes = replayRecord();
        attributes.put("event_type", "view");
        attributes.put("event_id", "metadata-id");
        attributes.put("view.type", "replay");
        return attributes;
    }
}
