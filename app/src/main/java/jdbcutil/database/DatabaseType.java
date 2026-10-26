package jdbcutil.database;

import java.util.Locale;

public enum DatabaseType {
    POSTGRESQL,
    ORACLE;

    public static DatabaseType from(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Database type is required.");
        }

        String normalizedValue = value.trim().toUpperCase(Locale.ROOT);

        switch (normalizedValue) {
            case "POSTGRESQL":
                return POSTGRESQL;

            case "ORACLE":
                return ORACLE;

            default:
                throw new IllegalArgumentException("Unsupported database type: " + value);
        }
    }
}
