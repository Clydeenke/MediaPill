plugins {
    alias(libs.plugins.agpApp) apply false
    alias(libs.plugins.composeCompiler) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
