package pl.laina.reforge.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Exact runtime identity. Custom model data is only unique within a material. */
public record RuntimeItemIdentity(String material, int customModelData) implements Comparable<RuntimeItemIdentity> {
    public RuntimeItemIdentity {
        material = Objects.requireNonNull(material, "material").trim().toLowerCase(Locale.ROOT);
        if (!material.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid Minecraft material: " + material);
        }
        if (customModelData <= 0) {
            throw new IllegalArgumentException("Custom model data must be positive");
        }
    }

    public String key() {
        return material + ":" + customModelData;
    }

    public static Optional<RuntimeItemIdentity> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int separator = value.lastIndexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new RuntimeItemIdentity(
                    value.substring(0, separator), Integer.parseInt(value.substring(separator + 1))));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public int compareTo(RuntimeItemIdentity other) {
        int materialOrder = material.compareTo(other.material);
        return materialOrder != 0 ? materialOrder : Integer.compare(customModelData, other.customModelData);
    }
}
