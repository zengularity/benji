/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.language.higherKinds

import scala.collection.generic.CanBuildFrom

private[benji] object Compat {
  type Factory[M[_], T] = CanBuildFrom[M[_], T, M[T]]

  @inline def newBuilder[M[_], T](f: Factory[M, T]) = f()

  @inline def mapValues[K, V1, V2](m: Map[K, V1])(f: V1 => V2) =
    m.mapValues(f)

  val javaConverters = scala.collection.JavaConverters
}
