import sbt.Keys._
import sbt._
import sbt.plugins.JvmPlugin

// !! 1 doc file = n sample in 1 package (with package object)
// highlightStartToken [in ThisBuild] := "..." // default "```scala"
// highlightEndToken [in ThisBuild] := "..." // default "```"
// highlightDirectory // default := baseDirectory
// includeFilter in doc // default *.md
// excludeFilter in doc
// watchSources: matching files in the highlightDirectory

object HighlightExtractorPlugin extends AutoPlugin {
  import scala.collection.JavaConverters._
  import org.apache.commons.io.FileUtils
  import org.apache.commons.io.filefilter.{ IOFileFilter, TrueFileFilter }

  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    val highlightStartToken = SettingKey[String]("highlightStartToken",
      """Token indicating a highlight starts; default: "```scala"""")

    val highlightEndToken = SettingKey[String]("highlightEndToken",
      """Token indicating a highlight is ended; default: "```"""")

    val highlightDirectory = SettingKey[File]("highlightDirectory",
      """Directory to be scanned; default: baseDirectory""")
  }

  import autoImport._

  private val markdownSources =
    SettingKey[Seq[File]]("highlightMarkdownSources")

  override def projectSettings = Seq(
    highlightStartToken := "```scala",
    highlightEndToken := "```",
    highlightDirectory := baseDirectory.value,
    includeFilter in doc := "*.md",
    markdownSources := {
      val filter = (includeFilter in doc).value
      val excludes = (excludeFilter in doc).value.accept(_)
      val iofilter = new IOFileFilter {
        def accept(f: File) = filter.accept(f)
        def accept(d: File, n: String) = !excludes(d) && accept(d / n)
      }
      val dirfilter = new IOFileFilter {
        def accept(f: File) = !excludes(f)
        def accept(d: File, n: String) = accept(d / n)
      }

      FileUtils.listFiles(
        highlightDirectory.value, iofilter, dirfilter).
        asScala.filterNot(excludes).toSeq
    },
    watchSources := markdownSources.value,
    sourceGenerators in Compile <+= Def.task {
      val src = markdownSources.value
      val out = sourceManaged.value
      val st = (highlightStartToken in ThisBuild).or(highlightStartToken).value
      val et = (highlightEndToken in ThisBuild).or(highlightEndToken).value
      val log = streams.value.log

      new HighlightExtractor(src, out, st, et, log).apply()
    },
    fork in Test := true
  )
}

final class HighlightExtractor(
  sources: Seq[File],
  out: File,
  startToken: String,
  endToken: String,
  log: Logger) {

  import java.io.PrintWriter

  private def generateFile(lines: Iterator[String], out: PrintWriter, ln: Long): (Iterator[String], Long) = if (!lines.hasNext) (lines -> ln) else {
    val line = lines.next()

    if (line == endToken) (lines -> (ln + 1)) else {
      out.println(line)
      out.flush()

      generateFile(lines, out, ln + 1)
    }
  }

  private def generate(out: File)(path: String, generated: Seq[File], samples: Seq[String], lines: Iterator[String], ln: Long, pkgi: Long): (Seq[File], Seq[String]) =
    if (!lines.hasNext) (generated -> samples) else {
      val line = lines.next()

      if (line contains startToken) {
        val n = generated.size
        val f = out / s"sample-$n.scala"
        lazy val p = new PrintWriter(new java.io.FileOutputStream(f))
        val first = lines.next()
        val pkg = first startsWith "package "

        log.info(s"Generating the sample #$n ...")

        try {
          if (!pkg) p.println(s"package samples$pkgi\n\ntrait Sample$n {")

          p.println(s"// File '$path', line ${ln + 1}\n")

          val (rem, no) = generateFile(Iterator(first) ++ lines, p, ln + 1L)

          if (!pkg) p.println("\n}")
          p.println(s"// end of sample #$n")

          p.flush()

          val sa = if (pkg) samples else samples :+ s"Sample$n"

          generate(out)(path, generated :+ f, sa, rem, no, pkgi)
        } finally { p.close() }
      } else generate(out)(path, generated, samples, lines, ln + 1, pkgi)
    }

  private def genPkg(out:File, i: Long, samples: Seq[String]): File = {
    val pkgf = out / s"package$i.scala"
    lazy val pkgout = new PrintWriter(new java.io.FileOutputStream(pkgf))

    try {
      pkgout.print(s"package object samples$i")

      samples.headOption.foreach { n =>
        pkgout.print(s"\n  extends $n")
      }

      samples.drop(1).foreach { n =>
        pkgout.print(s"\n  with $n")
      }

      pkgout.println(" { }")
      pkgout.flush()
    } finally {
      pkgout.close()
    }

    pkgf
  }

  def apply(): Seq[File] = {
    out.mkdirs()
    val gen = generate(out) _
    sources.foldLeft(Seq.empty[File] -> Seq.empty[String]) {
      case ((generated, samples), f) => {
        log.info(s"Processing $f ...")

        val pi = generated.size
        val (g, s) = gen(f.getAbsolutePath, generated, Nil,
          scala.io.Source.fromFile(f).getLines, 1L, pi)
        val pf = genPkg(out, pi, s)

        (g :+ pf) -> (samples ++ s)
      }
    }._1
  }
}
