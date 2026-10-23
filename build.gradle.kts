/*
 * Base version follows semver. Bug fixes bump the patch component.
 *
 *   ./gradlew build                 -> 3.0.2-dev+<short-commit>  (channel: dev)
 *   ./gradlew build -Prelease       -> 3.0.2                     (channel: stable)
 *
 * The resolved version is injected into every plugin.yml and into
 * build-info.properties so /acm info and the startup log can display it.
 */
val baseVersion = "3.0.2"
val releaseBuild = gradle.startParameter.projectProperties.containsKey("release")
val gitCommit = run {
    try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        output.ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}
val buildChannel = if (releaseBuild) "stable" else "dev"
val buildVersion = if (releaseBuild) baseVersion else "$baseVersion-dev+$gitCommit"

allprojects {
    group = "id.saryu"
    version = buildVersion

    // Consumed by the subproject scripts below.
    extra["buildVersion"] = buildVersion
    extra["buildCommit"] = gitCommit
    extra["buildChannel"] = buildChannel

    repositories {
        mavenCentral()
        maven {
            name = "papermc"
            setUrl("https://repo.papermc.io/repository/maven-public/")
        }
        maven {
            name = "codemc"
            setUrl("https://repo.codemc.io/repository/maven-public/")
        }
        maven {
            name = "placeholderapi"
            setUrl("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        }
    }
}

tasks.register("cleanAll") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
    doLast {
        println("Cleaned all subprojects.")
    }
}
