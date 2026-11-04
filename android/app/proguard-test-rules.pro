# R8 rules for the instrumented test APK of the minified build type.
# AndroidX Test refers to Error Prone annotations that aren't on its runtime classpath.
-dontwarn com.google.errorprone.annotations.**
