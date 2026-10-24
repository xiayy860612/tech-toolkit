package com.s2u2m.uam.cli;

import com.s2u2m.uam.cli.CliArguments.UsageException;
import com.s2u2m.uam.domain.model.ClientIdentity;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code generate-service-key --client <名称>}：生成一把服务密钥（research R4）。密钥是 32 字节密码学安全随机数的
 * base64url（无填充）， 前缀 {@code uamk_}。<b>标准输出只输出密钥一次</b>（一行）；对应的 {@code sha256:<hex>} 与可粘贴的 {@code
 * UAM_CLIENTS} 片段写标准错误。
 *
 * <p>本命令<b>不需要任何依赖</b>：不连 PostgreSQL、不连 Redis、不跑 Flyway。{@link StandaloneCommands} 在启动 Spring
 * 之前直接执行它，所以镜像里不配数据库也能用； 注册成 Bean 只是让 {@link CliRunner} 的用法提示里列出它。
 */
@Component
@Profile("cli")
public class GenerateServiceKeyCommand implements CliCommand {

  /** 命令名。 */
  public static final String NAME = "generate-service-key";

  static final String KEY_PREFIX = "uamk_";
  private static final int KEY_BYTES = 32;
  private static final Pattern CLIENT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");

  private final SecureRandom random;

  public GenerateServiceKeyCommand() {
    this(new SecureRandom());
  }

  public GenerateServiceKeyCommand(SecureRandom random) {
    this.random = random;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public int run(List<String> args, InputStream stdin, PrintStream stdout, PrintStream stderr) {
    try {
      CliArguments options = CliArguments.parse(args, Set.of("client"));
      String client = options.require("client");
      if (!CLIENT_NAME.matcher(client).matches()) {
        throw new UsageException("--client 只能含字母、数字、. _ -，以字母或数字开头，至多 63 个字符");
      }
      byte[] bytes = new byte[KEY_BYTES];
      random.nextBytes(bytes);
      String key = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
      String hash = "sha256:" + HexFormat.of().formatHex(sha256(key));

      stderr.println("已为客户端 " + client + " 生成服务密钥（标准输出，只此一次，请存入密钥存储，不要入库）。");
      stderr.println("把它的哈希加进 UAM 的 UAM_CLIENTS：" + hash);
      stderr.println(
          "[{\"id\":\""
              + client
              + "\",\"keyHashes\":[\""
              + hash
              + "\"],\"permissions\":[\""
              + ClientIdentity.ASSERT_END_USER_CONTEXT
              + "\"]}]");
      stderr.println("轮换时，同一个客户端的 keyHashes 同时列出新旧两个哈希（至多两个），" + "待客户端换用新密钥后再删掉旧哈希。");
      stdout.println(key);
      return 0;
    } catch (UsageException e) {
      stderr.println("参数错误：" + e.getMessage());
      return 2;
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 必须提供 SHA-256", e);
    }
  }
}
