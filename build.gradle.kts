import net.fabricmc.loom.task.prod.ClientProductionRunTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("fabric-loom") version "1.10.+"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("jvm") version "2.0.0"
    `maven-publish`
}

operator fun Project.get(prop: String) = extra[prop] as String

version = project["mod_version"]
group = project["maven_group"]
base.archivesName.set(project["archives_base_name"])

val api by sourceSets.registering {
    compileClasspath += sourceSets.main.get().compileClasspath
}

val bundledLibs by configurations.creating

configurations.named("compileOnly") {
    extendsFrom(bundledLibs)
}

sourceSets.main {
    java.srcDir("packwiz-installer/src/main/java")
    resources.srcDir("packwiz-installer/src/main/resources")
}

extensions.configure<KotlinJvmProjectExtension> {
    sourceSets.getByName("main").kotlin.srcDir("packwiz-installer/src/main/kotlin")
}

repositories {
    mavenCentral()
    google()
    maven("https://jitpack.io")
}

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.extra["minecraft_version"]}")
    mappings("net.fabricmc:yarn:${project.extra["yarn_mappings"]}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.extra["loader_version"]}")

    bundledLibs("net.lenni0451:Reflect:1.4.0")
    bundledLibs("com.formdev:flatlaf:3.5.4")
    bundledLibs(kotlin("stdlib-jdk8"))
    bundledLibs(kotlin("reflect"))
    bundledLibs("commons-cli:commons-cli:1.5.0")
    bundledLibs("com.google.code.gson:gson:2.9.0")
    bundledLibs("com.squareup.okio:okio:3.1.0")
    bundledLibs("com.squareup.okhttp3:okhttp:4.10.0")
    bundledLibs("cc.ekblad:4koma:1.1.0")
}

tasks.jar {
    includeEmptyDirs = false
    manifest {
        attributes["Premain-Class"] = "io.github.gaming32.modloadingscreen.ModLoadingScreenAgent"
    }
}

tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(bundledLibs)
    archiveClassifier.set("shadow")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    dependsOn(tasks.named("shadowJar"))
    inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
}

val apiJar by tasks.registering(Jar::class) {
    from(api.get().output)
    archiveClassifier.set("api")
}
tasks.build {
    dependsOn(apiJar)
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

val targetJavaVersion = 8
val targetKotlinJvm = if (targetJavaVersion == 8) "1.8" else targetJavaVersion.toString()
tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    if (JavaVersion.current().isJava10Compatible) {
        options.release.set(targetJavaVersion)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = targetKotlinJvm
        freeCompilerArgs = listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.io.path.ExperimentalPathApi",
            "-Xlambdas=indy"
        )
    }
}

java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
    }
    withSourcesJar()
}

tasks.named<Jar>("sourcesJar") {
    from(api.get().allSource)
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
    }
}

@Suppress("UnstableApiUsage")
val prodClient by tasks.registering(ClientProductionRunTask::class) {
    jvmArgs.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005")
    outputs.upToDateWhen { false }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(apiJar.get().archiveFile) {
                classifier = "api"
            }
        }
    }

    repositories {
        mavenLocal()
    }
}
