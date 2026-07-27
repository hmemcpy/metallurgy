import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class TestReportInvocations {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("expected JUnit XML and summary paths");
    }

    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

    var document = factory.newDocumentBuilder().parse(new File(args[0]));
    NodeList testcases = document.getElementsByTagName("testcase");
    var invocations = new ArrayList<String>(testcases.getLength());
    for (var index = 0; index < testcases.getLength(); index++) {
      var testcase = (Element) testcases.item(index);
      var className = testcase.getAttribute("classname");
      var name = testcase.getAttribute("name");
      if (className.isEmpty() || name.isEmpty() || className.indexOf('\t') >= 0 || name.indexOf('\t') >= 0) {
        throw new IllegalArgumentException("testcase requires tab-free classname and name attributes");
      }
      invocations.add(className + "\t" + name);
    }
    Collections.sort(invocations);
    for (var invocation : invocations) {
      System.out.println(invocation);
    }
    var summary =
        "tests="
            + testcases.getLength()
            + "\nfailures="
            + document.getElementsByTagName("failure").getLength()
            + "\nerrors="
            + document.getElementsByTagName("error").getLength()
            + "\nskipped="
            + document.getElementsByTagName("skipped").getLength()
            + "\n";
    Files.writeString(Path.of(args[1]), summary, StandardCharsets.UTF_8);
  }
}
