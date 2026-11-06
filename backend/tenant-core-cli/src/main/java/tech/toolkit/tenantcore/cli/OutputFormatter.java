package tech.toolkit.tenantcore.cli;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Prints command results as one text line or one JSON line; never prints credentials. */
public class OutputFormatter {

  private final PrintStream out;
  private final boolean json;

  public OutputFormatter(PrintStream out, boolean json) {
    this.out = out;
    this.json = json;
  }

  public boolean isJson() {
    return json;
  }

  /**
   * {@code created tenant=acme name=Acme} in text mode, {@code {"result":"created",...}} in JSON.
   */
  public void result(String result, Map<String, Object> fields) {
    if (json) {
      Map<String, Object> all = new LinkedHashMap<>();
      all.put("result", result);
      all.putAll(fields);
      out.println(toJson(all));
    } else {
      String details =
          fields.entrySet().stream()
              .map(e -> e.getKey() + "=" + e.getValue())
              .collect(Collectors.joining(" "));
      out.println(details.isEmpty() ? result : result + " " + details);
    }
  }

  public void line(String text) {
    out.println(text);
  }

  static String toJson(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream()
          .map(e -> quote(String.valueOf(e.getKey())) + ":" + toJson(e.getValue()))
          .collect(Collectors.joining(",", "{", "}"));
    }
    if (value instanceof List<?> list) {
      return list.stream().map(OutputFormatter::toJson).collect(Collectors.joining(",", "[", "]"));
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    return quote(value.toString());
  }

  private static String quote(String text) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : text.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
