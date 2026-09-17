plugins {
    id("java")
    alias(libs.plugins.paperweight.userdev)
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.v263.get())
    compileOnly(project(":nms:common"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}
