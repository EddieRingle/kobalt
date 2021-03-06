package com.beust.kobalt.plugin.packaging

import com.beust.kobalt.JarGenerator
import com.beust.kobalt.KobaltException
import com.beust.kobalt.TaskResult
import com.beust.kobalt.api.*
import com.beust.kobalt.api.annotation.Directive
import com.beust.kobalt.api.annotation.ExportedProjectProperty
import com.beust.kobalt.api.annotation.Task
import com.beust.kobalt.archive.*
import com.beust.kobalt.glob
import com.beust.kobalt.internal.JvmCompilerPlugin
import com.beust.kobalt.maven.DependencyManager
import com.beust.kobalt.maven.PomGenerator
import com.beust.kobalt.misc.KFiles
import com.beust.kobalt.misc.KobaltExecutors
import com.beust.kobalt.misc.log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PackagingPlugin @Inject constructor(val dependencyManager : DependencyManager,
        val executors: KobaltExecutors, val jarGenerator: JarGenerator, val warGenerator: WarGenerator,
        val zipGenerator: ZipGenerator, val taskContributor: TaskContributor,
        val pomFactory: PomGenerator.IFactory, val configActor: ConfigActor<InstallConfig>)
            : BasePlugin(), IConfigActor<InstallConfig> by configActor, ITaskContributor, IAssemblyContributor {

    companion object {
        const val PLUGIN_NAME = "Packaging"

        @ExportedProjectProperty(doc = "Where the libraries are saved", type = "String")
        const val LIBS_DIR = "libsDir"

        @ExportedProjectProperty(doc = "The list of packages produced for this project",
                type = "List<PackageConfig>")
        const val PACKAGES = "packages"

        const val TASK_ASSEMBLE: String = "assemble"
        const val TASK_INSTALL: String = "install"
    }

    override val name = PLUGIN_NAME

    private val packages = arrayListOf<PackageConfig>()

    override fun apply(project: Project, context: KobaltContext) {
        super.apply(project, context)
        project.projectProperties.put(LIBS_DIR, KFiles.libsDir(project))
        taskContributor.addVariantTasks(this, project, context, "assemble", runAfter = listOf("compile"),
                runTask = { doTaskAssemble(project) })
    }

    // IAssemblyContributor
    override fun assemble(project: Project, context: KobaltContext) : TaskResult {
        try {
            project.projectProperties.put(PACKAGES, packages)
            packages.filter { it.project.name == project.name }.forEach { pkg ->
                pkg.jars.forEach { jarGenerator.generateJar(pkg.project, context, it) }
                pkg.wars.forEach { warGenerator.generateWar(pkg.project, context, it) }
                pkg.zips.forEach { zipGenerator.generateZip(pkg.project, context, it) }
                if (pkg.generatePom) {
                    pomFactory.create(project).generate()
                }
            }
            return TaskResult()
        } catch(ex: Exception) {
            throw KobaltException(ex)
        }
    }

    @Task(name = TASK_ASSEMBLE, description = "Package the artifacts",
            runAfter = arrayOf(JvmCompilerPlugin.TASK_COMPILE))
    fun doTaskAssemble(project: Project) : TaskResult {
        context.pluginInfo.assemblyContributors.forEach {
            val thisResult = it.assemble(project, context)
            if (! thisResult.success) {
                // Abort at the first failure
                return thisResult
            }
        }
        return TaskResult()
    }

    fun addPackage(p: PackageConfig) {
        packages.add(p)
    }


    @Task(name = PackagingPlugin.TASK_INSTALL, description = "Install the artifacts",
            runAfter = arrayOf(PackagingPlugin.TASK_ASSEMBLE))
    fun taskInstall(project: Project) : TaskResult {
        val config = configurationFor(project) ?: InstallConfig()
        val buildDir = project.projectProperties.getString(LIBS_DIR)
        val buildDirFile = File(buildDir)
        if (buildDirFile.exists()) {
            log(1, "Installing from $buildDir to ${config.libDir}")

            val toDir = KFiles.makeDir(config.libDir)
            KFiles.copyRecursively(buildDirFile, toDir, deleteFirst = true)
        }

        return TaskResult()
    }

    //ITaskContributor
    override fun tasksFor(context: KobaltContext): List<DynamicTask> = taskContributor.dynamicTasks
}

@Directive
fun Project.install(init: InstallConfig.() -> Unit) {
    InstallConfig().let {
        it.init()
        (Kobalt.findPlugin(PackagingPlugin.PLUGIN_NAME) as PackagingPlugin).addConfiguration(this, it)
    }
}

class InstallConfig(var libDir : String = "libs")

@Directive
fun Project.assemble(init: PackageConfig.(p: Project) -> Unit) = let {
    PackageConfig(this).apply { init(it) }
}

class PackageConfig(val project: Project) : AttributeHolder {
    val jars = arrayListOf<Jar>()
    val wars = arrayListOf<War>()
    val zips = arrayListOf<Zip>()
    var generatePom: Boolean = false

    init {
        (Kobalt.findPlugin(PackagingPlugin.PLUGIN_NAME) as PackagingPlugin).addPackage(this)
    }

    @Directive
    fun jar(init: Jar.(p: Jar) -> Unit) = Jar().apply {
        init(this)
        jars.add(this)
    }

    @Directive
    fun zip(init: Zip.(p: Zip) -> Unit) = Zip().apply {
        init(this)
        zips.add(this)
    }

    @Directive
    fun war(init: War.(p: War) -> Unit) = War().apply {
        init(this)
        wars.add(this)
    }

    /**
     * Package all the jar files necessary for a maven repo: classes, sources, javadocs.
     */
    @Directive
    fun mavenJars(init: MavenJars.(p: MavenJars) -> Unit) : MavenJars {
        val m = MavenJars(this)
        m.init(m)

        val mainJar = jar {
            fatJar = m.fatJar
        }
        jar {
            name = "${project.name}-${project.version}-sources.jar"
            project.sourceDirectories.forEach {
                if (File(project.directory, it).exists()) {
                    include(from(it), to(""), glob("**"))
                }
            }
        }
        jar {
            name = "${project.name}-${project.version}-javadoc.jar"
            include(from(JvmCompilerPlugin.DOCS_DIRECTORY), to(""), glob("**"))
        }

        mainJarAttributes.forEach {
            mainJar.addAttribute(it.first, it.second)
        }

        generatePom = true

        return m
    }

    val mainJarAttributes = arrayListOf<Pair<String, String>>()

    override fun addAttribute(k: String, v: String) {
        mainJarAttributes.add(Pair(k, v))
    }

    class MavenJars(val ah: AttributeHolder, var fatJar: Boolean = false, var manifest: Manifest? = null) :
            AttributeHolder by ah {
        fun manifest(init: Manifest.(p: Manifest) -> Unit) : Manifest {
            val m = Manifest(this)
            m.init(m)
            return m
        }
    }
}

class Pom {

}

