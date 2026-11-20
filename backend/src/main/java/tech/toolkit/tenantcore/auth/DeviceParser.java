package tech.toolkit.tenantcore.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 把 User-Agent 解析为「浏览器 主版本 / 系统」，原始 UA 不对外展示。 */
public final class DeviceParser {

  private static final Pattern EDGE = Pattern.compile("Edg(?:e|A|iOS)?/(\\d+)");
  private static final Pattern FIREFOX = Pattern.compile("(?:Firefox|FxiOS)/(\\d+)");
  private static final Pattern CHROME = Pattern.compile("(?:Chrome|CriOS)/(\\d+)");
  private static final Pattern SAFARI = Pattern.compile("Version/(\\d+).*Safari/");
  static final String UNKNOWN = "未知设备";

  private DeviceParser() {}

  public static String parse(String userAgent) {
    if (userAgent == null || userAgent.isBlank()) {
      return UNKNOWN;
    }
    String browser = browser(userAgent);
    String os = os(userAgent);
    if (browser == null && os == null) {
      return UNKNOWN;
    }
    String result = (browser == null ? "未知浏览器" : browser) + " / " + (os == null ? "未知系统" : os);
    return result.length() > 100 ? result.substring(0, 100) : result;
  }

  private static String browser(String ua) {
    Matcher m;
    if ((m = EDGE.matcher(ua)).find()) return "Edge " + m.group(1);
    if ((m = FIREFOX.matcher(ua)).find()) return "Firefox " + m.group(1);
    if ((m = CHROME.matcher(ua)).find()) return "Chrome " + m.group(1);
    if ((m = SAFARI.matcher(ua)).find()) return "Safari " + m.group(1);
    return null;
  }

  private static String os(String ua) {
    if (ua.contains("Windows")) return "Windows";
    if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
    if (ua.contains("Android")) return "Android";
    if (ua.contains("Mac OS X") || ua.contains("Macintosh")) return "macOS";
    if (ua.contains("Linux")) return "Linux";
    return null;
  }
}
