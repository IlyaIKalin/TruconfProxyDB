package ru.truconf.proxydb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TruconfProxyDbApplication {

  public static void main(String[] args) {
    SpringApplication.run(TruconfProxyDbApplication.class, args);
  }
}
