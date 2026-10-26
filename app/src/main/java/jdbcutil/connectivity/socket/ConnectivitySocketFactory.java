package jdbcutil.connectivity.socket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.net.SocketFactory;

import com.sap.it.api.ccs.adapter.ConnectionType;

import jdbcutil.connectivity.ConnectivityProxyClient;
import jdbcutil.connectivity.ConnectivityTokenClient;
import jdbcutil.connectivity.Socks5Client;
import jdbcutil.model.ConnectivityProxyInfo;
import jdbcutil.model.OnPremiseTargetInfo;

public final class ConnectivitySocketFactory extends SocketFactory {
    private static final int DEFAULT_TIMEOUT_MILLIS = 10000;

    private final String connectivityCredentialName;
    private final String locationId;

    public ConnectivitySocketFactory() {
        throw new IllegalArgumentException("socketFactoryArg is required.");
    }

    public ConnectivitySocketFactory(String argument) {
        String[] values = argument == null ? new String[0] : argument.split("\\.", -1);
        if (values.length != 2) {
            throw new IllegalArgumentException("Invalid socketFactoryArg.");
        }

        try {
            this.connectivityCredentialName = decode(values[0]);
            this.locationId = decode(values[1]);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid socketFactoryArg.", exception);
        }

        if (connectivityCredentialName.trim().isEmpty()) {
            throw new IllegalArgumentException("Connectivity credential name is required.");
        }
    }

    public static String createArgument(String connectivityCredentialName, String locationId) {
        if (connectivityCredentialName == null || connectivityCredentialName.trim().isEmpty()) {
            throw new IllegalArgumentException("Connectivity credential name is required.");
        }

        return encode(connectivityCredentialName) + "." + encode(locationId == null ? "" : locationId);
    }

    public static Socket openTunnel(
            String connectivityCredentialName,
            String locationId,
            String targetHost,
            int targetPort,
            int timeoutMillis) throws IOException {
        ConnectivitySocketFactory factory = new ConnectivitySocketFactory(
                createArgument(connectivityCredentialName, locationId));
        Socket socket = factory.createSocket();
        socket.connect(
                InetSocketAddress.createUnresolved(targetHost, targetPort),
                timeoutMillis);
        return socket;
    }

    @Override
    public Socket createSocket() {
        return new ConnectivitySocket(connectivityCredentialName, locationId);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(InetSocketAddress.createUnresolved(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(
            String host,
            int port,
            InetAddress localAddress,
            int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(InetSocketAddress.createUnresolved(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket socket = createSocket();
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    @Override
    public Socket createSocket(
            InetAddress host,
            int port,
            InetAddress localAddress,
            int localPort) throws IOException {
        Socket socket = createSocket();
        socket.bind(new InetSocketAddress(localAddress, localPort));
        socket.connect(new InetSocketAddress(host, port));
        return socket;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static final class ConnectivitySocket extends Socket {
        private final String connectivityCredentialName;
        private final String locationId;

        private ConnectivitySocket(String connectivityCredentialName, String locationId) {
            this.connectivityCredentialName = connectivityCredentialName;
            this.locationId = locationId;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            connect(endpoint, 0);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            if (!(endpoint instanceof InetSocketAddress)) {
                throw new IOException("Database target must be an InetSocketAddress.");
            }
            if (isConnected()) {
                throw new IOException("Socket is already connected.");
            }

            InetSocketAddress targetAddress = (InetSocketAddress) endpoint;
            String targetHost = targetAddress.getHostString();
            int targetPort = targetAddress.getPort();
            int effectiveTimeout = timeout > 0 ? timeout : DEFAULT_TIMEOUT_MILLIS;

            try {
                String jwt = ConnectivityTokenClient.getJwt(connectivityCredentialName);
                ConnectivityProxyInfo proxyInfo = ConnectivityProxyClient.getTcpProxyInfo();
                validateProxyInfo(proxyInfo);

                super.connect(
                        new InetSocketAddress(proxyInfo.getHost(), proxyInfo.getPort()),
                        effectiveTimeout);
                setSoTimeout(effectiveTimeout);

                OnPremiseTargetInfo targetInfo =
                        new OnPremiseTargetInfo(targetHost, targetPort, ConnectionType.TCP);
                Socks5Client.establishTunnel(this, targetInfo, jwt, locationId);

                setSoTimeout(0);
            } catch (Exception exception) {
                try {
                    close();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }

                if (exception instanceof IOException) {
                    throw (IOException) exception;
                }
                throw new IOException("Failed to create an SCC tunnel.", exception);
            }
        }

        private static void validateProxyInfo(ConnectivityProxyInfo proxyInfo) throws IOException {
            if (proxyInfo == null
                    || proxyInfo.getHost() == null
                    || proxyInfo.getHost().trim().isEmpty()) {
                throw new IOException("Connectivity proxy host is required.");
            }
            if (proxyInfo.getPort() < 1 || proxyInfo.getPort() > 65535) {
                throw new IOException("Connectivity proxy port must be between 1 and 65535.");
            }
        }
    }
}
