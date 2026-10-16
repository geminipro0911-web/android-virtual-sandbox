# Keep virtual sandbox core reflection targets and stub components
-keep class com.sandbox.virtual.stub.** { *; }
-keep class com.sandbox.virtual.core.** { *; }
-keep class com.sandbox.virtual.server.** { *; }
-keep class com.sandbox.virtual.model.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
