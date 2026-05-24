package com.openclaw.kbbridge.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 application.yml 不包含硬编码的默认密码。
 * Defect 3: DB_PASSWORD 不应有默认值，缺失时应用应启动失败。
 */
class ApplicationConfigTest {

    @Test
    void applicationYml_shouldNotContainDefaultPassword() {
        Yaml yaml = new Yaml();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(is, "application.yml should be on the classpath");

            Map<String, Object> config = yaml.load(is);
            Map<String, Object> spring = getNestedMap(config, "spring");
            Map<String, Object> datasource = getNestedMap(spring, "datasource");

            String passwordValue = (String) datasource.get("password");
            assertNotNull(passwordValue, "password property should exist in datasource config");

            // The password must be an env placeholder referencing DB_PASSWORD
            assertTrue(passwordValue.startsWith("${DB_PASSWORD"),
                    "password should reference DB_PASSWORD env var, but found: " + passwordValue);

            // Allow ${DB_PASSWORD} or ${DB_PASSWORD:} (empty default), but reject
            // any non-empty default value like ${DB_PASSWORD:root}
            assertTrue(passwordValue.matches("\\$\\{DB_PASSWORD:?}"),
                    "DB_PASSWORD must not have a non-empty default value — "
                            + "expected '${DB_PASSWORD}' or '${DB_PASSWORD:}' but found: " + passwordValue);
        } catch (Exception e) {
            fail("Failed to parse application.yml: " + e.getMessage());
        }
    }

    @Test
    void applicationYml_shouldUseEnvPlaceholdersForAllScalarValues() {
        Yaml yaml = new Yaml();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(is, "application.yml should be on the classpath");

            Map<String, Object> config = yaml.load(is);
            assertAllScalarsAreEnvPlaceholders(config, "");
        } catch (Exception e) {
            fail("Failed to parse application.yml: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        assertNotNull(map, "Parent map should not be null when looking up key: " + key);
        Object value = map.get(key);
        assertNotNull(value, "Key '" + key + "' should exist in config");
        assertInstanceOf(Map.class, value, "Key '" + key + "' should be a map");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private void assertAllScalarsAreEnvPlaceholders(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nestedValue) -> assertAllScalarsAreEnvPlaceholders(
                    nestedValue, path.isEmpty() ? String.valueOf(key) : path + "." + key));
            return;
        }

        if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) {
                assertAllScalarsAreEnvPlaceholders(item, path + "[" + index + "]");
                index++;
            }
            return;
        }

        assertInstanceOf(String.class, value, "Scalar config value must be an env placeholder at " + path);
        String text = (String) value;
        // Allow ${ENV_VAR} or ${ENV_VAR:default_value} — both are valid env placeholder
        // patterns
        assertTrue(text.matches("\\$\\{[A-Z0-9_]+(:.*)?" + "}"),
                "Scalar config value must be an env placeholder (${VAR} or ${VAR:default}) at "
                        + path + ", but found: " + text);
    }
}
