package com.alibabacloud.rum.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

public class ActionEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ActionCandidate_CommitsDedicatedFieldsAndPreservesIdentity() {
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue("Action registration should succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            assertTrue("Native Action should use the public wrapper", event instanceof ActionEvent);
            ActionEvent action = (ActionEvent) event;
            assertEquals("Wire type should remain action", "action", action.getEventType());
            assertEquals("Name should read the prepared original", "checkout", action.getName());
            assertEquals("Snapshots should read the prepared original", "old snapshots", action.getSnapshots());
            assertEquals("Context should identify the native producer", "action.native", context.getSource());
            assertTrue("Name should accept an explicit replacement", action.setName("checkout.confirmed"));
            assertTrue("Snapshots should accept non-JSON text", action.setSnapshots("not JSON"));
            assertFalse("Null name should fail locally", action.setName(null));
            assertFalse("Null snapshots should fail locally", action.setSnapshots(null));
            assertEquals("Failed name edit should preserve the accepted value", "checkout.confirmed", action.getName());
            assertEquals("Failed snapshots edit should preserve the accepted value", "not JSON", action.getSnapshots());
            assertThrows("Public attributes must not expose writable action classification",
                    UnsupportedOperationException.class, () -> action.getAttributes().put("action.type", "rewritten"));
            return event;
        }));

        Map<String, Object> original = actionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processAction(
                original, "action.native", null);
        assertEquals("Explicitly returning the same wrapper should accept its underlying candidate",
                "accepted", result.getOutcome());
        assertEquals("The snapshot should invoke one callback", 1, callbacks.get());
        assertEquals("Final name should contain the committed replacement",
                "checkout.confirmed", result.getAttributes().get("action.name"));
        assertEquals("Final snapshots should contain the complete replacement",
                "not JSON", result.getAttributes().get("action.snapshots"));
        assertEquals("Action identity should remain read-only", "action-id", result.getAttributes().get("action.id"));
        assertEquals("Action type should remain read-only", "click", result.getAttributes().get("action.type"));
        assertEquals("View association should remain read-only", "view-id", result.getAttributes().get("view.id"));
        assertEquals("Session association should remain read-only",
                "session-id", result.getAttributes().get("session.id"));
        assertEquals("Timing should remain read-only", 120L, result.getAttributes().get("action.duration"));
        assertEquals("Original name must stay isolated", "checkout", original.get("action.name"));
        assertEquals("Original snapshots must stay isolated", "old snapshots", original.get("action.snapshots"));
    }

    @Test
    public void testIntercept_MissingFields_AddsEmptyAndWhitespaceWithoutDeletion() {
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ActionEvent action = (ActionEvent) event;
            assertNull("Missing Action name should be nullable", action.getName());
            assertNull("Missing snapshots should be nullable", action.getSnapshots());
            assertTrue("Empty name should be valid", action.setName(""));
            assertTrue("Whitespace snapshots should be accepted verbatim", action.setSnapshots(" \t"));
            assertFalse("Null name must not delete an existing field", action.setName(null));
            assertFalse("Null snapshots must not delete an existing field", action.setSnapshots(null));
            return event;
        });
        Map<String, Object> original = actionAttributes();
        original.remove("action.name");
        original.remove("action.snapshots");
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processAction(
                original, "action.native", null);
        assertEquals("Empty values should still commit the candidate", "accepted", result.getOutcome());
        assertEquals("Empty name must remain a present string", "", result.getAttributes().get("action.name"));
        assertEquals("Whitespace must remain unmodified", " \t", result.getAttributes().get("action.snapshots"));
        assertFalse("Candidate additions must not affect the original", original.containsKey("action.name"));
        assertFalse("Candidate additions must not affect the original", original.containsKey("action.snapshots"));
    }

    @Test
    public void testIntercept_ModifyThenReturnNull_DropsOnlyTheCurrentSnapshot() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ActionEvent) event).setName("discarded");
            ((ActionEvent) event).setSnapshots("discarded");
            return null;
        });
        Map<String, Object> original = actionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processAction(
                original, "action.native", null);
        assertEquals("Null return should use the existing drop contract", "dropped", result.getOutcome());
        assertNull("Dropped Action should have no output attributes", result.getAttributes());
        assertEquals("Drop should not repeat the callback", 1, callbacks.get());
        assertEquals("Discarded edits must not affect the original", "checkout", original.get("action.name"));
    }

    @Test
    public void testIntercept_CallbackThrows_RollsBackAllCandidateChangesOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ActionEvent) event).setName("must rollback");
            ((ActionEvent) event).setSnapshots("must rollback");
            throw new AssertionError("host callback failure");
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processAction(
                actionAttributes(), "action.native", null);
        assertEquals("Callback failure should use original fallback", "fallback", result.getOutcome());
        assertEquals("Callback failure should not repeat invocation", 1, callbacks.get());
        assertEquals("Name must revert to its unmodified original",
                "checkout", result.getAttributes().get("action.name"));
        assertEquals("Snapshots must revert to their unmodified original",
                "old snapshots", result.getAttributes().get("action.snapshots"));
    }

    @Test
    public void testIntercept_RetainedWrapper_CannotEditFinalOrReplaceAnotherCandidate() {
        ActionEvent[] retained = new ActionEvent[1];
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = (ActionEvent) event;
            retained[0].setName("accepted");
            retained[0].setSnapshots("");
            return event;
        });
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processAction(
                actionAttributes(), "action.native", null);
        assertEquals("The first wrapper should commit", "accepted", first.getOutcome());
        assertFalse("Retained wrappers must reject late name edits", retained[0].setName("too late"));
        assertFalse("Retained wrappers must reject late snapshots edits", retained[0].setSnapshots("too late"));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((ActionEvent) event).setName("must rollback");
            ((ActionEvent) event).setSnapshots("must rollback");
            return retained[0];
        });
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processAction(
                actionAttributes(), "action.native", null);
        assertEquals("Another wrapper must fail the same-candidate identity check", "fallback", second.getOutcome());
        assertEquals("Invalid candidate must restore the original name",
                "checkout", second.getAttributes().get("action.name"));
        assertEquals("Invalid candidate must restore original snapshots",
                "old snapshots", second.getAttributes().get("action.snapshots"));
        assertEquals("The first final name should stay frozen", "accepted", first.getAttributes().get("action.name"));
        assertEquals("The first final snapshots should stay frozen", "", first.getAttributes().get("action.snapshots"));
    }

    @Test
    public void testIntercept_NativeContext_BorrowsOnCurrentThreadAndReleasesAfterReturn() {
        String[] slots = {"targetView", "dialog", "menuItem", "motionEvent", "keyEvent"};
        Map<String, Object> nativeObjects = new LinkedHashMap<>();
        for (String slot : slots) nativeObjects.put(slot, new Object());
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("actionName", "checkout");
        parameters.put("actionType", "click");
        nativeObjects.put("originalParameters", Collections.unmodifiableMap(parameters));
        EventContext[] retained = new EventContext[1];
        Thread[] callbackThread = new Thread[1];
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbackThread[0] = Thread.currentThread();
            retained[0] = context;
            for (String slot : slots) {
                assertSame("Native objects must be borrowed without conversion",
                        nativeObjects.get(slot), context.getNativeObject(slot, Object.class));
                assertNull("A mismatched native type must be nullable", context.getNativeObject(slot, String.class));
            }
            Map<?, ?> originalParameters = context.getNativeObject("originalParameters", Map.class);
            assertEquals("Scalar original parameters must remain readable", parameters, originalParameters);
            assertNull("Unsupported slots must stay absent", context.getNativeObject("request", Object.class));
            return event;
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processAction(
                actionAttributes(), "action.native", nativeObjects);
        assertEquals("The current-thread callback must finish successfully", "accepted", result.getOutcome());
        assertSame("The adapter must not dispatch to another thread", Thread.currentThread(), callbackThread[0]);
        assertNotNull("The callback should expose an EventContext", retained[0]);
        for (String slot : slots) {
            assertNull("Native references must expire after return", retained[0].getNativeObject(slot, Object.class));
        }
        assertNull("Original parameters must expire after return",
                retained[0].getNativeObject("originalParameters", Map.class));
        assertEquals("Releasing native context must not serialize it into event attributes",
                actionAttributes(), result.getAttributes());
    }

    private Map<String, Object> actionAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "action");
        attributes.put("action.name", "checkout");
        attributes.put("action.snapshots", "old snapshots");
        attributes.put("action.id", "action-id");
        attributes.put("action.type", "click");
        attributes.put("action.duration", 120L);
        attributes.put("view.id", "view-id");
        attributes.put("session.id", "session-id");
        return attributes;
    }
}
