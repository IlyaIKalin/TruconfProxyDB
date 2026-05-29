package ru.truconf.proxydb.truconf;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.truconf.proxydb.config.AppProperties;

@Component
public class TrueConfHttpClientFactory {

  private static final Logger log = LoggerFactory.getLogger(TrueConfHttpClientFactory.class);

  private final boolean tlsInsecureSkipVerify;
  private final SSLContext sslContext;

  public TrueConfHttpClientFactory(AppProperties properties) {
    this.tlsInsecureSkipVerify = properties.tlsInsecureSkipVerify();
    this.sslContext = tlsInsecureSkipVerify ? trustAllSslContext() : null;
    if (tlsInsecureSkipVerify) {
      log.warn(
          "TrueConf TLS certificate verification is disabled for outbound TrueConf clients only");
    }
  }

  public RestClient.Builder configure(RestClient.Builder builder) {
    return builder.requestFactory(new JdkClientHttpRequestFactory(httpClient()));
  }

  public HttpClient httpClient(Duration connectTimeout) {
    return httpClientBuilder()
        .connectTimeout(connectTimeout)
        .build();
  }

  private HttpClient httpClient() {
    return httpClientBuilder().build();
  }

  private HttpClient.Builder httpClientBuilder() {
    HttpClient.Builder builder = HttpClient.newBuilder();
    if (tlsInsecureSkipVerify) {
      builder.sslContext(sslContext);
    }
    return builder;
  }

  private static SSLContext trustAllSslContext() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new TrustAllX509TrustManager()}, new SecureRandom());
      return context;
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Could not initialize insecure TrueConf TLS context", ex);
    }
  }

  private static final class TrustAllX509TrustManager implements X509TrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
