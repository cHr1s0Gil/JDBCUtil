package jdbcutil.model;

import com.sap.it.api.ccs.adapter.ConnectionType;

public class OnPremiseTargetInfo {
    private final String host;
    private final int port;
    private final ConnectionType connectionType;
    
    public OnPremiseTargetInfo(String host, int port, ConnectionType connectionType) {
        this.host = host;
        this.port = port;
        this.connectionType = connectionType;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public ConnectionType getConnectionType() {
        return connectionType;
    }
}
