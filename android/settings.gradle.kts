pluginManagement {
  repositories {
    google()
    // Google-hosted Maven Central mirror avoids transient HTTP 429 throttling
    // from repo.maven.apache.org on clean GitHub Actions runners.
    maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    // Keep dependency resolution resilient when Maven Central rate-limits CI.
    maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
    mavenCentral()
  }
}
rootProject.name = "hakuX"
include(":app")
