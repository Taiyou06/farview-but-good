plugins {
    id("java")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.netty)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.compileJava {
    options.release = 21
}
