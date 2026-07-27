plugins {
    kotlin("multiplatform").version("2.0.21-KBA-010").apply(false)
}

allprojects {
    group = providers.gradleProperty("groupID").get()
    version = "${providers.gradleProperty("mavenVersion").get()}-ohos"
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
