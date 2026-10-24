plugins {
    id("java-library")
    alias(libs.plugins.shadow)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
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
    // Shared core provided as a separate plugin
    compileOnly(project(":SaryuLibCore"))

    // Server API & Adventure
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.minimessage)
    compileOnly(libs.luckperms.api)

    // Shaded & relocated pure-Java libraries
    implementation(libs.toml4j)
    implementation(libs.re2j)
    implementation(libs.bcprov)

    // Database – provided at runtime by SaryuLibCore; needed at compile time
    compileOnly(libs.hikaricp)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.h2)

    // Tests
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(project(":SaryuLibCore"))
    testImplementation(libs.paper.api)
    testImplementation(libs.hikaricp)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.h2)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Jar>("jar") {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    archiveClassifier.set("slim")
}

tasks.shadowJar {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    archiveClassifier.set("")

    minimize {
        exclude(dependency(libs.re2j.get()))
        exclude(dependency(libs.toml4j.get()))
    }

    // Relocate pure-Java shaded libraries away from the global classpath
    relocate("com.moandjiezana.toml", "id.saryu.saryuguard.lib.toml")
    relocate("com.google.re2j", "id.saryu.saryuguard.lib.re2j")
    relocate("org.bouncycastle", "id.saryu.saryuguard.lib.bouncycastle")

    // Drop dependency META-INF (signatures / multi-release noise); keep our own resources.
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/MANIFEST.MF")
    exclude("META-INF/versions/**")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
