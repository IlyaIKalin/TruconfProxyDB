package ru.truconf.proxydb.truconf;

public interface TrueConfFileUploader {

  TrueConfResponse upload(
      String uploadTaskId,
      TrueConfUploadFile file,
      TrueConfUploadFile preview);
}
