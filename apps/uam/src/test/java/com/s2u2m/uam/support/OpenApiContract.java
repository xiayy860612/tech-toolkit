package com.s2u2m.uam.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 契约测试辅助：把请求 / 响应按 {@code contracts/uam/openapi.yaml} 的 {@code components.schemas} 校验。
 *
 * <p>除 JSON Schema 校验（含 {@code format}）外，还做严格的「没有契约之外的字段」检查：契约里的对象没有声明 {@code additionalProperties:
 * false}，多出来的字段（例如意外带出的哈希）单靠 Schema 校验发现不了。
 */
public final class OpenApiContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final JsonNode COMPONENTS = loadComponents();
  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder.schemaRegistryConfig(
                  SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

  private OpenApiContract() {}

  private static JsonNode loadComponents() {
    Path file = Path.of("..", "..", "contracts", "uam", "openapi.yaml");
    try {
      var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new YAMLFactory());
      String json =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(yaml.readTree(Files.readString(file)));
      return JSON.readTree(json).get("components");
    } catch (IOException e) {
      throw new IllegalStateException("读取 OpenAPI 契约失败: " + file.toAbsolutePath(), e);
    }
  }

  /** 校验 {@code instance} 符合 {@code components.schemas.<schemaName>}，且没有契约之外的字段。 */
  public static void assertConforms(String schemaName, JsonNode instance) {
    ObjectNode wrapper = JSON.createObjectNode();
    wrapper.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    wrapper.put("$ref", "#/components/schemas/" + schemaName);
    wrapper.set("components", COMPONENTS);
    Schema schema = REGISTRY.getSchema(wrapper);
    List<Error> errors = schema.validate(instance);
    assertThat(errors).as("%s 应符合契约，实例: %s", schemaName, instance).isEmpty();

    List<String> extras = new ArrayList<>();
    collectUnknown(COMPONENTS.get("schemas").get(schemaName), instance, "$", extras);
    assertThat(extras).as("%s 含契约之外的字段", schemaName).isEmpty();
  }

  /** 响应体里出现过的全部字段名（递归），用来断言没有泄露敏感字段。 */
  public static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    collectNames(node, names);
    return names;
  }

  private static void collectNames(JsonNode node, Set<String> names) {
    if (node.isObject()) {
      node.propertyNames()
          .forEach(
              name -> {
                names.add(name);
                collectNames(node.get(name), names);
              });
    } else if (node.isArray()) {
      node.forEach(child -> collectNames(child, names));
    }
  }

  private static void collectUnknown(
      JsonNode schema, JsonNode instance, String path, List<String> extras) {
    JsonNode resolved = resolve(schema);
    if (instance.isArray() && resolved.has("items")) {
      for (int i = 0; i < instance.size(); i++) {
        collectUnknown(resolved.get("items"), instance.get(i), path + "[" + i + "]", extras);
      }
      return;
    }
    if (!instance.isObject() || !resolved.has("properties")) {
      return;
    }
    JsonNode properties = resolved.get("properties");
    instance
        .propertyNames()
        .forEach(
            name -> {
              if (!properties.has(name)) {
                extras.add(path + "." + name);
              } else {
                collectUnknown(properties.get(name), instance.get(name), path + "." + name, extras);
              }
            });
  }

  private static JsonNode resolve(JsonNode schema) {
    JsonNode current = schema;
    while (current.has("$ref")) {
      String ref = current.get("$ref").asString();
      current = COMPONENTS.get("schemas").get(ref.substring(ref.lastIndexOf('/') + 1));
    }
    return current;
  }
}
