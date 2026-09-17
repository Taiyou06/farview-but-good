plugins {
    id("java")
    alias(libs.plugins.paperweight.userdev)
}

repositories {
    mavenCentral()
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.v12111.get())
    compileOnly(project(":nms:common"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.compileJava {
    options.release = 21
}
