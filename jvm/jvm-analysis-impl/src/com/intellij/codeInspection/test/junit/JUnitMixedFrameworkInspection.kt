// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.test.junit

import com.intellij.analysis.JvmAnalysisBundle
import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.FileModificationService
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.fix.RemoveAnnotationQuickFix
import com.intellij.codeInspection.fix.RenameQuickFix
import com.intellij.lang.jvm.DefaultJvmElementVisitor
import com.intellij.lang.jvm.JvmAnnotation
import com.intellij.lang.jvm.JvmElementVisitor
import com.intellij.lang.jvm.JvmMethod
import com.intellij.lang.jvm.inspection.JvmLocalInspection
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.asSafely
import com.siyeh.ig.junit.JUnitCommonClassNames.*
import com.siyeh.ig.psiutils.TestUtils
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElementOfType
import java.util.*

class JUnitMixedFrameworkInspection : JvmLocalInspection() {
  private fun shouldInspect(file: PsiFile): Boolean {
    var frameworkCount = 0
    if (isJUnit3InScope(file)) frameworkCount++
    if (isJUnit4InScope(file)) frameworkCount++
    if (isJUnit5InScope(file)) frameworkCount++
    return frameworkCount > 1
  }

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
    if (!shouldInspect(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return super.buildVisitor(holder, isOnTheFly)
  }

  override fun buildVisitor(project: Project, sink: HighlightSink, isOnTheFly: Boolean): JvmElementVisitor<Boolean> {
    return JUnitMixedAnnotationVisitor(sink)
  }
}

private class JUnitMixedAnnotationVisitor(private val sink: JvmLocalInspection.HighlightSink) : DefaultJvmElementVisitor<Boolean> {
  override fun visitMethod(method: JvmMethod): Boolean {
    val containingClass = method.containingClass?.asSafely<PsiClass>() ?: return true
    if (AnnotationUtil.isAnnotated(containingClass, TestUtils.RUN_WITH, AnnotationUtil.CHECK_HIERARCHY)) return true
    val preferedTestFramework = getPreferedTestFramework(containingClass) ?: return true
    when (preferedTestFramework) {
      JUnitVersion.V3 -> {
        prefixAnnotationHighlight(method, ORG_JUNIT_TEST, "test", true)
        prefixAnnotationHighlight(method, ORG_JUNIT_IGNORE, "_")
        annotationHighlight(method, *junit4RemoveAnnotations, version = preferedTestFramework) { ann ->
          listOf(RemoveAnnotationQuickFix(ann), JUnit4ConverterQuickfix())
        }
        prefixAnnotationHighlight(method, ORG_JUNIT_JUPITER_API_TEST, "test", true)
        prefixAnnotationHighlight(method, ORG_JUNIT_JUPITER_API_DISABLED, "_")
        annotationHighlight(method, *junit5RemoveAnnotations, version = preferedTestFramework) { ann ->
          listOf(RemoveAnnotationQuickFix(ann))
        }
      }
      JUnitVersion.V4 -> {
        annotationHighlight(method, *junit5Annotations, version = preferedTestFramework) { _ -> emptyList() } // TODO quickfix
      }
      JUnitVersion.V5 -> {
        annotationHighlight(method, *junit4Annotations, version = preferedTestFramework) { _ -> listOf(JUnit5ConverterQuickFix()) }
      }
    }
    return true
  }

  private fun prefixAnnotationHighlight(method: JvmMethod, annFqn: String, prefix: String, capitalize: Boolean = false) {
    method.getAnnotation(annFqn)?.let { annotation ->
      sink.highlight(junitMessage(annotation, JUnitVersion.V3), RemoveAnnotationAndPrefixQuickFix(annotation, prefix, capitalize))
    }
  }

  private fun annotationHighlight(method: JvmMethod, vararg annFqn: String, version: JUnitVersion, fix: (JvmAnnotation) -> List<LocalQuickFix>) {
    annFqn.mapNotNull { fqn -> method.getAnnotation(fqn) }.forEach { annotation ->
      sink.highlight(junitMessage(annotation, version), *fix(annotation).toTypedArray())
    }
  }

  private fun junitMessage(annotation: JvmAnnotation, version: JUnitVersion) = JvmAnalysisBundle.message(
    "jvm.inspections.junit.mixed.annotations.junit.descriptor",
    annotation.qualifiedName?.substringAfterLast("."), version.intRepresentation
  )

  private companion object {
    val junit4RemoveAnnotations = arrayOf(
      ORG_JUNIT_BEFORE, ORG_JUNIT_AFTER,
      ORG_JUNIT_BEFORE_CLASS, ORG_JUNIT_AFTER_CLASS
    )

    val junit5RemoveAnnotations = arrayOf(
      ORG_JUNIT_JUPITER_API_BEFORE_EACH, ORG_JUNIT_JUPITER_API_AFTER_EACH,
      ORG_JUNIT_JUPITER_API_BEFORE_ALL, ORG_JUNIT_JUPITER_API_AFTER_ALL
    )
  }
}

private class RemoveAnnotationAndPrefixQuickFix(
  annotation: JvmAnnotation,
  private val prefix: String,
  private val capitalize: Boolean = false
) : LocalQuickFix {
  val annotationPointer = SmartPointerManager.createPointer(annotation as PsiAnnotation)

  override fun getFamilyName(): String = JvmAnalysisBundle.message(
    "jvm.inspections.remove.annotation.quickfix.text",
    annotationPointer.element.asSafely<JvmAnnotation>()?.qualifiedName?.substringAfterLast(".")
  )

  override fun startInWriteAction(): Boolean = false

  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
    val annotation = PsiTreeUtil.findSameElementInCopy(
      annotationPointer.element?.navigationElement, previewDescriptor.psiElement.containingFile
    ).toUElementOfType<UAnnotation>() ?: return IntentionPreviewInfo.EMPTY
    val method = annotation.getParentOfType<UMethod>()
    if (method?.name?.startsWith(prefix) == false) {
      RenameQuickFix(method.javaPsi, prefix + method.name.capitalize()).generatePreview(project, previewDescriptor)
    }
    annotation.sourcePsi?.delete()
    return IntentionPreviewInfo.DIFF
  }

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    val annotation = annotationPointer.element ?: return
    val method = annotation.parentOfType<PsiMethod>()
    if (method?.name?.startsWith(prefix) == false) {
      RenameQuickFix(method, prefix + method.name.capitalize()).applyFix(project, descriptor)
    }
    if (!FileModificationService.getInstance().preparePsiElementForWrite(annotation)) return
    runWriteAction {
      annotation.delete()
    }
  }

  private fun String.capitalize(): String {
    return if (capitalize) replaceFirstChar {
      if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    } else this
  }
}