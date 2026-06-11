package com.example.hlsviewer.excluded;

import com.example.hlsviewer.roster.RosterVerifier;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class MeetingExcludedPersonService {
  private final MeetingExcludedPersonRepository repository;

  public MeetingExcludedPersonService(MeetingExcludedPersonRepository repository) {
    this.repository = repository;
  }

  public List<MeetingExcludedPerson> list(String streamKey) {
    return repository.listByStreamKey(normalizeStreamKey(streamKey));
  }

  public MeetingExcludedPerson add(String streamKey, String name, String birth, String phone) {
    String cleanStream = normalizeStreamKey(streamKey);
    String cleanName = RosterVerifier.normalizeName(name);
    String cleanBirth = RosterVerifier.normalizeBirth(birth);
    String cleanPhone = RosterVerifier.formatPhoneHyphen(phone);

    if (cleanName.isEmpty()) {
      throw new IllegalArgumentException("name_required");
    }
    if (cleanBirth.length() != 6) {
      throw new IllegalArgumentException("invalid_birth");
    }
    if (cleanPhone.isEmpty()) {
      throw new IllegalArgumentException("invalid_phone");
    }

    try {
      long id = repository.insert(cleanStream, cleanName, cleanBirth, cleanPhone);
      return repository.findById(id)
          .orElseThrow(() -> new IllegalStateException("excluded_person_insert_failed"));
    } catch (DuplicateKeyException dup) {
      throw new IllegalArgumentException("already_registered");
    }
  }

  public void delete(String streamKey, long id) {
    int affected = repository.delete(normalizeStreamKey(streamKey), id);
    if (affected == 0) {
      throw new IllegalArgumentException("not_found");
    }
  }

  private String normalizeStreamKey(String value) {
    return value == null || value.isBlank() ? "stream" : value.trim();
  }
}
