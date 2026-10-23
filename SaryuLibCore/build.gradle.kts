plugins {
    id("java-library")
}

import org.gradle.api.file.DuplicatesStrategy

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

dependencies {
    // Provided by the server at runtime; NOT bundled into the jar.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.adventure.serializer.json)
    // Optional at runtime; compile-only so PacketEvents is never bundled.
    compileOnly(libs.packetevents.api)

    // Bundled into the SaryuLibCore jar (fat jar) so consumers get a
    // self-contained shared library for all Saryu plugins.
    api(libs.hikaricp)
    api(libs.sqlite.jdbc)
    api(libs.h2)
    api(libs.mysql.connector.j)
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
