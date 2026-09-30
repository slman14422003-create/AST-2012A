pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // مكتبة Tesseract4Android (التعرّف الضوئي على الحروف) تُنشر عبر JitPack فقط وليس Maven Central.
        // حصرناه بمجموعتها فقط حتى لا يُستعمل مصدرًا لأي مكتبة أخرى.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("cz.adaptech.tesseract4android")
            }
        }
    }
}

rootProject.name = "Phizyo Studio"
include(":app")
