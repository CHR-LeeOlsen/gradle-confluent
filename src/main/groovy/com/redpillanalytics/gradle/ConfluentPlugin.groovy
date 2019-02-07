package com.redpillanalytics.gradle

import com.redpillanalytics.common.GradleUtils
import com.redpillanalytics.gradle.containers.TaskGroupContainer
import com.redpillanalytics.gradle.tasks.PipelineExecuteTask
import com.redpillanalytics.gradle.tasks.PipelineScriptTask
import com.redpillanalytics.gradle.tasks.LoadConfigTask
import groovy.util.logging.Slf4j
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

@Slf4j
class ConfluentPlugin implements Plugin<Project> {

   /**
    * Apply the Gradle Plugin.
    */
   void apply(Project project) {

      // apply Gradle built-in plugins
      project.apply plugin: 'base'

      // apply the Gradle plugin extension and the context container
      applyExtension(project)

      project.afterEvaluate {

         // Go look for any -P properties that have "confluent." in them
         // If so... update the extension value
         GradleUtils.setParameters(project, 'confluent')

         // add task to show configurations
         project.task('showConfigurations') {

            group "help"

            doLast {
               project.configurations.each { config ->
                  log.info config.toString()
               }
            }
         }

         // get the taskGroup
         String taskGroup = project.extensions.confluent.taskGroup

         // get the location of the SQL source files
         File pipelineDir = project.file(project.extensions.confluent.getPipelinePath())
         log.debug "pipelineDir: ${pipelineDir.getCanonicalPath()}"

         File pipelineBuildDir = project.file("${project.buildDir}/${project.extensions.confluent.pipelineBuildName}")
         log.debug "pipelineBuildDir: ${pipelineBuildDir.canonicalPath}"

         File pipelineDeployDir = project.file("${project.buildDir}/${project.extensions.confluent.pipelineDeployName}")
         log.debug "pipelineDeployDir: ${pipelineDeployDir.canonicalPath}"

         File functionDeployDir = project.file("${project.buildDir}/${project.extensions.confluent.functionDeployName}")
         log.debug "pipelineDeployDir: ${pipelineDeployDir.canonicalPath}"

         String pipelinePattern = project.extensions.confluent.pipelinePattern
         log.debug "pipelinePattern: ${pipelinePattern}"

         String functionPattern = project.extensions.confluent.functionPattern
         //log.debug "functionPattern: ${functionPattern}"

         String configPath = project.extensions.confluent.configPath
         //log.debug "configPath: ${configPath}"

         String configEnv = project.extensions.confluent.configEnv
         //log.debug "configEnv: ${configEnv}"

         Boolean enablePipelines = project.extensions.confluent.enablePipelines
         //log.debug "enablePipelines: ${enablePipelines}"

         Boolean enableFunctions = project.extensions.confluent.enableFunctions
         //log.debug "enableFunctions: ${enableFunctions}"

         Boolean enableStreams = project.extensions.confluent.enableStreams
         //log.debug "enableStreams: ${enableStreams}"

         // create deploy task
         project.task('deploy') {
            group taskGroup
            description "Execute any configured deployment tasks."
         }

         // configure build groups
         project.confluent.taskGroups.all { tg ->

            if (tg.isBuildEnv && enablePipelines) {

               project.task(tg.getTaskName('pipelineSync'), type: Sync) {
                  group taskGroup
                  description = "Synchronize the pipeline build directory from the pipeline source directory."
                  from pipelineDir
                  into pipelineBuildDir
                  doFirst {
                     log.info "Synchronizing '$pipelineBuildDir' from '$pipelineDir'."
                  }
               }

               project.build.dependsOn tg.getTaskName('pipelineSync')

               project.task(tg.getTaskName('pipelineScript'), type: PipelineScriptTask) {

                  group taskGroup
                  description('Build a single KSQL deployment script with individual pipeline processes ordered and normalized.'
                          + ' Primarily used for building a KSQL queries file used for KSQL Server startup.')

                  pipelinePath pipelineBuildDir.canonicalPath
                  onlyIf { dir.exists() }
                  dependsOn tg.getTaskName('pipelineSync')
               }

               project.build.dependsOn tg.getTaskName('pipelineScript')

               project.task(tg.getTaskName('pipelineZip'), type: Zip) {
                  group taskGroup
                  description "Build a distribution ZIP file with original pipeline source files plus the KSQL queries file generated by '${tg.getTaskName('pipelineScript')}'."
                  appendix = project.extensions.confluent.pipelinePattern
                  includeEmptyDirs false
                  from pipelineBuildDir
                  dependsOn tg.getTaskName('pipelineScript')
                  onlyIf { pipelineBuildDir.exists() }
               }

               project.build.dependsOn tg.getTaskName('pipelineZip')

               project.task(tg.getTaskName('pipelineExecute'), type: PipelineExecuteTask) {
                  group taskGroup
                  description = "Execute all KSQL pipelines from the provided source directory, in hierarchical order, with options for auto-generating and executing DROP and TERMINATE commands."
                  pipelinePath pipelineBuildDir.canonicalPath
                  onlyIf { pipelineBuildDir.exists() }
                  dependsOn tg.getTaskName('pipelineSync')
                  outputs.upToDateWhen { false }
               }
            }

            if (enablePipelines && tg.isDeployEnv) {
               if (GradleUtils.isUsableConfiguration(project, 'archives', pipelinePattern)) {

                  project.task(tg.getTaskName('pipelineExtract'), type: Copy) {
                     group taskGroup
                     description = "Extract the KSQL pipeline deployment dependency (or zip file) into the deployment directory."
                     from project.zipTree(GradleUtils.getDependency(project, 'archives', pipelinePattern))
                     into { pipelineDeployDir }

                  }

                  project.task(tg.getTaskName('pipelineDeploy'), type: PipelineExecuteTask) {
                     group taskGroup
                     description = "Execute all KSQL pipelines extracted from an artifact dependency, in hierarchical order, with options for auto-generating and executing DROP and TERMINATE commands."
                     pipelinePath pipelineDeployDir.canonicalPath
                     onlyIf { pipelineDeployDir.exists() }
                     dependsOn tg.getTaskName('pipelineExtract')
                     outputs.upToDateWhen { false }
                  }

                  project.deploy.dependsOn tg.getTaskName('pipelineDeploy')
               }
            }

            if (GradleUtils.isUsableConfiguration(project, 'archives', functionPattern) && enableFunctions && tg.isDeployEnv) {

               project.task(tg.getTaskName('functionCopy'), type: Copy) {
                  group taskGroup
                  description = "Copy the KSQL custom function deployment dependency (or JAR file) into the deployment directory."
                  from GradleUtils.getDependency(project, 'archives', functionPattern)
                  into { functionDeployDir }
                  if (project.extensions.confluent.functionArtifactName) rename {
                     project.extensions.confluent.functionArtifactName
                  }
               }

               project.deploy.dependsOn tg.getTaskName('functionCopy')
            }

            if (tg.isBuildEnv && enableStreams && project.plugins.hasPlugin(ApplicationPlugin)) {
               project.task(tg.getTaskName('loadConfig'), type: LoadConfigTask) {
                  group taskGroup
                  description "Load a config file using ConfigSlurper."
                  filePath configPath
                  environment configEnv
                  onlyIf { configFile.exists() }
               }
               project.build.dependsOn tg.getTaskName('loadConfig')
            }

         }

         // a bit of a hack at the moment

         if (project.tasks.findByName('loadConfig')) {

            project.tasks.each {
               task ->
                  if ((task.group == 'confluent' || task.group == 'build') && task.name != 'loadConfig') {
                     task.mustRunAfter project.loadConfig
                  }
            }
         }

         if (enablePipelines && project.tasks.findByName('pipelineZip') && project.plugins.hasPlugin(MavenPublishPlugin)) {

            project.publishing.publications {

               pipeline(MavenPublication) {
                  artifact project.pipelineZip {
                     artifactId project.archivesBaseName + '-' + pipelinePattern
                  }
               }
            }
         }
      }

      // end of afterEvaluate
   }
   /**
    * Apply the Gradle Plugin extension.
    */
   void applyExtension(Project project) {

      project.configure(project) {
         extensions.create('confluent', ConfluentPluginExtension)
      }

      project.confluent.extensions.taskGroups = project.container(TaskGroupContainer)

      project.extensions.confluent.taskGroups.add(new TaskGroupContainer(name: 'default'))

   }
}

