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

public class ApplicationEventApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_ApplicationSnapshots_CommitsOnlyTheDedicatedField() {
        AtomicInteger callbacks = new AtomicInteger();
        assertTrue("Application registration should succeed", AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            assertTrue("The native Application snapshot must use its public type", event instanceof ApplicationEvent);
            ApplicationEvent application = (ApplicationEvent) event;
            assertEquals("Prepared snapshots must remain readable", "{\"old\":true}", application.getSnapshots());
            assertTrue("Snapshots must accept a complete replacement", application.setSnapshots("{\"new\":true}"));
            assertFalse("Null must fail locally without deleting the field", application.setSnapshots(null));
            assertEquals("Failed edits must preserve prior success", "{\"new\":true}", application.getSnapshots());
            return event;
        }));
        Map<String, Object> original = applicationAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processApplication(
                original, "application.start", null);
        Map<String, Object> expected = applicationAttributes();
        expected.put("application.snapshots", "{\"new\":true}");
        assertEquals("The explicit current candidate must be accepted", "accepted", result.getOutcome());
        assertEquals("The snapshot must invoke the callback once", 1, callbacks.get());
        assertEquals("Only application.snapshots may change", expected, result.getAttributes());
        assertEquals("The replacement must not affect the original", applicationAttributes(), original);
    }

    @Test
    public void testIntercept_MissingSnapshots_AcceptsExactTextWithoutAddingOtherFields() {
        String largeText = String.join("", java.util.Collections.nCopies(16385, "plain snapshots "));
        String[] values = {"", " \t\n", "not JSON {", largeText};
        for (String value : values) {
            AlibabaCloudRum.setEventInterceptor((event, context) -> {
                ApplicationEvent application = (ApplicationEvent) event;
                assertNull("A producer need not collect snapshots by default", application.getSnapshots());
                assertTrue("Any non-null text must be accepted verbatim", application.setSnapshots(value));
                assertFalse("Null must not remove a successful replacement", application.setSnapshots(null));
                assertEquals("Getter must read the current candidate", value, application.getSnapshots());
                return event;
            });
            Map<String, Object> original = applicationAttributes();
            original.remove("application.snapshots");
            EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processApplication(
                    original, "application.state", null);
            assertEquals("Valid text must commit without JSON or size validation", "accepted", result.getOutcome());
            assertEquals("Text must remain exact, including empty/whitespace", value,
                    result.getAttributes().get("application.snapshots"));
            assertEquals("Adding snapshots must add exactly one field", original.size() + 1,
                    result.getAttributes().size());
            assertFalse("The missing original field must remain absent", original.containsKey("application.snapshots"));
        }
    }

    @Test
    public void testIntercept_ModifyThenReturnNull_DropsTheCurrentOutputOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ApplicationEvent) event).setSnapshots("discarded");
            return null;
        });
        Map<String, Object> original = applicationAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processApplication(
                original, "application.state", null);
        assertEquals("Null must mean intentional drop", "dropped", result.getOutcome());
        assertNull("Intentional drop must not produce output attributes", result.getAttributes());
        assertEquals("Drop must not repeat the callback", 1, callbacks.get());
        assertEquals("Drop must preserve its original snapshot", applicationAttributes(), original);
    }

    @Test
    public void testIntercept_CallbackThrows_FallsBackToUnmodifiedOriginalOnce() {
        AtomicInteger callbacks = new AtomicInteger();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            ((ApplicationEvent) event).setSnapshots("must rollback");
            throw new AssertionError("host callback failure");
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.start", null);
        assertEquals("A catchable callback failure must fall back", "fallback", result.getOutcome());
        assertEquals("Failure must not repeat the callback", 1, callbacks.get());
        assertEquals("No partially edited field may escape", applicationAttributes(), result.getAttributes());
    }

    @Test
    public void testIntercept_RetainedWrapper_CannotEditFinalOrReplaceAnotherCandidate() {
        AtomicReference<ApplicationEvent> retained = new AtomicReference<>();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained.set((ApplicationEvent) event);
            retained.get().setSnapshots("accepted snapshots");
            return event;
        });
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.start", null);
        assertEquals("The current wrapper must commit", "accepted", first.getOutcome());
        assertFalse("Late edits must fail", retained.get().setSnapshots("too late"));
        assertThrows("Final output must be immutable", UnsupportedOperationException.class,
                () -> first.getAttributes().put("application.state", "rewritten"));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((ApplicationEvent) event).setSnapshots("must rollback");
            return retained.get();
        });
        EventInterceptorRegistry.ProcessingResult second = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.state", null);
        assertEquals("A previous wrapper must fail the candidate identity check", "fallback", second.getOutcome());
        assertEquals("An invalid return must restore all original fields",
                applicationAttributes(), second.getAttributes());
        assertEquals("The first committed final must remain isolated", "accepted snapshots",
                first.getAttributes().get("application.snapshots"));
    }

    @Test
    public void testIntercept_ApplicationParameters_BorrowsOnCurrentThreadAndExpiresAfterReturn() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("appStartType", "cold");
        parameters.put("appStartDuration", 80L);
        parameters.put("isFirstLaunch", Boolean.TRUE);
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<Map<?, ?>> borrowed = new AtomicReference<>();
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained.set(context);
            callbackThread.set(Thread.currentThread());
            borrowed.set(context.getNativeObject("originalParameters", Map.class));
            assertEquals("The context must identify the actual startup source",
                    "application.start", context.getSource());
            assertNull("A mismatched type must remain nullable",
                    context.getNativeObject("originalParameters", String.class));
            for (String slot : new String[]{"application", "activity", "request", "response", "error"}) {
                assertNull("Startup metrics do not imply an available native object",
                        context.getNativeObject(slot, Object.class));
            }
            return event;
        });
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.start", parameters);
        assertEquals("Context observations must not affect output", "accepted", result.getOutcome());
        assertSame("The callback must stay on its actual completion thread",
                Thread.currentThread(), callbackThread.get());
        assertEquals("The original scalar parameters must stay available in the callback", parameters, borrowed.get());
        assertNull("Retained contexts must no longer expose parameters",
                retained.get().getNativeObject("originalParameters", Map.class));
        assertEquals("Parameters must not be added to exported attributes",
                applicationAttributes(), result.getAttributes());
    }

    @Test
    public void testIntercept_RegistrationReplacement_AppliesAtNextCallAndClearRestoresDefaults() {
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            AlibabaCloudRum.setEventInterceptor((next, nextContext) -> {
                ((ApplicationEvent) next).setSnapshots("replacement");
                return next;
            });
            return event;
        });
        EventInterceptorRegistry.ProcessingResult first = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.start", null);
        EventInterceptorRegistry.ProcessingResult next = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.state", null);
        assertEquals("Replacing registration must not rerun the active callback",
                applicationAttributes(), first.getAttributes());
        assertEquals("The next actual invocation must use current registration", "replacement",
                next.getAttributes().get("application.snapshots"));
        assertTrue("Clearing registration must succeed", AlibabaCloudRum.setEventInterceptor(null));
        EventInterceptorRegistry.ProcessingResult cleared = EventInterceptorRegistry.processApplication(
                applicationAttributes(), "application.state", null);
        assertEquals("No registration must preserve default behavior", "unregistered", cleared.getOutcome());
        assertEquals("No registration must invoke zero callbacks", 0, cleared.getCallbackCount());
        assertEquals("Default output must remain unchanged", applicationAttributes(), cleared.getAttributes());
    }

    private Map<String, Object> applicationAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "application");
        attributes.put("application.snapshots", "{\"old\":true}");
        attributes.put("application.state", "foreground");
        attributes.put("application.duration", 120L);
        attributes.put("start.type", "cold");
        attributes.put("start.duration", 80L);
        attributes.put("thread.id", 7L);
        attributes.put("app.id", "app-id");
        attributes.put("session.id", "session-id");
        attributes.put("view.id", "view-id");
        return attributes;
    }
}
