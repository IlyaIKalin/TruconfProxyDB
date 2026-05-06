package ru.truconf.proxydb.files;

import java.io.InputStream;
import org.springframework.web.multipart.MultipartFile;
import ru.truconf.proxydb.domain.FileStorageKind;

public interface FileStorageService {

  StoredFile store(long outboxId, MultipartFile file);

  InputStream open(FileStorageKind storageKind, String filePath, byte[] fileData);

  void delete(StoredFile file);
}
