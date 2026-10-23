allprojects {
    group = "id.saryu"
    version = "3.0.1"

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
    }
}

tasks.register("cleanAll") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
    doLast {
        println("Cleaned all subprojects.")
    }
}
