plugins {
    id("java-library")
}

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
    outputs.dir(buildInfoDir)
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
    filesMatching("plugin.yml") {
        expand("version" to buildVersion)
    }
}
tasks.named("sourcesJar") { dependsOn(generateBuildInfo) }

dependencies {
    implementation(project(":SaryuLibCore"))

    // Provided by the server + SaryuLibCore at runtime; NOT bundled.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.placeholderapi)
}
