import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
    }
    publishing {
        token = System.getenv("INTELLIJ_PLATFORM_PUBLISH_TOKEN")
            ?: project.findProperty("intellij.platform.publish.token") as? String
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.undertow.servlet)
    implementation(libs.servlet.api)
    implementation(libs.okhttp)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}
