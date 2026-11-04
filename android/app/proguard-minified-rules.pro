# R8 rules added to the minified build type only, which exists for instrumented tests.
# The test runner shares the app's library runtime and calls parts of it the app never
# uses, such as androidx.tracing.Trace, so keep AndroidX, Kotlin, and coroutines whole.
# The app's own classes still go through R8, which is what the minified run checks.
-keep class androidx.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
