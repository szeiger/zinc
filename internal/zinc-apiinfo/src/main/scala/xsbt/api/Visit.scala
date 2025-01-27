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

package xsbt.api

import xsbti.api._
import scala.collection.mutable

class Visit {
  private[this] val visitedStructures = new mutable.HashSet[Structure]
  private[this] val visitedClassLike = new mutable.HashSet[ClassLike]

  def visitAPI(c: ClassLike): Unit = {
    visitDefinition(c)
  }

  def visitPackage(p: Package): Unit = {
    visitString(p.name)
  }

  def visitDefinitions(ds: Array[_ <: Definition]) = visitArray(ds, visitDefinition)
  def visitDefinition(d: Definition): Unit = {
    visitString(d.name)
    visitAnnotations(d.annotations)
    visitModifiers(d.modifiers)
    visitAccess(d.access)
    d match {
      case c: ClassLikeDef    => visitClassDef(c)
      case c: ClassLike       => visitClass(c)
      case f: FieldLike       => visitField(f)
      case d: Def             => visitDef(d)
      case t: TypeDeclaration => visitTypeDeclaration(t)
      case t: TypeAlias       => visitTypeAlias(t)
    }
  }
  final def visitClassDef(c: ClassLikeDef): Unit = {
    visitTypeParameters(c.typeParameters)
  }
  final def visitClass(c: ClassLike): Unit = if (visitedClassLike add c) visitClass0(c)
  def visitClass0(c: ClassLike): Unit = {
    visitTypeParameters(c.typeParameters)
    visitType(c.selfType)
    visitStructure(c.structure)
  }
  def visitField(f: FieldLike): Unit = {
    visitType(f.tpe)
    f match {
      case v: Var => visitVar(v)
      case v: Val => visitVal(v)
    }
  }
  def visitVar(v: Var): Unit = ()
  def visitVal(v: Val): Unit = ()
  def visitDef(d: Def): Unit = {
    visitParameterizedDefinition(d)
    visitValueParameters(d.valueParameters)
    visitType(d.returnType)
  }
  def visitAccess(a: Access): Unit =
    a match {
      case pub: Public     => visitPublic(pub)
      case qual: Qualified => visitQualified(qual)
    }
  def visitQualified(qual: Qualified): Unit =
    qual match {
      case p: Protected => visitProtected(p)
      case p: Private   => visitPrivate(p)
    }
  def visitQualifier(qual: Qualifier): Unit =
    qual match {
      case unq: Unqualified     => visitUnqualified(unq)
      case thisq: ThisQualifier => visitThisQualifier(thisq)
      case id: IdQualifier      => visitIdQualifier(id)
    }
  def visitIdQualifier(id: IdQualifier): Unit = {
    visitString(id.value)
  }
  def visitUnqualified(unq: Unqualified): Unit = ()
  def visitThisQualifier(thisq: ThisQualifier): Unit = ()
  def visitPublic(pub: Public): Unit = ()
  def visitPrivate(p: Private): Unit = visitQualifier(p.qualifier)
  def visitProtected(p: Protected): Unit = visitQualifier(p.qualifier)
  def visitModifiers(m: Modifiers): Unit = ()

  def visitValueParameters(valueParameters: Array[ParameterList]) =
    visitArray(valueParameters, visitValueParameterList)
  def visitValueParameterList(list: ParameterList) =
    visitArray(list.parameters, visitValueParameter)
  def visitValueParameter(parameter: MethodParameter) = {
    visitString(parameter.name)
    visitType(parameter.tpe)
  }

  def visitParameterizedDefinition[T <: ParameterizedDefinition](d: T): Unit =
    visitTypeParameters(d.typeParameters)

  def visitTypeDeclaration(d: TypeDeclaration): Unit = {
    visitParameterizedDefinition(d)
    visitType(d.lowerBound)
    visitType(d.upperBound)
  }
  def visitTypeAlias(d: TypeAlias): Unit = {
    visitParameterizedDefinition(d)
    visitType(d.tpe)
  }

  def visitTypeParameters(parameters: Array[TypeParameter]) =
    visitArray(parameters, visitTypeParameter)
  def visitTypeParameter(parameter: TypeParameter): Unit = {
    visitTypeParameters(parameter.typeParameters)
    visitType(parameter.lowerBound)
    visitType(parameter.upperBound)
    visitAnnotations(parameter.annotations)
  }
  def visitAnnotations(annotations: Array[Annotation]) = visitArray(annotations, visitAnnotation)
  def visitAnnotation(annotation: Annotation) = {
    visitType(annotation.base)
    visitAnnotationArguments(annotation.arguments)
  }
  def visitAnnotationArguments(args: Array[AnnotationArgument]) =
    visitArray(args, visitAnnotationArgument)
  def visitAnnotationArgument(arg: AnnotationArgument): Unit = {
    visitString(arg.name)
    visitString(arg.value)
  }

  def visitTypes(ts: Array[Type]) = visitArray(ts, visitType)
  def visitType(t: Type): Unit = {
    t match {
      case s: Structure     => visitStructure(s)
      case e: Existential   => visitExistential(e)
      case c: Constant      => visitConstant(c)
      case p: Polymorphic   => visitPolymorphic(p)
      case a: Annotated     => visitAnnotated(a)
      case p: Parameterized => visitParameterized(p)
      case p: Projection    => visitProjection(p)
      case _: EmptyType     => visitEmptyType()
      case s: Singleton     => visitSingleton(s)
      case pr: ParameterRef => visitParameterRef(pr)
    }
  }

  def visitEmptyType(): Unit = ()
  def visitParameterRef(p: ParameterRef): Unit = ()
  def visitSingleton(s: Singleton): Unit = visitPath(s.path)
  def visitPath(path: Path) = visitArray(path.components, visitPathComponent)
  def visitPathComponent(pc: PathComponent) = pc match {
    case t: This  => visitThisPath(t)
    case s: Super => visitSuperPath(s)
    case id: Id   => visitIdPath(id)
  }
  def visitThisPath(t: This): Unit = ()
  def visitSuperPath(s: Super): Unit = visitPath(s.qualifier)
  def visitIdPath(id: Id): Unit = visitString(id.id)

  def visitConstant(c: Constant) = {
    visitString(c.value)
    visitType(c.baseType)
  }
  def visitExistential(e: Existential) = visitParameters(e.clause, e.baseType)
  def visitPolymorphic(p: Polymorphic) = visitParameters(p.parameters, p.baseType)
  def visitProjection(p: Projection) = {
    visitString(p.id)
    visitType(p.prefix)
  }
  def visitParameterized(p: Parameterized): Unit = {
    visitType(p.baseType)
    visitTypes(p.typeArguments)
  }
  def visitAnnotated(a: Annotated): Unit = {
    visitType(a.baseType)
    visitAnnotations(a.annotations)
  }
  final def visitStructure(structure: Structure) =
    if (visitedStructures add structure) visitStructure0(structure)
  def visitStructure0(structure: Structure): Unit = {
    visitTypes(structure.parents)
    visitDefinitions(structure.declared)
    visitDefinitions(structure.inherited)
  }
  def visitParameters(parameters: Array[TypeParameter], base: Type): Unit = {
    visitTypeParameters(parameters)
    visitType(base)
  }
  def visitString(s: String): Unit = ()
  @inline final def visitArray[T <: AnyRef](ts: Array[T], f: T => Unit): Unit = {
    var i = 0
    while (i < ts.length) {
      f(ts(i))
      i += 1
    }
  }
}
