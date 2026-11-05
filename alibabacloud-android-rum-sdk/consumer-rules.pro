# Public event API is shared by Java/Kotlin callers and the embedded SDK bridge.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep public class com.alibabacloud.rum.event.RumEvent { public *; }
-keep public class com.alibabacloud.rum.event.CustomEvent { public *; }
-keep public class com.alibabacloud.rum.event.ResourceEvent { public *; }
-keep public class com.alibabacloud.rum.event.ViewEvent { public *; }
-keep public class com.alibabacloud.rum.event.ActionEvent { public *; }
-keep public class com.alibabacloud.rum.event.ApplicationEvent { public *; }
-keep public class com.alibabacloud.rum.event.ExceptionEvent { public *; }
-keep public class com.alibabacloud.rum.event.LongTaskEvent { public *; }
-keep public enum com.alibabacloud.rum.event.ResourceSuccess { public *; }
-keep public class com.alibabacloud.rum.event.EventContext { public *; }
-keep public interface com.alibabacloud.rum.event.EventInterceptor { public *; }
-keepclassmembers class com.alibabacloud.rum.AlibabaCloudRum {
    public static boolean setEventInterceptor(com.alibabacloud.rum.event.EventInterceptor);
}
