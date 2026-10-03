// Top-level Gradle configuration.
// The Android application plugin belongs to :app; it is declared here only
// with its version so the module can apply it without an unresolved plugin error.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
}
