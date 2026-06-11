package com.example.hlsviewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HlsViewerApplication {
  public static void main(String[] args) {
    SpringApplication.run(HlsViewerApplication.class, args);
  }
}
