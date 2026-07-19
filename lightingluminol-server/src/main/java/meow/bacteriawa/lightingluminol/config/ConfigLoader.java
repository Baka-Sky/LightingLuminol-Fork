package meow.bacteriawa.lightingluminol.config;

import meow.bacteriawa.lightingluminol.config.flags.ConfigClassInfo;
import meow.bacteriawa.lightingluminol.config.flags.ConfigInfo;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {
    private static final DumperOptions DUMPER_OPTIONS = new DumperOptions();
    private static final Yaml YAML = new Yaml(DUMPER_OPTIONS);

    static {
        DUMPER_OPTIONS.setIndent(2);
        DUMPER_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    }

    private static final File CONFIG_DIR = new File("lightingluminol");

    private ConfigLoader() {
    }

    @SuppressWarnings("unchecked")
    public static void loadConfig(final Class<? extends IConfigModule> configClass) {
        final ConfigClassInfo classInfo = configClass.getAnnotation(ConfigClassInfo.class);
        if (classInfo == null) {
            return;
        }

        final File configFile = new File(CONFIG_DIR, classInfo.name() + ".yml");

        Map<String, Object> configValues = new HashMap<>();

        if (configFile.exists()) {
            try (final FileInputStream is = new FileInputStream(configFile)) {
                final Object loaded = YAML.load(is);
                if (loaded instanceof Map) {
                    configValues = (Map<String, Object>) loaded;
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }

        final Map<String, Object> defaultValues = new HashMap<>();

        for (final Field field : configClass.getDeclaredFields()) {
            final ConfigInfo fieldInfo = field.getAnnotation(ConfigInfo.class);
            if (fieldInfo == null) {
                continue;
            }

            final String configName = fieldInfo.name();
            final Object defaultValue = getDefaultValue(field);

            defaultValues.put(configName, defaultValue);

            if (configValues.containsKey(configName)) {
                final Object value = configValues.get(configName);
                setFieldValue(field, value);
            }
        }

        if (!configFile.exists()) {
            try {
                CONFIG_DIR.mkdirs();
                try (final FileWriter writer = new FileWriter(configFile)) {
                    YAML.dump(defaultValues, writer);
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        } else {
            boolean needsUpdate = false;
            for (final String key : defaultValues.keySet()) {
                if (!configValues.containsKey(key)) {
                    configValues.put(key, defaultValues.get(key));
                    needsUpdate = true;
                }
            }
            if (needsUpdate) {
                try (final FileWriter writer = new FileWriter(configFile)) {
                    YAML.dump(configValues, writer);
                } catch (final IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static Object getDefaultValue(final Field field) {
        try {
            field.setAccessible(true);
            return field.get(null);
        } catch (final Exception e) {
            return null;
        }
    }

    private static void setFieldValue(final Field field, final Object value) {
        try {
            field.setAccessible(true);
            final Class<?> type = field.getType();

            if (type == boolean.class || type == Boolean.class) {
                field.set(null, value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value)));
            } else if (type == int.class || type == Integer.class) {
                field.set(null, value instanceof Integer ? value : Integer.parseInt(String.valueOf(value)));
            } else if (type == long.class || type == Long.class) {
                field.set(null, value instanceof Long ? value : Long.parseLong(String.valueOf(value)));
            } else if (type == double.class || type == Double.class) {
                field.set(null, value instanceof Double ? value : Double.parseDouble(String.valueOf(value)));
            } else if (type == String.class) {
                field.set(null, String.valueOf(value));
            } else if (type.isEnum()) {
                field.set(null, Enum.valueOf((Class<? extends Enum>) type, String.valueOf(value)));
            } else {
                field.set(null, value);
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }
}