# App-specific R8 rules. The default proguard-android-optimize.txt already keeps
# JNI native methods (every *Bridge external fun) and enum values()/valueOf(), and
# Room, Compose, coroutines, and kotlinx.serialization ship their own consumer rules.
# Add a rule here only for code reached by reflection or by name from native code.

# Keep line numbers for readable crash stack traces, without the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
