# Original static JNI exports and reflected callback constructors/methods.
-keep class info.cemu.cemu.nativeinterface.** { *; }
-keep class info.cemu.cemu.emulation.EmulationActivity { public static *; }
-keepclassmembers class org.calamansi.cemu.CemuRuntime {
    public static void reportNativeError(java.lang.String);
    public static void requestRelaunch(long);
}
-keepnames class org.calamansi.cemu.CemuRuntime
-keep class org.calamansi.cemu.CemuActivity { public <init>(); }
-keep class org.calamansi.cemu.CemuSettingsActivity { public <init>(); }
-keep class org.calamansi.cemu.CemuMetadataService { public <init>(); }
