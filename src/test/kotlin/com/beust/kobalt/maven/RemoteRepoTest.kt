package com.beust.kobalt.maven

import com.beust.kobalt.TestModule
import org.testng.Assert
import org.testng.annotations.Test
import javax.inject.Inject

@Test
@org.testng.annotations.Guice(modules = arrayOf(TestModule::class))
class RemoteRepoTest @Inject constructor(val repoFinder: RepoFinder, val dependencyManager: DependencyManager){

    @Test
    fun mavenMetadata() {
        val dep = dependencyManager.create("org.codehaus.groovy:groovy-all:")
        // Note: this test might fail if a new version of Groovy gets uploaded, need
        // to find a stable (i.e. abandoned) package
        with(dep.id.split(":")[2]) {
            Assert.assertTrue(this == "2.4.5" || this == "2.4.6")
        }
    }

    @Test(enabled = false)
    fun metadataForSnapshots() {
        val jar = dependencyManager.create("org.apache.maven.wagon:wagon-provider-test:2.10-SNAPSHOT")
        Assert.assertTrue(jar.jarFile.get().exists())
    }

    fun resolveAarWithVersion() {
        val repoResult = repoFinder.findCorrectRepo("com.jakewharton.timber:timber:4.1.0")
        with(repoResult) {
            Assert.assertEquals(path, "com/jakewharton/timber/timber/4.1.0/timber-4.1.0.aar")
        }
    }

    @Test(groups = arrayOf("broken"), enabled = false)
    fun resolveAarWithoutVersion() {
        val repoResult = repoFinder.findCorrectRepo("com.jakewharton.timber:timber:")
        with(repoResult) {
            Assert.assertEquals(path, "com/jakewharton/timber/timber/4.1.0/timber-4.1.0.aar")
        }
    }
}
