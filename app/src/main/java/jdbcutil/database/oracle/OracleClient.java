package jdbcutil.database.oracle;

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
import jdbcutil.connectivity.socket.ConnectivityTcpBridge;
import jdbcutil.security.SecureStoreCredentialClient;

public final class OracleClient {
    private static final int CONNECT_TIMEOUT_MILLIS = 10000;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int MAX_RESULT_ROWS = 1000;
    private static final String DRIVER_CLASS_NAME = "oracle.jdbc.OracleDriver";

    private OracleClient() {}

    public static String connectionTest(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String serviceName,
            String locationId) throws Exception {
        try (OracleConnectionContext context = openConnection(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                serviceName,
                locationId)) {
            try (PreparedStatement statement = context.getConnection().prepareStatement(
                    "SELECT 1 FROM DUAL")) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getInt(1) != 1) {
                        throw new SQLException(
                                "Oracle validation query returned an unexpected result.");
                    }
                }
            }
        }

        return "Oracle Connection Success";
    }

    public static String execute(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String serviceName,
            String locationId,
            String query) throws Exception {
        List<String> queries = new ArrayList<>();
        queries.add(query);

        List<Map<String, Object>> results = executeStatements(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                serviceName,
                locationId,
                queries);

        return JsonOutput.toJson(results.get(0));
    }

    public static String execute(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String serviceName,
            String locationId,
            List<String> queries) throws Exception {
        List<Map<String, Object>> statementResults = executeStatements(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                serviceName,
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
            String serviceName,
            String locationId,
            List<String> queries) throws Exception {
        validateQueries(queries);

        try (OracleConnectionContext context = openConnection(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                serviceName,
                locationId)) {
            Connection connection = context.getConnection();
            connection.setAutoCommit(false);
            List<Map<String, Object>> results = new ArrayList<>();

            try {
                for (int index = 0; index < queries.size(); index++) {
                    try (PreparedStatement statement =
                            connection.prepareStatement(queries.get(index))) {
                        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                        results.add(executeStatement(statement));
                    } catch (Exception exception) {
                        throw new SQLException(
                                "Statement " + (index + 1) + " failed: "
                                        + exception.getMessage(),
                                exception);
                    }
                }

                connection.commit();
                return results;
            } catch (SQLException exception) {
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

    private static OracleConnectionContext openConnection(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String serviceName,
            String locationId) throws Exception {
        validateArguments(
                connectivityCredentialName,
                databaseCredentialName,
                targetHost,
                targetPort,
                serviceName);

        UserCredential databaseCredential =
                SecureStoreCredentialClient.getUserCredential(databaseCredentialName);
        String databaseUser = databaseCredential.getUsername();
        char[] databasePassword = databaseCredential.getPassword();

        if (databaseUser == null || databaseUser.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Database username is missing from the credential.");
        }
        if (databasePassword == null || databasePassword.length == 0) {
            throw new IllegalArgumentException(
                    "Database password is missing from the credential.");
        }

        ConnectivityTcpBridge bridge = ConnectivityTcpBridge.start(
                connectivityCredentialName,
                locationId,
                targetHost,
                targetPort,
                CONNECT_TIMEOUT_MILLIS);

        try {
            Properties properties = new Properties();
            properties.setProperty("user", databaseUser);
            properties.setProperty("password", new String(databasePassword));
            properties.setProperty(
                    "oracle.net.CONNECT_TIMEOUT",
                    Integer.toString(CONNECT_TIMEOUT_MILLIS));
            properties.setProperty(
                    "oracle.jdbc.ReadTimeout",
                    Integer.toString(QUERY_TIMEOUT_SECONDS * 1000));

            String jdbcUrl = buildJdbcUrl(
                    "127.0.0.1",
                    bridge.getLocalPort(),
                    serviceName);
            Connection connection = loadDriver().connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("Oracle driver rejected the JDBC URL.");
            }
            return new OracleConnectionContext(connection, bridge);
        } catch (Exception exception) {
            bridge.close();
            throw exception;
        }
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
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof String) {
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
            classLoader = OracleClient.class.getClassLoader();
        }

        Class<?> driverClass = Class.forName(DRIVER_CLASS_NAME, true, classLoader);
        if (!Driver.class.isAssignableFrom(driverClass)) {
            throw new IllegalStateException(
                    DRIVER_CLASS_NAME + " does not implement java.sql.Driver.");
        }

        return (Driver) driverClass.getDeclaredConstructor().newInstance();
    }

    private static String buildJdbcUrl(String targetHost, int targetPort, String serviceName) {
        return "jdbc:oracle:thin:@//"
                + targetHost + ":" + targetPort + "/" + serviceName;
    }

    private static void validateArguments(
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String serviceName) {
        requireText(connectivityCredentialName, "Connectivity credential name");
        requireText(databaseCredentialName, "Database credential name");
        requireText(targetHost, "Target host");
        requireText(serviceName, "Oracle service name");

        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException(
                    "Target port must be between 1 and 65535.");
        }
        if (targetHost.contains("/")
                || targetHost.contains("?")
                || targetHost.contains("#")) {
            throw new IllegalArgumentException(
                    "Target host contains an invalid character.");
        }
        if (serviceName.contains("/")
                || serviceName.contains("?")
                || serviceName.contains("#")) {
            throw new IllegalArgumentException(
                    "Oracle service name contains an invalid character.");
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

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }

    private static final class OracleConnectionContext implements AutoCloseable {
        private final Connection connection;
        private final ConnectivityTcpBridge bridge;

        private OracleConnectionContext(
                Connection connection,
                ConnectivityTcpBridge bridge) {
            this.connection = connection;
            this.bridge = bridge;
        }

        private Connection getConnection() {
            return connection;
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.close();
            } finally {
                bridge.close();
            }
        }
    }
}

