package com.github.ghik.silencer

class silent(s: String = ".*") extends scala.annotation.nowarn(s"msg=$s")
