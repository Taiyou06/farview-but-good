import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
    id("java")
    alias(libs.plugins.shadow)
    alias(libs.plugins.paperweight.userdev)
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.api.get())
    implementation(libs.configurate.hocon)
    implementation(libs.cloud.paper)
    implementation(libs.caffeine)
}

paperPluginYaml {
    main = "net.gensokyoreimagined.farview.FarViewPlugin"
    apiVersion = "26.2"
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
    foliaSupported = true
    authors.addAll("kidofcubes")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        val shadeBase = "net.gensokyoreimagined.farview.shaded"
        relocate("org.incendo.cloud", "$shadeBase.cloud")
        relocate("org.spongepowered.configurate", "$shadeBase.configurate")
        relocate("io.leangen.geantyref", "$shadeBase.geantyref")
        relocate("com.github.benmanes.caffeine", "$shadeBase.caffeine")

        dependencies {
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
            exclude(dependency("org.intellij:.*:.*"))
            exclude(dependency("org.jspecify:jspecify:.*"))
            exclude(dependency("org.checkerframework:checker-qual:.*"))
            exclude(dependency("com.google.errorprone:.*:.*"))
            exclude(dependency("com.google.code.findbugs:.*:.*"))
        }

        exclude("META-INF/maven/**")
        exclude("META-INF/proguard/**")
        exclude("META-INF/versions/*/module-info.class")
        exclude("module-info.class")

        mergeServiceFiles()
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }
}
