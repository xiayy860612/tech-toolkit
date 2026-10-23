package com.s2u2m.uam.infrastructure.security;

import com.s2u2m.uam.domain.model.ClientIdentity;
import com.s2u2m.uam.domain.port.ClientAuthenticator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * 客户端清单（{@code UAM_CLIENTS}，配置而非入库）与服务密钥校验（research R4）。
 *
 * <p>密钥高熵，用 SHA-256 哈希（{@code sha256:<hex>}）而不是慢哈希；比较用常量时间，并遍历全部哈希不提前返回。每个客户端 1–2 个有效哈希，
 * 以便不停机轮换。配置不合法时启动失败。
 */
public final class ClientRegistry implements ClientAuthenticator {

  /** {@code UAM_CLIENTS} 的一项。 */
  record ClientDefinition(String id, List<String> keyHashes, List<String> permissions) {}

  private record Entry(ClientIdentity identity, List<byte[]> hashes) {}

  private static final Pattern HASH_FORMAT = Pattern.compile("^sha256:[0-9a-fA-F]{64}$");
  private static final int MAX_KEYS_PER_CLIENT = 2;

  private final List<Entry> entries;

  private ClientRegistry(List<Entry> entries) {
    this.entries = entries;
  }

  public static ClientRegistry fromJson(JsonMapper mapper, String json) {
    List<ClientDefinition> definitions;
    try {
      definitions = List.of(mapper.readValue(json, ClientDefinition[].class));
    } catch (RuntimeException e) {
      // 不回显原文，避免把配置内容写进日志
      throw new IllegalArgumentException("UAM_CLIENTS 不是合法的客户端清单 JSON");
    }
    Set<String> ids = new HashSet<>();
    List<Entry> entries =
        definitions.stream()
            .map(
                definition -> {
                  if (definition.id() == null
                      || definition.id().isBlank()
                      || !ids.add(definition.id())) {
                    throw new IllegalArgumentException("UAM_CLIENTS 的客户端 id 必须非空且不重复");
                  }
                  return toEntry(definition);
                })
            .toList();
    return new ClientRegistry(entries);
  }

  private static Entry toEntry(ClientDefinition definition) {
    List<String> keyHashes = definition.keyHashes() == null ? List.of() : definition.keyHashes();
    if (keyHashes.isEmpty() || keyHashes.size() > MAX_KEYS_PER_CLIENT) {
      throw new IllegalArgumentException(
          "客户端 " + definition.id() + " 必须配置 1–" + MAX_KEYS_PER_CLIENT + " 个 keyHashes");
    }
    List<byte[]> hashes =
        keyHashes.stream()
            .map(
                hash -> {
                  if (hash == null || !HASH_FORMAT.matcher(hash).matches()) {
                    throw new IllegalArgumentException(
                        "客户端 " + definition.id() + " 的 keyHashes 必须是 sha256:<64 位十六进制>");
                  }
                  return java.util.HexFormat.of().parseHex(hash.substring("sha256:".length()));
                })
            .toList();
    Set<String> permissions =
        definition.permissions() == null ? Set.of() : Set.copyOf(definition.permissions());
    return new Entry(new ClientIdentity(definition.id(), permissions), hashes);
  }

  @Override
  public Optional<ClientIdentity> authenticate(String serviceKey) {
    if (serviceKey == null || serviceKey.isEmpty()) {
      return Optional.empty();
    }
    byte[] presented = sha256(serviceKey);
    ClientIdentity match = null;
    for (Entry entry : entries) {
      for (byte[] hash : entry.hashes()) {
        if (MessageDigest.isEqual(presented, hash)) {
          match = entry.identity();
        }
      }
    }
    return Optional.ofNullable(match);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JDK 必须提供 SHA-256", e);
    }
  }
}
