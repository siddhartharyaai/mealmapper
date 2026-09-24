# ML Kit finds its internal components by reflection (registrars named in manifest meta-data).
# R8 full mode strips them, and BarcodeScanning.getClient() then crashes with an NPE in release only.
# Found by the emulator smoke test (tools/smoke.sh), September 2026.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_**
