package jdbcutil.model;

public class ConnectivityProxyInfo {
    private final String host;
    private final int port;

    public ConnectivityProxyInfo(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
