// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.testFramework

import com.intellij.ide.highlighter.ProjectFileType
import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.coroutineDispatchingContext
import com.intellij.openapi.application.runUndoTransparentWriteAction
import com.intellij.openapi.command.impl.UndoManagerImpl
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.components.impl.stores.IProjectStore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.project.impl.ProjectManagerImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFSImpl
import com.intellij.project.stateStore
import com.intellij.util.containers.forEachGuaranteed
import com.intellij.util.io.systemIndependentPath
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

private var sharedModule: Module? = null

open class ApplicationRule : ExternalResource() {
  companion object {
    init {
      Logger.setFactory(TestLoggerFactory::class.java)
    }
  }

  public final override fun before() {
    IdeaTestApplication.getInstance()
    TestRunnerUtil.replaceIdeEventQueueSafely()
    (PersistentFS.getInstance() as PersistentFSImpl).cleanPersistedContents()
  }
}

/**
 * Project created on request, so, could be used as a bare (only application).
 */
class ProjectRule(val projectDescriptor: LightProjectDescriptor = LightProjectDescriptor()) : ApplicationRule() {
  companion object {
    private var sharedProject: ProjectEx? = null
    private val projectOpened = AtomicBoolean()

    private fun createLightProject(): ProjectEx {
      (PersistentFS.getInstance() as PersistentFSImpl).cleanPersistedContents()

      val projectFile = generateTemporaryPath("light_temp_shared_project${ProjectFileType.DOT_DEFAULT_EXTENSION}")
      val projectPath = projectFile.systemIndependentPath

      val buffer = ByteArrayOutputStream()
      Throwable(projectPath, null).printStackTrace(PrintStream(buffer))

      val project = PlatformTestCase.createProject(projectPath, "Light project: $buffer") as ProjectEx
      PlatformTestUtil.registerProjectCleanup {
        try {
          disposeProject()
        }
        finally {
          Files.deleteIfExists(projectFile)
        }
      }

      // TODO uncomment and figure out where to put this statement
//      (VirtualFilePointerManager.getInstance() as VirtualFilePointerManagerImpl).storePointers()
      return project
    }

    private fun disposeProject() {
      val project = sharedProject ?: return
      sharedProject = null
      sharedModule = null
      ProjectManagerEx.getInstanceEx().forceCloseProject(project, true)
      // TODO uncomment and figure out where to put this statement
//      (VirtualFilePointerManager.getInstance() as VirtualFilePointerManagerImpl).assertPointersAreDisposed()
    }
  }

  public override fun after() {
    if (projectOpened.compareAndSet(true, false)) {
      sharedProject?.let { runInEdtAndWait { ProjectManagerEx.getInstanceEx().forceCloseProject(it, false) } }
    }
  }

  val projectIfOpened: ProjectEx?
    get() = if (projectOpened.get()) sharedProject else null

  val project: ProjectEx
    get() {
      var result = sharedProject
      if (result == null) {
        synchronized(IdeaTestApplication.getInstance()) {
          result = sharedProject
          if (result == null) {
            result = createLightProject()
            sharedProject = result
          }
        }
      }

      if (projectOpened.compareAndSet(false, true)) {
        runInEdtAndWait { ProjectManagerEx.getInstanceEx().openTestProject(project) }
      }
      return result!!
    }

  val module: Module
    get() {
      var result = sharedModule
      if (result == null) {
        runInEdtAndWait {
          projectDescriptor.setUpProject(project, object : LightProjectDescriptor.SetupHandler {
            override fun moduleCreated(module: Module) {
              result = module
              sharedModule = module
            }
          })
        }
      }
      return result!!
    }
}

/**
 * rules: outer, middle, inner
 * out:
 * starting outer rule
 * starting middle rule
 * starting inner rule
 * finished inner rule
 * finished middle rule
 * finished outer rule
 */
class RuleChain(vararg val rules: TestRule) : TestRule {
  override fun apply(base: Statement, description: Description): Statement {
    var statement = base
    for (i in (rules.size - 1) downTo 0) {
      statement = rules[i].apply(statement, description)
    }
    return statement
  }
}

private fun <T : Annotation> Description.getOwnOrClassAnnotation(annotationClass: Class<T>) = getAnnotation(annotationClass) ?: testClass?.getAnnotation(annotationClass)

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class RunsInEdt

class EdtRule : TestRule {
  override fun apply(base: Statement, description: Description): Statement {
    return if (description.getOwnOrClassAnnotation(RunsInEdt::class.java) == null) {
      base
    }
    else {
      statement { runInEdtAndWait { base.evaluate() } }
    }
  }
}

class InitInspectionRule : TestRule {
  override fun apply(base: Statement, description: Description): Statement = statement { runInInitMode { base.evaluate() } }
}

inline fun statement(crossinline runnable: () -> Unit): Statement = object : Statement() {
  override fun evaluate() {
    runnable()
  }
}

