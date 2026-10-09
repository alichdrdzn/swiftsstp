# Keep the gomobile binding surface; its rules ship inside mobile.aar (proguard.txt).
-keep class sstpflash.mobile.** { *; }
-keep class go.** { *; }
