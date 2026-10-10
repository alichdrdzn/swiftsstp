# Keep the gomobile binding surface; its rules ship inside mobile.aar (proguard.txt).
-keep class swiftsstp.mobile.** { *; }
-keep class go.** { *; }

# Keep source file + line numbers so crash reports from minified builds stay
# readable (file names are obfuscated to "SourceFile" rather than dropped).
-keepattributes LineNumberTable,SourceFile
