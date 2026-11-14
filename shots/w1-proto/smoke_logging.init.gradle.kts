// Init script for the w1-proto smoke evidence run only: surface test stdout
// (the SMOKE printlns) without touching the repo's build files.
allprojects {
    tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }
}
