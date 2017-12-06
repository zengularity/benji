package tests.benji.vfs

import java.io.ByteArrayInputStream

import com.zengularity.benji.vfs.LimitedInputStream

class LimitedInputStreamSpec extends org.specs2.mutable.Specification {
  "LimitedInputStream" title

  "Input stream" should {
    "provide only the expected 5 bytes" in {
      read(stream1(5)) must_== "Hello"
    }

    "provide only the expected 6 bytes, after offset 2" in {
      read(stream2(6) { in => in.skip(2); in }) must_== "llo Wo"
    }
  }

  // ---

  def read(in: LimitedInputStream): String =
    scala.io.Source.fromInputStream(in).mkString("")

  def stream1(limit: Int): LimitedInputStream = stream2(limit)(identity)

  def stream2(limit: Int)(f: ByteArrayInputStream => ByteArrayInputStream): LimitedInputStream = {
    def sub = f(new ByteArrayInputStream("Hello World !!!".getBytes("UTF-8")))
    new LimitedInputStream(sub, limit)
  }
}
