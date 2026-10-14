package jdbcutil.connectivity;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.sap.it.api.exception.InvalidContextException;
import com.sap.it.api.securestore.UserCredential;
import com.sap.it.api.securestore.exception.SecureStoreException;

import groovy.json.JsonSlurper;
import jdbcutil.security.SecureStoreCredentialClient;

public class ConnectivityTokenClient {
    private static final String TOKEN_URL_PROPERTY_NAME = "sec:server.url";

    public static UserCredential getCredential(String credentialName)
            throws InvalidContextException, SecureStoreException {
        return SecureStoreCredentialClient.getUserCredential(credentialName);
    }

    public static String getJwt(String credentialName) throws InvalidContextException, SecureStoreException, IOException {
        UserCredential credential = getCredential(credentialName);

        String clientId = credential.getUsername();
        String clientSecret = new String(credential.getPassword());
        Map<String, String> properties = credential.getCredentialProperties();

        String tokenUrl = properties.get(TOKEN_URL_PROPERTY_NAME);

        if (tokenUrl == null || tokenUrl.trim().isEmpty()) {
            throw new IOException(
                    "Token URL is missing from credential properties.");
        }

        URI uri = URI.create(tokenUrl);

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException(
                    "Token URL must use HTTPS.");
        }

        String auth = clientId + ":" + clientSecret;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(60000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);

            try (Writer writer = new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write("grant_type=client_credentials");
            }

            int responseCode = connection.getResponseCode();

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_CREATED) {
                throw new IOException("JWT request failed with HTTP " + responseCode);
            }

            Object response;

            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                response = new JsonSlurper().parse(reader);
            }

            if (!(response instanceof Map)) {
                throw new IOException("Invalid JWT response format.");
            }

            Object accessToken = ((Map<?, ?>) response).get("access_token");

            if (!(accessToken instanceof String) || ((String) accessToken).trim().isEmpty()) {
                throw new IOException("access_token is missing.");
            }

            return (String) accessToken;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
