pluginManagement {
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); maven("https://maven-central.storage-download.googleapis.com/maven2/"); mavenCentral(); maven("https://jitpack.io") { content { includeGroup("cz.adaptech.tesseract4android") } } }
}
rootProject.name = "PDFMaster"
include(":app")
