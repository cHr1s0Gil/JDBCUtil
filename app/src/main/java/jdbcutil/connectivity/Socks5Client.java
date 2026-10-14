package jdbcutil.connectivity;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import jdbcutil.model.ConnectivityProxyInfo;
import jdbcutil.model.OnPremiseTargetInfo;

public final class Socks5Client {
    private static final int DEFAULT_TIMEOUT_MILLIS = 10000;

    private Socks5Client() {}

    public static String connectionTest(
            ConnectivityProxyInfo proxyInfo,
            OnPremiseTargetInfo targetInfo,
            String jwt,
            String locationId) throws IOException {
        try (Socket socket = openSocket(proxyInfo, targetInfo, jwt, locationId)) {
            return "Connection Success";
        }
    }

    public static Socket openSocket(
            ConnectivityProxyInfo proxyInfo,
            OnPremiseTargetInfo targetInfo,
            String jwt,
            String locationId) throws IOException {
        if (proxyInfo == null || proxyInfo.getHost() == null || proxyInfo.getHost().trim().isEmpty()) {
            throw new IOException("Connectivity proxy host is required.");
        }
        if (proxyInfo.getPort() < 1 || proxyInfo.getPort() > 65535) {
            throw new IOException("Connectivity proxy port must be between 1 and 65535.");
        }

        Socket socket = new Socket();
        try {
            socket.connect(
                    new InetSocketAddress(proxyInfo.getHost(), proxyInfo.getPort()),
                    DEFAULT_TIMEOUT_MILLIS);
            socket.setSoTimeout(DEFAULT_TIMEOUT_MILLIS);
            establishTunnel(socket, targetInfo, jwt, locationId);
            return socket;
        } catch (IOException | RuntimeException exception) {
            try {
                socket.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    public static void establishTunnel(
            Socket socket,
            OnPremiseTargetInfo targetInfo,
            String jwt,
            String locationId) throws IOException {
        if (socket == null || !socket.isConnected() || socket.isClosed()) {
            throw new IOException("A connected proxy socket is required.");
        }
        if (targetInfo == null || targetInfo.getHost() == null || targetInfo.getHost().trim().isEmpty()) {
            throw new IOException("Target host is required.");
        }
        if (targetInfo.getPort() < 1 || targetInfo.getPort() > 65535) {
            throw new IOException("Target port must be between 1 and 65535.");
        }
        if (jwt == null || jwt.trim().isEmpty()) {
            throw new IOException("JWT is required.");
        }

        byte[] hostBytes = targetInfo.getHost().getBytes(StandardCharsets.UTF_8);
        byte[] tokenBytes = jwt.getBytes(StandardCharsets.UTF_8);
        byte[] locationBytes = locationId == null
                ? new byte[0]
                : Base64.getEncoder().encode(locationId.getBytes(StandardCharsets.UTF_8));

        if (hostBytes.length > 255) {
            throw new IOException("Target host must not exceed 255 bytes.");
        }
        if (locationBytes.length > 255) {
            throw new IOException("Base64-encoded Location ID must not exceed 255 bytes.");
        }

        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());

        // Negotiate SAP's JWT authentication method: 05 01 80.
        output.writeByte(0x05);
        output.writeByte(0x01);
        output.writeByte(0x80);
        output.flush();

        int version = input.readUnsignedByte();
        int authenticationMethod = input.readUnsignedByte();
        if (version != 0x05) {
            throw new IOException("Invalid SOCKS5 negotiation version: " + version);
        }
        if (authenticationMethod != 0x80) {
            throw new IOException("Proxy did not accept JWT authentication: " + authenticationMethod);
        }

        // JWT length is four bytes; encoded Location ID length is one byte.
        output.writeByte(0x01);
        output.writeInt(tokenBytes.length);
        output.write(tokenBytes);
        output.writeByte(locationBytes.length);
        output.write(locationBytes);
        output.flush();

        int authenticationVersion = input.readUnsignedByte();
        int authenticationStatus = input.readUnsignedByte();
        if (authenticationVersion != 0x01) {
            throw new IOException("Invalid JWT authentication version: " + authenticationVersion);
        }
        if (authenticationStatus != 0x00) {
            throw new IOException("SOCKS5 JWT authentication failed: " + authenticationStatus);
        }

        // Send the virtual host as a domain address without local DNS lookup.
        output.writeByte(0x05);
        output.writeByte(0x01);
        output.writeByte(0x00);
        output.writeByte(0x03);
        output.writeByte(hostBytes.length);
        output.write(hostBytes);
        output.writeShort(targetInfo.getPort());
        output.flush();

        int connectVersion = input.readUnsignedByte();
        int replyCode = input.readUnsignedByte();
        int reserved = input.readUnsignedByte();
        int addressType = input.readUnsignedByte();

        if (connectVersion != 0x05 || reserved != 0x00) {
            throw new IOException("Invalid SOCKS5 CONNECT response header.");
        }

        int addressLength;
        switch (addressType) {
            case 0x01:
                addressLength = 4;
                break;
            case 0x03:
                addressLength = input.readUnsignedByte();
                if (addressLength == 0) {
                    throw new IOException("Empty domain address in SOCKS5 CONNECT response.");
                }
                break;
            case 0x04:
                addressLength = 16;
                break;
            default:
                throw new IOException("Unsupported SOCKS5 response address type: " + addressType);
        }

        // Consume the complete bound address and port, including fragmented replies.
        input.readFully(new byte[addressLength]);
        input.readUnsignedShort();

        if (replyCode != 0x00) {
            throw new IOException(
                    "SOCKS5 CONNECT failed: " + describeReplyCode(replyCode) + " (" + replyCode + ")");
        }
    }

    private static String describeReplyCode(int replyCode) {
        switch (replyCode) {
            case 0x01:
                return "general failure";
            case 0x02:
                return "connection not allowed";
            case 0x03:
                return "network unreachable";
            case 0x04:
                return "host unreachable";
            case 0x05:
                return "connection refused";
            case 0x06:
                return "TTL expired";
            case 0x07:
                return "command not supported";
            case 0x08:
                return "address type not supported";
            default:
                return "unknown reply";
        }
    }
}
