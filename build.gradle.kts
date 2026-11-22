plugins {
    id("com.android.application") version "9.1.0" apply false
    // AGP 9 compiles Kotlin itself; this only pins the Kotlin version so it matches the Compose compiler.
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
}

// OneDrive locks and "frees up" files while Gradle is writing them, which breaks builds at random.
// When the project lives in OneDrive, keep build output in ~/.gradle-builds instead (source stays put).
if (rootDir.absolutePath.contains("OneDrive", ignoreCase = true)) {
    val outside = File(System.getProperty("user.home"), ".gradle-builds/${rootProject.name}")
    allprojects {
        layout.buildDirectory.set(File(outside, if (this == rootProject) "root" else name))
    }
}
