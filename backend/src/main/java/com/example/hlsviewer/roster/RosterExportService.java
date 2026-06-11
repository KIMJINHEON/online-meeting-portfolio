package com.example.hlsviewer.roster;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;

@Service
public class RosterExportService {
  private static final double MIN_COLUMN_WIDTH = 8.0;
  private static final double MAX_COLUMN_WIDTH = 60.0;
  private static final double COLUMN_PADDING = 2.0;
  private static final double SIGN_COLUMN_MIN_WIDTH = 20.0;
  private static final double IMAGE_ROW_HEIGHT = 58.0;

  private static final Path SIGN_IMAGE_STORAGE_ROOT = Path.of("/opt/meeting/storage/roster-signatures");

  private static final String[] HEADERS = {
      "연번",
      "이름",
      "동",
      "지번",
      "생년월일",
      "휴대폰번호",
      "서면제출확인",
      "우편제출확인",
      "전자투표",
      "휴대폰 접속일",
      "출입시간(QR)",
      "IP주소",
      "온라인 총회 접속 시작",
      "온라인 총회 접속 종료",
      "명부 등록일",
      "현장투표 가능여부",
      "대리인",
      "휴대폰번호2(대리인)",
      "싸인이미지"
  };
  private static final int SIGN_IMAGE_COL_INDEX = HEADERS.length - 1;

  private final RosterRepository rosterRepository;

  public RosterExportService(RosterRepository rosterRepository) {
    this.rosterRepository = rosterRepository;
  }

  public byte[] exportXlsx(String streamKey) {
    List<RosterRow> rows = rosterRepository.findAllForExport(streamKey);
    SheetBuild sheet = buildSheetData(rows);
    Map<String, ExportImage> imageByCell = indexImagesByCell(sheet.images());
    Set<Integer> imageRows = indexImageRows(sheet.images());

    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
         ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      writeEntry(zip, "[Content_Types].xml", buildContentTypesXml(sheet.images()));
      writeEntry(zip, "_rels/.rels", buildRootRelsXml());
      writeEntry(zip, "xl/workbook.xml", buildWorkbookXml());
      writeEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml());
      writeEntry(zip, "xl/styles.xml", buildStylesXml());
      writeEntry(zip, "xl/worksheets/sheet1.xml", buildSheetXml(sheet.rows(), imageByCell, imageRows, !sheet.images().isEmpty()));

      if (!sheet.images().isEmpty()) {
        writeEntry(zip, "xl/worksheets/_rels/sheet1.xml.rels", buildSheetRelsXml());
        writeEntry(zip, "xl/drawings/drawing1.xml", buildDrawingXml(sheet.images()));
        writeEntry(zip, "xl/drawings/_rels/drawing1.xml.rels", buildDrawingRelsXml(sheet.images()));
        for (ExportImage image : sheet.images()) {
          writeEntry(zip, "xl/media/" + image.mediaName(), image.bytes());
        }
      }

