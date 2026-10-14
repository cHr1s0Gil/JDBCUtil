package jdbcutil.connectivity;

import com.sap.it.api.ITApiFactory;
import com.sap.it.api.ccs.adapter.CloudConnectorContext;
import com.sap.it.api.ccs.adapter.CloudConnectorProperties;
import com.sap.it.api.ccs.adapter.ConnectionType;

import jdbcutil.model.ConnectivityProxyInfo;

public final class ConnectivityProxyClient {
  private ConnectivityProxyClient() {}

  public static ConnectivityProxyInfo getTcpProxyInfo() throws Exception {
    CloudConnectorContext context = new CloudConnectorContext();
    context.setConnectionType(ConnectionType.TCP);

    CloudConnectorProperties properties = ITApiFactory.getService(CloudConnectorProperties.class, context);

    return new ConnectivityProxyInfo(properties.getProxyHost(), properties.getProxyPort());
  }
}
