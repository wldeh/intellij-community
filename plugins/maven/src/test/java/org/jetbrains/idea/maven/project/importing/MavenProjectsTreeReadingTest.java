/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.maven.project.importing;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.model.MavenExplicitProfiles;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.server.NativeMavenProjectHolder;
import org.jetbrains.idea.maven.utils.MavenProcessCanceledException;
import org.jetbrains.idea.maven.utils.MavenProgressIndicator;
import org.jetbrains.idea.maven.utils.MavenUtil;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class MavenProjectsTreeReadingTest extends MavenProjectsTreeTestCase {
  @Test 
  public void testTwoRootProjects() {
    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(m1, m2);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(2, roots.size());
    assertEquals(m1, roots.get(0).getFile());
    assertEquals(m2, roots.get(1).getFile());
  }

  @Test 
  public void testModulesWithWhiteSpaces() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>
                         m  </module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testDoNotImportChildAsRootProject() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom, m);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test
  public void testDoNotImportSameRootProjectTwice() {
    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(m1, m2, m1);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(2, roots.size());
    assertEquals(m1, roots.get(0).getFile());
    assertEquals(m2, roots.get(1).getFile());

    assertEquals(log().add("updated", "m1", "m2").add("deleted"), listener.log);
  }

  @Test 
  public void testRereadingChildIfParentWasReadAfterIt() {
    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       <properties>
                                        <childId>m2</childId>
                                       </properties>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>${childId}</artifactId>
                                       <version>1</version>
                                       <parent>
                                         <groupId>test</groupId>
                                         <artifactId>m1</artifactId>
                                         <version>1</version>
                                       </parent>
                                       """);

    updateAll(m2, m1);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertEquals(m1, roots.get(0).getFile());
    assertEquals(m2, roots.get(1).getFile());
    assertEquals("m1", roots.get(0).getMavenId().getArtifactId());
    assertEquals("m2", roots.get(1).getMavenId().getArtifactId());

    assertEquals(log().add("updated", "m2", "m1").add("deleted"), listener.log);
  }

  @Test 
  public void testSameProjectAsModuleOfSeveralProjects() {
    VirtualFile p1 = createModulePom("project1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>project1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>../module</module>
                                       </modules>
                                       """);

    VirtualFile p2 = createModulePom("project2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>project2</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>../module</module>
                                       </modules>
                                       """);

    VirtualFile m = createModulePom("module",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>module</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(p1, p2);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(2, roots.size());
    assertEquals(p1, roots.get(0).getFile());
    assertEquals(p2, roots.get(1).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());

    assertEquals(0, myTree.getModules(roots.get(1)).size());
  }

  @Test 
  public void testSameProjectAsModuleOfSeveralProjectsInHierarchy() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>module1</module>
                         <module>module1/module2</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("module1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>module1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>module2</module>
                                       </modules>
                                       """);

    VirtualFile m2 = createModulePom("module1/module2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>module2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(myProjectPom);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    var allModules = collectAllModulesRecursively(myTree, roots.get(0));
    assertEquals(2, allModules.size());
    assertSameElements(Set.of(m1, m2), ContainerUtil.map(allModules, m -> m.getFile()));
  }

  private static List<MavenProject> collectAllModulesRecursively(MavenProjectsTree tree, MavenProject aggregator) {
    var directModules = new ArrayList<>(tree.getModules(aggregator));
    var allModules = new ArrayList<>(directModules);
    for (var directModule : directModules) {
      allModules.addAll(collectAllModulesRecursively(tree, directModule));
    }
    return allModules;
  }

  @Test 
  public void testRemovingChildProjectFromRootProjects() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    // all projects are processed in the specified order
    // if we have imported a child project as a root one,
    // we have to correct ourselves and to remove it from roots.
    updateAll(m, myProjectPom);
    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testSendingNotificationsWhenAggregationChanged() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                         <module>m2</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(myProjectPom, m1, m2);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(2, myTree.getModules(roots.get(0)).size());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                       </modules>
                       """);

    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());
    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());

    assertEquals(log().add("updated", "project", "m2").add("deleted"), listener.log);
  }

  @Test 
  public void testUpdatingWholeModel() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();

    MavenProject parentNode = roots.get(0);
    MavenProject childNode = myTree.getModules(roots.get(0)).get(0);

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project1</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    createModulePom("m", """
      <groupId>test</groupId>
      <artifactId>m1</artifactId>
      <version>1</version>
      """);

    updateAll(myProjectPom);

    roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());

    MavenProject parentNode1 = roots.get(0);
    MavenProject childNode1 = myTree.getModules(roots.get(0)).get(0);

    assertSame(parentNode, parentNode1);
    assertSame(childNode, childNode1);

    assertEquals("project1", parentNode1.getMavenId().getArtifactId());
    assertEquals("m1", childNode1.getMavenId().getArtifactId());
  }

  @Test 
  public void testForceUpdatingWholeModel() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    MyLoggingListener l = new MyLoggingListener();
    myTree.addListener(l, getTestRootDisposable());

    updateAll(myProjectPom);
    assertEquals(log().add("updated", "project", "m").add("deleted"), l.log);
    l.log.clear();

    myTree.updateAll(false, getMavenGeneralSettings(), getMavenProgressIndicator());
    assertEquals(log(), l.log);
    l.log.clear();

    myTree.updateAll(true, getMavenGeneralSettings(), getMavenProgressIndicator());
    assertEquals(log().add("updated", "project", "m").add("deleted"), l.log);
  }

  @Test 
  public void testForceUpdatingSingleProject() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    MyLoggingListener l = new MyLoggingListener();
    myTree.addListener(l, getTestRootDisposable());

    update(myProjectPom);
    assertEquals(log().add("updated", "project", "m").add("deleted"), l.log);
    l.log.clear();

    myTree.update(Collections.singletonList(myProjectPom), false, getMavenGeneralSettings(), getMavenProgressIndicator());
    assertEquals(log(), l.log);
    l.log.clear();

    myTree.update(Collections.singletonList(myProjectPom), true, getMavenGeneralSettings(), getMavenProgressIndicator());
    assertEquals(log().add("updated", "project").add("deleted"), l.log);
    l.log.clear();
  }

  @Test 
  public void testUpdatingModelWithNewProfiles() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <profiles>
                         <profile>
                           <id>one</id>
                           <modules>
                             <module>m1</module>
                           </modules>
                         </profile>
                         <profile>
                           <id>two</id>
                           <modules>
                             <module>m2</module>
                           </modules>
                         </profile>
                       </profiles>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(Collections.singletonList("one"), myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m1, myTree.getModules(roots.get(0)).get(0).getFile());

    updateAll(Collections.singletonList("two"), myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());

    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m2, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testUpdatingParticularProject() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    createModulePom("m", """
      <groupId>test</groupId>
      <artifactId>m1</artifactId>
      <version>1</version>
      """);

    update(m);

    MavenProject n = myTree.findProject(m);
    assertEquals("m1", n.getMavenId().getArtifactId());
  }

  @Test 
  public void testUpdatingInheritance() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <childName>child</childName>
                       </properties>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(myProjectPom, child);
    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <childName>child2</childName>
                       </properties>
                       """);

    update(myProjectPom);

    assertEquals("child2", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testUpdatingInheritanceHierarhically() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <subChildName>subChild</subChildName>
                       </properties>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    VirtualFile subChild = createModulePom("subChild",
                                           """
                                             <groupId>test</groupId>
                                             <artifactId>${subChildName}</artifactId>
                                             <version>1</version>
                                             <parent>
                                               <groupId>test</groupId>
                                               <artifactId>child</artifactId>
                                               <version>1</version>
                                             </parent>
                                             """);

    updateAll(myProjectPom, child, subChild);

    assertEquals("subChild", myTree.findProject(subChild).getMavenId().getArtifactId());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <subChildName>subChild2</subChildName>
                       </properties>
                       """);

    update(myProjectPom);

    assertEquals("subChild2", myTree.findProject(subChild).getMavenId().getArtifactId());
  }

  @Test 
  public void testSendingNotificationAfterProjectIsAddedInToHierarchy() {
    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>m1</artifactId>
                       <version>1</version>
                       """);
    updateAll(myProjectPom);

    assertEquals(log().add("updated", "m1").add("deleted"), listener.log);
  }

  @Test 
  public void testSendingNotificationsWhenResolveFailed() throws Exception {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <name""");

    updateAll(myProjectPom);

    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    MavenProject project = myTree.findProject(myProjectPom);
    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    final List<NativeMavenProjectHolder> nativeProject = new ArrayList<>();
    try {
      myTree.addListener(new MavenProjectsTree.Listener() {
        @Override
        public void projectResolved(@NotNull Pair<MavenProject, MavenProjectChanges> projectWithChanges,
                                    NativeMavenProjectHolder nativeMavenProject) {
          nativeProject.add(nativeMavenProject);
        }
      }, getTestRootDisposable());
      resolve(myProject,
              project,
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator()
      );
    }
    finally {
      embeddersManager.releaseInTests();
    }

    assertEquals(log().add("resolved", "project"), listener.log);
    assertTrue(project.hasReadingProblems());
    assertSize(1, nativeProject);
    assertNull(nativeProject.get(0));
  }

  @Test 
  public void testDoNotUpdateChildAfterParentWasResolved() throws Exception {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    updateAll(myProjectPom, child);

    MavenProject parentProject = myTree.findProject(myProjectPom);

    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    try {
      final NativeMavenProjectHolder[] nativeProject = new NativeMavenProjectHolder[1];
      myTree.addListener(new MavenProjectsTree.Listener() {
        @Override
        public void projectResolved(@NotNull Pair<MavenProject, MavenProjectChanges> projectWithChanges,
                                    NativeMavenProjectHolder nativeMavenProject) {
          nativeProject[0] = nativeMavenProject;
        }
      }, getTestRootDisposable());
      resolve(myProject,
              parentProject,
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator()
      );

      var pluginResolver = new MavenPluginResolver(myTree);
      pluginResolver.resolvePlugins(List.of(new MavenProjectWithHolder(parentProject, nativeProject[0])),
                                    embeddersManager,
                                    NULL_MAVEN_CONSOLE,
                                    getMavenProgressIndicator(),
                                    false,
                                    false);

      var folderResolver = new MavenFolderResolver();
      folderResolver.resolveFolders(parentProject,
                                    myTree,
                                    getMavenImporterSettings(),
                                    embeddersManager,
                                    NULL_MAVEN_CONSOLE,
                                    getMavenProgressIndicator());
    }
    finally {
      embeddersManager.releaseInTests();
    }

    assertEquals(
      log()
        .add("updated", "parent", "child")
        .add("deleted")
        .add("resolved", "parent")
        .add("plugins", "parent")
        .add("folders", "parent"),
      listener.log);
    myTree.updateAll(false, getMavenGeneralSettings(), getMavenProgressIndicator());
    assertEquals(
      log()
        .add("updated", "parent", "child")
        .add("deleted")
        .add("resolved", "parent")
        .add("plugins", "parent")
        .add("folders", "parent"),
      listener.log);
  }

  @Test 
  public void testAddingInheritanceParent() {
    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(child);
    assertEquals("${childName}", myTree.findProject(child).getMavenId().getArtifactId());

    VirtualFile parent = createModulePom("parent",
                                         """
                                           <groupId>test</groupId>
                                           <artifactId>parent</artifactId>
                                           <version>1</version>
                                           <properties>
                                             <childName>child</childName>
                                           </properties>
                                           """);

    update(parent);

    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testAddingInheritanceChild() {
    VirtualFile parent = createModulePom("parent",
                                         """
                                           <groupId>test</groupId>
                                           <artifactId>parent</artifactId>
                                           <version>1</version>
                                           <properties>
                                             <childName>child</childName>
                                           </properties>
                                           """);

    updateAll(parent);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    update(child);

    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test
  public void testParentPropertyInterpolation() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <properties>
                         <childName>child</childName>
                       </properties>
                       """);
    update(myProjectPom);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    update(child);

    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test
  public void testAddingInheritanceChildOnParentUpdate() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <properties>
                         <childName>child</childName>
                       </properties>
                       <modules>
                         <module>child</module>
                       </modules>
                       """);

    updateAll(myProjectPom);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    update(myProjectPom);

    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testDoNotReAddInheritanceChildOnParentModulesRemoval() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <modules>
                        <module>child</module>
                       </modules>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);
    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(child, myTree.getModules(roots.get(0)).get(0).getFile());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       """);

    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(0, myTree.getModules(roots.get(0)).size());
  }

  @Test 
  public void testChangingInheritance() {
    VirtualFile parent1 = createModulePom("parent1",
                                          """
                                            <groupId>test</groupId>
                                            <artifactId>parent1</artifactId>
                                            <version>1</version>
                                            <properties>
                                              <childName>child1</childName>
                                            </properties>
                                            """);

    VirtualFile parent2 = createModulePom("parent2",
                                          """
                                            <groupId>test</groupId>
                                            <artifactId>parent2</artifactId>
                                            <version>1</version>
                                            <properties>
                                              <childName>child2</childName>
                                            </properties>
                                            """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent1</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(parent1, parent2, child);
    assertEquals("child1", myTree.findProject(child).getMavenId().getArtifactId());

    createModulePom("child", """
      <groupId>test</groupId>
      <artifactId>${childName}</artifactId>
      <version>1</version>
      <parent>
        <groupId>test</groupId>
        <artifactId>parent2</artifactId>
        <version>1</version>
      </parent>
      """);

    update(child);

    assertEquals("child2", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testChangingInheritanceParentId() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <childName>child</childName>
                       </properties>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(myProjectPom, child);
    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent2</artifactId>
                       <version>1</version>
                       <properties>
                         <childName>child</childName>
                       </properties>
                       """);

    update(myProjectPom);

    assertEquals("${childName}", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testHandlingSelfInheritance() throws IOException {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <parent>
                         <groupId>test</groupId>
                         <artifactId>parent</artifactId>
                         <version>1</version>
                       </parent>
                       """);

    updateAll(myProjectPom); // shouldn't hang

    updateTimestamps(myProjectPom);
    update(myProjectPom); // shouldn't hang

    updateTimestamps(myProjectPom);
    updateAll(myProjectPom); // shouldn't hang
  }

  @Test 
  public void testHandlingRecursiveInheritance() throws IOException {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <parent>
                         <groupId>test</groupId>
                         <artifactId>child</artifactId>
                         <version>1</version>
                       </parent>
                       <modules>
                         <module>child</module>
                       </properties>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(myProjectPom, child); // shouldn't hang

    updateTimestamps(myProjectPom, child);
    update(myProjectPom); // shouldn't hang

    updateTimestamps(myProjectPom, child);
    update(child); // shouldn't hang

    updateTimestamps(myProjectPom, child);
    updateAll(myProjectPom, child); // shouldn't hang
  }

  @Test 
  public void testDeletingInheritanceParent() {
    VirtualFile parent = createModulePom("parent",
                                         """
                                           <groupId>test</groupId>
                                           <artifactId>parent</artifactId>
                                           <version>1</version>
                                           <properties>
                                             <childName>child</childName>
                                           </properties>
                                           """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>${childName}</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    updateAll(parent, child);

    assertEquals("child", myTree.findProject(child).getMavenId().getArtifactId());

    deleteProject(parent);

    assertEquals("${childName}", myTree.findProject(child).getMavenId().getArtifactId());
  }

  @Test 
  public void testDeletingInheritanceChild() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <properties>
                         <subChildName>subChild</subChildName>
                       </properties>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          """);

    VirtualFile subChild = createModulePom("subChild",
                                           """
                                             <groupId>test</groupId>
                                             <artifactId>${subChildName}</artifactId>
                                             <version>1</version>
                                             <parent>
                                               <groupId>test</groupId>
                                               <artifactId>child</artifactId>
                                               <version>1</version>
                                             </parent>
                                             """);

    updateAll(myProjectPom, child, subChild);
    assertEquals("subChild", myTree.findProject(subChild).getMavenId().getArtifactId());

    deleteProject(child);
    assertEquals("${subChildName}", myTree.findProject(subChild).getMavenId().getArtifactId());
  }

  @Test 
  public void testRecursiveInheritanceAndAggregation() throws IOException {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <parent>
                         <groupId>test</groupId>
                         <artifactId>child</artifactId>
                         <version>1</version>
                       </parent>
                       <modules>
                        <module>child</module>
                       </modules>
                       """);

    VirtualFile child = createModulePom("child",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>child</artifactId>
                                          <version>1</version>
                                          """);
    updateAll(myProjectPom); // should not recurse

    updateTimestamps(myProjectPom, child);
    updateAll(child); // should not recurse
  }

  @Test 
  public void testUpdatingAddsModules() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(0, myTree.getModules(roots.get(0)).size());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testUpdatingUpdatesModulesIfProjectIsChanged() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    assertEquals("m", myTree.findProject(m).getMavenId().getArtifactId());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <name>foo</name>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    createModulePom("m", """
      <groupId>test</groupId>
      <artifactId>m2</artifactId>
      <version>1</version>
      """);
    update(myProjectPom);

    assertEquals("m2", myTree.findProject(m).getMavenId().getArtifactId());
  }

  @Test 
  public void testUpdatingDoesNotUpdateModulesIfProjectIsNotChanged() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    assertEquals("m", myTree.findProject(m).getMavenId().getArtifactId());

    createModulePom("m", """
      <groupId>test</groupId>
      <artifactId>m2</artifactId>
      <version>1</version>
      """);

    update(myProjectPom);

    // did not change
    assertEquals("m", myTree.findProject(m).getMavenId().getArtifactId());
  }

  @Test 
  public void testAddingProjectAsModuleToExistingOne() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(0, myTree.getModules(roots.get(0)).size());

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    update(m);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testAddingProjectAsAggregatorForExistingOne() {
    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(m);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(m, roots.get(0).getFile());
    assertEquals(0, myTree.getModules(roots.get(0)).size());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testAddingProjectWithModules() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(0, myTree.getModules(roots.get(0)).size());

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>m2</module>
                                       </modules>
                                       """);

    VirtualFile m2 = createModulePom("m1/m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    update(m1);

    roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(m1, roots.get(1).getFile());
    assertEquals(1, myTree.getModules(roots.get(1)).size());
    assertEquals(m2, myTree.getModules(roots.get(1)).get(0).getFile());
  }

  @Test 
  public void testUpdatingAddsModulesFromRootProjects() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom, m);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(m, roots.get(1).getFile());
    assertEquals("m", roots.get(1).getMavenId().getArtifactId());
    assertEquals(0, myTree.getModules(roots.get(0)).size());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(m, myTree.getModules(roots.get(0)).get(0).getFile());
  }

  @Test 
  public void testMovingModuleToRootsWhenAggregationChanged() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);
    updateAll(myProjectPom, m);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       """);

    update(myProjectPom);

    roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertTrue(myTree.getModules(roots.get(0)).isEmpty());
    assertTrue(myTree.getModules(roots.get(1)).isEmpty());
  }

  @Test 
  public void testDeletingProject() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());

    deleteProject(m);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(0, myTree.getModules(roots.get(0)).size());
  }

  @Test 
  public void testDeletingProjectWithModules() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>m2</module>
                                       </modules>
                                       """);

    createModulePom("m1/m2",
                    """
                      <groupId>test</groupId>
                      <artifactId>m2</artifactId>
                      <version>1</version>
                      """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(1, myTree.getModules(myTree.getModules(roots.get(0)).get(0)).size());

    deleteProject(m1);

    roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(0, myTree.getModules(roots.get(0)).size());
  }

  @Test 
  public void testSendingNotificationsWhenProjectDeleted() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>m2</module>
                                       </modules>
                                       """);

    createModulePom("m1/m2",
                    """
                      <groupId>test</groupId>
                      <artifactId>m2</artifactId>
                      <version>1</version>
                      """);

    updateAll(myProjectPom);

    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    deleteProject(m1);

    assertEquals(log().add("updated").add("deleted", "m2", "m1"), listener.log);
  }

  @Test 
  public void testReconnectModuleOfDeletedProjectIfModuleIsManaged() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       <packaging>pom</packaging>
                                       <modules>
                                         <module>m2</module>
                                       </modules>
                                       """);

    VirtualFile m2 = createModulePom("m1/m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(myProjectPom, m2);

    List<MavenProject> roots = myTree.getRootProjects();
    assertEquals(1, roots.size());
    assertEquals(1, myTree.getModules(roots.get(0)).size());
    assertEquals(1, myTree.getModules(myTree.getModules(roots.get(0)).get(0)).size());

    MyLoggingListener listener = new MyLoggingListener();
    myTree.addListener(listener, getTestRootDisposable());

    deleteProject(m1);

    roots = myTree.getRootProjects();
    assertEquals(2, roots.size());
    assertEquals(myProjectPom, roots.get(0).getFile());
    assertEquals(0, myTree.getModules(roots.get(0)).size());
    assertEquals(m2, roots.get(1).getFile());
    assertEquals(0, myTree.getModules(roots.get(1)).size());

    assertEquals(log().add("updated", "m2").add("deleted", "m1"), listener.log);
  }

  @Test 
  public void testAddingProjectsOnUpdateAllWhenManagedFilesChanged() {
    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);
    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);
    VirtualFile m3 = createModulePom("m3",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m3</artifactId>
                                       <version>1</version>
                                       """);
    updateAll(m1, m2);
    assertEquals(2, myTree.getRootProjects().size());

    updateAll(m1, m2, m3);
    assertEquals(3, myTree.getRootProjects().size());
  }

  @Test 
  public void testDeletingProjectsOnUpdateAllWhenManagedFilesChanged() {
    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);
    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);
    VirtualFile m3 = createModulePom("m3",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m3</artifactId>
                                       <version>1</version>
                                       """);
    updateAll(m1, m2, m3);
    assertEquals(3, myTree.getRootProjects().size());

    updateAll(m1, m2);
    assertEquals(2, myTree.getRootProjects().size());
  }

  @Test 
  public void testSendingNotificationsWhenAddingOrDeletingManagedFiles() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>parent</artifactId>
                       <version>1</version>
                       <modules>
                         <module>m1</module>
                         <module>m2</module>
                       </modules>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    MyLoggingListener l = new MyLoggingListener();
    myTree.addListener(l, getTestRootDisposable());

    myTree.addManagedFilesWithProfiles(Collections.singletonList(myProjectPom), MavenExplicitProfiles.NONE);
    myTree.updateAll(false, getMavenGeneralSettings(), getMavenProgressIndicator());

    assertEquals(log().add("updated", "parent", "m1", "m2").add("deleted"), l.log);
    l.log.clear();

    myTree.removeManagedFiles(Arrays.asList(myProjectPom));
    myTree.updateAll(false, getMavenGeneralSettings(), getMavenProgressIndicator());

    assertEquals(log().add("updated").add("deleted", "m1", "m2", "parent"), l.log);
  }

  @Test 
  public void testUpdatingModelWhenActiveProfilesChange() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <profiles>
                         <profile>
                           <id>one</id>
                           <properties>
                             <prop>value1</prop>
                           </properties>
                         </profile>
                         <profile>
                           <id>two</id>
                           <properties>
                             <prop>value2</prop>
                           </properties>
                         </profile>
                       </profiles>
                       <modules>
                         <module>m</module>
                       </modules>
                       <build>
                         <sourceDirectory>${prop}</sourceDirectory>
                       </build>
                       """);

    createModulePom("m",
                    """
                      <groupId>test</groupId>
                      <artifactId>m</artifactId>
                      <version>1</version>
                      <parent>
                        <groupId>test</groupId>
                        <artifactId>project</artifactId>
                        <version>1</version>
                      </parent>
                      <build>
                        <sourceDirectory>${prop}</sourceDirectory>
                      </build>
                      """);

    updateAll(Arrays.asList("one"), myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();

    MavenProject parentNode = roots.get(0);
    MavenProject childNode = myTree.getModules(roots.get(0)).get(0);

    assertUnorderedPathsAreEqual(parentNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/value1")));
    assertUnorderedPathsAreEqual(childNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value1")));

    updateAll(Arrays.asList("two"), myProjectPom);

    assertUnorderedPathsAreEqual(parentNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/value2")));
    assertUnorderedPathsAreEqual(childNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value2")));
  }

  @Test 
  public void testUpdatingModelWhenProfilesXmlChange() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <build>
                         <sourceDirectory>${prop}</sourceDirectory>
                       </build>
                       """);

    createProfilesXmlOldStyle("""
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value1</prop>
                                  </properties>
                                </profile>
                                """);

    updateAll(myProjectPom);

    List<MavenProject> roots = myTree.getRootProjects();

    MavenProject project = roots.get(0);
    assertUnorderedPathsAreEqual(project.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/value1")));

    createProfilesXmlOldStyle("""
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value2</prop>
                                  </properties>
                                </profile>
                                """);

    updateAll(myProjectPom);

    assertUnorderedPathsAreEqual(project.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/value2")));
  }

  @Test 
  public void testUpdatingModelWhenParentProfilesXmlChange() {
    VirtualFile parent = createModulePom("parent",
                                         """
                                           <groupId>test</groupId>
                                           <artifactId>parent</artifactId>
                                           <version>1</version>
                                           <packaging>pom</packaging>
                                           """);

    createProfilesXmlOldStyle("parent",
                              """
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value1</prop>
                                  </properties>
                                </profile>
                                """);

    VirtualFile child = createModulePom("m",
                                        """
                                          <groupId>test</groupId>
                                          <artifactId>m</artifactId>
                                          <version>1</version>
                                          <parent>
                                            <groupId>test</groupId>
                                            <artifactId>parent</artifactId>
                                            <version>1</version>
                                          </parent>
                                          <build>
                                            <sourceDirectory>${prop}</sourceDirectory>
                                          </build>
                                          """);

    updateAll(parent, child);

    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(2, roots.size());
    MavenProject childProject = roots.get(0);
    assertUnorderedPathsAreEqual(childProject.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value1")));

    createProfilesXmlOldStyle("parent",
                              """
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value2</prop>
                                  </properties>
                                </profile>
                                """);

    update(parent);
    assertUnorderedPathsAreEqual(childProject.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value2")));
  }

  @Test 
  public void testUpdatingModelWhenParentProfilesXmlChangeAndItIsAModuleAlso() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    createProfilesXmlOldStyle("""
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value1</prop>
                                  </properties>
                                </profile>
                                """);

    createModulePom("m",
                    """
                      <groupId>test</groupId>
                      <artifactId>m</artifactId>
                      <version>1</version>
                      <parent>
                        <groupId>test</groupId>
                        <artifactId>project</artifactId>
                        <version>1</version>
                      </parent>
                      <build>
                        <sourceDirectory>${prop}</sourceDirectory>
                      </build>
                      """);

    updateAll(myProjectPom);

    MavenProject childNode = myTree.getModules(myTree.getRootProjects().get(0)).get(0);
    assertUnorderedPathsAreEqual(childNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value1")));

    createProfilesXmlOldStyle("""
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value2</prop>
                                  </properties>
                                </profile>
                                """);

    updateAll(myProjectPom);
    assertUnorderedPathsAreEqual(childNode.getSources(), Arrays.asList(FileUtil.toSystemDependentName(getProjectPath() + "/m/value2")));
  }

  @Test 
  public void testDoNotUpdateModelWhenAggregatorProfilesXmlChange() {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    createModulePom("m",
                    """
                      <groupId>test</groupId>
                      <artifactId>m</artifactId>
                      <version>1</version>
                      <build>
                        <sourceDirectory>${prop}</sourceDirectory>
                      </build>
                      """);

    createProfilesXmlOldStyle("""
                                <profile>
                                 <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                    <prop>value1</prop>
                                  </properties>
                                </profile>
                                """);

    updateAll(myProjectPom);

    createProfilesXmlOldStyle("""
                                <profile>
                                  <id>one</id>
                                  <activation>
                                    <activeByDefault>true</activeByDefault>
                                  </activation>
                                  <properties>
                                 <prop>value2</prop>
                                  </properties>
                                </profile>
                                """);

    updateAll(myProjectPom);

    List<VirtualFile> existingManagedFiles = myTree.getExistingManagedFiles();
    List<VirtualFile> obsoleteFiles = myTree.getRootProjectsFiles();
    assertEquals(existingManagedFiles, obsoleteFiles);
  }

  @Test 
  public void testSaveLoad() throws Exception {
    //todo: move to resolver test
    // stripping down plugins
    // stripping down Xpp3Dom fields
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <modules>
                         <module>m1</module>
                         <module>m2</module>
                       </modules>
                       <build>
                         <plugins>
                           <plugin>
                             <groupId>org.apache.maven.plugins</groupId>
                             <artifactId>maven-compiler-plugin</artifactId>
                             <configuration>
                               <source>1.4</source>
                             </configuration>
                           </plugin>
                         </plugins>
                       </build>
                       <reports>
                         <someTag/>
                       </reports>
                       """);

    VirtualFile m1 = createModulePom("m1",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m1</artifactId>
                                       <version>1</version>
                                       """);

    VirtualFile m2 = createModulePom("m2",
                                     """
                                       <groupId>test</groupId>
                                       <artifactId>m2</artifactId>
                                       <version>1</version>
                                       """);

    updateAll(myProjectPom);

    MavenProject parentProject = myTree.findProject(myProjectPom);

    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    try {
      resolve(myProject,
              parentProject,
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator());
    }
    finally {
      embeddersManager.releaseInTests();
    }

    Path f = myDir.toPath().resolve("tree.dat");
    myTree.save(f);
    MavenProjectsTree read = MavenProjectsTree.read(myProject, f);

    List<MavenProject> roots = read.getRootProjects();
    assertEquals(1, roots.size());

    MavenProject rootProject = roots.get(0);
    assertEquals(myProjectPom, rootProject.getFile());

    assertEquals(2, read.getModules(rootProject).size());
    assertEquals(m1, read.getModules(rootProject).get(0).getFile());
    assertEquals(m2, read.getModules(rootProject).get(1).getFile());

    assertNull(read.findAggregator(rootProject));
    assertEquals(rootProject, read.findAggregator(read.findProject(m1)));
    assertEquals(rootProject, read.findAggregator(read.findProject(m2)));
  }

  @Test 
  public void testCollectingProfilesFromSettingsXmlAndPluginsXml() throws Exception {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <profiles>
                         <profile>
                           <id>one</id>
                         </profile>
                       </profiles>
                       """);

    createProfilesXml("""
                        <profile>
                          <id>two</id>
                        </profile>
                        """);

    updateSettingsXml("""
                        <profiles>
                          <profile>
                            <id>three</id>
                          </profile>
                        </profiles>
                        """);

    updateAll(myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getAvailableProfiles(), "one", "two", "three");
  }

  @Test 
  public void testCollectingProfilesFromSettingsXmlAndPluginsXmlAfterResolve() throws Exception {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <profiles>
                         <profile>
                           <id>one</id>
                         </profile>
                       </profiles>
                       """);

    createProfilesXml("""
                        <profile>
                          <id>two</id>
                        </profile>
                        """);

    updateSettingsXml("""
                        <profiles>
                          <profile>
                            <id>three</id>
                          </profile>
                        </profiles>
                        """);

    updateAll(myProjectPom);

    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    try {
      resolve(myProject,
              myTree.getRootProjects().get(0),
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator()
      );
    }
    finally {
      embeddersManager.releaseInTests();
    }

    assertUnorderedElementsAreEqual(myTree.getAvailableProfiles(), "one", "two", "three");
  }

  @Test 
  public void testCollectingProfilesFromParentsAfterResolve() throws Exception {
    createModulePom("parent1",
                    """
                      <groupId>test</groupId>
                      <artifactId>parent1</artifactId>
                      <version>1</version>
                      <packaging>pom</packaging>
                      <profiles>
                        <profile>
                          <id>parent1Profile</id>
                        </profile>
                      </profiles>
                      """);

    createProfilesXml("parent1",
                      """
                        <profile>
                          <id>parent1ProfileXml</id>
                        </profile>
                        """);

    createModulePom("parent2",
                    """
                      <groupId>test</groupId>
                      <artifactId>parent2</artifactId>
                      <version>1</version>
                      <packaging>pom</packaging>
                      <parent>
                       <groupId>test</groupId>
                       <artifactId>parent1</artifactId>
                       <version>1</version>
                       <relativePath>../parent1/pom.xml</relativePath>
                      </parent>
                      <profiles>
                        <profile>
                          <id>parent2Profile</id>
                        </profile>
                      </profiles>
                      """);

    createProfilesXml("parent2",
                      """
                        <profile>
                          <id>parent2ProfileXml</id>
                        </profile>
                        """);

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <parent>
                        <groupId>test</groupId>
                        <artifactId>parent2</artifactId>
                        <version>1</version>
                        <relativePath>parent2/pom.xml</relativePath>
                       </parent>
                       <profiles>
                         <profile>
                           <id>projectProfile</id>
                         </profile>
                       </profiles>
                       """);

    createProfilesXml("""
                        <profile>
                          <id>projectProfileXml</id>
                        </profile>
                        """);

    updateSettingsXml("""
                        <profiles>
                          <profile>
                            <id>settings</id>
                          </profile>
                        </profiles>
                        """);

    updateAll(Arrays.asList("projectProfileXml",
                            "projectProfile",
                            "parent1Profile",
                            "parent1ProfileXml",
                            "parent2Profile",
                            "parent2ProfileXml",
                            "settings",
                            "xxx"),
              myProjectPom);

    MavenProject project = myTree.findProject(myProjectPom);
    assertUnorderedElementsAreEqual(project.getActivatedProfilesIds().getEnabledProfiles(),
                                    "projectProfileXml",
                                    "projectProfile",
                                    "parent1Profile",
                                    "parent1ProfileXml",
                                    "parent2Profile",
                                    "parent2ProfileXml",
                                    "settings");

    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    try {
      resolve(myProject,
              project,
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator()
      );
    }
    finally {
      embeddersManager.releaseInTests();
    }
    assertUnorderedElementsAreEqual(project.getActivatedProfilesIds().getEnabledProfiles(),
                                    "projectProfile",
                                    "parent1Profile",
                                    "parent2Profile",
                                    "settings");
  }

  @Test 
  public void testDeletingAndRestoringActiveProfilesWhenAvailableProfilesChange() throws IOException {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <profiles>
                         <profile>
                           <id>one</id>
                         </profile>
                       </profiles>
                       """);

    createProfilesXml("""
                        <profile>
                          <id>two</id>
                        </profile>
                        """);

    updateAll(Arrays.asList("one", "two"), myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one", "two");

    deleteProfilesXml();
    update(myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one");

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       """);

    update(myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles());

    createProfilesXml("""
                        <profile>
                          <id>two</id>
                        </profile>
                        """);
    update(myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "two");

    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <profiles>
                         <profile>
                           <id>one</id>
                         </profile>
                       </profiles>
                       """);
    update(myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one", "two");
  }

  @Test 
  public void testDeletingAndRestoringActiveProfilesWhenProjectDeletes() throws IOException {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <packaging>pom</packaging>
                       <profiles>
                         <profile>
                           <id>one</id>
                         </profile>
                       </profiles>
                       <modules>
                         <module>m</module>
                       </modules>
                       """);

    VirtualFile m = createModulePom("m",
                                    """
                                      <groupId>test</groupId>
                                      <artifactId>m</artifactId>
                                      <version>1</version>
                                      <profiles>
                                        <profile>
                                          <id>two</id>
                                        </profile>
                                      </profiles>
                                      """);

    updateAll(Arrays.asList("one", "two"), myProjectPom);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one", "two");

    final VirtualFile finalM = m;
    WriteCommandAction.writeCommandAction(myProject).run(() -> {
      finalM.delete(this);
      deleteProject(finalM);
    });

    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one");

    m = createModulePom("m",
                        """
                          <groupId>test</groupId>
                          <artifactId>m</artifactId>
                          <version>1</version>
                          <profiles>
                            <profile>
                              <id>two</id>
                            </profile>
                          </profiles>
                          """);
    update(m);
    assertUnorderedElementsAreEqual(myTree.getExplicitProfiles().getEnabledProfiles(), "one", "two");
  }

  @Test 
  public void testFindRootWithMultiLevelAggregator() {
    VirtualFile p1 = createModulePom("project1", """
      <groupId>test</groupId>
      <artifactId>project1</artifactId>
      <version>1</version>
      <packaging>pom</packaging>
      <modules>
        <module>../project2</module>
      </modules>"""
    );

    VirtualFile p2 = createModulePom("project2", """
      <groupId>test</groupId>
      <artifactId>project2</artifactId>
      <version>1</version>
      <packaging>pom</packaging>
      <modules>
        <module>../module</module>
      </modules>"""
    );

    VirtualFile m = createModulePom("module", """
      <groupId>test</groupId>
      <artifactId>module</artifactId>
      <version>1</version>"""
    );

    updateAll(p1, p2, m);

    List<MavenProject> roots = myTree.getRootProjects();

    assertEquals(1, roots.size());
    MavenProject p1Project = roots.get(0);
    assertEquals(p1, p1Project.getFile());
    assertEquals(p1Project, myTree.findRootProject(p1Project));

    assertEquals(1, myTree.getModules(p1Project).size());
    MavenProject p2Project = myTree.getModules(p1Project).get(0);
    assertEquals(p2, p2Project.getFile());
    assertEquals(p1Project, myTree.findRootProject(p2Project));

    assertEquals(1, myTree.getModules(p2Project).size());
    MavenProject mProject = myTree.getModules(p2Project).get(0);
    assertEquals(m, mProject.getFile());
    assertEquals(p1Project, myTree.findRootProject(mProject));

    assertEquals(0, myTree.getModules(mProject).size());
  }

  @Test 
  public void testOutputPathsAreBasedOnTargetPathWhenResolving() throws Exception {
    createProjectPom("""
                       <groupId>test</groupId>
                       <artifactId>project</artifactId>
                       <version>1</version>
                       <build>
                         <directory>my-target</directory>
                       </build>
                       """);

    updateAll(myProjectPom);

    MavenProject project = myTree.getRootProjects().get(0);
    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target"), project.getBuildDirectory());
    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target/classes"), project.getOutputDirectory());
    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target/test-classes"), project.getTestOutputDirectory());

    MavenEmbeddersManager embeddersManager = new MavenEmbeddersManager(myProject);
    try {
      resolve(myProject,
              project,
              getMavenGeneralSettings(),
              embeddersManager,
              NULL_MAVEN_CONSOLE,
              getMavenProgressIndicator());
    }
    finally {
      embeddersManager.releaseInTests();
    }

    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target"), project.getBuildDirectory());
    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target/classes"), project.getOutputDirectory());
    PlatformTestUtil.assertPathsEqual(pathFromBasedir("my-target/test-classes"), project.getTestOutputDirectory());
  }

  private void resolve(@NotNull Project project,
                       @NotNull MavenProject mavenProject,
                       @NotNull MavenGeneralSettings generalSettings,
                       @NotNull MavenEmbeddersManager embeddersManager,
                       @NotNull MavenConsole console,
                       @NotNull MavenProgressIndicator process) throws MavenProcessCanceledException {
    var resolver = MavenProjectResolver.getInstance(project);
    resolver.resolve(List.of(mavenProject), generalSettings, embeddersManager, console, process);
  }

  private static ListenerLog log() {
    return new ListenerLog();
  }

  private static class ListenerLog extends CopyOnWriteArrayList<Pair<String, Set<String>>> {
    ListenerLog() { super(); }

    ListenerLog(ListenerLog log) { super(log); }

    ListenerLog add(String key, String... values) {
      var log = new ListenerLog(this);
      log.add(new Pair<>(key, Set.of(values)));
      return log;
    }
  }

  private static class MyLoggingListener implements MavenProjectsTree.Listener {
    List<Pair<String, Set<String>>> log = new CopyOnWriteArrayList<>();

    private void add(String key, Set<String> value) {
      log.add(new Pair<>(key, value));
    }

    @Override
    public void projectsUpdated(@NotNull List<Pair<MavenProject, MavenProjectChanges>> updated, @NotNull List<MavenProject> deleted) {
      append(MavenUtil.collectFirsts(updated), "updated");
      append(deleted, "deleted");
    }

    private void append(List<MavenProject> updated, String text) {
      add(text, updated.stream().map(each -> each.getMavenId().getArtifactId()).collect(Collectors.toSet()));
    }

    @Override
    public void projectResolved(@NotNull Pair<MavenProject, MavenProjectChanges> projectWithChanges,
                                NativeMavenProjectHolder nativeMavenProject) {
      add("resolved", Set.of(projectWithChanges.first.getMavenId().getArtifactId()));
    }

    @Override
    public void pluginsResolved(@NotNull MavenProject project) {
      add("plugins", Set.of(project.getMavenId().getArtifactId()));
    }

    @Override
    public void foldersResolved(@NotNull Pair<MavenProject, MavenProjectChanges> projectWithChanges) {
      add("folders", Set.of(projectWithChanges.first.getMavenId().getArtifactId()));
    }
  }
}
