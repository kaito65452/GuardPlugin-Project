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

/*
 * Static guard for capabilities that must never appear in plugin code.
 * CI runs this through `check`, so a violation fails the build.
 */
val scanForbiddenApis = tasks.register("scanForbiddenApis") {
    group = "verification"
    description = "Fail the build if dangerous JVM APIs appear in plugin sources."
    val sources = files(
        fileTree("AntiChatManager/src/main/java") { include("**/*.java") },
        fileTree("SaryuLibCore/src/main/java") { include("**/*.java") }
    )
    inputs.files(sources)
    doLast {
        val forbidden = listOf(
            Regex("""\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\("""),
            Regex("""\bnew\s+ProcessBuilder\s*\("""),
            Regex("""\bObjectInputStream\b"""),
            Regex("""\bURLClassLoader\b"""),
            Regex("""\bdefineClass\s*\("""),
            Regex("""\bScriptEngine(Manager)?\b"""),
            Regex("""\bsun\s*\.\s*misc\s*\.\s*Unsafe\b"""),
            Regex("""\bSystem\s*\.\s*load(Library)?\s*\(""")
        )
        val violations = mutableListOf<String>()
        sources.files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                forbidden.forEach { pattern ->
                    if (pattern.containsMatchIn(line)) {
                        violations += "${file.relativeTo(rootDir)}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Forbidden API usage detected:\n" + violations.joinToString("\n"))
        }
    }
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(scanForbiddenApis)
    }
}
