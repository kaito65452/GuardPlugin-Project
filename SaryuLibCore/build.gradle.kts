plugins {
    id("java-library")
}

import org.gradle.api.file.DuplicatesStrategy

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

val buildVersion = rootProject.extra["buildVersion"] as String
val buildCommit = rootProject.extra["buildCommit"] as String
val buildChannel = rootProject.extra["buildChannel"] as String
val buildInfoDir = layout.buildDirectory.dir("generated/build-info")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.property("buildVersion", buildVersion)
    inputs.property("buildCommit", buildCommit)
    inputs.property("buildChannel", buildChannel)
    outputs.file(buildInfoDir.map { it.file("build-info.properties") })
    doLast {
        val file = buildInfoDir.get().file("build-info.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "version=$buildVersion\ncommit=$buildCommit\nchannel=$buildChannel\n"
        )
    }
}
sourceSets.main {
    resources.srcDir(buildInfoDir)
}
tasks.processResources {
    dependsOn(generateBuildInfo)
    inputs.property("buildVersion", buildVersion)
    inputs.property("buildCommit", buildCommit)
    inputs.property("buildChannel", buildChannel)
    filesMatching("plugin.yml") {
        expand("version" to buildVersion)
    }
}
tasks.named("sourcesJar") { dependsOn(generateBuildInfo) }

dependencies {
    // Provided by the server at runtime; NOT bundled into the jar.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.json)
    // Optional at runtime; compile-only so PacketEvents is never bundled.
    compileOnly(libs.packetevents.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Bundled into the SaryuLibCore jar (fat jar) so consumers get a
    // self-contained shared library for all Saryu plugins.
    api(libs.hikaricp)
    api(libs.sqlite.jdbc)
    api(libs.h2)
    api(libs.mysql.connector.j)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    dependsOn("classes")
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().files.map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "id.saryu.libcore.SaryuLib"
    }
}
