/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Scala Center, Lightbend, and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * SPDX-License-Identifier: Apache-2.0
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt
package internal
package inc

import java.beans.ConstructorProperties
import java.lang.annotation.{ Repeatable, Retention, RetentionPolicy }

import sbt.internal.inc.classfile.JavaCompilerForUnitTesting
import xsbti.{ AnalysisCallback, VirtualFileRef }
import xsbti.api.{ ClassLike, ClassLikeDef, DefinitionType }

class ClassToAPISpecification extends UnitSpec {

  "ClassToAPI annotations" should "serialize scalar members in name order" in {
    val annotation = new java.lang.Deprecated {
      def annotationType(): Class[java.lang.Deprecated] = classOf[java.lang.Deprecated]
      def since(): String = "a, b"
      def forRemoval(): Boolean = true
    }
    assert(
      ClassToAPI.annotation(annotation).arguments()(0).value() ==
        "@java.lang.Deprecated(forRemoval=true, since=4:a, b)"
    )
  }

  it should "serialize enum and class values by name" in {
    val retention = new Retention {
      def annotationType(): Class[Retention] = classOf[Retention]
      def value(): RetentionPolicy = RetentionPolicy.RUNTIME
    }
    val repeatable = new Repeatable {
      def annotationType(): Class[Repeatable] = classOf[Repeatable]
      def value(): Class[Retention] = classOf[Retention]
    }
    assert(
      ClassToAPI.annotation(retention).arguments()(0).value() ==
        "@java.lang.annotation.Retention(value=RUNTIME)"
    )
    assert(
      ClassToAPI.annotation(repeatable).arguments()(0).value() ==
        "@java.lang.annotation.Repeatable(value=java.lang.annotation.Retention)"
    )
  }

  it should "preserve array order and string boundaries" in {
    val annotation = new ConstructorProperties {
      def annotationType(): Class[ConstructorProperties] = classOf[ConstructorProperties]
      def value(): Array[String] = Array("a, b", "c")
    }
    assert(
      ClassToAPI.annotation(annotation).arguments()(0).value() ==
        "@java.beans.ConstructorProperties(value={6:4:a, b, 3:1:c})"
    )
  }

  "ClassToAPI" should "extract api of inner classes" in {
    val src =
      """|class A {
        |  class B {}
        |}
      """.stripMargin
    val apis = extractApisFromSrc("A.java" -> src).map(c => c.name -> c).toMap
    assert(apis.keySet === Set("A", "A.B"))

    val companionsA = apis("A")
    assert(companionsA.classApi.topLevel === true)
    assert(companionsA.objectApi.topLevel === true)

    val innerClassDefB =
      findDeclaredInnerClass(companionsA.classApi, "A.B", DefinitionType.ClassDef)
    assert(innerClassDefB.isDefined)

    val companionsB = apis("A.B")
    assert(companionsB.classApi.topLevel === false)
    assert(companionsB.objectApi.topLevel === false)
    assert(companionsB.classApi.structure.declared.isEmpty === false)
  }

  it should "extract a private inner class" in {
    val src =
      """|class A {
        |  private class B {}
        |}
      """.stripMargin
    val apis = extractApisFromSrc("A.java" -> src).map(c => c.name -> c).toMap
    assert(apis.keySet === Set("A", "A.B"))
  }

  /**
   * Compiles given source code using Java compiler and returns API representation
   * extracted by ClassToAPI class.
   */
  private def extractApisFromSrc(src: (String, String)): Set[Companions] = {
    val (Seq(tempSrcFile), analysisCallback) =
      JavaCompilerForUnitTesting.compileJavaSrcs(src)(readAPI)
    val apis = analysisCallback.apis(tempSrcFile)
    apis.groupBy(_.name).map((companions _).tupled).toSet
  }

  private def companions(className: String, classes: Set[ClassLike]): Companions = {
    assert(classes.size <= 2, s"Too many classes named $className: $classes")
    def isClass(c: ClassLike) =
      (c.definitionType == DefinitionType.Trait) || (c.definitionType == DefinitionType.ClassDef)
    def isModule(c: ClassLike) =
      (c.definitionType == DefinitionType.Module) || (c.definitionType == DefinitionType.PackageModule)
    // the ClassToAPI always create both class and object APIs
    val classApi = classes.find(isClass).get
    val objectApi = classes.find(isModule).get
    Companions(className, classApi, objectApi)
  }

  private case class Companions(name: String, classApi: ClassLike, objectApi: ClassLike)

  private def findDeclaredInnerClass(
      classApi: ClassLike,
      innerClassName: String,
      defType: DefinitionType
  ): Option[ClassLikeDef] = {
    classApi.structure.declared.collectFirst({
      case c: ClassLikeDef if c.name == innerClassName && c.definitionType == defType => c
    })
  }

  def readAPI(
      callback: AnalysisCallback,
      source: VirtualFileRef,
      classes: Seq[Class[?]]
  ): Set[(String, String)] = {
    val (apis, mainClasses, inherits) = ClassToAPI.process(classes)
    apis.foreach(callback.api(source, _))
    mainClasses.foreach(callback.mainClass(source, _))
    inherits.map {
      case (from, to) => (from.getName, to.getName)
    }
  }

}
