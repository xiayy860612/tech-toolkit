package tech.toolkit.tenantcore.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public final class IpAddresses {

  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

  private IpAddresses() {}

  /** 只解析 IP 字面量（IPv4/IPv6），不触发 DNS 查询；无效返回 null。 */
  public static InetAddress parse(String ip) {
    if (ip == null || ip.isBlank()) {
      return null;
    }
    if (!IPV4.matcher(ip).matches() && !ip.contains(":")) {
      return null;
    }
    try {
      return InetAddress.getByName(ip);
    } catch (UnknownHostException e) {
      return null;
    }
  }
}
