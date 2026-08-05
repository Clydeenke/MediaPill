import java.net.URI

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
