package jdbcutil.database.postgresql;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.sap.it.api.securestore.UserCredential;

import groovy.json.JsonOutput;
import jdbcutil.security.SecureStoreCredentialClient;

public final class PostgresClient {
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int MAX_RESULT_ROWS = 1000;
    private static final String DRIVER_CLASS_NAME = "org.postgresql.Driver";
    private static final String SOCKET_FACTORY_CLASS_NAME =
            "jdbcutil.database.postgresql.PostgresConnectivitySocketFactory";

    private PostgresClient() {}

    public static String connectionTest(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId) throws Exception {
        try (Connection connection = openConnection(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                databaseName,
                locationId)) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1");
                    ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new SQLException("PostgreSQL validation query returned an unexpected result.");
                }
            }
        }

        return "PostgreSQL Connection Success";
    }

    public static String execute(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId,
            String query) throws Exception {
        List<String> queries = new ArrayList<>();
        queries.add(query);

        List<Map<String, Object>> results = executeStatements(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                databaseName,
                locationId,
                queries);

        return JsonOutput.toJson(results.get(0));
    }

    public static String execute(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId,
            List<String> queries) throws Exception {
        List<Map<String, Object>> statementResults = executeStatements(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                databaseName,
                locationId,
                queries);

        List<Map<String, Object>> indexedResults = new ArrayList<>();
        for (int index = 0; index < statementResults.size(); index++) {
            Map<String, Object> indexedResult = new LinkedHashMap<>();
            indexedResult.put("statementIndex", index + 1);
            indexedResult.putAll(statementResults.get(index));
            indexedResults.add(indexedResult);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("resultType", "STATEMENT_RESULTS");
        response.put("statementCount", indexedResults.size());
        response.put("results", indexedResults);
        return JsonOutput.toJson(response);
    }

    private static List<Map<String, Object>> executeStatements(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId,
            List<String> queries) throws Exception {
        validateQueries(queries);

        try (Connection connection = openConnection(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                databaseName,
                locationId)) {
            connection.setAutoCommit(false);
            List<Map<String, Object>> results = new ArrayList<>();

            try {
                for (int index = 0; index < queries.size(); index++) {
                    try (PreparedStatement statement = connection.prepareStatement(queries.get(index))) {
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        results.add(executeStatement(statement));
                    } catch (Exception exception) {
                        throw new SQLException(
                                "Statement " + (index + 1) + " failed: " + exception.getMessage(),
                                exception);
                    }
                }

                connection.commit();
                return results;
            } catch (Exception exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private static Map<String, Object> executeStatement(PreparedStatement statement)
            throws SQLException {
        boolean hasResultSet = statement.execute();

        if (hasResultSet) {
            try (ResultSet resultSet = statement.getResultSet()) {
                return readResultSet(resultSet);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("resultType", "UPDATE_COUNT");
        response.put("updateCount", statement.getUpdateCount());
        return response;
    }

    private static Connection openConnection(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId) throws Exception {
        validateArguments(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                databaseName);

        UserCredential databaseCredential =
                SecureStoreCredentialClient.getUserCredential(databaseCredentialName);
        String databaseUser = databaseCredential.getUsername();
        char[] databasePassword = databaseCredential.getPassword();

        if (databaseUser == null || databaseUser.trim().isEmpty()) {
            throw new IllegalArgumentException("Database username is missing from the credential.");
        }
        if (databasePassword == null || databasePassword.length == 0) {
            throw new IllegalArgumentException("Database password is missing from the credential.");
        }

        Properties properties = new Properties();
        properties.setProperty("user", databaseUser);
        properties.setProperty("password", new String(databasePassword));
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", Integer.toString(QUERY_TIMEOUT_SECONDS));
        properties.setProperty("socketFactory", SOCKET_FACTORY_CLASS_NAME);
        properties.setProperty(
                "socketFactoryArg",
                PostgresConnectivitySocketFactory.createArgument(
                        connectivityCredentialName,
                        locationId));
        properties.setProperty("classLoaderStrategy", "context-first");

        String jdbcUrl = buildJdbcUrl(targetHost, targetPort, databaseName);
        Connection connection = loadDriver().connect(jdbcUrl, properties);
        if (connection == null) {
            throw new SQLException("PostgreSQL driver rejected the JDBC URL.");
        }
        return connection;
    }

    private static Map<String, Object> readResultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();

        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
            columns.add(metadata.getColumnLabel(columnIndex));
        }

        while (rows.size() < MAX_RESULT_ROWS && resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                row.add(toJsonValue(resultSet.getObject(columnIndex)));
            }
            rows.add(row);
        }

        boolean truncated = rows.size() == MAX_RESULT_ROWS && resultSet.next();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("resultType", "RESULT_SET");
        response.put("columns", columns);
        response.put("rows", rows);
        response.put("rowCount", rows.size());
        response.put("truncated", truncated);
        return response;
    }

    private static Object toJsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof byte[]) {
            return Base64.getEncoder().encodeToString((byte[]) value);
        }
        if (value instanceof java.sql.Date || value instanceof Time || value instanceof Timestamp) {
            return value.toString();
        }
        return value.toString();
    }

    private static void rollback(Connection connection, Exception exception) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }
    }

    private static Driver loadDriver() throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PostgresClient.class.getClassLoader();
        }

        Class<?> driverClass = Class.forName(DRIVER_CLASS_NAME, true, classLoader);
        if (!Driver.class.isAssignableFrom(driverClass)) {
            throw new IllegalStateException(DRIVER_CLASS_NAME + " does not implement java.sql.Driver.");
        }

        return (Driver) driverClass.getDeclaredConstructor().newInstance();
    }

    private static String buildJdbcUrl(String targetHost, int targetPort, String databaseName) {
        String urlHost = targetHost.indexOf(':') >= 0
                ? "[" + targetHost + "]"
                : targetHost;
        return "jdbc:postgresql://" + urlHost + ":" + targetPort + "/" + databaseName;
    }

    private static void validateArguments(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName) {
        requireText(connectivityCredentialName, "Connectivity credential name");
        requireText(databaseCredentialName, "Database credential name");
        requireText(targetHost, "Target host");
        requireText(databaseName, "Database name");

        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("Target port must be between 1 and 65535.");
        }
        if (targetHost.contains("/") || targetHost.contains("?") || targetHost.contains("#")) {
            throw new IllegalArgumentException("Target host contains an invalid character.");
        }
        if (databaseName.contains("/") || databaseName.contains("?") || databaseName.contains("#")) {
            throw new IllegalArgumentException("Database name contains an invalid character.");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }

    private static void validateQueries(List<String> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("At least one query is required.");
        }

        for (int index = 0; index < queries.size(); index++) {
            requireText(queries.get(index), "Query " + (index + 1));
        }
    }
}
