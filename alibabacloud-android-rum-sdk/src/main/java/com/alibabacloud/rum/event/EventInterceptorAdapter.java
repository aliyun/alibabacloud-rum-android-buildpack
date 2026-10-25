package com.alibabacloud.rum.event;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;

import com.alibabacloud.rum.core.event.EventInterceptorRegistry;

/** SDK 内部跨包注册桥，不提供事件构造或内部模型访问能力。 */
@RestrictTo(RestrictTo.Scope.LIBRARY)
public final class EventInterceptorAdapter {
    private EventInterceptorAdapter() {}

    public static boolean register(@Nullable EventInterceptor interceptor) {
        try {
            if (interceptor == null) {
                return EventInterceptorRegistry.setEventInterceptor(null);
            }
            return EventInterceptorRegistry.setEventInterceptor((candidate, context) -> {
                RumEvent event;
                if ("custom".equals(candidate.getEventType())) {
                    event = new CustomEvent(candidate);
                } else if ("resource".equals(candidate.getEventType())) {
                    event = new ResourceEvent(candidate);
                } else {
                    return EventInterceptorRegistry.invalidCandidate();
                }
                RumEvent result = interceptor.intercept(event, new EventContext(context));
                if (result == null) {
                    return null;
                }
                return result == event ? candidate : EventInterceptorRegistry.invalidCandidate();
            });
        } catch (Throwable ignored) {
            try {
                Log.w("RUM-EventInterceptor", "registration_failed");
            } catch (Throwable loggingFailure) {
                // A diagnostic must not turn registration failure into a host exception.
            }
            return false;
        }
    }
}
