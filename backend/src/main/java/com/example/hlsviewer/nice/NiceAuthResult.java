package com.example.hlsviewer.nice;

import java.time.Instant;

public class NiceAuthResult {
  private String reqSeq;
  private String streamKey;
  private String deviceId;
  private String status;
  private String name;
  private String birthDate;
  private String phone;
  private String mobileCo;
  private String di;
  private String ci;
  private String responseNumber;
  private Instant verifiedAt;
  private String message;

  public String getReqSeq() {
    return reqSeq;
  }

  public void setReqSeq(String reqSeq) {
    this.reqSeq = reqSeq;
  }

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

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getBirthDate() {
    return birthDate;
  }

  public void setBirthDate(String birthDate) {
    this.birthDate = birthDate;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getMobileCo() {
    return mobileCo;
  }

  public void setMobileCo(String mobileCo) {
    this.mobileCo = mobileCo;
  }

  public String getDi() {
    return di;
  }

  public void setDi(String di) {
    this.di = di;
  }

  public String getCi() {
    return ci;
  }

  public void setCi(String ci) {
    this.ci = ci;
  }

  public String getResponseNumber() {
    return responseNumber;
  }

  public void setResponseNumber(String responseNumber) {
    this.responseNumber = responseNumber;
  }

  public Instant getVerifiedAt() {
    return verifiedAt;
  }

  public void setVerifiedAt(Instant verifiedAt) {
    this.verifiedAt = verifiedAt;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
