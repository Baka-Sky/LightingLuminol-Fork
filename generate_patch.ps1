$originalFile = ".gradle/caches/paperweight/upstreams/paper/paper-server/build.gradle.kts"
$patchFile = "folia-server/build.gradle.kts.patch"

$content = @"
--- a/paper-server/build.gradle.kts
+++ b/paper-server/build.gradle.kts
@@ -21,6 +21,25 @@
 paperweight {
     minecraftVersion = providers.gradleProperty("mcVersion")
+    filterPatches = false
     gitFilePatches = false

+    val fork = forks.register("folia") {
+        upstream.patchDir("paperServer") {
+            upstreamPath = "paper-server"
+            excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
+            patchesDir = rootDirectory.dir("folia-server/paper-patches")
+            outputDir = rootDirectory.dir("paper-server")
+        }
+        upstream.patchDir("lightingluminolServer") {
+            upstreamPath = "paper-server"
+            excludes = setOf("src/minecraft", "patches", "build.gradle.kts")
+            patchesDir = rootDirectory.dir("lightingluminol-server/paper-patches")
+            outputDir = rootDirectory.dir("paper-server")
+        }
+    }
+
+    activeFork = fork
+
+
     updatingMinecraft {
         // oldPaperCommit = "d4fe85375af18bfa88f44d7c1e6a61904ae550cc"
     }
@@ -87,7 +106,21 @@
 }

-val log4jPlugins = sourceSets.create("log4jPlugins")
+sourceSets {
+    main {
+        java { srcDir("../paper-server/src/main/java"); srcDir("../paper-server/src/generated/java"); srcDir("../lightingluminol-server/src/main/java") }
+        resources { srcDir("../paper-server/src/main/resources") }
+    }
+    test {
+        java { srcDir("../paper-server/src/test/java") }
+        resources { srcDir("../paper-server/src/test/resources") }
+    }
+}
+val log4jPlugins = sourceSets.create("log4jPlugins") {
+    java { srcDir("../paper-server/src/log4jPlugins/java") }
+}
 configurations.named(log4jPlugins.compileClasspathConfigurationName) {
     extendsFrom(configurations.compileClasspath.get())
 }
@@ -111,7 +144,7 @@
 }

 dependencies {
-    implementation(project(":paper-api"))
+    implementation(project(":folia-api"))
     implementation("ca.spottedleaf:leafpile:1.0.0")
     implementation("org.jline:jline-terminal-ffm:3.27.1") // use ffm on java 22+
     implementation("org.jline:jline-terminal-jni:3.27.1") // fall back to jni on java 21
@@ -173,14 +206,14 @@
         attributes(
             "Main-Class" to "org.bukkit.craftbukkit.Main",
-            "Implementation-Title" to "Paper",
+            "Implementation-Title" to "LightingLuminol",
             "Implementation-Version" to implementationVersion,
             "Implementation-Vendor" to date,
-            "Specification-Title" to "Paper",
+            "Specification-Title" to "LightingLuminol",
             "Specification-Version" to project.version,
             "Specification-Vendor" to "Paper Team",
-            "Brand-Id" to "papermc:paper",
-            "Brand-Name" to "Paper",
+            "Brand-Id" to "luminolmc:lightingluminol",
+            "Brand-Name" to "LightingLuminol",
             "Build-Number" to (build ?: ""),
             "Build-Time" to buildTime.toString(),
             "Git-Branch" to gitBranch,
@@ -328,7 +361,7 @@
 }

 fill {
-    project("paper")
+    project("folia")
     versionFamily(paperweight.minecraftVersion.map { it.split(".", "-").takeWhile { part -> part.toIntOrNull() != null }.take(2).joinToString(".") })
     version(paperweight.minecraftVersion)
"@

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($patchFile, $content, $utf8NoBom)
Write-Host "Patch file generated successfully"
