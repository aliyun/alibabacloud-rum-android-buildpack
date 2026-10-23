package com.alibabacloud.rum;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

public class CustomLogDelegationTest {
    @Test
    public void snapshotsAndLogLevelKeepTheirDistinctParameterPositions() {
        Map<String, Object> attributes = Collections.singletonMap("order", "123");
        // The embedded entry point and public wrapper deliberately have the same simple name.
        try (MockedStatic<com.alibabacloud.rum.android.sdk.AlibabaCloudRum> agent =
                     mockStatic(com.alibabacloud.rum.android.sdk.AlibabaCloudRum.class)) {
            AlibabaCloudRum.setCustomLog("payment failed", "checkout", "{\"order\":123}",
                    "WARN", attributes);
            agent.verify(() -> com.alibabacloud.rum.android.sdk.AlibabaCloudRum.setCustomLog(
                    "payment failed", "checkout", "WARN", "{\"order\":123}", attributes));
        }
    }
}
