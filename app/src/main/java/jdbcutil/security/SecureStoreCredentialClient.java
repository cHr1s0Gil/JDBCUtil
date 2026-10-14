package jdbcutil.security;

import com.sap.it.api.ITApiFactory;
import com.sap.it.api.exception.InvalidContextException;
import com.sap.it.api.securestore.SecureStoreService;
import com.sap.it.api.securestore.UserCredential;
import com.sap.it.api.securestore.exception.SecureStoreException;

public final class SecureStoreCredentialClient {
    private SecureStoreCredentialClient() {}

    public static UserCredential getUserCredential(String credentialName)
            throws InvalidContextException, SecureStoreException {
        if (credentialName == null || credentialName.trim().isEmpty()) {
            throw new IllegalArgumentException("Credential name is required.");
        }

        SecureStoreService service = ITApiFactory.getService(SecureStoreService.class, null);
        if (service == null) {
            throw new IllegalStateException("SecureStoreService is unavailable.");
        }

        UserCredential credential = service.getUserCredential(credentialName);
        if (credential == null) {
            throw new IllegalStateException("Credential was not found: " + credentialName);
        }

        return credential;
    }
}
