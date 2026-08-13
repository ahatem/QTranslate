val smokePluginProjects = subprojects.filter {
    it.path.startsWith(":plugins:") && it.path != ":plugins:common"
}
val smokeDirectory = layout.buildDirectory.dir("plugin-smoke-test")

val cleanPluginSmoke by tasks.registering(Delete::class) {
    delete(smokeDirectory)
}

val preparePluginSmoke by tasks.registering(Sync::class) {
    dependsOn(cleanPluginSmoke)
    dependsOn(smokePluginProjects.map { "${it.path}:shadowJar" })
    into(smokeDirectory.map { it.dir("app-data/plugins") })

    smokePluginProjects.forEach { pluginProject ->
        from(pluginProject.layout.buildDirectory.dir("libs")) {
            include("*-plugin.jar")
        }
    }
}

evaluationDependsOn(":app")

tasks.register<JavaExec>("smokeTestAllPlugins") {
    group = "verification"
    description = "Loads, configures, toggles, and exercises every bundled plugin."
    dependsOn(preparePluginSmoke, ":app:classes")

    val appProject = project(":app")
    classpath(
        appProject.layout.buildDirectory.dir("classes/kotlin/main"),
        appProject.layout.buildDirectory.dir("resources/main"),
        appProject.configurations.named("runtimeClasspath")
    )
    mainClass.set("com.github.ahatem.qtranslate.app.PluginSmokeTestMainKt")

    val root = smokeDirectory.get().asFile
    args(root.resolve("app-data").absolutePath, root.resolve("report.txt").absolutePath)
    systemProperty("java.awt.headless", "true")
}
