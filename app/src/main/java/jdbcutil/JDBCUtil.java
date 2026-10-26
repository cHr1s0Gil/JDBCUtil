package jdbcutil;

import java.util.List;

import com.sap.it.api.ccs.adapter.ConnectionType;

import jdbcutil.connectivity.ConnectivityProxyClient;
import jdbcutil.connectivity.ConnectivityTokenClient;
import jdbcutil.connectivity.Socks5Client;
import jdbcutil.database.DatabaseType;
import jdbcutil.database.oracle.OracleClient;
import jdbcutil.database.postgresql.PostgresClient;
import jdbcutil.model.ConnectivityProxyInfo;
import jdbcutil.model.OnPremiseTargetInfo;

public final class JDBCUtil {
    private JDBCUtil() {}

    public static String connectionTest(
            String credentialName,
            String targetHost,
            int targetPort,
            String locationId) throws Exception {
        if (credentialName == null || credentialName.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential name is required.");
        }
        if (targetHost == null || targetHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Target host is required.");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException(
                    "Target port must be between 1 and 65535.");
        }

        OnPremiseTargetInfo targetInfo =
                new OnPremiseTargetInfo(targetHost, targetPort, ConnectionType.TCP);

        String jwt = ConnectivityTokenClient.getJwt(credentialName);
        ConnectivityProxyInfo proxyInfo = ConnectivityProxyClient.getTcpProxyInfo();

        return Socks5Client.connectionTest(
                proxyInfo,
                targetInfo,
                jwt,
                locationId == null ? "" : locationId);
    }

    public static String databaseConnectionTest(
            String databaseType,
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId) throws Exception {
        String normalizedLocationId = locationId == null ? "" : locationId;

        switch (DatabaseType.from(databaseType)) {
            case POSTGRESQL:
                return PostgresClient.connectionTest(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId);
            case ORACLE:
                return OracleClient.connectionTest(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId);
            default:
                throw unsupportedDatabaseType(databaseType);
        }
    }

    public static String execute(
            String databaseType,
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId,
            String query) throws Exception {
        String normalizedLocationId = locationId == null ? "" : locationId;

        switch (DatabaseType.from(databaseType)) {
            case POSTGRESQL:
                return PostgresClient.execute(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId,
                        query);
            case ORACLE:
                return OracleClient.execute(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId,
                        query);
            default:
                throw unsupportedDatabaseType(databaseType);
        }
    }

    public static String execute(
            String databaseType,
            String connectivityCredentialName,
            String databaseCredentialName,
            String targetHost,
            int targetPort,
            String databaseName,
            String locationId,
            List<String> queries) throws Exception {
        String normalizedLocationId = locationId == null ? "" : locationId;

        switch (DatabaseType.from(databaseType)) {
            case POSTGRESQL:
                return PostgresClient.execute(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId,
                        queries);
            case ORACLE:
                return OracleClient.execute(
                        connectivityCredentialName,
                        databaseCredentialName,
                        targetHost,
                        targetPort,
                        databaseName,
                        normalizedLocationId,
                        queries);
            default:
                throw unsupportedDatabaseType(databaseType);
        }
    }

    private static IllegalArgumentException unsupportedDatabaseType(String databaseType) {
        return new IllegalArgumentException(
                "Unsupported database type: " + databaseType);
    }
}
