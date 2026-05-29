package ru.truconf.proxydb.truconf;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import ru.truconf.proxydb.config.AppProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class TrueConfFileUploadClient implements TrueConfFileUploader {

  private static final String FILE_UPLOAD_PATH = "/bridge/api/client/v1/files";
  private static final MediaType DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM;

  private final RestClient restClient;
  private final TrueConfTokenService tokenService;
  private final TrueConfResponseMapper responseMapper;
  private final TrueConfErrorClassifier errorClassifier;
  private final ObjectMapper objectMapper;

  @Autowired
  public TrueConfFileUploadClient(
      AppProperties properties,
      RestClient.Builder restClientBuilder,
      TrueConfHttpClientFactory httpClientFactory,
      TrueConfTokenService tokenService,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper) {
    this(
        httpClientFactory.configure(restClientBuilder)
            .baseUrl(stripTrailingSlash(properties.httpBaseUrl()))
            .build(),
        tokenService,
        responseMapper,
        errorClassifier,
        objectMapper);
  }

  public TrueConfFileUploadClient(
      AppProperties properties,
      RestClient.Builder restClientBuilder,
      TrueConfTokenService tokenService,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper) {
    this(
        properties,
        restClientBuilder,
        new TrueConfHttpClientFactory(properties),
        tokenService,
        responseMapper,
        errorClassifier,
        objectMapper);
  }

  TrueConfFileUploadClient(
      RestClient restClient,
      TrueConfTokenService tokenService,
      TrueConfResponseMapper responseMapper,
      TrueConfErrorClassifier errorClassifier,
      ObjectMapper objectMapper) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    this.tokenService = Objects.requireNonNull(tokenService, "tokenService must not be null");
    this.responseMapper = Objects.requireNonNull(responseMapper, "responseMapper must not be null");
    this.errorClassifier = Objects.requireNonNull(errorClassifier, "errorClassifier must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public TrueConfResponse upload(
      String uploadTaskId,
      TrueConfUploadFile file,
      TrueConfUploadFile preview) {
    if (uploadTaskId == null || uploadTaskId.isBlank()) {
      throw new IllegalArgumentException("uploadTaskId must not be blank");
    }
    Objects.requireNonNull(file, "file must not be null");

    try (
        InputStream fileStream = file.openStream();
        InputStream previewStream = preview == null ? null : preview.openStream()) {
      MultiValueMap<String, Object> multipartBody = new LinkedMultiValueMap<>();
      multipartBody.add("file", part("file", file, fileStream));

      if (preview != null) {
        multipartBody.add("preview", part("preview", preview, previewStream));
      }

      String body = restClient.post()
          .uri(FILE_UPLOAD_PATH)
          .header("Upload-Task-Id", uploadTaskId)
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getAccessToken())
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .accept(MediaType.APPLICATION_JSON)
          .body(multipartBody)
          .retrieve()
          .body(String.class);

      JsonNode response = objectMapper.readTree(body);
      var error = responseMapper.extractError(response);
      if (error.isPresent()) {
        TrueConfError trueConfError = error.get();
        throw new TrueConfException(
            trueConfError.code(),
            trueConfError.message(),
            errorClassifier.isRetryable(trueConfError),
            trueConfError.rawResponse());
      }
      return responseMapper.mapSuccess(response);
    } catch (TrueConfException ex) {
      throw ex;
    } catch (RestClientResponseException ex) {
      throw new TrueConfException(
          "FILE_UPLOAD_HTTP_" + ex.getStatusCode().value(),
          "TrueConf file upload endpoint returned HTTP " + ex.getStatusCode().value(),
          ex.getStatusCode().is5xxServerError(),
          ex);
    } catch (RestClientException ex) {
      throw new TrueConfException(
          "FILE_UPLOAD_REQUEST_FAILED",
          "TrueConf file upload request failed",
          true,
          ex);
    } catch (IOException ex) {
      throw new TrueConfException(
          "FILE_UPLOAD_READ_FAILED",
          "Stored file could not be read for TrueConf upload",
          false,
          ex);
    } catch (Exception ex) {
      throw new TrueConfException(
          "FILE_UPLOAD_RESPONSE_INVALID",
          "TrueConf file upload response is invalid",
          true,
          ex);
    }
  }

  private static MediaType contentType(String mimeType) {
    if (mimeType == null || mimeType.isBlank()) {
      return DEFAULT_CONTENT_TYPE;
    }
    try {
      return MediaType.parseMediaType(mimeType);
    } catch (Exception ex) {
      return DEFAULT_CONTENT_TYPE;
    }
  }

  private static HttpEntity<UploadResource> part(
      String partName,
      TrueConfUploadFile file,
      InputStream stream) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(contentType(file.mimeType()));
    headers.setContentDispositionFormData(partName, file.fileName());
    return new HttpEntity<>(new UploadResource(file, stream), headers);
  }

  private static String stripTrailingSlash(String value) {
    if (value.endsWith("/")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static final class UploadResource extends InputStreamResource {

    private final TrueConfUploadFile file;

    private UploadResource(TrueConfUploadFile file, InputStream inputStream) {
      super(inputStream);
      this.file = file;
    }

    @Override
    public String getFilename() {
      return file.fileName();
    }

    @Override
    public long contentLength() {
      return file.sizeBytes();
    }
  }
}
