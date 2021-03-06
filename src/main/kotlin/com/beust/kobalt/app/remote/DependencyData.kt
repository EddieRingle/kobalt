package com.beust.kobalt.app.remote

import com.beust.kobalt.Args
import com.beust.kobalt.api.IClasspathDependency
import com.beust.kobalt.api.ProjectDescription
import com.beust.kobalt.app.BuildFileCompiler
import com.beust.kobalt.internal.JvmCompilerPlugin
import com.beust.kobalt.internal.PluginInfo
import com.beust.kobalt.internal.build.BuildFile
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.dependency.FileDependency
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltExecutors
import com.google.inject.Inject
import java.io.File
import java.nio.file.Paths

class DependencyData @Inject constructor(val executors: KobaltExecutors, val dependencyManager: DependencyManager,
        val buildFileCompilerFactory: BuildFileCompiler.IFactory, val pluginInfo: PluginInfo) {
    fun dependenciesDataFor(buildFilePath: String, args: Args) : GetDependenciesData {
        val projectDatas = arrayListOf<ProjectData>()

        fun toDependencyData(d: IClasspathDependency, scope: String): DependencyData {
            val dep = dependencyManager.create(d.id)
            return DependencyData(d.id, scope, dep.jarFile.get().absolutePath)
        }

        fun allDeps(l: List<IClasspathDependency>) = dependencyManager.transitiveClosure(l)

        val buildFile = BuildFile(Paths.get(buildFilePath), "GetDependenciesCommand")
        val buildFileCompiler = buildFileCompilerFactory.create(listOf(buildFile), pluginInfo)
        val projectResult = buildFileCompiler.compileBuildFiles(args)
        val pluginUrls = buildFileCompiler.parsedBuildFiles.flatMap { it.pluginUrls }

        val pluginDependencies = pluginUrls.map { File(it.toURI()) }.map { FileDependency(it.absolutePath) }
        projectResult.projects.forEach { project ->
            val compileDependencies = pluginDependencies.map { toDependencyData(it, "compile") } +
                    allDeps(project.compileDependencies).map { toDependencyData(it, "compile") } +
                    allDeps(project.compileProvidedDependencies).map { toDependencyData(it, "compile") }
            val testDependencies = allDeps(project.testDependencies).map { toDependencyData(it, "testCompile") }

            @Suppress("UNCHECKED_CAST")
            val pd = (project.projectProperties.get(JvmCompilerPlugin.DEPENDENT_PROJECTS)
                    as List<ProjectDescription>)
            val dependentProjects = pd.filter { it.project.name == project.name }.flatMap {
                it.dependsOn.map {
                    it.name
                }
            }

            // Separate resource from source directories
            val sources = project.sourceDirectories.partition { KFiles.isResource(it) }
            val tests = project.sourceDirectoriesTest.partition { KFiles.isResource(it) }
            projectDatas.add(ProjectData(project.name, project.directory, dependentProjects,
                    compileDependencies, testDependencies,
                    sources.second.toSet(), tests.second.toSet(), sources.first.toSet(), tests.first.toSet()))
        }
        return GetDependenciesData(projectDatas, projectResult.taskResult.errorMessage)
    }

    /////
    // The JSON payloads that this command uses
    //

    class DependencyData(val id: String, val scope: String, val path: String)

    class ProjectData(val name: String, val directory: String,
            val dependentProjects: List<String>,
            val compileDependencies: List<DependencyData>,
            val testDependencies: List<DependencyData>, val sourceDirs: Set<String>, val testDirs: Set<String>,
            val sourceResourceDirs: Set<String>, val testResourceDirs: Set<String>)

    class GetDependenciesData(val projects: List<ProjectData>, val errorMessage: String?)
}
