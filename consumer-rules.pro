# ProGuard rules inherited by consumer apps using protolib.aar

-keep public class com.deepak.protolib.VehicleSDK {
    public *;
}

-keep public class com.deepak.protolib.VehicleTelemetry {
    public *;
}

-keep public class com.deepak.protolib.CryptoUtils {
    public *;
}

-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    public <methods>;
}
