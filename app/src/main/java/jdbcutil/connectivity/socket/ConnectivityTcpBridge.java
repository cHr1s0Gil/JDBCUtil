package jdbcutil.connectivity.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Bridges one loopback TCP connection to an SAP Connectivity Service tunnel.
 * Oracle JDBC has no PostgreSQL-style plain SocketFactory property, so the
 * driver connects to this loopback listener while the bridge forwards bytes
 * to the SCC virtual host.
 */
public final class ConnectivityTcpBridge implements AutoCloseable {
    private static final int BUFFER_SIZE = 8192;

    private final ServerSocket listener;

    private volatile Socket clientSocket;
    private volatile Socket tunnelSocket;
    private volatile boolean closed;

    private ConnectivityTcpBridge(
            String connectivityCredentialName,
            String locationId,
            String targetHost,
            int targetPort,
            int timeoutMillis) throws IOException {
        this.listener = new ServerSocket();
        this.listener.bind(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                1);
    }

    public static ConnectivityTcpBridge start(
            String connectivityCredentialName,
            String locationId,
            String targetHost,
            int targetPort,
            int timeoutMillis) throws IOException {
        ConnectivityTcpBridge bridge = new ConnectivityTcpBridge(
                connectivityCredentialName,
                locationId,
                targetHost,
                targetPort,
                timeoutMillis);
        try {
            bridge.tunnelSocket = ConnectivitySocketFactory.openTunnel(
                    connectivityCredentialName,
                    locationId,
                    targetHost,
                    targetPort,
                    timeoutMillis);
            bridge.tunnelSocket.setSoTimeout(0);
        } catch (IOException exception) {
            bridge.close();
            throw exception;
        }
        bridge.startAcceptThread();
        return bridge;
    }

    public int getLocalPort() {
        return listener.getLocalPort();
    }

    private void startAcceptThread() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptAndForward();
            }
        }, "jdbcutil-connectivity-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptAndForward() {
        try {
            clientSocket = listener.accept();
            closeListener();

            final Socket acceptedClient = clientSocket;
            final Socket connectedTunnel = tunnelSocket;
            Thread requestThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    forward(acceptedClient, connectedTunnel);
                }
            }, "jdbcutil-connectivity-request");
            requestThread.setDaemon(true);
            requestThread.start();

            forward(connectedTunnel, acceptedClient);
        } catch (IOException exception) {
            if (!closed) {
                closeSocket(clientSocket);
                closeSocket(tunnelSocket);
            }
        } finally {
            closeListener();
        }
    }

    private void forward(Socket source, Socket destination) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while (!closed && (length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
                output.flush();
            }
            if (!destination.isClosed() && !destination.isOutputShutdown()) {
                destination.shutdownOutput();
            }
        } catch (IOException exception) {
            if (!closed) {
                closeSocket(source);
                closeSocket(destination);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        closeListener();
        closeSocket(clientSocket);
        closeSocket(tunnelSocket);
    }

    private void closeListener() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
