package com.alibabacloud.rum.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

/** Public binding at the normal native-crash record recovery seam. */
public class NativeCrashRecoveryEventApiTest {
    private static final String SOURCE = "exception.native.recovered";

    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void testIntercept_NativeCrashRecord_BorrowsReadOnlyFieldsAndPreservesIdentity() {
        Map<String, Object> record = recoveredRecord();
        AtomicReference<EventContext> retained = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        Thread recoveryThread = Thread.currentThread();
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            callbacks.incrementAndGet();
            retained.set(context);
            assertTrue("Recovery has the existing public Exception type", event instanceof ExceptionEvent);
            assertEquals("Identify the safe recovery source", SOURCE, context.getSource());
            assertSame("Use the caller's safe processing thread", recoveryThread, Thread.currentThread());
            Map<?, ?> borrowed = context.getNativeObject("recoveredRecord", Map.class);
            assertSame("Borrow the existing parsed record", record, borrowed);
            assertEquals("SIGSEGV / SEGV_MAPERR", borrowed.get("crashReason"));
            assertEquals(0L, borrowed.get("crashAddress"));
            assertEquals(7, borrowed.get("requestingThread"));
            assertEquals(1720000000L, borrowed.get("timeDateStamp"));
            assertThrows("Record fields are read-only", UnsupportedOperationException.class, borrowed::clear);
            Map<?, ?> images = (Map<?, ?>) borrowed.get("binaryImages");
            assertEquals("102030405060708090a0b0c0d0e0f001", images.get("libcheckout.so"));
            assertThrows("Nested image records are read-only", UnsupportedOperationException.class, images::clear);
            List<?> frames = (List<?>) borrowed.get("stackTraces");
            assertEquals("checkout_native", ((Map<?, ?>) frames.get(0)).get("symbol"));
            assertThrows("The frame list is read-only", UnsupportedOperationException.class, frames::clear);
            assertThrows("Each frame is read-only", UnsupportedOperationException.class,
                    () -> ((Map<?, ?>) frames.get(0)).clear());
            assertNull("The crashed process has no live Throwable",
                    context.getNativeObject("exception", Throwable.class));
            assertNull("Recovery has no original API arguments",
                    context.getNativeObject("originalParameters", Map.class));
            assertNull("A mismatched native type is absent",
                    context.getNativeObject("recoveredRecord", Throwable.class));
            ExceptionEvent exception = (ExceptionEvent) event;
            assertTrue(exception.setName("checkout.native"));
            assertTrue(exception.setMessage("payment failed"));
            assertTrue(exception.setCausedBy("business attribution"));
            assertTrue(exception.setSnapshots("not JSON"));
            return event;
        }));

        Map<String, Object> original = exceptionAttributes();
        EventInterceptorRegistry.ProcessingResult result = EventInterceptorRegistry.processException(
                original, SOURCE, Collections.singletonMap("recoveredRecord", record));

        assertEquals("The recovery source must opt into processing", "accepted", result.getOutcome());
        assertEquals("Process this snapshot once", 1, callbacks.get());
        Map<String, Object> expected = exceptionAttributes();
        expected.put("exception.name", "checkout.native");
        expected.put("exception.message", "payment failed");
        expected.put("exception.caused_by", "business attribution");
        expected.put("exception.snapshots", "not JSON");
        assertEquals("Identity, association, timing and native diagnostics remain protected",
                expected, result.getAttributes());
        assertEquals("The recovery input remains isolated", exceptionAttributes(), original);
        assertEquals("User edits must not change the original diagnostic", "SIGSEGV / SEGV_MAPERR",
                record.get("crashReason"));
        assertNull("The record borrow expires after the callback",
                retained.get().getNativeObject("recoveredRecord", Map.class));
        assertFalse("Native records must not be exported as attributes",
                result.getAttributes().containsKey("recoveredRecord"));
    }

    private Map<String, Object> recoveredRecord() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("crashType", "crash");
        record.put("subCrashType", "native");
        record.put("crashReason", "SIGSEGV / SEGV_MAPERR");
        record.put("crashAddress", 0L);
        record.put("assertion", "");
        record.put("requestingThread", 7);
        record.put("timeDateStamp", 1720000000L);
        record.put("processCreateTime", 1719999990L);
        record.put("threads", "captured native threads");
        record.put("binaryImages", Collections.singletonMap("libcheckout.so", "102030405060708090a0b0c0d0e0f001"));
        record.put("stackTraces", Collections.singletonList(Collections.singletonMap("symbol", "checkout_native")));
        return Collections.unmodifiableMap(record);
    }

    private Map<String, Object> exceptionAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "exception");
        attributes.put("event_id", "native-recovery-event");
        attributes.put("exception.type", "crash");
        attributes.put("exception.sub_type", "native");
        attributes.put("exception.name", "SIGSEGV / SEGV_MAPERR");
        attributes.put("exception.message", "SIGSEGV / SEGV_MAPERR");
        attributes.put("exception.caused_by", "SIGSEGV / SEGV_MAPERR");
        attributes.put("exception.stack", "original native stack");
        attributes.put("exception.binary_images", "{\"libcheckout.so\":\"102030405060708090a0b0c0d0e0f001\"}");
        attributes.put("thread.id", 7L);
        attributes.put("view.id", "previous-view");
        attributes.put("view.name", "checkout");
        attributes.put("session.id", "previous-session-01");
        attributes.put("timestamp", 1720000000123L);
        return attributes;
    }
}
