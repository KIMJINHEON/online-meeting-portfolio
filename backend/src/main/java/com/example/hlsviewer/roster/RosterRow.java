package com.example.hlsviewer.roster;

public record RosterRow(
    Integer seqNo,
    String name,
    String dong,
    String jibun,
    String birth,
    String phone,
    String paperSubmitConfirm,
    String mailSubmitConfirm,
    String electronicVote,
    String phoneAccessedAt,
    String entryTime,
    String onlineMeeting,
    String onlineMeetingStartedAt,
    String onlineMeetingEndedAt,
    String ipAddress,
    String rosterRegisteredAt,
    String onsiteVoteAllowed,
    String proxyName,
    String proxyPhone,
    String signImage
) {}
