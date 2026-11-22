plugins {
    id("com.android.application") version "9.1.0" apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin version so it matches the Compose compiler.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}
