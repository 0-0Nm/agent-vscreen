plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
// Build outputs live on the root disk (the /data disk is nearly full).
allprojects { layout.buildDirectory.set(file("/srv/avs-build/${project.name}")) }
