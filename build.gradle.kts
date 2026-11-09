plugins {
    // AGP 9 compiles Kotlin itself, so no separate Kotlin plugin is applied.
    id("com.android.application") version "9.5.1" apply false
    // Only puts Kotlin 2.4 on the build classpath for AGP's built-in Kotlin; LiteRT needs it.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
