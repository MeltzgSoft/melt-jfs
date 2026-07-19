// Resolves the Java toolchain declared in build.gradle.kts, downloading the JDK from the Foojay
// Disco API when no matching one is installed. Without this, any machine (or CI runner) lacking that
// exact JDK fails the build outright instead of provisioning it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "melt-jfs"
