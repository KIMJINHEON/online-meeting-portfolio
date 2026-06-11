package com.example.hlsviewer.nice;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nice")
public class NiceProperties {
  private boolean enabled = false;
  private String apiBaseUrl = "https://auth.niceid.co.kr";
  private String clientId = "";
  private String clientSecret = "";
  private String returnUrl = "";
  private String closeUrl = "";
  private List<String> svcTypes = List.of("M");
  private String methodType = "GET";
  private List<String> expMods = List.of("closeButtonOn");
  private String devLang = "Linux/JAVA";
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 7000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getApiBaseUrl() {
    return apiBaseUrl;
  }

  public void setApiBaseUrl(String apiBaseUrl) {
    this.apiBaseUrl = apiBaseUrl;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public String getReturnUrl() {
    return returnUrl;
  }

  public void setReturnUrl(String returnUrl) {
    this.returnUrl = returnUrl;
  }

  public String getCloseUrl() {
    return closeUrl;
  }

  public void setCloseUrl(String closeUrl) {
    this.closeUrl = closeUrl;
  }

  public List<String> getSvcTypes() {
    return svcTypes;
  }

  public void setSvcTypes(List<String> svcTypes) {
    this.svcTypes = svcTypes;
  }

  public String getMethodType() {
    return methodType;
  }

  public void setMethodType(String methodType) {
    this.methodType = methodType;
  }

  public List<String> getExpMods() {
    return expMods;
  }

  public void setExpMods(List<String> expMods) {
    this.expMods = expMods;
  }

  public String getDevLang() {
    return devLang;
  }

  public void setDevLang(String devLang) {
    this.devLang = devLang;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }
}
