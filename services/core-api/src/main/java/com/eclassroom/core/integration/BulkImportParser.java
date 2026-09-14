package com.eclassroom.core.integration;

import com.eclassroom.core.shared.api.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class BulkImportParser {
    public static final int MAX_ROWS = 5000;

    public List<RawRow> parse(String format, byte[] bytes) {
        try {
            List<RawRow> rows = "CSV".equals(format) ? parseCsv(bytes) : parseXlsx(bytes);
            if (rows.size() > MAX_ROWS) throw ApiException.badRequest("IMPORT_TOO_MANY_ROWS", "Import files may contain at most 5000 data rows");
            if (rows.isEmpty()) throw ApiException.badRequest("IMPORT_EMPTY_FILE", "Import file contains no data rows");
            return rows;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.badRequest("IMPORT_PARSE_FAILED", "The import file could not be parsed");
        }
    }

    private List<RawRow> parseCsv(byte[] bytes) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .get();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            Map<String, Integer> rawHeaders = parser.getHeaderMap();
            if (rawHeaders.isEmpty()) throw ApiException.badRequest("IMPORT_HEADER_REQUIRED", "CSV header row is required");
            validateHeaders(rawHeaders.keySet().stream().map(this::normalizeHeader).toList());
            List<RawRow> result = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> values = new LinkedHashMap<>();
                for (String header : rawHeaders.keySet()) values.put(normalizeHeader(header), record.isMapped(header) ? record.get(header).trim() : "");
                if (values.values().stream().allMatch(String::isBlank)) continue;
                result.add(new RawRow((int) record.getRecordNumber() + 1, values));
            }
            return result;
        }
    }

    private List<RawRow> parseXlsx(byte[] bytes) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw ApiException.badRequest("IMPORT_SHEET_REQUIRED", "Workbook must contain a worksheet");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) throw ApiException.badRequest("IMPORT_HEADER_REQUIRED", "Spreadsheet header row is required");
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++) {
                Cell cell = header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                headers.add(normalizeHeader(cell == null ? "" : formatter.formatCellValue(cell)));
            }
            validateHeaders(headers);
            List<RawRow> result = new ArrayList<>();
            for (int r = header.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row data = sheet.getRow(r);
                if (data == null) continue;
                Map<String, String> values = new LinkedHashMap<>();
                boolean blank = true;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = data.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                    values.put(headers.get(c), value);
                    if (!value.isBlank()) blank = false;
                }
                if (!blank) result.add(new RawRow(r + 1, values));
            }
            return result;
        }
    }

    private void validateHeaders(List<String> headers) {
        if (headers.isEmpty() || headers.size() > 64) throw ApiException.badRequest("IMPORT_HEADERS_INVALID", "Import must contain 1-64 columns");
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            if (header.isBlank() || !seen.add(header)) throw ApiException.badRequest("IMPORT_HEADERS_INVALID", "Headers must be unique and non-empty");
        }
    }

    private String normalizeHeader(String value) {
        String header = value == null ? "" : value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
        return header.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    public record RawRow(int rowNumber, Map<String, String> values) {}
}
