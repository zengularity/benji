/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.github.ghik.silencer

class silent(s: String = ".*") extends scala.annotation.nowarn(s"msg=$s")
