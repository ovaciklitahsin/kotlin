// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.gradle.tooling.util.resolve

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gradle.model.DefaultExternalProjectDependency
import org.jetbrains.plugins.gradle.model.ExternalDependency
import org.jetbrains.plugins.gradle.tooling.util.resolve.DependencyResolverImpl.removeDuplicates
import org.jetbrains.plugins.gradle.tooling.util.resolve.Scope.*
import org.junit.Test

class DependencyResolverImplTest {

  @Test
  fun testRemoveDuplicatesNoDuplicates() {
    val testDependencies = mutableListOf<ExternalDependency>(
      projectDep("p1", COMPILE),
      projectDep("p2", COMPILE))

    removeDuplicates(testDependencies);

    assertThat(testDependencies)
      .extracting("name")
      .containsExactly("p1", "p2")
  }

  @Test
  fun testRemoveDuplicatesUpgradeScope() {
    mutableListOf<ExternalDependency>(
      projectDep("p1", COMPILE),
      projectDep("p1", RUNTIME)
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(projectDep("p1", COMPILE))
    }

    mutableListOf<ExternalDependency>(
      projectDep("p1", PROVIDED),
      projectDep("p1", RUNTIME)
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(projectDep("p1", COMPILE))
    }

    mutableListOf<ExternalDependency>(
      projectDep("p1", RUNTIME),
      projectDep("p1", RUNTIME)
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(projectDep("p1", RUNTIME))
    }
  }

  @Test
  fun testMergeDuplicateLeafs() {
    mutableListOf<ExternalDependency>(
      projectDep("p1", COMPILE),
      projectDep("p2", COMPILE) {
        projectDep("p3", COMPILE) {
          projectDep("p1", RUNTIME)
        }
      }
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(
        projectDep("p1", COMPILE),
        projectDep("p2", COMPILE) {
          projectDep("p3", COMPILE)
        }
      )
    }


    mutableListOf<ExternalDependency>(
      projectDep("p2", COMPILE) {
        projectDep("p3", COMPILE) {
          projectDep("p1", RUNTIME)
        }
      },
      projectDep("p1", COMPILE)
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(
        projectDep("p2", COMPILE) {
          projectDep("p3", COMPILE) {
            projectDep("p1", COMPILE)
          }
        }
      )
    }
  }

  @Test
  fun testMergeDependenciesGraphs() {
    mutableListOf<ExternalDependency>(
      projectDep("A", COMPILE) {
        projectDep("B", COMPILE) {
          projectDep("D", RUNTIME)
          projectDep("E", COMPILE)
        }
        projectDep("C", RUNTIME)
      },
      projectDep("B", RUNTIME) {
        projectDep("F", RUNTIME)
        projectDep("G", RUNTIME)
      },
      projectDep("H", COMPILE) {
        projectDep("G", COMPILE)
        projectDep("D", COMPILE)
      },
      projectDep("C", COMPILE)
    ).let {
      removeDuplicates(it)
      assertThat(it).containsExactly(
        projectDep("A", COMPILE) {
          projectDep("B", COMPILE) {
            projectDep("D", COMPILE)
            projectDep("E", COMPILE)
            projectDep("F", RUNTIME)
            projectDep("G", COMPILE)
          }
          projectDep("C", COMPILE)
        },
        projectDep("H", COMPILE)
      )
    }
  }
}



fun projectDep(targetName: String,
                depScope: Scope,
                builder: DefaultExternalProjectDependency.() -> Unit = {})
  : DefaultExternalProjectDependency {
  val newDependency = DefaultExternalProjectDependency().apply {
    name = targetName
    scope = depScope.toString()
    group = "testGrp"
    version = "1.0"
    projectPath = "projects/${targetName}"
    classpathOrder = 0
  }
  newDependency.builder()
  return newDependency
}


fun DefaultExternalProjectDependency.projectDep(targetName: String,
                                                depScope: Scope,
                                                builder: DefaultExternalProjectDependency.() -> Unit = {})
: DefaultExternalProjectDependency
{
  val newDependency = DefaultExternalProjectDependency().apply {
    name = targetName
    scope = depScope.toString()
    group = "testGrp"
    version = "1.0"
    projectPath = "projects/${targetName}"
    classpathOrder = 0
  }
  newDependency.builder()
  this.dependencies.add(newDependency)
  return this
}

enum class Scope {
  COMPILE, RUNTIME, PROVIDED
}