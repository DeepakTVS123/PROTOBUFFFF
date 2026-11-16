# R8 / ProGuard Obfuscation Rules for protolib SDK

# Repackage internal implementation classes
-repackageclasses 'com.deepak.protolib.internal'
-allowaccessmodification

# Obfuscate names & strip source file info
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Preserve public SDK entry points and model classes
-keep public class com.deepak.protolib.VehicleSDK {
    public *;
}

-keep public class com.deepak.protolib.VehicleTelemetry {
    public *;
}

-keep public class com.deepak.protolib.CryptoUtils {
    public *;
}

# Keep GeneratedMessageLite public methods
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    public <methods>;
}
