package report;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExecutiveSummaryReport {

    // ------------ Data models ------------
    static class TestCaseResult {
        String name;
        String classname;
        double timeSec;
        Status status;
        String message;     // short reason if failed
        String details;     // optional long text
        String sourceXml;   // file path (optional)

        TestCaseResult(String name, String classname, double timeSec, Status status, String message, String details, String sourceXml) {
            this.name = name;
            this.classname = classname;
            this.timeSec = timeSec;
            this.status = status;
            this.message = message;
            this.details = details;
            this.sourceXml = sourceXml;
        }
    }

    static class SuiteResult {
        String displayName; // typically feature file or suite name
        int tests;
        int passed;
        int failed;
        int skipped;
        double timeSec;
        List<TestCaseResult> cases = new ArrayList<>();
    }

        enum Status { PASS, FAIL, UNSTABLE, SKIP }

    static class Summary {
        int totalTests;
        int passed;
        int failed;
        int skipped;
        double timeSec;
        List<SuiteResult> suites = new ArrayList<>();
        List<TestCaseResult> failedCases = new ArrayList<>();
        Path karateSummaryHtml; // optional
        String suiteGuess = "unknown";
        String envGuess = "unknown";
        String serviceGuess = "unknown";
    }

    // ------------ Main ------------
    public static void main(String[] args) throws Exception {
        String inputRoot = args != null && args.length > 0 ? args[0] : "target/karate-reports";
        String outputHtml = args != null && args.length > 1 ? args[1] : "target/executive-summary/index.html";

        Path inputDir = Paths.get(inputRoot).normalize();
        Path outputFile = Paths.get(outputHtml).normalize();

        Summary summary = collectSummary(inputDir);
        ensureParentDir(outputFile);

        String html = buildHtml(summary, inputDir, outputFile);
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);

        System.out.println("[ExecutiveSummaryReport] Generated: " + outputFile.toAbsolutePath());
    }

    // ------------ Collect ------------
    static Summary collectSummary(Path inputDir) throws Exception {
        Summary summary = new Summary();

        if (!Files.exists(inputDir)) {
            throw new IllegalStateException("Input folder not found: " + inputDir.toAbsolutePath());
        }

        // 1) Find JUnit XML files (Karate writes many XMLs)
        List<Path> xmlFiles;
        try (Stream<Path> s = Files.walk(inputDir)) {
            xmlFiles = s
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .collect(Collectors.toList());
        }

        if (xmlFiles.isEmpty()) {
            throw new IllegalStateException("No JUnit XML files found under: " + inputDir.toAbsolutePath());
        }

        // 2) Try to find karate-summary.html (evidence link)
        summary.karateSummaryHtml = findFirstFile(inputDir, "karate-summary.html");

        // 3) Guess suite/env/service from folder structure: target/karate-reports/<suite>/<env>/<service>/
        Path guessFrom = summary.karateSummaryHtml != null ? summary.karateSummaryHtml.getParent() : xmlFiles.get(0).getParent();
        if (guessFrom != null) {
            guessContextFromPath(summary, inputDir, guessFrom);
        }

        // 4) Parse each XML into SuiteResult(s)
        for (Path xml : xmlFiles) {
            parseJUnitXmlIntoSummary(xml, summary);
        }

        // 5) Compute totals and collect failed test cases
        for (SuiteResult s : summary.suites) {
            summary.totalTests += s.tests;
            summary.passed += s.passed;
            summary.failed += s.failed;
            summary.skipped += s.skipped;
            summary.timeSec += s.timeSec;

            for (TestCaseResult c : s.cases) {
                if (c.status == Status.FAIL) summary.failedCases.add(c);
            }
        }

        summary.failedCases.sort(Comparator
                .comparingDouble((TestCaseResult c) -> c.timeSec).reversed()
                .thenComparing(c -> safe(c.name)));

        summary.suites.sort(Comparator
                .comparingInt((SuiteResult s) -> s.failed).reversed()
                .thenComparing(s -> safe(s.displayName)));

        return summary;
    }

    static void parseJUnitXmlIntoSummary(Path xmlFile, Summary summary) throws Exception {
        Document doc = parseXml(xmlFile);

        Element root = doc.getDocumentElement();
        if (root == null) return;

        String rootName = root.getTagName();
        if ("testsuite".equalsIgnoreCase(rootName)) {
            SuiteResult suite = parseSuite(root, xmlFile);
            if (suite.tests > 0) summary.suites.add(suite);
        } else if ("testsuites".equalsIgnoreCase(rootName)) {
            NodeList suites = root.getElementsByTagName("testsuite");
            for (int i = 0; i < suites.getLength(); i++) {
                Node n = suites.item(i);
                if (n instanceof Element) {
                    SuiteResult suite = parseSuite((Element) n, xmlFile);
                    if (suite.tests > 0) summary.suites.add(suite);
                }
            }
        } else {
            NodeList suites = root.getElementsByTagName("testsuite");
            for (int i = 0; i < suites.getLength(); i++) {
                Node n = suites.item(i);
                if (n instanceof Element) {
                    SuiteResult suite = parseSuite((Element) n, xmlFile);
                    if (suite.tests > 0) summary.suites.add(suite);
                }
            }
        }
    }

    static SuiteResult parseSuite(Element suiteEl, Path xmlFile) {
        SuiteResult suite = new SuiteResult();

        String name = attr(suiteEl, "name");
        if (name.isBlank()) name = xmlFile.getFileName().toString();
        suite.displayName = name;

        suite.tests = intAttr(suiteEl, "tests");
        int failures = intAttr(suiteEl, "failures");
        int errors = intAttr(suiteEl, "errors");
        suite.failed = failures + errors;
        suite.skipped = intAttr(suiteEl, "skipped");
        suite.timeSec = doubleAttr(suiteEl, "time");

        NodeList tcNodes = suiteEl.getElementsByTagName("testcase");
        if (suite.tests <= 0) suite.tests = tcNodes.getLength();

        for (int i = 0; i < tcNodes.getLength(); i++) {
            Node n = tcNodes.item(i);
            if (!(n instanceof Element)) continue;
            Element tc = (Element) n;

            String tcName = attr(tc, "name");
            String cls = attr(tc, "classname");
            double t = doubleAttr(tc, "time");

            Status status = Status.PASS;
            String msg = "";
            String details = "";

            Element failure = firstChildElement(tc, "failure");
            Element error = firstChildElement(tc, "error");
            Element skipped = firstChildElement(tc, "skipped");

            if (skipped != null) {
                status = Status.SKIP;
                msg = firstNonBlank(attr(skipped, "message"), text(skipped));
                details = text(skipped);
            } else if (failure != null) {
                status = Status.FAIL;
                msg = firstNonBlank(attr(failure, "message"), text(failure));
                details = text(failure);
            } else if (error != null) {
                status = Status.FAIL;
                msg = firstNonBlank(attr(error, "message"), text(error));
                details = text(error);
            }

            suite.cases.add(new TestCaseResult(
                    tcName, cls, t, status,
                    trimOneLine(msg, 160),
                    trim(details, 1200),
                    xmlFile.toString()
            ));
        }

        suite.passed = Math.max(0, suite.tests - suite.failed - suite.skipped);
        return suite;
    }

    // ------------ HTML Builder (Mood #1) ------------
    static String buildHtml(Summary s, Path inputDir, Path outputFile) {
        String generated = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String statusLabel = statusLabel(s);
        String statusDotClass = statusDotClass(s);
        String duration = formatDuration(s.timeSec);

        String karateLink = "";
        if (s.karateSummaryHtml != null && Files.exists(s.karateSummaryHtml)) {
            karateLink = toRelativeHref(outputFile.getParent(), s.karateSummaryHtml);
        }

        StringBuilder sb = new StringBuilder(120_000);
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\"/>\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n")
                .append("<title>API Test Executive Summary</title>\n")
                .append("<style>\n")
                .append(CALM_PRO_CSS)
                .append("\n</style>\n</head>\n<body>\n");

        sb.append("<div class=\"container\">");

        // Topbar
        sb.append("<div class=\"topbar\">")
                .append("<div class=\"title\">")
                .append("<h1>API Test Executive Summary</h1>")
                .append("<p>")
                .append("Suite: <b>").append(esc(s.suiteGuess)).append("</b>")
                .append(" \u00b7 Env: <b>").append(esc(s.envGuess)).append("</b>")
                .append(" \u00b7 Service: <b>").append(esc(s.serviceGuess)).append("</b>")
                .append(" \u00b7 Generated: <b>").append(esc(generated)).append("</b>")
                .append("</p>")
                .append("</div>");

        sb.append("<div class=\"badge\">")
                .append("<span class=\"dot ").append(statusDotClass).append("\"></span>")
                .append(esc(statusLabel))
                .append("</div>");

        sb.append("</div>"); // topbar

        // KPI row (horizontal)
        sb.append("<div class=\"grid cards\">");
        sb.append(card("Total", String.valueOf(s.totalTests), "Scenarios executed"));
        sb.append(card("Passed", String.valueOf(s.passed), percentHint(s.passed, s.totalTests)));
        sb.append(card("Failed", String.valueOf(s.failed), s.failed > 0 ? percentHint(s.failed, s.totalTests)+" - Needs attention" : "No failures"));
        sb.append(card("Skipped", String.valueOf(s.skipped), s.skipped > 0 ? percentHint(s.skipped, s.totalTests)+" - Filtered or conditional" : "None"));
        sb.append(card("Duration", duration, "Wall clock (approx.)"));
        sb.append("</div>");

        // Evidence
        sb.append("<h2>Evidence</h2>");
        sb.append("<div class=\"card\">");
        if (!karateLink.isBlank()) {
            sb.append("<div class=\"pill info\"><span class=\"dot\"></span>")
                    .append("<a href=\"").append(escAttr(karateLink)).append("\">Open Karate HTML summary</a>")
                    .append("</div>");
        } else {
            sb.append("<p class=\"muted\">Karate summary not found (expected: karate-summary.html under ")
                    .append(esc(inputDir.toString()))
                    .append(")</p>");
        }
        sb.append("</div>");

        // Results by Feature
        sb.append("<h2>Results by Feature</h2>");
        sb.append("<table><thead><tr>")
                .append("<th>Feature</th>")
                .append("<th>Status</th>")
                .append("<th>Passed</th>")
                .append("<th>Failed</th>")
                .append("<th>Skipped</th>")
                .append("<th>Duration</th>")
                .append("</tr></thead><tbody>");

        int suiteIndex = 0;
        for (SuiteResult suite : s.suites) {
            int executed = executedCount(suite.tests, suite.skipped);

            String pillClass;
            String pillText;

            if (suite.tests == 0) {
                pillClass = "warn";
                pillText = "NO TESTS";
            } else if (executed == 0) {
                pillClass = "warn";
                pillText = "ALL SKIPPED";
            } else if (suite.failed == 0) {
                pillClass = "ok";
                pillText = "PASS";
            } else if (suite.failed == executed) {
                pillClass = "bad";
                pillText = "FAIL";
            } else {
                pillClass = "warn";
                pillText = "UNSTABLE";
            }
            String anchor = "suite-" + suiteIndex++;

            sb.append("<tr>")
                    .append("<td><a href=\"#").append(escAttr(anchor)).append("\">").append(esc(suite.displayName)).append("</a></td>")
                    .append("<td><span class=\"pill ").append(pillClass).append("\">").append(esc(pillText)).append("</span></td>")
                    .append("<td>").append(suite.passed).append("</td>")
                    .append("<td>").append(suite.failed).append("</td>")
                    .append("<td>").append(suite.skipped).append("</td>")
                    .append("<td>").append(esc(formatDuration(suite.timeSec))).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table>");

        // Top failures
        sb.append("<h2>Top Failures</h2>");
        if (s.failedCases.isEmpty()) {
            sb.append("<div class=\"card\"><p class=\"muted\">No failures detected.</p></div>");
        } else {
            sb.append("<table><thead><tr>")
                    .append("<th>Scenario</th>")
                    .append("<th>Feature</th>")
                    .append("<th>Reason</th>")
                    .append("<th>Time</th>")
                    .append("</tr></thead><tbody>");

            int limit = Math.min(20, s.failedCases.size());
            for (int i = 0; i < limit; i++) {
                TestCaseResult c = s.failedCases.get(i);
                sb.append("<tr>")
                        .append("<td>").append(esc(c.name)).append("</td>")
                        .append("<td class=\"muted\">").append(esc(c.classname)).append("</td>")
                        .append("<td>").append(esc(firstNonBlank(c.message, "(no message)"))).append("</td>")
                        .append("<td>").append(esc(formatDuration(c.timeSec))).append("</td>")
                        .append("</tr>");
            }
            sb.append("</tbody></table>");
            sb.append("<div class=\"footer\">Showing ").append(limit).append(" of ").append(s.failedCases.size()).append(" failing scenarios</div>");
        }

        // ✅ Scenario Results (NEW)
        sb.append("<h2>Scenario Results</h2>");
        sb.append("<div class=\"toolbar\">")
                .append("<div class=\"btn-group\">")
                .append("<button class=\"btn\" data-filter=\"ALL\">All</button>")
                .append("<button class=\"btn\" data-filter=\"FAIL\">Failed</button>")
                .append("<button class=\"btn\" data-filter=\"PASS\">Passed</button>")
                .append("<button class=\"btn\" data-filter=\"SKIP\">Skipped</button>")
                .append("</div>")
                .append("<input id=\"search\" class=\"search\" type=\"search\" placeholder=\"Search scenario / feature…\"/>")
                .append("</div>");

        suiteIndex = 0;
        for (SuiteResult suite : s.suites) {
            String anchor = "suite-" + suiteIndex++;
            int executed = executedCount(suite.tests, suite.skipped);
            Status roll = rollupStatus(suite.tests, suite.failed, suite.skipped);

            String suiteStatus;
            String suitePill;

            if (suite.tests == 0) {
                suiteStatus = "NO TESTS";
                suitePill = "warn";
            } else if (executed == 0) {
                suiteStatus = "ALL SKIPPED";
                suitePill = "warn";
            } else if (roll == Status.PASS) {
                suiteStatus = "PASS";
                suitePill = "ok";
            } else if (roll == Status.FAIL) {
                suiteStatus = "FAIL";
                suitePill = "bad";
            } else { // UNSTABLE
                suiteStatus = "UNSTABLE";
                suitePill = "warn";
            }


            sb.append("<details class=\"suite\" open id=\"").append(escAttr(anchor)).append("\">");
            sb.append("<summary>")
                    .append("<span class=\"sum-title\">").append(esc(suite.displayName)).append("</span>")
                    .append("<span class=\"sum-meta\">")
                    .append("<span class=\"pill ").append(suitePill).append("\">").append(esc(suiteStatus)).append("</span>")
                    .append("<span class=\"meta\">").append("P ").append(suite.passed).append("</span>")
                    .append("<span class=\"meta\">").append("F ").append(suite.failed).append("</span>")
                    .append("<span class=\"meta\">").append("S ").append(suite.skipped).append("</span>")
                    .append("<span class=\"meta\">").append(esc(formatDuration(suite.timeSec))).append("</span>")
                    .append("</span>")
                    .append("</summary>");

            sb.append("<table class=\"scenario-table\">")
                    .append("<thead><tr>")
                    .append("<th>Status</th>")
                    .append("<th>Scenario</th>")
                    .append("<th>Duration</th>")
                    .append("<th>Reason (only if failed)</th>")
                    .append("</tr></thead><tbody>");

            for (TestCaseResult c : suite.cases) {
                String st = switch (c.status) {
                    case FAIL -> "FAIL";
                    case SKIP -> "SKIP";
                    default -> "PASS";
                };
                String stClass = switch (c.status) {
                    case FAIL -> "bad";
                    case SKIP -> "warn";
                    default -> "ok";
                };

                String rowText = (suite.displayName + " " + c.name).toLowerCase(Locale.ROOT);

                sb.append("<tr class=\"sc-row\" data-status=\"").append(escAttr(st)).append("\" data-text=\"").append(escAttr(rowText)).append("\">")
                        .append("<td><span class=\"pill ").append(stClass).append("\">").append(esc(st)).append("</span></td>")
                        .append("<td>").append(esc(c.name)).append("</td>")
                        .append("<td>").append(esc(formatDuration(c.timeSec))).append("</td>")
                        .append("<td>");

                if (c.status == Status.FAIL) {
                    sb.append("<div class=\"reason\">").append(esc(firstNonBlank(c.message, "(no message)"))).append("</div>");
                    if (c.details != null && !c.details.isBlank()) {
                        sb.append("<details class=\"mini\">")
                                .append("<summary>Details</summary>")
                                .append("<pre class=\"details\">").append(esc(c.details)).append("</pre>")
                                .append("</details>");
                    }
                } else {
                    sb.append("<span class=\"muted\">—</span>");
                }

                sb.append("</td></tr>");
            }

            sb.append("</tbody></table>");
            sb.append("</details>");
        }

        sb.append("<div class=\"footer\">Generated by ExecutiveSummaryReport</div>");
        sb.append("</div>"); // container

        // ✅ Inline JS (single file)
        sb.append("<script>\n")
                .append(JS_FILTERS)
                .append("\n</script>\n");

        sb.append("\n</body>\n</html>");
        return sb.toString();
    }

    static String card(String label, String value, String hint) {
        return "<div class=\"card\">" +
                "<p class=\"label\">" + esc(label) + "</p>" +
                "<p class=\"value\">" + esc(value) + "</p>" +
                "<p class=\"hint\">" + esc(hint) + "</p>" +
                "</div>";
    }

    static String percentHint(int part, int total) {
        if (total <= 0) return "0%";
        double pct = (100.0 * part) / total;
        return String.format(Locale.US, "%.1f%%", pct);
    }

    static String statusLabel(Summary s) {
        if (s.totalTests == 0) return "NO TESTS";

        int executed = executedCount(s.totalTests, s.skipped);
        if (executed == 0) return "ALL SKIPPED";

        if (s.failed == 0) return "PASS";
        if (s.failed == executed) return "FAIL";
        return "UNSTABLE";
    }

    static String statusDotClass(Summary s) {
        if (s.totalTests == 0) return "warn";

        int executed = executedCount(s.totalTests, s.skipped);
        if (executed == 0) return "warn";

        if (s.failed == 0) return "ok";
        if (s.failed == executed) return "bad";
        return "warn"; // UNSTABLE uses amber
    }

    // ------------ Helpers ------------
    static Document parseXml(Path file) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder db = dbf.newDocumentBuilder();
        try (InputStream is = Files.newInputStream(file)) {
            return db.parse(is);
        }
    }

    static Element firstChildElement(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        return (n instanceof Element) ? (Element) n : null;
    }

    static String attr(Element el, String name) {
        if (el.hasAttribute(name)) return el.getAttribute(name).trim();
        return "";
    }

    static int intAttr(Element el, String name) {
        String v = attr(el, name);
        if (v.isBlank()) return 0;
        try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
    }

    static double doubleAttr(Element el, String name) {
        String v = attr(el, name);
        if (v.isBlank()) return 0.0;
        try { return Double.parseDouble(v); } catch (Exception e) { return 0.0; }
    }

    static String text(Element el) {
        String t = el.getTextContent();
        return t == null ? "" : t.trim();
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        String x = s.trim();
        if (x.length() <= max) return x;
        return x.substring(0, max - 3) + "...";
    }

    static String trimOneLine(String s, int max) {
        if (s == null) return "";
        String x = s.replaceAll("\\s+", " ").trim();
        return trim(x, max);
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    static String safe(String s) { return s == null ? "" : s; }

    static void ensureParentDir(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    static Path findFirstFile(Path root, String fileName) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst()
                    .orElse(null);
        }
    }

    static void guessContextFromPath(Summary summary, Path inputRoot, Path actualPath) {
        Path rel;
        try {
            rel = inputRoot.relativize(actualPath);
        } catch (Exception e) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Path p : rel) parts.add(p.toString());

        if (parts.size() >= 3) {
            summary.suiteGuess = parts.get(0);
            summary.envGuess = parts.get(1);
            summary.serviceGuess = parts.get(2);
        }
    }

    static String toRelativeHref(Path fromDir, Path toFile) {
        try {
            Path rel = fromDir.toAbsolutePath().normalize().relativize(toFile.toAbsolutePath().normalize());
            return rel.toString().replace('\\', '/');
        } catch (Exception e) {
            return toFile.toString().replace('\\', '/');
        }
    }

    static String formatDuration(double seconds) {
        if (seconds <= 0) return "00:00";
        long sec = Math.round(seconds);
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String escAttr(String s) {
        return esc(s).replace("'", "&#39;");
    }

    static int executedCount(int total, int skipped) {
        return Math.max(0, total - skipped);
    }

    static Status rollupStatus(int tests, int failed, int skipped) {
        int executed = executedCount(tests, skipped);
        if (tests == 0) return Status.SKIP;          // or treat as NO TESTS elsewhere
        if (executed == 0) return Status.SKIP;       // all skipped
        if (failed == 0) return Status.PASS;
        if (failed == executed) return Status.FAIL;
        return Status.UNSTABLE;
    }
    // ------------ Inline JS (filters/search) ------------
    static final String JS_FILTERS = """
(function(){
  const buttons = Array.from(document.querySelectorAll('.btn[data-filter]'));
  const search = document.getElementById('search');
  const rows = () => Array.from(document.querySelectorAll('.sc-row'));

  let currentFilter = 'ALL';
  let query = '';

  function apply(){
    const q = (query || '').toLowerCase().trim();
    rows().forEach(r => {
      const st = r.getAttribute('data-status') || '';
      const text = r.getAttribute('data-text') || '';
      const okFilter = (currentFilter === 'ALL') || (st === currentFilter);
      const okSearch = !q || text.includes(q);
      r.style.display = (okFilter && okSearch) ? '' : 'none';
    });
  }

  buttons.forEach(b => {
    b.addEventListener('click', () => {
      buttons.forEach(x => x.classList.remove('active'));
      b.classList.add('active');
      currentFilter = b.getAttribute('data-filter');
      apply();
    });
  });

  if (buttons.length) buttons[0].classList.add('active');

  search?.addEventListener('input', (e) => {
    query = e.target.value || '';
    apply();
  });

  apply();
})();
""";

    // ------------ Embedded CSS theme (Mood #1: Calm & Professional) ------------
    static final String CALM_PRO_CSS = """
  :root{
    --bg: #ffffff;
    --text: rgba(15, 23, 42, .92);
    --muted: rgba(15, 23, 42, .58);
    --line: rgba(15, 23, 42, .10);

    --ok: #16a34a;
    --warn: #d97706;
    --bad: #dc2626;
    --info: #2563eb;
  }

  * { box-sizing: border-box; }

  body{
    margin: 0;
    font-family: ui-sans-serif, system-ui, -apple-system, Segoe UI, Roboto, Arial, "Noto Sans", "Liberation Sans", sans-serif;
    background: var(--bg);
    color: var(--text);
    line-height: 1.55;
  }

  .container{
    max-width: 1160px;
    margin: 0 auto;
    padding: 34px 22px 70px;
  }

  .topbar{
    display:flex;
    align-items:flex-start;
    justify-content:space-between;
    gap: 18px;
    padding: 0 0 18px 0;
    border-bottom: 1px solid var(--line);
  }

  .title h1{
    margin: 0;
    font-size: 24px;
    font-weight: 850;
    letter-spacing: .2px;
  }

  .title p{
    margin: 10px 0 0;
    color: var(--muted);
    font-size: 13px;
  }

  .badge{
    display:inline-flex;
    align-items:center;
    gap:10px;
    padding: 0;
    border: 0;
    background: transparent;
    font-weight: 850;
    font-size: 13px;
    white-space: nowrap;
    color: rgba(15,23,42,.80);
  }

  .dot{
    width: 10px; height: 10px;
    border-radius: 999px;
    background: var(--info);
  }
  .dot.ok{ background: var(--ok); }
  .dot.warn{ background: var(--warn); }
  .dot.bad{ background: var(--bad); }

  .grid{
    display:grid;
    gap: 10px;
    margin-top: 18px;
  }

  .grid.cards{
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 18px;
    margin-top: 18px;
    padding: 6px 0 18px;
    border-bottom: 1px solid var(--line);
  }

  @media (max-width: 1020px){
    .grid.cards{ grid-template-columns: repeat(2, minmax(0, 1fr)); }
  }
  @media (max-width: 560px){
    .grid.cards{ grid-template-columns: 1fr; }
  }

  .card{
    border: 0;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
    padding: 0;
  }

  .grid.cards .card{
    padding-left: 14px;
    border-left: 1px solid rgba(15,23,42,.08);
  }
  .grid.cards .card:first-child{
    padding-left: 0;
    border-left: 0;
  }
  @media (max-width: 1020px){
    .grid.cards .card{
      padding-left: 0;
      border-left: 0;
    }
  }

  .card .label{
    margin: 0;
    font-size: 12px;
    letter-spacing: .28px;
    text-transform: uppercase;
    color: var(--muted);
  }

  .card .value{
    margin: 8px 0 0;
    font-size: 28px;
    font-weight: 900;
    letter-spacing: .2px;
  }

  .card .hint{
    margin: 8px 0 0;
    font-size: 12.5px;
    color: var(--muted);
  }

  h2{
    margin: 28px 0 12px;
    font-size: 14px;
    font-weight: 900;
    letter-spacing: .28px;
    text-transform: uppercase;
    color: rgba(15,23,42,.75);
  }

  table{
    width:100%;
    border-collapse: collapse;
    border-spacing: 0;
    border: 0;
    background: transparent;
  }

  thead th{
    text-align:left;
    font-size: 12px;
    letter-spacing: .28px;
    color: rgba(15,23,42,.65);
    padding: 10px 0;
    border-bottom: 1px solid var(--line);
  }

  tbody td{
    padding: 12px 0;
    border-bottom: 1px solid rgba(15,23,42,.08);
    font-size: 13px;
    color: rgba(15,23,42,.88);
    vertical-align: top;
  }

  .pill{
    padding: 0;
    border: 0;
    background: transparent;
    font-weight: 900;
    font-size: 12px;
  }
  .pill.ok{ color: var(--ok); }
  .pill.warn{ color: var(--warn); }
  .pill.bad{ color: var(--bad); }
  .pill.info{ color: var(--info); }

  a{
    color: var(--info);
    text-decoration: none;
    font-weight: 750;
  }
  a:hover{ text-decoration: underline; }

  .muted{ color: var(--muted); }

  .footer{
    margin-top: 18px;
    color: var(--muted);
    font-size: 12px;
    text-align: right;
  }

  /* Scenario section */
  .toolbar{
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap: 12px;
    padding: 10px 0 6px;
    border-bottom: 1px solid rgba(15,23,42,.08);
    margin-bottom: 10px;
  }

  .btn-group{ display:flex; gap: 10px; flex-wrap: wrap; }

  .btn{
    appearance: none;
    border: 1px solid rgba(15,23,42,.12);
    background: transparent;
    padding: 7px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 800;
    color: rgba(15,23,42,.75);
    cursor: pointer;
  }
  .btn:hover{ border-color: rgba(15,23,42,.22); }
  .btn.active{
    border-color: rgba(37,99,235,.45);
    color: rgba(37,99,235,.95);
  }

  .search{
    width: min(420px, 100%);
    padding: 9px 12px;
    border-radius: 12px;
    border: 1px solid rgba(15,23,42,.12);
    font-size: 13px;
    outline: none;
  }
  .search:focus{
    border-color: rgba(37,99,235,.45);
  }

  details.suite{
    padding: 10px 0;
    border-bottom: 1px solid rgba(15,23,42,.08);
  }

  details.suite summary{
    list-style: none;
    cursor: pointer;
    display:flex;
    align-items:center;
    justify-content:space-between;
    gap: 14px;
    padding: 10px 0;
  }
  details.suite summary::-webkit-details-marker{ display:none; }

  .sum-title{
    font-weight: 900;
    color: rgba(15,23,42,.86);
  }

  .sum-meta{
    display:flex;
    align-items:center;
    gap: 12px;
    flex-wrap: wrap;
  }
  .meta{
    font-size: 12px;
    font-weight: 850;
    color: rgba(15,23,42,.60);
  }

  .scenario-table thead th{ padding-top: 8px; }
  .scenario-table tbody td{ padding: 10px 0; }

  .reason{
    font-weight: 700;
    color: rgba(15,23,42,.84);
  }

  details.mini summary{
    margin-top: 8px;
    font-size: 12px;
    font-weight: 850;
    color: rgba(37,99,235,.90);
  }

  pre.details{
    white-space: pre-wrap;
    word-break: break-word;
    margin: 8px 0 0;
    padding: 10px 12px;
    border-radius: 12px;
    border: 1px solid rgba(15,23,42,.10);
    background: rgba(15,23,42,.02);
    font-size: 12px;
    color: rgba(15,23,42,.82);
  }
""";
}
