package com.example.hlsviewer.nice;

import java.time.Instant;

public record NiceAuthPublicResult(String reqSeq, String status, Instant verifiedAt, String message) {
  public static NiceAuthPublicResult from(NiceAuthResult result) {
    if (result == null) {
      return new NiceAuthPublicResult("", "PENDING", null, "");
    }
    return new NiceAuthPublicResult(
        result.getReqSeq(),
        result.getStatus(),
        result.getVerifiedAt(),
        result.getMessage()
    );
  }
}