      zip.finish();
      return bytes.toByteArray();
    } catch (Exception ex) {
      throw new IllegalStateException("failed_to_build_roster_xlsx", ex);
    }
  }

  private SheetBuild buildSheetData(List<RosterRow> rows) {
    List<List<String>> tableRows = new ArrayList<>(rows.size() + 1);
    List<ExportImage> images = new ArrayList<>();
    tableRows.add(List.of(HEADERS));

    int mediaSeq = 1;
    for (RosterRow row : rows) {
      List<String> values = new ArrayList<>(HEADERS.length);
      values.add(row.seqNo() == null ? "" : String.valueOf(row.seqNo()));
      values.add(nullToEmpty(row.name()));
      values.add(nullToEmpty(row.dong()));
      values.add(nullToEmpty(row.jibun()));
      values.add(nullToEmpty(row.birth()));
      values.add(nullToEmpty(row.phone()));
      values.add(nullToEmpty(row.paperSubmitConfirm()));
      values.add(nullToEmpty(row.mailSubmitConfirm()));
      values.add(nullToEmpty(row.electronicVote()));
      values.add(nullToEmpty(row.phoneAccessedAt()));
      values.add(nullToEmpty(row.entryTime()));
      values.add(nullToEmpty(row.ipAddress()));
      values.add(nullToEmpty(row.onlineMeetingStartedAt()));
      values.add(nullToEmpty(row.onlineMeetingEndedAt()));
      values.add(nullToEmpty(row.rosterRegisteredAt()));
      values.add(nullToEmpty(row.onsiteVoteAllowed()));
      values.add(nullToEmpty(row.proxyName()));
      values.add(nullToEmpty(row.proxyPhone()));

      int nextRowIndexZeroBased = tableRows.size();
      String sign = nullToEmpty(row.signImage());
      ExportImage image = loadSignImage(sign, nextRowIndexZeroBased, SIGN_IMAGE_COL_INDEX, mediaSeq);
      if (image != null) {
        images.add(image);
        mediaSeq++;
        values.add("");
      } else {
        values.add(sign);
      }

      tableRows.add(values);
    }

    return new SheetBuild(tableRows, images);
  }

  private ExportImage loadSignImage(String signValue, int rowIndex, int colIndex, int mediaSeq) {
    Path file = resolveSignImagePath(signValue);
    if (file == null) {
      return null;
    }
    try {
      if (!Files.isRegularFile(file)) {
        return null;
      }
      byte[] bytes = Files.readAllBytes(file);
      if (bytes.length == 0) {
        return null;
      }
      String ext = extensionOf(file.getFileName().toString());
      return new ExportImage(
          rowIndex,
          colIndex,
          "rId" + mediaSeq,
          "sign_" + mediaSeq + "." + ext,
          ext,
          bytes
      );
    } catch (Exception ex) {
      return null;
    }
  }

  private Path resolveSignImagePath(String signValue) {
    if (signValue == null || signValue.isBlank()) {
      return null;
    }
    String trimmed = signValue.trim();
    if (trimmed.startsWith("=") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      return null;
    }

    Path candidate;
    if (trimmed.startsWith("/opt/meeting/storage/roster-signatures/")) {
      candidate = Path.of(trimmed);
    } else if (trimmed.startsWith("/storage/roster-signatures/")) {
      candidate = Path.of("/opt/meeting").resolve(trimmed.substring(1));
    } else if (trimmed.startsWith("storage/roster-signatures/")) {
      candidate = Path.of("/opt/meeting").resolve(trimmed);
    } else {
      return null;
    }

    try {
      Path normalized = candidate.normalize();
      if (!normalized.startsWith(SIGN_IMAGE_STORAGE_ROOT)) {
        return null;
      }
      return normalized;
    } catch (Exception ex) {
      return null;
    }
  }

  private Map<String, ExportImage> indexImagesByCell(List<ExportImage> images) {
    Map<String, ExportImage> out = new HashMap<>();
    for (ExportImage image : images) {
      out.put(cellKey(image.rowIndex(), image.colIndex()), image);
    }
    return out;
  }

  private Set<Integer> indexImageRows(List<ExportImage> images) {
    Set<Integer> rows = new HashSet<>();
    for (ExportImage image : images) {
      rows.add(image.rowIndex());
    }
    return rows;
  }

  private void writeEntry(ZipOutputStream zip, String path, String xml) throws Exception {
    writeEntry(zip, path, xml.getBytes(StandardCharsets.UTF_8));
  }

  private void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
    ZipEntry entry = new ZipEntry(path);
    zip.putNextEntry(entry);
    zip.write(bytes);
    zip.closeEntry();
  }

  private String buildContentTypesXml(List<ExportImage> images) {
    StringBuilder xml = new StringBuilder(1024);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    xml.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
    xml.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
    xml.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");

    Set<String> imageExts = new HashSet<>();
    for (ExportImage image : images) {
      imageExts.add(image.extension());
    }
    for (String ext : imageExts) {
      xml.append("<Default Extension=\"")
          .append(xmlEscape(ext))
          .append("\" ContentType=\"")
          .append(xmlEscape(mediaContentType(ext)))
          .append("\"/>");
    }

    xml.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
    xml.append("<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
    xml.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
    if (!images.isEmpty()) {
      xml.append("<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
    }
    xml.append("</Types>");
    return xml.toString();
  }

  private String buildRootRelsXml() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
        """;
  }

  private String buildWorkbookXml() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="명부" sheetId="1" r:id="rId1"/>
          </sheets>
        </workbook>
        """;
  }

  private String buildWorkbookRelsXml() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                        Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                        Target="styles.xml"/>
        </Relationships>
        """;
  }

  private String buildStylesXml() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="1">
            <font>
              <sz val="11"/>
              <color theme="1"/>
              <name val="Calibri"/>
              <family val="2"/>
              <scheme val="minor"/>
            </font>
          </fonts>
          <fills count="2">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
          </fills>
          <borders count="2">
            <border>
              <left/><right/><top/><bottom/><diagonal/>
            </border>
            <border>
              <left style="thin"><color rgb="FF000000"/></left>
              <right style="thin"><color rgb="FF000000"/></right>
              <top style="thin"><color rgb="FF000000"/></top>
              <bottom style="thin"><color rgb="FF000000"/></bottom>
              <diagonal/>
            </border>
          </borders>
          <cellStyleXfs count="1">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
          </cellStyleXfs>
          <cellXfs count="2">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
          </cellXfs>
          <cellStyles count="1">
            <cellStyle name="Normal" xfId="0" builtinId="0"/>
          </cellStyles>
        </styleSheet>
        """;
  }

  private String buildSheetXml(
      List<List<String>> tableRows,
      Map<String, ExportImage> imageByCell,
      Set<Integer> imageRows,
      boolean hasDrawing) {
    List<Double> columnWidths = computeColumnWidths(tableRows);

    StringBuilder xml = new StringBuilder(256 * 1024);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"");
    if (hasDrawing) {
      xml.append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"");
    }
    xml.append(">");

    writeCols(xml, columnWidths);
    xml.append("<sheetData>");

    int rowIndexOneBased = 1;
    for (List<String> tableRow : tableRows) {
      int rowIndexZeroBased = rowIndexOneBased - 1;
      boolean hasImageRow = imageRows.contains(rowIndexZeroBased);
      writeRow(xml, rowIndexOneBased, rowIndexZeroBased, tableRow, imageByCell, hasImageRow);
      rowIndexOneBased++;
    }

    xml.append("</sheetData>");
    if (hasDrawing) {
      xml.append("<drawing r:id=\"rId1\"/>");
    }
    xml.append("</worksheet>");
    return xml.toString();
  }

  private String buildSheetRelsXml() {
    return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing"
                        Target="../drawings/drawing1.xml"/>
        </Relationships>
        """;
  }

  private String buildDrawingXml(List<ExportImage> images) {
    StringBuilder xml = new StringBuilder(16 * 1024);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    xml.append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" ");
    xml.append("xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ");
    xml.append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");

    int id = 1;
    for (ExportImage image : images) {
      xml.append("<xdr:twoCellAnchor editAs=\"oneCell\">");
      xml.append("<xdr:from>")
          .append("<xdr:col>").append(image.colIndex()).append("</xdr:col>")
          .append("<xdr:colOff>0</xdr:colOff>")
          .append("<xdr:row>").append(image.rowIndex()).append("</xdr:row>")
          .append("<xdr:rowOff>0</xdr:rowOff>")
          .append("</xdr:from>");
      xml.append("<xdr:to>")
          .append("<xdr:col>").append(image.colIndex() + 1).append("</xdr:col>")
          .append("<xdr:colOff>0</xdr:colOff>")
          .append("<xdr:row>").append(image.rowIndex() + 1).append("</xdr:row>")
          .append("<xdr:rowOff>0</xdr:rowOff>")
          .append("</xdr:to>");
      xml.append("<xdr:pic>");
      xml.append("<xdr:nvPicPr>")
          .append("<xdr:cNvPr id=\"").append(id).append("\" name=\"").append(xmlEscape(image.mediaName())).append("\"/>")
          .append("<xdr:cNvPicPr/>")
          .append("</xdr:nvPicPr>");
      xml.append("<xdr:blipFill>")
          .append("<a:blip r:embed=\"").append(xmlEscape(image.relId())).append("\"/>")
          .append("<a:stretch><a:fillRect/></a:stretch>")
          .append("</xdr:blipFill>");
      xml.append("<xdr:spPr>")
          .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>")
          .append("</xdr:spPr>");
      xml.append("</xdr:pic>");
      xml.append("<xdr:clientData/>");
      xml.append("</xdr:twoCellAnchor>");
      id++;
    }

    xml.append("</xdr:wsDr>");
    return xml.toString();
  }

  private String buildDrawingRelsXml(List<ExportImage> images) {
    StringBuilder xml = new StringBuilder(1024);
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
    xml.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
    for (ExportImage image : images) {
      xml.append("<Relationship Id=\"")
          .append(xmlEscape(image.relId()))
          .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/")
          .append(xmlEscape(image.mediaName()))
          .append("\"/>");
    }
    xml.append("</Relationships>");
    return xml.toString();
  }

  private void writeCols(StringBuilder xml, List<Double> widths) {
    if (widths == null || widths.isEmpty()) {
      return;
    }
    xml.append("<cols>");
    for (int i = 0; i < widths.size(); i++) {
      xml.append("<col min=\"")
          .append(i + 1)
          .append("\" max=\"")
          .append(i + 1)
          .append("\" width=\"")
          .append(String.format(Locale.US, "%.2f", widths.get(i)))
          .append("\" customWidth=\"1\"/>");
    }
    xml.append("</cols>");
  }

  private List<Double> computeColumnWidths(List<List<String>> rows) {
    int columnCount = HEADERS.length;
    int[] maxDisplay = new int[columnCount];
    for (List<String> row : rows) {
      for (int col = 0; col < columnCount; col++) {
        String value = (row != null && col < row.size()) ? row.get(col) : "";
        int width = displayLength(value);
        if (width > maxDisplay[col]) {
          maxDisplay[col] = width;
        }
      }
    }

    List<Double> widths = new ArrayList<>(columnCount);
    for (int col = 0; col < columnCount; col++) {
      double width = maxDisplay[col] + COLUMN_PADDING;
      if (width < MIN_COLUMN_WIDTH) {
        width = MIN_COLUMN_WIDTH;
      }
      if (col == SIGN_IMAGE_COL_INDEX && width < SIGN_COLUMN_MIN_WIDTH) {
        width = SIGN_COLUMN_MIN_WIDTH;
      }
      if (width > MAX_COLUMN_WIDTH) {
        width = MAX_COLUMN_WIDTH;
      }
      widths.add(width);
    }
    return widths;
  }

  private int displayLength(String value) {
    String input = nullToEmpty(value);
    if (input.isEmpty()) {
      return 0;
    }
    int maxLine = 0;
    int current = 0;
    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i);
      if (ch == '\r') {
        continue;
      }
      if (ch == '\n') {
        if (current > maxLine) {
          maxLine = current;
        }
        current = 0;
        continue;
      }
      if (ch == '\t') {
        current += 4;
      } else if (ch <= 0x007F) {
        current += 1;
      } else {
        current += 2;
      }
    }
    if (current > maxLine) {
      maxLine = current;
    }
    return maxLine;
  }

  private void writeRow(
      StringBuilder xml,
      int rowIndexOneBased,
      int rowIndexZeroBased,
      List<String> values,
      Map<String, ExportImage> imageByCell,
      boolean hasImageRow) {
    xml.append("<row r=\"").append(rowIndexOneBased).append("\"");
    if (hasImageRow && rowIndexOneBased > 1) {
      xml.append(" ht=\"").append(String.format(Locale.US, "%.1f", IMAGE_ROW_HEIGHT)).append("\" customHeight=\"1\"");
    }
    xml.append(">");

    for (int i = 0; i < values.size(); i++) {
      String cellRef = toColumnLetters(i + 1) + rowIndexOneBased;
      String raw = nullToEmpty(values.get(i));
      boolean hasImageInCell = imageByCell.containsKey(cellKey(rowIndexZeroBased, i));
      if (hasImageInCell) {
        xml.append("<c r=\"").append(cellRef).append("\" t=\"inlineStr\" s=\"1\"><is><t></t></is></c>");
        continue;
      }

      if (i == SIGN_IMAGE_COL_INDEX && isFormula(raw)) {
        String formula = xmlEscape(stripFormulaPrefix(raw));
        xml.append("<c r=\"").append(cellRef).append("\" s=\"1\">");
        xml.append("<f>").append(formula).append("</f>");
        xml.append("</c>");
      } else {
        String text = xmlEscape(raw);
        xml.append("<c r=\"").append(cellRef).append("\" t=\"inlineStr\" s=\"1\">");
        xml.append("<is><t>").append(text).append("</t></is>");
        xml.append("</c>");
      }
    }
    xml.append("</row>");
  }

  private String cellKey(int rowIndex, int colIndex) {
    return rowIndex + ":" + colIndex;
  }

  private boolean isFormula(String value) {
    if (value == null) {
      return false;
    }
    return value.trim().startsWith("=");
  }

  private String stripFormulaPrefix(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("=")) {
      return trimmed.substring(1);
    }
    return trimmed;
  }

  private String toColumnLetters(int oneBasedColumn) {
    int value = oneBasedColumn;
    StringBuilder out = new StringBuilder();
    while (value > 0) {
      int rem = (value - 1) % 26;
      out.insert(0, (char) ('A' + rem));
      value = (value - 1) / 26;
    }
    return out.toString();
  }

  private String mediaContentType(String ext) {
    String lower = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "jpg", "jpeg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "bmp" -> "image/bmp";
      case "webp" -> "image/webp";
      case "svg" -> "image/svg+xml";
      case "emf" -> "image/x-emf";
      case "wmf" -> "image/x-wmf";
      case "tif", "tiff" -> "image/tiff";
      default -> "image/png";
    };
  }

  private String extensionOf(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return "png";
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return "png";
    }
    String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT).trim();
    return switch (ext) {
      case "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "emf", "wmf", "tif", "tiff" -> ext;
      default -> "png";
    };
  }

  private String xmlEscape(String value) {
    String input = nullToEmpty(value);
    StringBuilder out = new StringBuilder(input.length() + 16);
    for (int i = 0; i < input.length(); i++) {
      char ch = input.charAt(i);
      switch (ch) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> out.append(ch);
      }
    }
    return out.toString();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private record SheetBuild(List<List<String>> rows, List<ExportImage> images) {
  }

  private record ExportImage(
      int rowIndex,
      int colIndex,
      String relId,
      String mediaName,
      String extension,
      byte[] bytes) {
  }
}
