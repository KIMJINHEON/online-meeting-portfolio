package com.example.hlsviewer.nice;

public class NiceStartResponse {
  private String requestNo;
  private String authUrl;

  public NiceStartResponse(String requestNo, String authUrl) {
    this.requestNo = requestNo;
    this.authUrl = authUrl;
  }

  public String getRequestNo() {
    return requestNo;
  }

  public void setRequestNo(String requestNo) {
    this.requestNo = requestNo;
  }

  public String getAuthUrl() {
    return authUrl;
  }

  public void setAuthUrl(String authUrl) {
    this.authUrl = authUrl;
  }
}
