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

package sbt.internal.inc

import java.nio.file.Paths
import java.util.concurrent.{ Callable, CountDownLatch, Executors, TimeUnit }
import xsbti.VirtualFileRef
import xsbti.api.DependencyContext.DependencyByMemberRef
import xsbti.compile.{ CompileAnalysis, CompileOrder, FileHash, IncOptions, MiniOptions, MiniSetup }

class AnalysisCallbackSpec extends UnitSpec {
  private val converter = PlainVirtualFileConverter.converter
  private val source = converter.toVirtualFile(Paths.get("A.scala"))
  private val library = converter.toVirtualFile(Paths.get("library.jar"))
  private val names = List("example.Zeta", "example.Alpha", "example.Beta")

  it should "choose the same library class name for every callback order" in {
    names.permutations.foreach { order =>
      val callback = newCallback()
      order.foreach(report(callback, _, library, source))
      val relations = callback.getPostJavaAnalysis.relations
      relations.libraryClassNames(library) shouldBe Set("example.Alpha")
      relations.libraryDeps(source) shouldBe Set(library)
    }
  }

  it should "ignore repeated library class names" in {
    val callback = newCallback()
    List.fill(20)(names).flatten.foreach(report(callback, _, library, source))
    val relations = callback.getPostJavaAnalysis.relations
    relations.libraryClassNames(library) shouldBe Set("example.Alpha")
    relations.libraryDeps(source) shouldBe Set(library)
  }

  it should "choose class names per library while retaining all source dependencies" in {
    val callback = newCallback()
    val otherSource = converter.toVirtualFile(Paths.get("B.scala"))
    val otherLibrary = converter.toVirtualFile(Paths.get("other.jar"))
    callback.startSource(otherSource)
    report(callback, "example.Zeta", library, source)
    report(callback, "example.Alpha", library, otherSource)
    report(callback, "other.Zeta", otherLibrary, otherSource)
    report(callback, "other.Beta", otherLibrary, source)
    val relations = callback.getPostJavaAnalysis.relations
    relations.libraryClassNames(library) shouldBe Set("example.Alpha")
    relations.libraryClassNames(otherLibrary) shouldBe Set("other.Beta")
    relations.libraryDeps(source) shouldBe Set(library, otherLibrary)
    relations.libraryDeps(otherSource) shouldBe Set(library, otherLibrary)
  }

  it should "choose the minimum library class name during concurrent callbacks" in {
    val executor = Executors.newFixedThreadPool(names.size)
    try {
      for (_ <- 1 to 20) {
        val callback = newCallback()
        val ready = new CountDownLatch(names.size)
        val start = new CountDownLatch(1)
        val tasks = names.map { name =>
          executor.submit(new Callable[Unit] {
            def call(): Unit = {
              ready.countDown()
              assert(start.await(10, TimeUnit.SECONDS))
              for (_ <- 1 to 100) report(callback, name, library, source)
            }
          })
        }
        try assert(ready.await(10, TimeUnit.SECONDS))
        finally start.countDown()
        tasks.foreach(_.get(10, TimeUnit.SECONDS))
        val relations = callback.getPostJavaAnalysis.relations
        relations.libraryClassNames(library) shouldBe Set("example.Alpha")
        relations.libraryDeps(source) shouldBe Set(library)
      }
    } finally {
      val _ = executor.shutdownNow()
      if (!executor.awaitTermination(10, TimeUnit.SECONDS))
        fail("Callback executor did not terminate")
    }
  }

  it should "not register dependencies on rt.jar" in {
    val callback = newCallback()
    val runtimeLibrary = converter.toVirtualFile(Paths.get("rt.jar"))
    report(callback, "java.lang.String", runtimeLibrary, source)
    val relations = callback.getPostJavaAnalysis.relations
    relations.libraryClassNames(runtimeLibrary) shouldBe Set.empty
    relations.libraryDeps(source) shouldBe Set.empty
  }

  private def report(
      callback: AnalysisCallback,
      name: String,
      binary: xsbti.VirtualFile,
      from: VirtualFileRef
  ): Unit =
    callback.binaryDependency(converter.toPath(binary), name, "Example", from, DependencyByMemberRef)

  private def newCallback(): AnalysisCallback = {
    val output = CompileOutput.empty
    val lookup = new Lookup with NoopExternalLookup {
      def changedClasspathHash: Option[Vector[FileHash]] = None
      def analyses: Vector[CompileAnalysis] = Vector.empty
      def lookupOnClasspath(name: String): Option[VirtualFileRef] = None
      def lookupAnalysis(name: String): Option[CompileAnalysis] = None
    }
    val setup = MiniSetup.of(
      output,
      MiniOptions.of(Array.empty, Array.empty, Array.empty),
      scala.util.Properties.versionNumberString,
      CompileOrder.Mixed,
      true,
      Array.empty
    )
    val callback = new AnalysisCallback.Builder(
      externalAPI = (_, _) => None,
      stampReader = Stamps.empty,
      options = IncOptions.of(),
      currentSetup = setup,
      converter = converter,
      lookup = lookup,
      output = output,
      outputJarContent = JarUtils.createOutputJarContent(output),
      earlyOutput = None,
      earlyAnalysisStore = None,
      pickleJarPair = None,
      progress = None,
      log = log
    ).build()
    callback.startSource(source)
    callback
  }
}
