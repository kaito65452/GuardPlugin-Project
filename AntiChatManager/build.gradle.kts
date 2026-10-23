plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

dependencies {
    implementation(project(":SaryuLibCore"))

    // Provided by the server + SaryuLibCore at runtime; NOT bundled.
    compileOnly(libs.paper.api)
    compileOnly(libs.adventure.minimessage)
}
