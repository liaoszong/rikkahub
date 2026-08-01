// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

// One repository-level definition of "verified" for local development, CI,
// nightly builds, and the release pipeline. Individual modules keep ownership
// of their own test/lint tasks; this task only aggregates them lazily so newly
// added modules cannot silently fall outside the quality gate.
val verifyForkRelease by tasks.registering {
    group = "verification"
    description = "Runs every module's JVM tests and Android lint checks."
}

gradle.projectsEvaluated {
    val moduleVerificationTasks = subprojects.flatMap { project ->
        listOf("test", "lint").mapNotNull(project.tasks::findByName)
    }
    verifyForkRelease.configure {
        dependsOn(moduleVerificationTasks)
    }
}