/**
 * Do not optimise test load speed.
 * @see IProjectStore.setOptimiseTestLoadSpeed
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class RunsInActiveStoreMode

class ActiveStoreRule(private val projectRule: ProjectRule) : TestRule {
  override fun apply(base: Statement, description: Description): Statement {
    if (description.getOwnOrClassAnnotation(RunsInActiveStoreMode::class.java) == null) {
      return base
    }
    else {
      return statement {
        projectRule.project.runInLoadComponentStateMode { base.evaluate() }
      }
    }
  }
}

/**
 * In test mode component state is not loaded. Project or module store will load component state if project/module file exists.
 * So must be a strong reason to explicitly use this method.
 */
inline fun <T> Project.runInLoadComponentStateMode(task: () -> T): T {
  val store = stateStore
  val isModeDisabled = store.isOptimiseTestLoadSpeed
  if (isModeDisabled) {
    store.isOptimiseTestLoadSpeed = false
  }
  try {
    return task()
  }
  finally {
    if (isModeDisabled) {
      store.isOptimiseTestLoadSpeed = true
    }
  }
}

fun createHeavyProject(path: String, isUseDefaultProjectSettings: Boolean = false): Project {
  return ProjectManagerEx.getInstanceEx().newProject(null, path, isUseDefaultProjectSettings, false)!!
}

suspend fun Project.use(task: suspend (Project) -> Unit) {
  val projectManager = ProjectManagerEx.getInstanceEx()
  try {
    withContext(AppUIExecutor.onUiThread().coroutineDispatchingContext()) {
      projectManager.openTestProject(this@use)
    }
    task(this)
  }
  finally {
    withContext(AppUIExecutor.onUiThread().coroutineDispatchingContext()) {
      projectManager.forceCloseProject(this@use, true)
    }
  }
}

class DisposeNonLightProjectsRule : ExternalResource() {
  override fun after() {
    val projectManager = if (ApplicationManager.getApplication().isDisposed) null else ProjectManagerEx.getInstanceEx()
    projectManager?.openProjects?.forEachGuaranteed {
      if (!ProjectManagerImpl.isLight(it)) {
        runInEdtAndWait { projectManager.forceCloseProject(it, true) }
      }
    }
  }
}

class DisposeModulesRule(private val projectRule: ProjectRule) : ExternalResource() {
  override fun after() {
    projectRule.projectIfOpened?.let { project ->
      val moduleManager = ModuleManager.getInstance(project)
      runInEdtAndWait {
        moduleManager.modules.forEachGuaranteed {
          if (!it.isDisposed && it !== sharedModule) {
            moduleManager.disposeModule(it)
          }
        }
      }
    }
  }
}

/**
 * Only and only if "before" logic in case of exception doesn't require "after" logic - must be no side effects if "before" finished abnormally.
 * So, should be one task per rule.
 */
class WrapRule(private val before: () -> () -> Unit) : TestRule {
  override fun apply(base: Statement, description: Description): Statement = statement {
    val after = before()
    try {
      base.evaluate()
    }
    finally {
      after()
    }
  }
}

suspend fun createProjectAndUseInLoadComponentStateMode(tempDirManager: TemporaryDirectory, directoryBased: Boolean = false, task: suspend (Project) -> Unit) {
  createOrLoadProject(tempDirManager, task = task, directoryBased = directoryBased, loadComponentState = true)
}

suspend fun loadAndUseProjectInLoadComponentStateMode(tempDirManager: TemporaryDirectory, projectCreator: (suspend (VirtualFile) -> String)? = null, task: suspend (Project) -> Unit) {
  createOrLoadProject(tempDirManager, projectCreator, task = task, directoryBased = false, loadComponentState = true)
}

suspend fun <T> runNonUndoableWriteAction(file: VirtualFile, runnable: suspend () -> T): T {
  return runUndoTransparentWriteAction {
    val result = runBlocking { runnable() }
    val documentReference = DocumentReferenceManager.getInstance().create(file)
    val undoManager = com.intellij.openapi.command.undo.UndoManager.getGlobalInstance() as UndoManagerImpl
    undoManager.nonundoableActionPerformed(documentReference, false)
    result
  }
}

suspend fun createOrLoadProject(tempDirManager: TemporaryDirectory, projectCreator: (suspend (VirtualFile) -> String)? = null, directoryBased: Boolean = true, loadComponentState: Boolean = false, task: suspend (Project) -> Unit) {
  withContext(AppUIExecutor.onUiThread().coroutineDispatchingContext()) {
    val filePath = if (projectCreator == null) {
      tempDirManager.newPath("test${if (directoryBased) "" else ProjectFileType.DOT_DEFAULT_EXTENSION}", refreshVfs = true).systemIndependentPath
    }
    else {
      val dir = tempDirManager.newVirtualDirectory()
      runNonUndoableWriteAction(dir) {
        projectCreator(dir)
      }
    }

    val project = when (projectCreator) {
      null -> createHeavyProject(filePath, isUseDefaultProjectSettings = true)
      else -> ProjectManagerEx.getInstanceEx().loadProject(filePath)!!
    }
    if (loadComponentState) {
      project.runInLoadComponentStateMode {
        project.use(task)
      }
    }
    else {
      project.use(task)
    }
  }
}

class DisposableRule : ExternalResource() {
  private var _disposable = lazy { Disposer.newDisposable() }

  val disposable: Disposable
    get() = _disposable.value

  override fun after() {
    if (_disposable.isInitialized()) {
      Disposer.dispose(_disposable.value)
    }
  }
}