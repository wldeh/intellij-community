// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplacePutWithAssignment")

package org.jetbrains.intellij.build.impl

import org.jetbrains.annotations.ApiStatus.Obsolete
import org.jetbrains.intellij.build.BuildContext
import java.util.function.BiConsumer

sealed class BaseLayoutSpec(private val layout: BaseLayout) {
  /**
   * Register an additional module to be included into the plugin distribution. Module-level libraries from
   * {@code moduleName} with scopes 'Compile' and 'Runtime' will be also copied to the 'lib' directory of the plugin.
   */
  fun withModule(moduleName: String) {
    layout.withModule(moduleName)
  }

  fun withModules(names: Iterable<String>) {
    layout.withModules(names)
  }

  /**
   * Register an additional module to be included into the plugin distribution. If {@code relativeJarPath} doesn't contain '/' (i.e. the
   * JAR will be added to the plugin's classpath) this will also cause modules library from {@code moduleName} with scopes 'Compile' and
   * 'Runtime' to be copied to the 'lib' directory of the plugin.
   *
   * @param relativeJarPath target JAR path relative to 'lib' directory of the plugin; different modules may be packed into the same JAR,
   * but <strong>don't use this for new plugins</strong>; this parameter is temporary added to keep layout of old plugins.
   */
  fun withModule(moduleName: String, relativeJarPath: String) {
    layout.withModule(moduleName, relativeJarPath)
  }

  /**
   * Include the project library to 'lib' directory or its subdirectory of the plugin distribution
   * @param libraryName path relative to 'lib' plugin directory
   */
  fun withProjectLibrary(libraryName: String) {
    layout.withProjectLibrary(libraryName)
  }

  fun withProjectLibraries(libraryNames: Iterable<String>) {
    layout.withProjectLibraries(libraryNames)
  }

  fun withProjectLibrary(libraryName: String, outPath: String) {
    layout.includedProjectLibraries.add(ProjectLibraryData(libraryName = libraryName, packMode = LibraryPackMode.MERGED, outPath = outPath))
  }

  fun withProjectLibrary(libraryName: String, packMode: LibraryPackMode) {
    layout.includedProjectLibraries.add(ProjectLibraryData(libraryName = libraryName, packMode = packMode))
  }

  fun withProjectLibrary(libraryName: String, outPath: String, packMode: LibraryPackMode) {
    layout.includedProjectLibraries.add(ProjectLibraryData(libraryName = libraryName, packMode = packMode, outPath = outPath))
  }

  /**
   * Include the module library to the plugin distribution. Please note that it makes sense to call this method only
   * for additional modules which aren't copied directly to the 'lib' directory of the plugin distribution, because for ordinary modules
   * their module libraries are included into the layout automatically.
   * @param relativeOutputPath target path relative to 'lib' directory
   */
  fun withModuleLibrary(libraryName: String, moduleName: String, relativeOutputPath: String, extraCopy: Boolean = false) {
    layout.withModuleLibrary(libraryName = libraryName, moduleName = moduleName, relativeOutputPath = relativeOutputPath, extraCopy = extraCopy)
  }

  /**
   * Exclude the specified files when {@code moduleName} is packed into JAR file.
   * <strong>This is a temporary method added to keep layout of some old plugins. If some files from a module shouldn't be included into the
   * module JAR it's strongly recommended to move these files outside the module source roots.</strong>
   * @param excludedPattern Ant-like pattern describing files to be excluded (relatively to the module output root); e.g. foo&#47;**
   * to exclude `foo` directory
  */
  fun excludeFromModule(moduleName: String, excludedPattern: String) {
    layout.excludeFromModule(moduleName, excludedPattern)
  }

  fun excludeFromModule(moduleName: String, excludedPatterns: List<String>) {
    layout.excludeFromModule(moduleName, excludedPatterns)
  }

  /**
   * Include an artifact output to the plugin distribution.
   * @param artifactName name of the project configuration
   * @param relativeOutputPath target path relative to 'lib' directory
   */
  fun withArtifact(artifactName: String, relativeOutputPath: String) {
    layout.includedArtifacts = layout.includedArtifacts.put(artifactName, relativeOutputPath)
  }

  /**
   * Include contents of JARs of the project library {@code libraryName} into JAR {@code jarName}
   */
  fun withProjectLibraryUnpackedIntoJar(libraryName: String, jarName: String) {
    layout.withProjectLibrary(libraryName, jarName)
  }

  fun withPatch(patcher: suspend (ModuleOutputPatcher, BuildContext) -> Unit) {
    layout.withPatch(patcher)
  }

  @Obsolete
  fun withSyncPatch(patcher: BiConsumer<ModuleOutputPatcher, BuildContext>) {
    layout.withPatch(patcher::accept)
  }
}
