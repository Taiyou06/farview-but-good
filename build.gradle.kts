import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml
import xyz.jpenilla.resourcefactory.bukkit.Permission
import xyz.jpenilla.resourcefactory.paper.PaperPluginYaml
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("java")
    alias(libs.plugins.shadow)
    alias(libs.plugins.paperweight.userdev) apply false
    alias(libs.plugins.resource.factory.paper)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven {
        url = uri("https://mvn.lumine.io/repository/maven-public/")
        content { includeGroup("io.lumine") }
        metadataSources { artifact() }
    }
    maven { url = uri("https://repo.extendedclip.com/content/repositories/placeholderapi/") }
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.netty)
    implementation(project(":nms:common"))
    implementation(project(":nms:v26_2"))
    implementation(project(":nms:v26_3"))
    implementation(libs.configurate.hocon)
    implementation(libs.caffeine)
    compileOnly(libs.mythic) { isTransitive = false }
    compileOnly(libs.lumine.utils) { isTransitive = false }
    compileOnly(libs.placeholderapi) { isTransitive = false }
}

paperPluginYaml {
    main = "net.gensokyoreimagined.farview.FarViewPlugin"
    apiVersion = "26.2"
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
    foliaSupported = true
    authors.addAll("kidofcubes")
    dependencies {
        server("MythicMobs", PaperPluginYaml.Load.BEFORE, false, true)
        server("PlaceholderAPI", PaperPluginYaml.Load.BEFORE, false, true)
    }
    permissions {
        register("farview.use") { default = Permission.Default.TRUE }
        register("farview.others") { default = Permission.Default.OP }
        register("farview.reload") { default = Permission.Default.OP }
    }
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

        manifest {
            attributes("paperweight-mappings-namespace" to "mojang")
        }

        mergeServiceFiles()
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        runDirectory(layout.projectDirectory.dir("run/paper-${libs.versions.minecraft.get()}").asFile)
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    register<RunServer>("runServer262") {
        minecraftVersion("26.2")
        runDirectory(layout.projectDirectory.dir("run/paper-26.2").asFile)
        pluginJars(shadowJar.flatMap { it.archiveFile })
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }
}
