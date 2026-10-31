plugins {
    id("com.android.application") version "9.1.1" apply false
    // AGP 9.1 ships built-in Kotlin (KGP 2.2.10); keep Compose compiler aligned.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
