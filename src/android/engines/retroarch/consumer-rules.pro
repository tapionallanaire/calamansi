# RetroArch resolves these original Android frontend methods and JNI names.
-keep class com.retroarch.browser.retroactivity.RetroActivityCommon { *; }
-keep class org.calamansi.retroarch.RetroArchActivity { *; }
-keep class org.calamansi.retroarch.RetroArchMetadataService { public <init>(); }
-keep class org.calamansi.retroarch.RetroArchSettingsActivity { public <init>(); }
