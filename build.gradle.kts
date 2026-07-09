import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

// Coordinates: io.github.meltzg:melt-jfs:<version>. The version is supplied by the release workflow
// from the git tag (VERSION env var); local builds get a SNAPSHOT so they never collide with a release.
group = "io.github.meltzg"
version = System.getenv("VERSION") ?: "0.0.0-SNAPSHOT"

java {
    // FFM (java.lang.foreign) was finalized in Java 22, so no --enable-preview is needed.
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("commons-io:commons-io:2.15.1")
    // Test-only (never published): the same tag parser the consuming app uses, so prototype tests can
    // prove a stitched minimal-metadata stream parses where a truncated whole-header read crashes.
    testImplementation("net.jthink:jaudiotagger:3.0.1")
}

// Source set for developer tools — compiled separately, never included in the published JAR.
val dev = sourceSets.create("dev") {
    java.srcDir("src/dev/java")
    compileClasspath += sourceSets.main.get().output
    compileClasspath += configurations["runtimeClasspath"]
    runtimeClasspath += sourceSets.main.get().output
    runtimeClasspath += configurations["runtimeClasspath"]
}

// Real-device integration tests live in their own source set so they run in a separate JVM from the
// fake-backed unit tests — no shared MTPDeviceBridge singleton or libmtp state across the two.
val integrationTest = sourceSets.create("integrationTest") {
    java.srcDir("src/integrationTest/java")
    compileClasspath += sourceSets.main.get().output + configurations["testCompileClasspath"]
    runtimeClasspath += sourceSets.main.get().output + configurations["testRuntimeClasspath"]
}

tasks.register<JavaExec>("browse") {
    description = "Walk and print the directory tree of all connected MTP devices."
    classpath = dev.runtimeClasspath
    mainClass = "org.meltzg.fs.mtp.MTPBrowser"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // pass an optional depth limit: ./gradlew browse --args="2"
}

tasks.test {
    // Required for FFM restricted operations (MemorySegment.reinterpret, libraryLookup)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a connected MTP device (skipped when none present)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    // Fresh JVM per test class: isolates native libmtp + device-connection state between classes.
    forkEvery = 1
    maxParallelForks = 1
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    shouldRunAfter(tasks.test)

    // These tests self-skip (via JUnit assumptions) when no device is attached, so make skips
    // visible on the console — otherwise an all-skipped run is indistinguishable from a passing one.
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
    }

    // Opt-in guard: `-PrequireDevice` turns an all-skipped run into a build failure, so a run meant
    // to exercise a real device can't quietly pass without executing anything. Off by default and
    // deliberately NOT used in CI, which never has a device connected.
    val requireDevice = providers.gradleProperty("requireDevice").isPresent
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(test: TestDescriptor) {}
        override fun afterTest(test: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) { // the root aggregate suite: totals across all forked JVMs
                println("Integration: ${result.testCount} tests, ${result.successfulTestCount} passed, " +
                    "${result.skippedTestCount} skipped, ${result.failedTestCount} failed")
                if (requireDevice && result.successfulTestCount == 0L) {
                    throw GradleException("requireDevice: no integration tests executed (all skipped) " +
                        "— is a device connected?")
                }
            }
        }
    })
}

mavenPublishing {
    // Publishes to the Sonatype Central Portal (central.sonatype.com), where the io.github.meltzg
    // namespace is registered. Since 0.34.0 this is the only target (legacy OSSRH was removed), so
    // the call takes no host argument. Whether the upload is auto-released or left in the portal for
    // manual review is controlled by mavenCentralAutomaticPublishing in gradle.properties.
    publishToMavenCentral()
    // GPG-sign every artifact; Central rejects unsigned uploads. The key is provided in-memory by
    // the release workflow via ORG_GRADLE_PROJECT_signingInMemoryKey(+Password).
    signAllPublications()

    // Auto-generates the sources and javadoc JARs that Central requires alongside the main JAR.
    coordinates(group.toString(), "melt-jfs", version.toString())

    pom {
        name.set("melt-jfs")
        description.set(
            "A Java NIO FileSystemProvider for MTP devices (Android phones, audio players), " +
                "built on the Java Foreign Function & Memory API with no native build step.",
        )
        url.set("https://github.com/meltzg/melt-jfs")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("meltzg")
                name.set("Gregory Meltzer")
                url.set("https://github.com/meltzg")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/meltzg/melt-jfs.git")
            developerConnection.set("scm:git:ssh://git@github.com/meltzg/melt-jfs.git")
            url.set("https://github.com/meltzg/melt-jfs")
        }
    }
}
