package com.alibabacloud.rum.event;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class EventInterceptorApiTest {
    @After
    public void clearRegistration() {
        AlibabaCloudRum.setEventInterceptor(null);
    }

    @Test
    public void customCandidateCommitsOnlyExplicitChanges() {
        assertTrue(AlibabaCloudRum.setEventInterceptor((event, context) -> {
            assertTrue(event instanceof CustomEvent);
            CustomEvent custom = (CustomEvent) event;
            assertEquals("custom", custom.getEventType());
            assertEquals("checkout", custom.getName());
            assertTrue(custom.setName("checkout.success"));
            assertTrue(custom.setGroup("orders"));
            assertTrue(custom.setSnapshots("not JSON"));
            assertFalse(custom.setSnapshots(null));
            assertEquals("not JSON", custom.getSnapshots());
            assertEquals("custom.event", context.getSource());
            return event;
        }));

        Map<String, Object> original = customAttributes();
        Map<String, Object> result = EventInterceptorRegistry.processCustom(
                original, "custom.event", new LinkedHashMap<>()).getAttributes();
        assertEquals("checkout.success", result.get("custom.name"));
        assertEquals("orders", result.get("custom.group"));
        assertEquals("not JSON", result.get("custom.snapshots"));
        assertEquals("checkout", original.get("custom.name"));
        assertEquals("original-session", result.get("session.id"));
    }

    @Test
    public void retainedCandidateCannotReplaceAnotherEventOrModifyFinalOutput() {
        RumEvent[] retained = new RumEvent[1];
        EventContext[] retainedContext = new EventContext[1];
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            retained[0] = event;
            retainedContext[0] = context;
            assertTrue(((CustomEvent) event).setSnapshots(""));
            assertNotNull(context.getNativeObject("originalParameters", Map.class));
            assertNull(context.getNativeObject("originalParameters", String.class));
            return event;
        });
        Map<String, Object> first = EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", new LinkedHashMap<>()).getAttributes();
        assertEquals("", first.get("custom.snapshots"));
        assertFalse(((CustomEvent) retained[0]).setSnapshots("too late"));
        assertNull(retainedContext[0].getNativeObject("originalParameters", Map.class));

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((CustomEvent) event).setName("must rollback");
            return retained[0];
        });
        assertEquals("checkout", EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes().get("custom.name"));
        assertEquals("", first.get("custom.snapshots"));
    }

    @Test
    public void nullDropsButCallbackFailurePreservesOriginal() {
        AlibabaCloudRum.setEventInterceptor((event, context) -> null);
        assertNull(EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes());

        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            ((CustomEvent) event).setName("must rollback");
            throw new AssertionError("host callback failure");
        });
        assertEquals("checkout", EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes().get("custom.name"));
    }

    @Test
    public void replacementInsideCallbackAppliesToNextInvocationAndClearRestoresDefault() {
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            AlibabaCloudRum.setEventInterceptor((next, nextContext) -> {
                ((CustomEvent) next).setName("replacement");
                return next;
            });
            return event;
        });
        assertEquals("checkout", EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes().get("custom.name"));
        assertEquals("replacement", EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes().get("custom.name"));
        assertTrue(AlibabaCloudRum.setEventInterceptor(null));
        assertEquals("checkout", EventInterceptorRegistry.processCustom(
                customAttributes(), "custom.event", null).getAttributes().get("custom.name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nestedAttributeContainersHaveNoWritableAlias() {
        List<Object> list = new ArrayList<>();
        list.add("original");
        Map<String, Object> attributes = customAttributes();
        attributes.put("properties", list);
        AlibabaCloudRum.setEventInterceptor((event, context) -> {
            assertThrows(UnsupportedOperationException.class,
                    () -> event.getAttributes().put("session.id", "rewritten"));
            assertThrows(UnsupportedOperationException.class,
                    () -> ((List<Object>) event.getAttribute("properties")).set(0, "rewritten"));
            list.set(0, "host changed");
            assertEquals("original", ((List<?>) event.getAttribute("properties")).get(0));
            return event;
        });
        Map<String, Object> result = EventInterceptorRegistry.processCustom(
                attributes, "custom.event", null).getAttributes();
        assertEquals("original", ((List<?>) result.get("properties")).get(0));
    }

    @Test
    public void publicEventSurfaceHasNoConstructionOrGenericMutation() {
        for (Class<?> type : new Class<?>[] {RumEvent.class, CustomEvent.class, EventContext.class}) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertFalse(Modifier.isPublic(constructor.getModifiers()));
                assertFalse(Modifier.isProtected(constructor.getModifiers()));
            }
        }
        assertTrue(Modifier.isFinal(CustomEvent.class.getModifiers()));
        assertThrows(NoSuchMethodException.class,
                () -> RumEvent.class.getMethod("setAttribute", String.class, Object.class));
        assertThrows(NoSuchMethodException.class,
                () -> RumEvent.class.getMethod("removeAttribute", String.class));
        assertThrows(NoSuchMethodException.class, () -> CustomEvent.class.getMethod("clone"));
    }

    private Map<String, Object> customAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", "custom");
        attributes.put("custom.type", "event");
        attributes.put("custom.name", "checkout");
        attributes.put("session.id", "original-session");
        return attributes;
    }
}
