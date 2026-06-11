package com.example.hlsviewer.nice;

public class NiceStartRequest {
  private String streamKey;
  private String deviceId;
  private String userId;
  private String name;
  private String birth;
  private String phone;

  public String getStreamKey() {
    return streamKey;
  }

  public void setStreamKey(String streamKey) {
    this.streamKey = streamKey;
  }

  public String getDeviceId() {
    return deviceId;
  }

  public void setDeviceId(String deviceId) {
    this.deviceId = deviceId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getBirth() {
    return birth;
  }

  public void setBirth(String birth) {
    this.birth = birth;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }
}
