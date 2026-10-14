package jdbcutil;

import java.util.List;

import com.sap.it.api.ccs.adapter.ConnectionType;

import jdbcutil.connectivity.ConnectivityProxyClient;
import jdbcutil.connectivity.ConnectivityTokenClient;
import jdbcutil.connectivity.Socks5Client;
import jdbcutil.database.postgresql.PostgresClient;
import jdbcutil.model.ConnectivityProxyInfo;
import jdbcutil.model.OnPremiseTargetInfo;

public final class JDBCUtil {
    private JDBCUtil() {}

    public static String connectionTest(
        String credentialName,
        String targetHost,
        int targetPort,
        String locationId
    ) throws Exception {
        if (credentialName == null || credentialName.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential name is required.");
        }
        if (targetHost == null || targetHost.trim().isEmpty()) {
            throw new IllegalArgumentException("Target host is required.");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("Target port must be between 1 and 65535.");
        }

        OnPremiseTargetInfo targetInfo =
            new OnPremiseTargetInfo(targetHost, targetPort, ConnectionType.TCP);

        String jwt = ConnectivityTokenClient.getJwt(credentialName);
        ConnectivityProxyInfo proxyInfo = ConnectivityProxyClient.getTcpProxyInfo();

        return Socks5Client.connectionTest(
            proxyInfo,
            targetInfo,
            jwt,
            locationId == null ? "" : locationId
        );
    }

    public static String postgresConnectionTest(
        String connectivityCredentialName,
        String databaseCredentialName,
        String targetHost,
        int targetPort,
        String databaseName,
        String locationId
    ) throws Exception {
        return PostgresClient.connectionTest(
            connectivityCredentialName,
            databaseCredentialName,
            targetHost,
            targetPort,
            databaseName,
            locationId == null ? "" : locationId
        );
    }

    public static String executePostgres(
        String connectivityCredentialName,
        String databaseCredentialName,
        String targetHost,
        int targetPort,
        String databaseName,
        String locationId,
        String query
    ) throws Exception {
        return PostgresClient.execute(
            connectivityCredentialName,
            databaseCredentialName,
            targetHost,
            targetPort,
            databaseName,
            locationId == null ? "" : locationId,
            query
        );
    }

    public static String executePostgres(
        String connectivityCredentialName,
        String databaseCredentialName,
        String targetHost,
        int targetPort,
        String databaseName,
        String locationId,
        List<String> queries
    ) throws Exception {
        return PostgresClient.execute(
            connectivityCredentialName,
            databaseCredentialName,
            targetHost,
            targetPort,
            databaseName,
            locationId == null ? "" : locationId,
            queries
        );
    }
}
