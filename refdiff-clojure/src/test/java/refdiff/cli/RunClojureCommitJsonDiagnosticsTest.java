package refdiff.cli;
import static org.junit.Assert.*;
import org.junit.Test;
import com.fasterxml.jackson.databind.JsonNode;
public class RunClojureCommitJsonDiagnosticsTest {
 @Test public void preservesNestedParsingCausesAndLegacyFields() {
  Throwable root = new IllegalArgumentException("Unexpected EOF. [at line 55, column 39]");
  Throwable error = new RuntimeException("Error parsing cps.clj", new RuntimeException("Could not parse Clojure source", root));
  JsonNode json = RunClojureCommitJson.failureDetails(error);
  assertEquals("refdiff_failed", json.get("status").asText());
  assertEquals("Error parsing cps.clj", json.get("error").asText());
  assertEquals(3, json.get("causes").size());
  assertEquals(root.getMessage(), json.get("causes").get(2).get("message").asText());
 }
 @Test public void handlesNullMessagesAndCyclicCauses() {
  Throwable first = new RuntimeException(); Throwable second = new RuntimeException(first); first.initCause(second);
  JsonNode json = RunClojureCommitJson.failureDetails(first);
  assertEquals("java.lang.RuntimeException", json.get("error").asText());
  assertEquals(2, json.get("causes").size());
  assertTrue(json.get("causes").get(0).get("message").isNull());
 }
}
