// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.base.fir.analysisApiProviders

import com.intellij.codeInsight.JavaLibraryModificationTracker
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.analysis.project.structure.KtModule
import org.jetbrains.kotlin.analysis.project.structure.KtSourceModule
import org.jetbrains.kotlin.analysis.providers.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.providers.KtModuleStateTracker
import org.jetbrains.kotlin.idea.base.analysisApiProviders.KotlinModuleStateTrackerProvider
import org.jetbrains.kotlin.idea.base.projectStructure.ideaModule

internal class FirIdeKotlinModificationTrackerFactory(private val project: Project) : KotlinModificationTrackerFactory() {
    override fun createProjectWideOutOfBlockModificationTracker(): ModificationTracker {
        return KotlinFirOutOfBlockModificationTracker(project)
    }

    override fun createModuleWithoutDependenciesOutOfBlockModificationTracker(module: KtSourceModule): ModificationTracker {
        return KotlinFirOutOfBlockModuleModificationTracker(module.ideaModule)
    }

    override fun createLibrariesWideModificationTracker(): ModificationTracker {
        return JavaLibraryModificationTracker.getInstance(project)
    }

    override fun createModuleStateTracker(module: KtModule): KtModuleStateTracker {
        return KotlinModuleStateTrackerProvider.getInstance(project).getModuleStateTrackerFor(module)
    }

    @TestOnly
    override fun incrementModificationsCount(includeBinaryTrackers: Boolean) {
        if (includeBinaryTrackers) {
            (createLibrariesWideModificationTracker() as JavaLibraryModificationTracker).incModificationCount()
        }

        // `FirIdeModificationTrackerService` is for source modules only.
        project.getService(FirIdeModificationTrackerService::class.java).increaseModificationCountForAllModules()

        KotlinModuleStateTrackerProvider.getInstance(project).incrementModificationCountForAllModules(includeBinaryTrackers)
    }
}

private class KotlinFirOutOfBlockModificationTracker(project: Project) : ModificationTracker {
    private val trackerService = project.getService(FirIdeModificationTrackerService::class.java)

    override fun getModificationCount(): Long {
        return trackerService.projectGlobalOutOfBlockInKotlinFilesModificationCount
    }
}

private class KotlinFirOutOfBlockModuleModificationTracker(private val module: Module) : ModificationTracker {
    private val trackerService = module.project.getService(FirIdeModificationTrackerService::class.java)

    override fun getModificationCount(): Long {
        return trackerService.getOutOfBlockModificationCountForModules(module)
    }

    override fun toString(): String {
        return "Out-of-block tracker for IDEA module '" + module.name + "'"
    }
}