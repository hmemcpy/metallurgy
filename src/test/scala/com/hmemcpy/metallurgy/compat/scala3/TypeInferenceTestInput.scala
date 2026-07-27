package com.hmemcpy.metallurgy.compat.scala3

private[scala3] object TypeInferenceTestInput:

  def normalizedSource(source: String): String =
    source.trim.replace("\r\n", "\n").replace('\r', '\n')

  def expectedType(source: String): String =
    val lastLine = source.linesIterator.toVector.lastOption.getOrElse("").trim
    if lastLine.startsWith("//") then lastLine.substring(2).trim
    else throw new AssertionError("type inference input has no expected result in its final comment")
