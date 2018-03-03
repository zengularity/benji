// FindBugs
findbugsReportType := Some(FindbugsReport.PlainHtml)

findbugsReportPath := Some(target.value / "findbugs.html")

findbugsExcludeFilters := {
  val loadXML = scala.xml.XML.loadFile(_: File)
  val commonFilters = loadXML(
    baseDirectory.value / "project" / (
      "findbugs-exclude-filters.xml"))

  val filters = {
    val f = baseDirectory.value / "findbugs-exclude-filters.xml"
    if (!f.exists) scala.xml.NodeSeq.Empty else loadXML(f).child
  }

  Some(
    <FindBugsFilter>${ commonFilters.child }${ filters }</FindBugsFilter>
  )
}
