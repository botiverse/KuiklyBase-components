plugins {
    id("com.android.library").version("8.13.2").apply(false)
    kotlin("multiplatform").version("2.1.21").apply(false)
}

allprojects {
    group = providers.gradleProperty("groupID").get()
    version = providers.gradleProperty("mavenVersion").get()
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
