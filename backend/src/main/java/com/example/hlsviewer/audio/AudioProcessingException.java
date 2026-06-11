package com.example.hlsviewer.audio;

public class AudioProcessingException extends RuntimeException {
  private final String code;

  public AudioProcessingException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
