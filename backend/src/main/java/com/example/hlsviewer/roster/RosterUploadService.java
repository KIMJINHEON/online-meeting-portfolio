package com.example.hlsviewer.roster;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RosterUploadService {
  private static final int MAX_ERROR_MESSAGES = 30;
  private static final DateTimeFormatter DATE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final long MAX_XLSX_XML_BYTES = 50L * 1024L * 1024L;
  private static final Path SIGN_IMAGE_STORAGE_DIR = Path.of("/opt/meeting/storage/roster-signatures");
  private static final HexFormat HEX = HexFormat.of();

  private final RosterRepository rosterRepository;

  public RosterUploadService(RosterRepository rosterRepository) {
    this.rosterRepository = rosterRepository;
  }

  public RosterUploadResult upload(String streamKey, MultipartFile file) {
    String key = RosterVerifier.normalizeStreamKey(streamKey);
    if (file == null || file.isEmpty()) {
      return new RosterUploadResult(key, 0, 0, List.of("file_empty"));
    }

    ParseResult parsed = parse(file);
    if (!parsed.ok) {
      return new RosterUploadResult(key, 0, 0, parsed.errors);
    }
    if (parsed.rows.isEmpty()) {
      List<String> errors = new ArrayList<>(parsed.errors);
      if (errors.isEmpty()) {
        errors.add("no_valid_rows");
      }
      return new RosterUploadResult(key, 0, parsed.skipped, errors);
    }

    List<String> duplicateMessages = findDuplicateRowMessages(parsed.rows);
    if (!duplicateMessages.isEmpty()) {
      List<String> errors = new ArrayList<>(parsed.errors);
      errors.addAll(duplicateMessages);
      return new RosterUploadResult(key, 0, parsed.skipped, errors);
    }

    int inserted = rosterRepository.replaceAll(key, parsed.rows);
    return new RosterUploadResult(key, inserted, parsed.skipped, parsed.errors);
  }

  /**
   * Detects rows with the same (name, phone, birth) within the uploaded batch. The user
   * chose policy (a) — reject the whole upload and surface which lines collide.
   * Returns at most {@link #MAX_ERROR_MESSAGES} messages so the response stays small.
   */
  private List<String> findDuplicateRowMessages(List<RosterRow> rows) {
    Map<String, List<Integer>> firstSeenByKey = new LinkedHashMap<>();
    for (int i = 0; i < rows.size(); i++) {
      RosterRow row = rows.get(i);
      String compositeKey = String.join(
          "|",
          nullSafe(row.name()).trim(),
          nullSafe(row.phone()).trim(),
          nullSafe(row.birth()).trim());
      firstSeenByKey.computeIfAbsent(compositeKey, k -> new ArrayList<>()).add(i + 1);
    }
    List<String> messages = new ArrayList<>();
    for (Map.Entry<String, List<Integer>> entry : firstSeenByKey.entrySet()) {
      List<Integer> positions = entry.getValue();
      if (positions.size() <= 1) {
        continue;
      }
      messages.add("duplicate_row: lines=" + positions + " key=" + entry.getKey());
      if (messages.size() >= MAX_ERROR_MESSAGES) {
        break;
      }
    }
    return messages;
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private ParseResult parse(MultipartFile file) {
    String name = file.getOriginalFilename();
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    try {
      if (lower.endsWith(".xlsx")) {
        return parseXlsx(file);
      }
      if (lower.endsWith(".xls")) {
        return ParseResult.error("xls_not_supported_use_xlsx");
      }
      if (lower.endsWith(".csv")) {
        return parseCsv(file);
      }
      return ParseResult.error("unsupported_file_type");
    } catch (Exception ex) {
      return ParseResult.error("parse_failed");
    }
  }

  private ParseResult parseCsv(MultipartFile file) throws Exception {
    try (InputStream raw = file.getInputStream()) {
      try (PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(raw), 3)) {
        Charset charset = detectUtf8BomOrUtf8(in);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
          List<List<String>> rows = readCsvRows(reader);
          return convertTabularRows(rows);
        }
      }
    }
  }

  private Charset detectUtf8BomOrUtf8(PushbackInputStream in) throws Exception {
    byte[] bom = new byte[3];
    int read = in.read(bom);
    if (read == 3 && (bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
      return StandardCharsets.UTF_8;
    }
    if (read > 0) {
      in.unread(bom, 0, read);
    }
    return StandardCharsets.UTF_8;
  }

  private List<List<String>> readCsvRows(BufferedReader reader) throws Exception {
    List<List<String>> rows = new ArrayList<>();
    String line;
    while ((line = reader.readLine()) != null) {
      rows.add(parseCsvLine(line));
    }
    return rows;
  }

  private List<String> parseCsvLine(String line) {
    if (line == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          field.append('"');
          i++;
          continue;
        }
        inQuotes = !inQuotes;
        continue;
      }
      if (c == ',' && !inQuotes) {
        out.add(field.toString());
        field.setLength(0);
        continue;
      }
      field.append(c);
    }
    out.add(field.toString());
    return out;
  }

  private ParseResult parseXlsx(MultipartFile file) throws Exception {
    Path temp = Files.createTempFile("roster-", ".xlsx");
    try {
      file.transferTo(temp);
      try (ZipFile zip = new ZipFile(temp.toFile())) {
        List<String> sharedStrings = readSharedStrings(zip);
        ZipEntry sheetEntry = findFirstSheet(zip);
        if (sheetEntry == null) {
          return ParseResult.error("sheet_not_found");
        }
        List<List<String>> rows;
        try (InputStream in = zip.getInputStream(sheetEntry)) {
          rows = readSheetRows(new LimitedInputStream(in, MAX_XLSX_XML_BYTES), sharedStrings);
        }
        rows = attachSignImagesFromDrawing(zip, sheetEntry.getName(), rows);
        return convertTabularRows(rows);
      }
    } catch (IllegalStateException ex) {
      if ("xlsx_xml_too_large".equals(ex.getMessage())) {
        return ParseResult.error("xlsx_too_large");
      }
      throw ex;
    } finally {
      try {
        Files.deleteIfExists(temp);
      } catch (Exception ignored) {
        // best-effort cleanup
      }
    }
  }

  private ZipEntry findFirstSheet(ZipFile zip) {
    ZipEntry sheet1 = zip.getEntry("xl/worksheets/sheet1.xml");
    if (sheet1 != null) {
      return sheet1;
    }
    List<? extends ZipEntry> sheets = zip.stream()
        .filter(entry -> entry.getName().startsWith("xl/worksheets/sheet") && entry.getName().endsWith(".xml"))
        .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
        .toList();
    return sheets.isEmpty() ? null : sheets.getFirst();
  }

  private List<String> readSharedStrings(ZipFile zip) throws Exception {
    ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
    if (entry == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(entry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      StringBuilder current = null;
      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("si".equals(local)) {
            current = new StringBuilder();
          } else if ("t".equals(local) && current != null) {
            current.append(reader.getElementText());
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if ("si".equals(reader.getLocalName()) && current != null) {
            out.add(current.toString());
            current = null;
          }
        }
      }
    }
    return out;
  }

  private List<List<String>> readSheetRows(InputStream in, List<String> sharedStrings) throws Exception {
    XMLInputFactory factory = newSafeXmlInputFactory();
    XMLStreamReader reader = factory.createXMLStreamReader(in);

    List<List<String>> rows = new ArrayList<>();
    Map<Integer, String> rowCells = null;
    int rowMaxCol = -1;
    int rowNumberOneBased = -1;

    boolean inCell = false;
    int cellCol = -1;
    String cellType = "";
    StringBuilder cellValue = new StringBuilder();
    StringBuilder cellFormula = new StringBuilder();
    String cellFormulaType = "";
    String cellFormulaSi = "";
    Map<String, String> sharedFormulas = new HashMap<>();

    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        String local = reader.getLocalName();
        if ("row".equals(local)) {
          rowCells = new HashMap<>();
          rowMaxCol = -1;
          rowNumberOneBased = parsePositiveInt(attr(reader, "r"), rows.size() + 1);
        } else if ("c".equals(local)) {
          inCell = true;
          cellValue.setLength(0);
          cellFormula.setLength(0);
          cellFormulaType = "";
          cellFormulaSi = "";
          cellType = attr(reader, "t");
          cellCol = colIndex(attr(reader, "r"));
          rowMaxCol = Math.max(rowMaxCol, cellCol);
        } else if ("f".equals(local) && inCell) {
          cellFormulaType = attr(reader, "t");
          cellFormulaSi = attr(reader, "si");
          String formulaText = reader.getElementText();
          if ("shared".equalsIgnoreCase(cellFormulaType)) {
            if (formulaText != null && !formulaText.isBlank()) {
              sharedFormulas.put(cellFormulaSi, formulaText);
              cellFormula.append(formulaText);
            } else {
              String shared = sharedFormulas.get(cellFormulaSi);
              if (shared != null) {
                cellFormula.append(shared);
              }
            }
          } else {
            cellFormula.append(formulaText == null ? "" : formulaText);
          }
        } else if ("v".equals(local) && inCell) {
          String text = reader.getElementText();
          String decoded = decodeCellValue(text, cellType, sharedStrings);
          cellValue.append(decoded);
        } else if ("t".equals(local) && inCell) {
          // inlineStr or rich text (within inlineStr/shared strings)
          cellValue.append(reader.getElementText());
        }
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        String local = reader.getLocalName();
        if ("c".equals(local) && inCell) {
          String resolved = normalizeCellValue(cellValue.toString(), cellType, cellFormula.toString());
          rowCells.put(cellCol, resolved);
          inCell = false;
          cellCol = -1;
          cellType = "";
          cellFormulaType = "";
          cellFormulaSi = "";
          cellValue.setLength(0);
          cellFormula.setLength(0);
        } else if ("row".equals(local) && rowCells != null) {
          while (rows.size() < Math.max(0, rowNumberOneBased - 1)) {
            rows.add(new ArrayList<>());
          }
          int size = rowMaxCol + 1;
          if (size <= 0) {
            rows.add(new ArrayList<>());
            rowCells = null;
            continue;
          }
          List<String> row = new ArrayList<>(Collections.nCopies(size, ""));
          for (Map.Entry<Integer, String> cell : rowCells.entrySet()) {
            int idx = cell.getKey();
            if (idx >= 0 && idx < row.size()) {
              row.set(idx, cell.getValue() == null ? "" : cell.getValue());
            }
          }
          rows.add(row);
          rowCells = null;
          rowNumberOneBased = -1;
        }
      }
    }
    return rows;
  }

  private List<List<String>> attachSignImagesFromDrawing(ZipFile zip, String sheetPath, List<List<String>> rows) {
    if (rows == null || rows.isEmpty()) {
      return rows;
    }

    int headerRowIndex = findHeaderRow(rows);
    if (headerRowIndex < 0) {
      return rows;
    }
    Map<String, Integer> headerIndex = buildHeaderIndex(rows.get(headerRowIndex));
    Integer signIdx = findSignColumn(headerIndex, rows, headerRowIndex);

    List<String> fallbackMediaPaths = new ArrayList<>();
    Map<CellPosition, String> anchoredImages = new HashMap<>();

    String drawingPath = findDrawingPathForSheet(zip, sheetPath);
    if (!drawingPath.isBlank()) {
      Map<String, String> imageRelToPath = loadDrawingImageTargets(zip, drawingPath);
      if (!imageRelToPath.isEmpty()) {
        fallbackMediaPaths.addAll(imageRelToPath.values());
      }
      Map<CellPosition, String> normalAnchors = loadAnchoredImages(zip, drawingPath, imageRelToPath);
      if (!normalAnchors.isEmpty()) {
        anchoredImages.putAll(normalAnchors);
      }
    }

    String legacyDrawingPath = findLegacyDrawingPathForSheet(zip, sheetPath);
    if (!legacyDrawingPath.isBlank()) {
      Map<String, String> legacyImageRelToPath = loadDrawingImageTargets(zip, legacyDrawingPath);
      if (!legacyImageRelToPath.isEmpty()) {
        fallbackMediaPaths.addAll(legacyImageRelToPath.values());
      }
      Map<CellPosition, String> legacyAnchors = loadLegacyAnchoredImages(zip, legacyDrawingPath, legacyImageRelToPath);
      if (!legacyAnchors.isEmpty()) {
        anchoredImages.putAll(legacyAnchors);
      }
    }

    Map<CellPosition, String> workbookAnchors = loadWorkbookAnchoredImages(zip);
    if (!workbookAnchors.isEmpty()) {
      for (Map.Entry<CellPosition, String> entry : workbookAnchors.entrySet()) {
        anchoredImages.putIfAbsent(entry.getKey(), entry.getValue());
      }
      fallbackMediaPaths.addAll(workbookAnchors.values());
    }

    Map<CellPosition, String> richValueImages = loadRichValueCellImages(zip, sheetPath);
    if (!richValueImages.isEmpty()) {
      for (Map.Entry<CellPosition, String> entry : richValueImages.entrySet()) {
        anchoredImages.putIfAbsent(entry.getKey(), entry.getValue());
      }
      fallbackMediaPaths.addAll(richValueImages.values());
    }
    if (signIdx == null) {
      signIdx = findSignColumnFromAnchors(anchoredImages, headerRowIndex);
    }
    if (signIdx == null) {
      signIdx = findSignColumnFromData(rows, headerRowIndex);
    }
    if (signIdx == null) {
      return rows;
    }

    boolean hasExistingSignValue = hasAnySignData(rows, headerRowIndex, signIdx);
    Map<String, String> dispImgIdToMediaPath = loadCellImageTargets(zip);
    if (!dispImgIdToMediaPath.isEmpty()) {
      fallbackMediaPaths.addAll(dispImgIdToMediaPath.values());
    }
    applyDispImgFormulas(zip, rows, headerRowIndex, signIdx, dispImgIdToMediaPath);
    if (!anchoredImages.isEmpty()) {
      applyAnchoredSignImages(zip, rows, headerRowIndex, signIdx, anchoredImages);
    }

    // Some files store images without straightforward sheet anchors.
    // If sign column is still empty, fallback to xl/media/* order only when count matches rows.
    if (!hasExistingSignValue && !hasAnySignData(rows, headerRowIndex, signIdx)) {
      if (fallbackMediaPaths.isEmpty()) {
        fallbackMediaPaths = loadAllMediaPaths(zip);
      }
      if (shouldApplyFallbackByOrder(rows, headerRowIndex, fallbackMediaPaths)) {
        applyFallbackImagesByOrder(zip, rows, headerRowIndex, signIdx, fallbackMediaPaths);
      }
    }

    return rows;
  }

  private Map<CellPosition, String> loadWorkbookAnchoredImages(ZipFile zip) {
    if (zip == null) {
      return Map.of();
    }

    Map<CellPosition, String> out = new HashMap<>();
    List<String> drawingParts = zip.stream()
        .map(ZipEntry::getName)
        .filter(name -> name != null)
        .filter(name -> name.startsWith("xl/drawings/"))
        .filter(name -> !name.contains("/_rels/"))
        .filter(name -> name.endsWith(".xml") || name.endsWith(".vml"))
        .sorted(String::compareToIgnoreCase)
        .toList();

    for (String drawingPath : drawingParts) {
      Map<String, String> imageRelToPath = loadDrawingImageTargets(zip, drawingPath);
      if (imageRelToPath.isEmpty()) {
        continue;
      }
      Map<CellPosition, String> anchors = drawingPath.endsWith(".vml")
          ? loadLegacyAnchoredImages(zip, drawingPath, imageRelToPath)
          : loadAnchoredImages(zip, drawingPath, imageRelToPath);
      if (anchors.isEmpty()) {
        continue;
      }
      for (Map.Entry<CellPosition, String> entry : anchors.entrySet()) {
        out.putIfAbsent(entry.getKey(), entry.getValue());
      }
    }

    return out;
  }

  private Map<CellPosition, String> loadRichValueCellImages(ZipFile zip, String sheetPath) {
    if (zip == null || sheetPath == null || sheetPath.isBlank()) {
      return Map.of();
    }
    ZipEntry sheetEntry = zip.getEntry(sheetPath);
    if (sheetEntry == null) {
      return Map.of();
    }

    List<Integer> valueMetaToRichValue = loadRichValueMetadataMapping(zip);
    List<Integer> richValueToRelIndex = loadRichValueLocalImageRelIndex(zip);
    List<String> relIds = loadRichValueRelIds(zip);
    Map<String, String> relIdToMediaPath = loadDrawingImageTargets(zip, "xl/richData/richValueRel.xml");
    if (valueMetaToRichValue.isEmpty()
        || richValueToRelIndex.isEmpty()
        || relIds.isEmpty()
        || relIdToMediaPath.isEmpty()) {
      return Map.of();
    }

    Map<CellPosition, String> out = new HashMap<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(sheetEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      int currentRowOneBased = -1;

      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        String local = reader.getLocalName();
        if ("row".equals(local)) {
          currentRowOneBased = parsePositiveInt(attr(reader, "r"), currentRowOneBased > 0 ? currentRowOneBased + 1 : 1);
          continue;
        }
        if (!"c".equals(local)) {
          continue;
        }
        String vmText = attr(reader, "vm");
        int vmOneBased = parseIntOrDefault(vmText, -1);
        if (vmOneBased <= 0) {
          continue;
        }

        String cellRef = attr(reader, "r");
        int col = colIndex(cellRef);
        int rowOneBased = rowIndexOneBased(cellRef);
        if (rowOneBased <= 0) {
          rowOneBased = currentRowOneBased;
        }
        if (rowOneBased <= 0 || col < 0) {
          continue;
        }

        int metaIndex = vmOneBased - 1;
        if (metaIndex < 0 || metaIndex >= valueMetaToRichValue.size()) {
          continue;
        }
        int richValueIndex = valueMetaToRichValue.get(metaIndex);
        if (richValueIndex < 0 || richValueIndex >= richValueToRelIndex.size()) {
          continue;
        }
        int relIndex = richValueToRelIndex.get(richValueIndex);
        if (relIndex < 0 || relIndex >= relIds.size()) {
          continue;
        }
        String relId = relIds.get(relIndex);
        if (relId == null || relId.isBlank()) {
          continue;
        }
        String mediaPath = relIdToMediaPath.get(relId);
        if (!isSupportedImagePath(mediaPath)) {
          continue;
        }
        out.putIfAbsent(new CellPosition(rowOneBased - 1, col), mediaPath);
      }
    } catch (Exception ignored) {
      return Map.of();
    }
    return out;
  }

  private List<Integer> loadRichValueMetadataMapping(ZipFile zip) {
    ZipEntry entry = zip.getEntry("xl/metadata.xml");
    if (entry == null) {
      return List.of();
    }

    List<Integer> out = new ArrayList<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(entry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      boolean inValueMetadata = false;
      boolean inBk = false;

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("valueMetadata".equals(local)) {
            inValueMetadata = true;
          } else if (inValueMetadata && "bk".equals(local)) {
            inBk = true;
          } else if (inBk && "rc".equals(local)) {
            String t = attrAnyNs(reader, "t");
            String v = attrAnyNs(reader, "v");
            int mapped = "1".equals(t) ? parseIntOrDefault(v, -1) : -1;
            out.add(mapped);
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String local = reader.getLocalName();
          if ("bk".equals(local)) {
            inBk = false;
          } else if ("valueMetadata".equals(local)) {
            inValueMetadata = false;
          }
        }
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return out;
  }

  private List<Integer> loadRichValueLocalImageRelIndex(ZipFile zip) {
    ZipEntry entry = zip.getEntry("xl/richData/rdrichvalue.xml");
    if (entry == null) {
      return List.of();
    }

    List<Integer> out = new ArrayList<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(entry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      boolean inRv = false;
      List<Integer> values = new ArrayList<>();

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("rv".equals(local)) {
            inRv = true;
            values.clear();
          } else if (inRv && "v".equals(local)) {
            values.add(parseIntOrDefault(reader.getElementText(), -1));
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String local = reader.getLocalName();
          if ("rv".equals(local)) {
            out.add(values.isEmpty() ? -1 : values.getFirst());
            inRv = false;
          }
        }
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return out;
  }

  private List<String> loadRichValueRelIds(ZipFile zip) {
    ZipEntry entry = zip.getEntry("xl/richData/richValueRel.xml");
    if (entry == null) {
      return List.of();
    }

    List<String> out = new ArrayList<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(entry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"rel".equals(reader.getLocalName())) {
          continue;
        }
        String relId = attrAnyNs(reader, "id");
        out.add(relId == null ? "" : relId.trim());
      }
    } catch (Exception ignored) {
      return List.of();
    }
    return out;
  }

  private String findLegacyDrawingPathForSheet(ZipFile zip, String sheetPath) {
    if (sheetPath == null || sheetPath.isBlank()) {
      return "";
    }
    ZipEntry sheetEntry = zip.getEntry(sheetPath);
    if (sheetEntry == null) {
      return "";
    }

    String legacyRelId = "";
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(sheetEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        String local = reader.getLocalName();
        if (!"legacyDrawing".equals(local) && !"legacyDrawingHF".equals(local)) {
          continue;
        }
        legacyRelId = attrAnyNs(reader, "id");
        if (!legacyRelId.isBlank()) {
          break;
        }
      }
    } catch (Exception ignored) {
      return "";
    }
    if (legacyRelId.isBlank()) {
      return "";
    }

    String relsPath = buildRelsPath(sheetPath);
    ZipEntry relsEntry = zip.getEntry(relsPath);
    if (relsEntry == null) {
      return "";
    }

    try (InputStream in = new LimitedInputStream(zip.getInputStream(relsEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"Relationship".equals(reader.getLocalName())) {
          continue;
        }
        String id = attrAnyNs(reader, "Id");
        String type = attrAnyNs(reader, "Type");
        String target = attrAnyNs(reader, "Target");
        if (!legacyRelId.equals(id) || !type.contains("/vmlDrawing")) {
          continue;
        }
        return resolveZipPath(sheetPath, target);
      }
    } catch (Exception ignored) {
      return "";
    }
    return "";
  }

  private Integer findSignColumnFromAnchors(Map<CellPosition, String> anchoredImages, int headerRowIndex) {
    if (anchoredImages == null || anchoredImages.isEmpty()) {
      return null;
    }
    Map<Integer, Integer> scoreByCol = new HashMap<>();
    for (Map.Entry<CellPosition, String> entry : anchoredImages.entrySet()) {
      CellPosition pos = entry.getKey();
      if (pos == null || pos.row() <= headerRowIndex || pos.col() < 0) {
        continue;
      }
      if (!isSupportedImagePath(entry.getValue())) {
        continue;
      }
      scoreByCol.put(pos.col(), scoreByCol.getOrDefault(pos.col(), 0) + 1);
    }
    int bestCol = -1;
    int bestScore = 0;
    for (Map.Entry<Integer, Integer> entry : scoreByCol.entrySet()) {
      int col = entry.getKey();
      int score = entry.getValue();
      if (score > bestScore || (score == bestScore && col > bestCol)) {
        bestScore = score;
        bestCol = col;
      }
    }
    return bestCol >= 0 ? bestCol : null;
  }

  private Map<String, String> loadCellImageTargets(ZipFile zip) {
    if (zip == null) {
      return Map.of();
    }
    List<String> cellImageParts = findCellImagePartPaths(zip);
    if (cellImageParts.isEmpty()) {
      return Map.of();
    }

    Map<String, String> out = new HashMap<>();
    for (String cellImagesPath : cellImageParts) {
      Map<String, String> relToPath = loadDrawingImageTargets(zip, cellImagesPath);
      if (relToPath.isEmpty()) {
        continue;
      }
      parseCellImagePart(zip, cellImagesPath, relToPath, out);
    }
    return out;
  }

  private List<String> findCellImagePartPaths(ZipFile zip) {
    if (zip == null) {
      return List.of();
    }
    return zip.stream()
        .map(ZipEntry::getName)
        .filter(name -> name != null)
        .filter(name -> name.startsWith("xl/"))
        .filter(name -> name.endsWith(".xml"))
        .filter(name -> name.toLowerCase(Locale.ROOT).contains("cellimage"))
        .sorted(String::compareToIgnoreCase)
        .toList();
  }

  private void parseCellImagePart(
      ZipFile zip,
      String cellImagesPath,
      Map<String, String> relToPath,
      Map<String, String> out) {
    if (zip == null || cellImagesPath == null || cellImagesPath.isBlank() || relToPath == null || relToPath.isEmpty()) {
      return;
    }
    ZipEntry cellImagesEntry = zip.getEntry(cellImagesPath);
    if (cellImagesEntry == null) {
      return;
    }
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(cellImagesEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      boolean inCellImage = false;
      String dispImgId = "";
      String relId = "";

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("cellImage".equals(local)) {
            inCellImage = true;
            dispImgId = "";
            relId = "";
          } else if (inCellImage && dispImgId.isBlank()) {
            String attrToken = findDispImgIdInAttributes(reader);
            if (!attrToken.isBlank()) {
              dispImgId = attrToken;
            }
          } else if (inCellImage && "cNvPr".equals(local)) {
            String name = attrAnyNs(reader, "name");
            String descr = attrAnyNs(reader, "descr");
            String fromName = normalizeDispImgToken(name);
            String fromDescr = normalizeDispImgToken(descr);
            if (!fromName.isBlank()) {
              dispImgId = fromName;
            } else if (!fromDescr.isBlank()) {
              dispImgId = fromDescr;
            }
          } else if (inCellImage && "blip".equals(local)) {
            String embed = attrAnyNs(reader, "embed");
            if (!embed.isBlank()) {
              relId = embed.trim();
            }
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if ("cellImage".equals(reader.getLocalName())) {
            if (!dispImgId.isBlank() && !relId.isBlank()) {
              String mediaPath = relToPath.get(relId);
              if (isSupportedImagePath(mediaPath)) {
                out.putIfAbsent(dispImgId, mediaPath);
              }
            }
            inCellImage = false;
            dispImgId = "";
            relId = "";
          }
        }
      }
    } catch (Exception ignored) {
      // best effort: ignore malformed cell image part
    }
  }

  private String findDispImgIdInAttributes(XMLStreamReader reader) {
    if (reader == null) {
      return "";
    }
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      String value = reader.getAttributeValue(i);
      String token = extractDispImgId(value);
      if (!token.isBlank()) {
        return token;
      }
    }
    return "";
  }

  private void applyDispImgFormulas(
      ZipFile zip,
      List<List<String>> rows,
      int headerRowIndex,
      int signIdx,
      Map<String, String> dispImgIdToMediaPath) {
    if (zip == null
        || rows == null
        || rows.isEmpty()
        || signIdx < 0
        || dispImgIdToMediaPath == null
        || dispImgIdToMediaPath.isEmpty()) {
      return;
    }
    for (int row = headerRowIndex + 1; row < rows.size(); row++) {
      List<String> data = rows.get(row);
      if (data == null || signIdx >= data.size()) {
        continue;
      }
      String current = data.get(signIdx);
      String dispImgId = extractDispImgId(current);
      if (dispImgId.isBlank()) {
        continue;
      }
      String mediaPath = dispImgIdToMediaPath.get(dispImgId);
      if (!isSupportedImagePath(mediaPath)) {
        continue;
      }
      String storedPath = persistSignImage(zip, mediaPath);
      if (storedPath.isBlank()) {
        continue;
      }
      data.set(signIdx, storedPath);
    }
  }

  private void applyAnchoredSignImages(
      ZipFile zip,
      List<List<String>> rows,
      int headerRowIndex,
      int signIdx,
      Map<CellPosition, String> anchoredImages) {
    if (zip == null || rows == null || anchoredImages == null || anchoredImages.isEmpty()) {
      return;
    }

    Map<Integer, CellPosition> bestPosByRow = new HashMap<>();
    Map<Integer, String> bestMediaByRow = new HashMap<>();

    for (Map.Entry<CellPosition, String> entry : anchoredImages.entrySet()) {
      CellPosition pos = entry.getKey();
      if (pos == null || pos.row() <= headerRowIndex || pos.col() < 0) {
        continue;
      }
      if (!isSupportedImagePath(entry.getValue())) {
        continue;
      }

      CellPosition current = bestPosByRow.get(pos.row());
      if (current == null || isBetterAnchor(pos, current, signIdx)) {
        bestPosByRow.put(pos.row(), pos);
        bestMediaByRow.put(pos.row(), entry.getValue());
      }
    }

    List<Integer> rowIndexes = new ArrayList<>(bestMediaByRow.keySet());
    Collections.sort(rowIndexes);
    for (Integer rowIndex : rowIndexes) {
      String mediaPath = bestMediaByRow.get(rowIndex);
      if (mediaPath == null || mediaPath.isBlank()) {
        continue;
      }
      String storedPath = persistSignImage(zip, mediaPath);
      if (storedPath.isBlank()) {
        continue;
      }
      ensureRowSize(rows, rowIndex, signIdx);
      String current = rows.get(rowIndex).get(signIdx);
      if (current == null || current.isBlank()) {
        rows.get(rowIndex).set(signIdx, storedPath);
      }
    }
  }

  private boolean isBetterAnchor(CellPosition candidate, CellPosition current, int signIdx) {
    int candidateDistance = Math.abs(candidate.col() - signIdx);
    int currentDistance = Math.abs(current.col() - signIdx);
    if (candidateDistance != currentDistance) {
      return candidateDistance < currentDistance;
    }
    return candidate.col() < current.col();
  }

  private boolean hasAnySignData(List<List<String>> rows, int headerRowIndex, int signIdx) {
    if (rows == null || rows.isEmpty() || signIdx < 0) {
      return false;
    }
    for (int row = headerRowIndex + 1; row < rows.size(); row++) {
      List<String> data = rows.get(row);
      if (data == null || signIdx >= data.size()) {
        continue;
      }
      String value = data.get(signIdx);
      if (value != null && !value.trim().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private List<String> loadAllMediaPaths(ZipFile zip) {
    if (zip == null) {
      return List.of();
    }
    return zip.stream()
        .map(ZipEntry::getName)
        .filter(name -> name != null && name.startsWith("xl/media/"))
        .filter(this::isSupportedImagePath)
        .sorted(String::compareToIgnoreCase)
        .toList();
  }

  private boolean shouldApplyFallbackByOrder(
      List<List<String>> rows,
      int headerRowIndex,
      List<String> mediaPaths) {
    if (rows == null || rows.isEmpty() || mediaPaths == null || mediaPaths.isEmpty()) {
      return false;
    }
    int dataRows = 0;
    for (int row = headerRowIndex + 1; row < rows.size(); row++) {
      List<String> data = rows.get(row);
      if (data != null && !isRowBlank(data)) {
        dataRows++;
      }
    }
    if (dataRows <= 0) {
      return false;
    }

    int imageCount = 0;
    HashMap<String, Boolean> dedupe = new HashMap<>();
    for (String mediaPath : mediaPaths) {
      if (!isSupportedImagePath(mediaPath)) {
        continue;
      }
      String key = mediaPath.toLowerCase(Locale.ROOT);
      if (!dedupe.containsKey(key)) {
        dedupe.put(key, Boolean.TRUE);
        imageCount++;
      }
    }
    return imageCount > 0 && imageCount == dataRows;
  }

  private boolean isSupportedImagePath(String name) {
    if (name == null || name.isBlank()) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.startsWith("xl/media/") && !lower.endsWith("/")) {
      return true;
    }
    return lower.endsWith(".png")
        || lower.endsWith(".jpg")
        || lower.endsWith(".jpeg")
        || lower.endsWith(".gif")
        || lower.endsWith(".bmp")
        || lower.endsWith(".webp")
        || lower.endsWith(".svg")
        || lower.endsWith(".emf")
        || lower.endsWith(".wmf")
        || lower.endsWith(".tif")
        || lower.endsWith(".tiff");
  }

  private void applyFallbackImagesByOrder(
      ZipFile zip,
      List<List<String>> rows,
      int headerRowIndex,
      int signIdx,
      List<String> mediaPaths) {
    if (zip == null || rows == null || mediaPaths == null || mediaPaths.isEmpty()) {
      return;
    }
    List<String> uniqueMediaPaths = mediaPaths.stream()
        .filter(this::isSupportedImagePath)
        .map(path -> path == null ? "" : path.trim())
        .filter(path -> !path.isBlank())
        .distinct()
        .toList();
    if (uniqueMediaPaths.isEmpty()) {
      return;
    }
    int dataRow = headerRowIndex + 1;
    for (String mediaPath : uniqueMediaPaths) {
      while (dataRow < rows.size()) {
        ensureRowSize(rows, dataRow, signIdx);
        String current = rows.get(dataRow).get(signIdx);
        if (current != null && !current.isBlank()) {
          dataRow++;
          continue;
        }
        String storedPath = persistSignImage(zip, mediaPath);
        if (!storedPath.isBlank()) {
          rows.get(dataRow).set(signIdx, storedPath);
        }
        dataRow++;
        break;
      }
      if (dataRow >= rows.size()) {
        break;
      }
    }
  }

  private String findDrawingPathForSheet(ZipFile zip, String sheetPath) {
    if (sheetPath == null || sheetPath.isBlank()) {
      return "";
    }
    ZipEntry sheetEntry = zip.getEntry(sheetPath);
    if (sheetEntry == null) {
      return "";
    }

    String drawingRelId = "";
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(sheetEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"drawing".equals(reader.getLocalName())) {
          continue;
        }
        drawingRelId = attrAnyNs(reader, "id");
        break;
      }
    } catch (Exception ignored) {
      return "";
    }
    if (drawingRelId.isBlank()) {
      return "";
    }

    String relsPath = buildRelsPath(sheetPath);
    ZipEntry relsEntry = zip.getEntry(relsPath);
    if (relsEntry == null) {
      return "";
    }

    try (InputStream in = new LimitedInputStream(zip.getInputStream(relsEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"Relationship".equals(reader.getLocalName())) {
          continue;
        }
        String id = attrAnyNs(reader, "Id");
        String type = attrAnyNs(reader, "Type");
        String target = attrAnyNs(reader, "Target");
        if (!drawingRelId.equals(id) || !type.contains("/drawing")) {
          continue;
        }
        return resolveZipPath(sheetPath, target);
      }
    } catch (Exception ignored) {
      return "";
    }
    return "";
  }

  private Map<String, String> loadDrawingImageTargets(ZipFile zip, String drawingPath) {
    if (drawingPath == null || drawingPath.isBlank()) {
      return Map.of();
    }
    String relsPath = buildRelsPath(drawingPath);
    ZipEntry relsEntry = zip.getEntry(relsPath);
    if (relsEntry == null) {
      return Map.of();
    }

    Map<String, String> out = new LinkedHashMap<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(relsEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);
      while (reader.hasNext()) {
        int event = reader.next();
        if (event != XMLStreamConstants.START_ELEMENT) {
          continue;
        }
        if (!"Relationship".equals(reader.getLocalName())) {
          continue;
        }
        String type = attrAnyNs(reader, "Type");
        if (!type.contains("/image")) {
          continue;
        }
        String id = attrAnyNs(reader, "Id");
        String target = attrAnyNs(reader, "Target");
        if (id.isBlank() || target.isBlank()) {
          continue;
        }
        out.put(id, resolveZipPath(drawingPath, target));
      }
    } catch (Exception ignored) {
      return Map.of();
    }
    return out;
  }

  private Map<CellPosition, String> loadAnchoredImages(
      ZipFile zip,
      String drawingPath,
      Map<String, String> imageRelToPath) {
    ZipEntry drawingEntry = zip.getEntry(drawingPath);
    if (drawingEntry == null || imageRelToPath == null || imageRelToPath.isEmpty()) {
      return Map.of();
    }

    Map<CellPosition, String> out = new HashMap<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(drawingEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);

      boolean inAnchor = false;
      boolean inFrom = false;
      int fromRow = -1;
      int fromCol = -1;
      String embedRelId = "";

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("twoCellAnchor".equals(local) || "oneCellAnchor".equals(local)) {
            inAnchor = true;
            inFrom = false;
            fromRow = -1;
            fromCol = -1;
            embedRelId = "";
          } else if (inAnchor && "from".equals(local)) {
            inFrom = true;
          } else if (inAnchor && inFrom && "row".equals(local)) {
            fromRow = parseIntOrDefault(reader.getElementText(), -1);
          } else if (inAnchor && inFrom && "col".equals(local)) {
            fromCol = parseIntOrDefault(reader.getElementText(), -1);
          } else if (inAnchor && "blip".equals(local)) {
            embedRelId = attrAnyNs(reader, "embed");
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String local = reader.getLocalName();
          if ("from".equals(local)) {
            inFrom = false;
          } else if ("twoCellAnchor".equals(local) || "oneCellAnchor".equals(local)) {
            if (fromRow >= 0 && fromCol >= 0 && !embedRelId.isBlank()) {
              String mediaPath = imageRelToPath.get(embedRelId);
              if (mediaPath != null && !mediaPath.isBlank()) {
                out.putIfAbsent(new CellPosition(fromRow, fromCol), mediaPath);
              }
            }
            inAnchor = false;
            inFrom = false;
            fromRow = -1;
            fromCol = -1;
            embedRelId = "";
          }
        }
      }
    } catch (Exception ignored) {
      return Map.of();
    }
    return out;
  }

  private Map<CellPosition, String> loadLegacyAnchoredImages(
      ZipFile zip,
      String drawingPath,
      Map<String, String> imageRelToPath) {
    ZipEntry drawingEntry = zip.getEntry(drawingPath);
    if (drawingEntry == null || imageRelToPath == null || imageRelToPath.isEmpty()) {
      return Map.of();
    }

    Map<CellPosition, String> out = new HashMap<>();
    XMLInputFactory factory = newSafeXmlInputFactory();
    try (InputStream in = new LimitedInputStream(zip.getInputStream(drawingEntry), MAX_XLSX_XML_BYTES)) {
      XMLStreamReader reader = factory.createXMLStreamReader(in);

      boolean inShape = false;
      boolean inClientData = false;
      int row = -1;
      int col = -1;
      String relId = "";

      while (reader.hasNext()) {
        int event = reader.next();
        if (event == XMLStreamConstants.START_ELEMENT) {
          String local = reader.getLocalName();
          if ("shape".equals(local)) {
            inShape = true;
            inClientData = false;
            row = -1;
            col = -1;
            relId = "";
          } else if (inShape && "imagedata".equals(local)) {
            relId = attrAnyNs(reader, "relid");
            if (relId.isBlank()) {
              relId = attrAnyNs(reader, "id");
            }
          } else if (inShape && "ClientData".equals(local)) {
            inClientData = true;
          } else if (inShape && inClientData && "Row".equals(local)) {
            row = parseIntOrDefault(reader.getElementText(), row);
          } else if (inShape && inClientData && "Column".equals(local)) {
            col = parseIntOrDefault(reader.getElementText(), col);
          } else if (inShape && inClientData && "Anchor".equals(local)) {
            String[] parts = reader.getElementText().split(",");
            if (parts.length >= 4) {
              col = parseIntOrDefault(parts[0], col);
              row = parseIntOrDefault(parts[2], row);
            }
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String local = reader.getLocalName();
          if ("ClientData".equals(local)) {
            inClientData = false;
          } else if ("shape".equals(local)) {
            if (row >= 0 && col >= 0 && !relId.isBlank()) {
              String mediaPath = imageRelToPath.get(relId);
              if (isSupportedImagePath(mediaPath)) {
                out.putIfAbsent(new CellPosition(row, col), mediaPath);
              }
            }
            inShape = false;
            inClientData = false;
            row = -1;
            col = -1;
            relId = "";
          }
        }
      }
    } catch (Exception ignored) {
      return Map.of();
    }
    return out;
  }

  private String persistSignImage(ZipFile zip, String mediaPath) {
    if (mediaPath == null || mediaPath.isBlank()) {
      return "";
    }
    ZipEntry mediaEntry = zip.getEntry(mediaPath);
    if (mediaEntry == null) {
      return "";
    }

    byte[] bytes;
    try (InputStream in = zip.getInputStream(mediaEntry)) {
      bytes = readAllBytes(in);
    } catch (Exception ex) {
      return "";
    }
    if (bytes.length == 0) {
      return "";
    }

    String ext = extensionOf(mediaPath);
    String hash;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      hash = HEX.formatHex(digest.digest(bytes));
    } catch (Exception ex) {
      return "";
    }

    String fileName = hash + "." + ext;
    try {
      Files.createDirectories(SIGN_IMAGE_STORAGE_DIR);
      Path target = SIGN_IMAGE_STORAGE_DIR.resolve(fileName);
      if (!Files.exists(target)) {
        Files.write(target, bytes);
      }
    } catch (Exception ex) {
      return "";
    }
    return "/storage/roster-signatures/" + fileName;
  }

  private byte[] readAllBytes(InputStream in) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
    byte[] buffer = new byte[8192];
    int read;
    while ((read = in.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private void ensureRowSize(List<List<String>> rows, int rowIndex, int colIndex) {
    while (rows.size() <= rowIndex) {
      rows.add(new ArrayList<>());
    }
    List<String> row = rows.get(rowIndex);
    if (!(row instanceof ArrayList)) {
      row = new ArrayList<>(row);
      rows.set(rowIndex, row);
    }
    while (row.size() <= colIndex) {
      row.add("");
    }
  }

  private String buildRelsPath(String sourcePath) {
    String normalized = normalizeZipPath(sourcePath);
    int slash = normalized.lastIndexOf('/');
    String dir = slash >= 0 ? normalized.substring(0, slash) : "";
    String file = slash >= 0 ? normalized.substring(slash + 1) : normalized;
    if (dir.isEmpty()) {
      return "_rels/" + file + ".rels";
    }
    return dir + "/_rels/" + file + ".rels";
  }

  private String resolveZipPath(String basePath, String target) {
    if (target == null || target.isBlank()) {
      return "";
    }
    String trimmed = target.trim();
    if (trimmed.startsWith("/")) {
      return normalizeZipPath(trimmed.substring(1));
    }
    String normalizedBase = normalizeZipPath(basePath);
    int slash = normalizedBase.lastIndexOf('/');
    String baseDir = slash >= 0 ? normalizedBase.substring(0, slash) : "";
    String joined = baseDir.isEmpty() ? trimmed : baseDir + "/" + trimmed;
    return normalizeZipPath(joined);
  }

  private String normalizeZipPath(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String[] parts = value.replace('\\', '/').split("/");
    Deque<String> stack = new ArrayDeque<>();
    for (String part : parts) {
      if (part == null || part.isBlank() || ".".equals(part)) {
        continue;
      }
      if ("..".equals(part)) {
        if (!stack.isEmpty()) {
          stack.removeLast();
        }
        continue;
      }
      stack.addLast(part);
    }
    return String.join("/", stack);
  }

  private int parsePositiveInt(String text, int fallback) {
    int value = parseIntOrDefault(text, fallback);
    return value > 0 ? value : fallback;
  }

  private int parseIntOrDefault(String text, int fallback) {
    if (text == null || text.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(text.trim());
    } catch (Exception ex) {
      return fallback;
    }
  }

  private String attrAnyNs(XMLStreamReader reader, String localName) {
    if (reader == null || localName == null || localName.isBlank()) {
      return "";
    }
    for (int i = 0; i < reader.getAttributeCount(); i++) {
      if (localName.equals(reader.getAttributeLocalName(i))) {
        String value = reader.getAttributeValue(i);
        return value == null ? "" : value;
      }
    }
    return "";
  }

  private String extensionOf(String path) {
    if (path == null || path.isBlank()) {
      return "png";
    }
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) {
      return "png";
    }
    String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
    if (ext.isEmpty()) {
      return "png";
    }
    return switch (ext) {
      case "jpeg", "jpg", "png", "gif", "bmp", "webp", "svg", "emf", "wmf", "tif", "tiff" -> ext;
      default -> "png";
    };
  }

  private record CellPosition(int row, int col) {
  }

  private XMLInputFactory newSafeXmlInputFactory() {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    // Prevent XXE via malicious XLSX XML parts.
    trySetXmlProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
    trySetXmlProperty(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    trySetXmlProperty(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
    return factory;
  }

  private void trySetXmlProperty(XMLInputFactory factory, String name, Object value) {
    try {
      factory.setProperty(name, value);
    } catch (IllegalArgumentException ignored) {
      // Some implementations do not support all properties.
    }
  }

  private static final class LimitedInputStream extends FilterInputStream {
    private long remaining;

    private LimitedInputStream(InputStream in, long maxBytes) {
      super(in);
      this.remaining = Math.max(0L, maxBytes);
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        throw new IllegalStateException("xlsx_xml_too_large");
      }
      int value = super.read();
      if (value >= 0) {
        remaining -= 1;
      }
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (remaining <= 0) {
        throw new IllegalStateException("xlsx_xml_too_large");
      }
      int toRead = (int) Math.min(len, remaining);
      int read = super.read(b, off, toRead);
      if (read > 0) {
        remaining -= read;
      }
      return read;
    }
  }

  private String decodeCellValue(String text, String cellType, List<String> sharedStrings) {
    if (text == null) {
      return "";
    }
    if ("s".equals(cellType)) {
      try {
        int idx = Integer.parseInt(text.trim());
        if (idx >= 0 && idx < sharedStrings.size()) {
          return sharedStrings.get(idx);
        }
      } catch (Exception ignored) {
        return "";
      }
      return "";
    }
    return text;
  }

  private String normalizeCellValue(String value, String cellType, String formula) {
    String current = value == null ? "" : value;
    String trimmed = current.trim();
    String imageUrl = extractImageFormulaUrl(formula);
    boolean hasError = "e".equalsIgnoreCase(cellType) || isExcelErrorToken(trimmed);
    String normalizedFormula = normalizeFormulaForStorage(formula, imageUrl);
    if (!normalizedFormula.isEmpty() && (trimmed.isEmpty() || hasError)) {
      return normalizedFormula;
    }
    if (hasError) {
      return "";
    }
    return current;
  }

  private String normalizeFormulaForStorage(String formula, String imageUrl) {
    String raw = formula == null ? "" : formula.trim();
    if (raw.isEmpty()) {
      return imageUrl;
    }
    if (!imageUrl.isEmpty()) {
      String escapedUrl = imageUrl.replace("\"", "\"\"");
      return "=IMAGE(\"" + escapedUrl + "\")";
    }
    return raw.startsWith("=") ? raw : "=" + raw;
  }

  private boolean isExcelErrorToken(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String upper = value.trim().toUpperCase(Locale.ROOT);
    return "#VALUE!".equals(upper)
        || "#N/A".equals(upper)
        || "#NAME?".equals(upper)
        || "#DIV/0!".equals(upper)
        || "#REF!".equals(upper)
        || "#NUM!".equals(upper)
        || "#NULL!".equals(upper)
        || "#SPILL!".equals(upper)
        || "#CALC!".equals(upper)
        || "#BLOCKED!".equals(upper);
  }

  private String extractImageFormulaUrl(String formula) {
    if (formula == null || formula.isBlank()) {
      return "";
    }
    String trimmed = formula.trim();
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (!upper.contains("IMAGE(")) {
      return "";
    }

    int firstQuote = trimmed.indexOf('"');
    if (firstQuote < 0) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (int i = firstQuote + 1; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '"') {
        if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '"') {
          out.append('"');
          i++;
          continue;
        }
        break;
      }
      out.append(c);
    }
    String url = out.toString().trim();
    if (url.length() <= 255) {
      return url;
    }
    return url.substring(0, 255);
  }

  private String extractDispImgId(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String fromFormula = extractDispImgTokenFromFormula(value);
    if (!fromFormula.isBlank()) {
      return normalizeDispImgToken(fromFormula);
    }
    return normalizeDispImgToken(extractLegacyDispImgToken(value));
  }

  private String extractDispImgTokenFromFormula(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String raw = value.trim();
    String upper = raw.toUpperCase(Locale.ROOT);
    int functionStart = upper.indexOf("DISPIMG(");
    if (functionStart < 0) {
      return "";
    }
    int paren = raw.indexOf('(', functionStart);
    if (paren < 0) {
      return "";
    }
    int firstQuote = -1;
    char quote = 0;
    for (int i = paren + 1; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == '"' || c == '\'') {
        firstQuote = i;
        quote = c;
        break;
      }
      if (c == ')') {
        break;
      }
    }
    if (firstQuote < 0 || quote == 0) {
      return "";
    }

    StringBuilder token = new StringBuilder();
    for (int i = firstQuote + 1; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c == quote) {
        if (quote == '"' && i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
          token.append('"');
          i++;
          continue;
        }
        break;
      }
      token.append(c);
    }
    return token.toString().trim();
  }

  private String extractLegacyDispImgToken(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String upper = value.trim().toUpperCase(Locale.ROOT);
    int start = upper.indexOf("ID_");
    if (start < 0) {
      return "";
    }
    int end = start + 3;
    while (end < upper.length()) {
      char c = upper.charAt(end);
      if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
        end++;
      } else {
        break;
      }
    }
    if (end <= start + 3) {
      return "";
    }
    return upper.substring(start, end);
  }

  private String normalizeDispImgToken(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
        out.append(Character.toUpperCase(c));
      }
    }
    return out.toString();
  }

  private int colIndex(String cellRef) {
    if (cellRef == null || cellRef.isBlank()) {
      return -1;
    }
    int col = 0;
    int i = 0;
    while (i < cellRef.length()) {
      char c = cellRef.charAt(i);
      if (c >= 'A' && c <= 'Z') {
        col = col * 26 + (c - 'A' + 1);
      } else if (c >= 'a' && c <= 'z') {
        col = col * 26 + (c - 'a' + 1);
      } else {
        break;
      }
      i++;
    }
    return col - 1;
  }

  private int rowIndexOneBased(String cellRef) {
    if (cellRef == null || cellRef.isBlank()) {
      return -1;
    }
    int i = 0;
    while (i < cellRef.length()) {
      char c = cellRef.charAt(i);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
        i++;
        continue;
      }
      break;
    }
    if (i >= cellRef.length()) {
      return -1;
    }
    int out = 0;
    boolean hasDigit = false;
    while (i < cellRef.length()) {
      char c = cellRef.charAt(i);
      if (c < '0' || c > '9') {
        break;
      }
      hasDigit = true;
      out = out * 10 + (c - '0');
      i++;
    }
    return hasDigit && out > 0 ? out : -1;
  }

  private String attr(XMLStreamReader reader, String name) {
    String value = reader.getAttributeValue(null, name);
    return value == null ? "" : value;
  }

  private ParseResult convertTabularRows(List<List<String>> rows) {
    if (rows == null || rows.isEmpty()) {
      return ParseResult.error("empty_sheet");
    }

    int headerRowIndex = findHeaderRow(rows);
    if (headerRowIndex < 0) {
      return ParseResult.error("header_not_found");
    }

    Map<String, Integer> headerIndex = buildHeaderIndex(rows.get(headerRowIndex));
    Integer nameIdx = findHeader(headerIndex, "이름", "성명");
    Integer birthIdx = findHeader(headerIndex, "생년월일", "생일", "birth");
    Integer phoneIdx = findHeader(headerIndex, "휴대폰번호", "휴대폰", "핸드폰", "전화번호", "mobile");
    if (nameIdx == null) {
      List<String> missing = new ArrayList<>();
      if (nameIdx == null) missing.add("이름");
      return ParseResult.error("missing_required_header:" + String.join(",", missing));
    }

    Integer seqIdx = findHeader(headerIndex, "연번", "순번", "번호");
    Integer dongIdx = findHeader(headerIndex, "동");
    Integer jibunIdx = findHeader(headerIndex, "지번");
    Integer paperIdx = findHeader(headerIndex, "서면제출확인", "서면제출후확인");
    Integer mailIdx = findHeader(headerIndex, "우편제출확인");
    Integer eVoteIdx = findHeader(headerIndex, "전자투표");
    Integer phoneAccessIdx = findHeader(headerIndex, "휴대폰접속일", "휴대폰 접속일", "휴대폰접속");
    Integer entryIdx = findHeader(headerIndex, "출입시간(QR)", "출입시간qr", "출입시간");
    Integer onlineMeetingIdx = findHeader(headerIndex, "온라인총회");
    Integer onlineMeetingStartIdx =
        findHeader(
            headerIndex,
            "온라인 총회 접속 시작",
            "온라인총회접속시작",
            "온라인총회시작");
    Integer onlineMeetingEndIdx =
        findHeader(
            headerIndex,
            "온라인 총회 접속 종료",
            "온라인총회접속종료",
            "온라인 총회 접송 종료",
            "온라인총회접송종료",
            "온라인총회종료");
    Integer ipIdx = findHeader(headerIndex, "IP주소", "IP");
    Integer rosterRegIdx = findHeader(headerIndex, "명부등록일");
    Integer proxyIdx = findHeader(headerIndex, "대리인");
    Integer proxyPhoneIdx = findHeader(headerIndex, "휴대폰번호2(대리인)", "휴대폰번호2", "대리인휴대폰번호");
    Integer signIdx = findSignColumn(headerIndex, rows, headerRowIndex);

    List<RosterRow> out = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    int skipped = 0;

    for (int i = headerRowIndex + 1; i < rows.size(); i++) {
      List<String> row = rows.get(i);
      if (row == null || row.isEmpty() || isRowBlank(row)) {
        continue;
      }

      Integer seqNo = parseInt(cell(row, seqIdx));
      String name = normalizeNameForStorage(cell(row, nameIdx));
      String birth = normalizeBirthForStorage(cell(row, birthIdx), seqNo, i + 1, name, cell(row, phoneIdx));
      String phone = normalizePhoneForStorage(cell(row, phoneIdx), seqNo, i + 1, name, birth);

      if (name.isEmpty()) {
        skipped++;
        if (errors.size() < MAX_ERROR_MESSAGES) {
          errors.add("row " + (i + 1) + ": missing(name)");
        }
        continue;
      }

      String paperSubmitConfirm = normalizeSubmitConfirmForStorage(cell(row, paperIdx));
      String mailSubmitConfirm = normalizeSubmitConfirmForStorage(cell(row, mailIdx));
      String electronicVote = normalizeElectronicVoteForStorage(cell(row, eVoteIdx));
      String onsiteVoteAllowed =
          deriveOnsiteVoteAllowed(
              paperSubmitConfirm,
              mailSubmitConfirm,
              electronicVote);
      out.add(new RosterRow(
          seqNo,
          name,
          cell(row, dongIdx),
          cell(row, jibunIdx),
          birth,
          phone,
          paperSubmitConfirm,
          mailSubmitConfirm,
          electronicVote,
          normalizeDateTimeMinute(cell(row, phoneAccessIdx)),
          normalizeDateTimeMinute(cell(row, entryIdx)),
          cell(row, onlineMeetingIdx),
          normalizeDateTimeMinute(cell(row, onlineMeetingStartIdx)),
          normalizeDateTimeMinute(cell(row, onlineMeetingEndIdx)),
          cell(row, ipIdx),
          normalizeDateTimeMinute(cell(row, rosterRegIdx)),
          onsiteVoteAllowed,
          cell(row, proxyIdx),
          normalizePhoneForStorageOptional(cell(row, proxyPhoneIdx)),
          normalizeSignImageForStorage(cell(row, signIdx))
      ));
    }

    return new ParseResult(out, skipped, errors, true);
  }

  private int findHeaderRow(List<List<String>> rows) {
    int limit = Math.min(rows.size(), 20);
    for (int i = 0; i < limit; i++) {
      Map<String, Integer> headerIndex = buildHeaderIndex(rows.get(i));
      if (findHeader(headerIndex, "이름", "성명") != null) {
        return i;
      }
    }
    return -1;
  }

  private Map<String, Integer> buildHeaderIndex(List<String> headerRow) {
    if (headerRow == null) {
      return Map.of();
    }
    Map<String, Integer> out = new HashMap<>();
    for (int i = 0; i < headerRow.size(); i++) {
      String key = normalizeHeaderKey(headerRow.get(i));
      if (!key.isEmpty() && !out.containsKey(key)) {
        out.put(key, i);
      }
    }
    return out;
  }

  private Integer findHeader(Map<String, Integer> headerIndex, String... candidates) {
    if (headerIndex == null || headerIndex.isEmpty() || candidates == null) {
      return null;
    }
    for (String candidate : candidates) {
      String key = normalizeHeaderKey(candidate);
      Integer idx = headerIndex.get(key);
      if (idx != null) {
        return idx;
      }
    }
    return null;
  }

  private Integer findSignColumn(Map<String, Integer> headerIndex, List<List<String>> rows, int headerRowIndex) {
    Integer byHeader = findSignHeader(headerIndex);
    if (byHeader != null) {
      return byHeader;
    }
    return findSignColumnFromData(rows, headerRowIndex);
  }

  private Integer findSignHeader(Map<String, Integer> headerIndex) {
    Integer exact = findHeader(
        headerIndex,
        "싸인이미지",
        "싸인 이미지",
        "사인이미지",
        "사인 이미지",
        "서명이미지",
        "서명 이미지",
        "싸인",
        "사인",
        "서명",
        "signature",
        "signatureimage",
        "signimage");
    if (exact != null) {
      return exact;
    }

    if (headerIndex == null || headerIndex.isEmpty()) {
      return null;
    }
    Integer minIndex = null;
    for (Map.Entry<String, Integer> entry : headerIndex.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        continue;
      }
      if (key.contains("싸인")
          || key.contains("사인")
          || key.contains("서명")
          || key.contains("날인")
          || key.contains("signature")
          || key.contains("signimage")
          || key.contains("signimg")) {
        Integer idx = entry.getValue();
        if (idx != null && (minIndex == null || idx < minIndex)) {
          minIndex = idx;
        }
      }
    }
    return minIndex;
  }

  private Integer findSignColumnFromData(List<List<String>> rows, int headerRowIndex) {
    if (rows == null || rows.isEmpty() || headerRowIndex < 0 || headerRowIndex >= rows.size()) {
      return null;
    }

    Map<Integer, Integer> score = new HashMap<>();
    for (int rowIndex = headerRowIndex + 1; rowIndex < rows.size(); rowIndex++) {
      List<String> row = rows.get(rowIndex);
      if (row == null || row.isEmpty()) {
        continue;
      }
      for (int col = 0; col < row.size(); col++) {
        if (!isLikelySignImageValue(row.get(col))) {
          continue;
        }
        score.put(col, score.getOrDefault(col, 0) + 1);
      }
    }

    int bestCol = -1;
    int bestScore = 0;
    for (Map.Entry<Integer, Integer> entry : score.entrySet()) {
      int col = entry.getKey();
      int colScore = entry.getValue();
      if (colScore > bestScore || (colScore == bestScore && col < bestCol)) {
        bestScore = colScore;
        bestCol = col;
      }
    }
    return bestCol >= 0 ? bestCol : null;
  }

  private boolean isLikelySignImageValue(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String trimmed = value.trim();
    String upper = trimmed.toUpperCase(Locale.ROOT);
    if (upper.contains("IMAGE(")) {
      return true;
    }
    if (upper.contains("DISPIMG(") || !extractDispImgId(trimmed).isBlank()) {
      return true;
    }
    if (trimmed.startsWith("/storage/roster-signatures/")
        || trimmed.startsWith("storage/roster-signatures/")
        || trimmed.startsWith("/opt/meeting/storage/roster-signatures/")) {
      return true;
    }
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return true;
    }
    return isSupportedImagePath(trimmed);
  }

  private String normalizeHeaderKey(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (Character.isWhitespace(c)) {
        continue;
      }
      if (Character.isLetterOrDigit(c)) {
        out.append(Character.toLowerCase(c));
      }
    }
    return out.toString();
  }

  private boolean isRowBlank(List<String> row) {
    for (String cell : row) {
      if (cell != null && !cell.trim().isEmpty()) {
        return false;
      }
    }
    return true;
  }

  private String cell(List<String> row, Integer idx) {
    if (row == null || idx == null || idx < 0 || idx >= row.size()) {
      return "";
    }
    String value = row.get(idx);
    return value == null ? "" : value.trim();
  }

  private Integer parseInt(String value) {
    String digits = RosterVerifier.digitsOnly(value);
    if (digits.isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(digits);
    } catch (Exception ex) {
      return null;
    }
  }

  private String deriveOnsiteVoteAllowed(
      String paperSubmitConfirm, String mailSubmitConfirm, String electronicVote) {
    if (!paperSubmitConfirm.isBlank() || !mailSubmitConfirm.isBlank() || !electronicVote.isBlank()) {
      return "불가능";
    }
    return "가능";
  }

  private String normalizeNameForStorage(String value) {
    return RosterVerifier.normalizeName(value);
  }

  private String normalizeBirthForStorage(
      String value,
      Integer seqNo,
      int rowNumber,
      String name,
      String phone) {
    String birth = RosterVerifier.normalizeBirth(value);
    if (birth.length() == 6) {
      return birth;
    }
    // 생년월일 누락/비정상이어도 명부 업로드는 유지해야 하므로
    // 인증에는 매칭되지 않는 기본값으로 저장한다.
    return fallbackBirthForStorage(seqNo, rowNumber, name, phone, value);
  }

  private String normalizePhoneForStorage(
      String value,
      Integer seqNo,
      int rowNumber,
      String name,
      String birth) {
    String formatted = RosterVerifier.formatPhoneHyphen(value);
    if (!formatted.isEmpty()) {
      return formatted;
    }
    // 번호가 비었거나 양식이 달라도 업로드는 유지해야 하므로
    // DB 체크 제약을 통과하는 fallback 번호를 생성해서 저장한다.
    return fallbackPhoneForStorage(seqNo, rowNumber, name, birth, value);
  }

  private String normalizePhoneForStorageOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String formatted = RosterVerifier.formatPhoneHyphen(value);
    return formatted.isEmpty() ? null : formatted;
  }

  private String fallbackPhoneForStorage(
      Integer seqNo,
      int rowNumber,
      String name,
      String birth,
      String originalValue) {
    String base =
        (seqNo == null ? "" : String.valueOf(seqNo))
            + "|"
            + rowNumber
            + "|"
            + (name == null ? "" : name)
            + "|"
            + (birth == null ? "" : birth)
            + "|"
            + (originalValue == null ? "" : originalValue.trim());
    long seed = base.hashCode();
    if (seed == Long.MIN_VALUE) {
      seed = 0L;
    }
    long normalized = Math.abs(seed) % 100_000_000L;
    int middle = (int) (normalized / 10_000L);
    int tail = (int) (normalized % 10_000L);
    return String.format(Locale.ROOT, "019-%04d-%04d", middle, tail);
  }

  private String fallbackBirthForStorage(
      Integer seqNo,
      int rowNumber,
      String name,
      String phone,
      String originalValue) {
    // NICE 실명인증 값(yyMMdd)과 우연히 일치하지 않도록
    // 누락/비정상 값은 고정 불일치 값으로 저장한다.
    return "000000";
  }

  private String normalizeSignImageForStorage(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty() || isExcelErrorToken(trimmed)) {
      return "";
    }
    if (trimmed.length() <= 255) {
      return trimmed;
    }
    return trimmed.substring(0, 255);
  }

  private String normalizeSubmitConfirmForStorage(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }

    // 엑셀 날짜 직렬값(예: 46075)이나 날짜 문자열이면 yyyy-MM-dd 로 정규화한다.
    String normalizedDateTime = normalizeDateTimeMinute(trimmed);
    if (normalizedDateTime != null && normalizedDateTime.length() >= 10) {
      return normalizedDateTime.substring(0, 10);
    }

    if (trimmed.length() <= 255) {
      return trimmed;
    }
    return trimmed.substring(0, 255);
  }

  private String normalizeElectronicVoteForStorage(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return "";
    }

    // 전자투표 값이 엑셀 직렬값(예: 46075)로 들어오면 날짜/시간 문자열로 정규화한다.
    String normalizedDateTime = normalizeDateTimeMinute(trimmed);
    String out = normalizedDateTime != null ? normalizedDateTime : trimmed;
    if (out.length() <= 20) {
      return out;
    }
    return out.substring(0, 20);
  }

  private String normalizeDateTimeMinute(String value) {
    if (value == null) {
      return null;
    }
    String s = value.trim();
    if (s.isEmpty()) {
      return null;
    }

    // Excel may provide date-time cells as a serial number (days since 1899-12-31) with fractional time.
    // Example: 45678.6041666667 -> "2025-01-xx 14:30" (depends on date).
    if (s.matches("^[0-9]+(\\.[0-9]+)?$")) {
      try {
        double serial = Double.parseDouble(s);
        int days = (int) Math.floor(serial);
        double frac = serial - days;
        // Guard against "time only" values (e.g., 0.5). We only accept plausible date ranges.
        if (days < 20000 || days > 90000) {
          return null;
        }
        if (days >= 60) {
          days -= 1; // Excel 1900 leap-year bug compensation
        }
        LocalDate date = LocalDate.of(1899, 12, 31).plusDays(days);
        int minutes = (int) Math.floor(frac * 1440d + 1e-8);
        if (minutes < 0) {
          minutes = 0;
        }
        if (minutes >= 1440) {
          date = date.plusDays(1);
          minutes = 0;
        }
        LocalTime time = LocalTime.of(minutes / 60, minutes % 60);
        return LocalDateTime.of(date, time).format(DATE_TIME_MINUTE);
      } catch (Exception ignored) {
        // fall through to string parsing
      }
    }

    s = s.replace('T', ' ');
    // Normalize common separators in the date part.
    String[] parts = s.split("\\s+");
    if (parts.length == 0) {
      return null;
    }
    String datePart = parts[0].trim().replace('/', '-').replace('.', '-');
    String timePart = parts.length >= 2 ? parts[1].trim() : "00:00";

    // If input is "yyyyMMdd" without separators, normalize it.
    if (datePart.matches("^[0-9]{8}$")) {
      datePart = datePart.substring(0, 4) + "-" + datePart.substring(4, 6) + "-" + datePart.substring(6, 8);
    }

    String[] dateSeg = datePart.split("-");
    if (dateSeg.length != 3) {
      return null;
    }
    int year;
    int month;
    int day;
    try {
      // Supports both yyyy-MM-dd and MM-dd-yy(yy/MM/dd from some roster sheets).
      if (dateSeg[0].length() == 4) {
        year = Integer.parseInt(dateSeg[0]);
        month = Integer.parseInt(dateSeg[1]);
        day = Integer.parseInt(dateSeg[2]);
      } else {
        month = Integer.parseInt(dateSeg[0]);
        day = Integer.parseInt(dateSeg[1]);
        int y = Integer.parseInt(dateSeg[2]);
        if (dateSeg[2].length() == 2) {
          year = y >= 70 ? 1900 + y : 2000 + y;
        } else if (dateSeg[2].length() == 4) {
          year = y;
        } else {
          return null;
        }
      }
      // Basic sanity check.
      if (year < 1900 || year > 2100) {
        return null;
      }
      if (month < 1 || month > 12) {
        return null;
      }
      if (day < 1 || day > 31) {
        return null;
      }
      // Validates calendar correctness (e.g., 2025-02-30).
      LocalDate.of(year, month, day);
    } catch (Exception ex) {
      return null;
    }

    // Trim seconds/timezone and keep HH:mm only.
    if (timePart.contains("+")) {
      timePart = timePart.substring(0, timePart.indexOf('+'));
    }
    if (timePart.contains("Z")) {
      timePart = timePart.replace("Z", "");
    }
    int hour = 0;
    int minute = 0;
    try {
      if (timePart.isBlank()) {
        hour = 0;
        minute = 0;
      } else if (timePart.contains(":")) {
        String[] t = timePart.split(":");
        if (t.length >= 1) {
          hour = Integer.parseInt(t[0]);
        }
        if (t.length >= 2) {
          minute = Integer.parseInt(t[1]);
        }
      } else if (timePart.matches("^[0-9]{1,2}$")) {
        hour = Integer.parseInt(timePart);
        minute = 0;
      } else {
        return null;
      }
      if (hour < 0 || hour > 23) {
        return null;
      }
      if (minute < 0 || minute > 59) {
        return null;
      }
    } catch (Exception ex) {
      return null;
    }

    return String.format("%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute);
  }

  private static class ParseResult {
    private final List<RosterRow> rows;
    private final int skipped;
    private final List<String> errors;
    private final boolean ok;

    private ParseResult(List<RosterRow> rows, int skipped, List<String> errors, boolean ok) {
      this.rows = rows == null ? List.of() : rows;
      this.skipped = skipped;
      this.errors = errors == null ? List.of() : errors;
      this.ok = ok;
    }

    private static ParseResult error(String message) {
      return new ParseResult(List.of(), 0, List.of(message), false);
    }
  }
}
