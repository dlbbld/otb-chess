package io.github.dlbbld.otbchess.message;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

public final class Messages {

  // Absolute (leading-slash) resource path. Loaded via Class.getResourceAsStream so it resolves
  // against this class's own module, which works on both the classpath and the module path.
  // ClassLoader.getResourceAsStream would return null on the module path because resources in a
  // named module are encapsulated from the classloader's view.
  private static final String RESOURCE = "/messages/messages.properties";
  private static final Properties PROPERTIES = loadProperties();

  private Messages() {
  }

  public static String get(MessageKey key, Object... args) {
    final String pattern = PROPERTIES.getProperty(key.propertyKey());
    if (pattern == null) {
      throw new IllegalArgumentException("Missing message key: " + key.propertyKey());
    }
    return new MessageFormat(pattern, Locale.ROOT).format(args);
  }

  private static Properties loadProperties() {
    final Properties properties = new Properties();
    try (var stream = Messages.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException("Missing message resource: " + RESOURCE);
      }
      try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        properties.load(reader);
      }
    } catch (final IOException e) {
      throw new IllegalStateException("Could not load message resource: " + RESOURCE, e);
    }
    return properties;
  }
}
