package com.eclassroom.core.integration;

import com.eclassroom.core.shared.api.ApiException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class TabularFileService {
    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_ROWS = 10_000;

    public ParseSummary parse(String format, byte[] bytes, RowConsumer consumer) {
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("IMPORT_EMPTY_FILE", "Import file is empty");
        if (bytes.length > MAX_FILE_BYTES) throw ApiException.badRequest("IMPORT_FILE_TOO_LARGE", "Import files are limited to 10 MB");
        try {
            return "XLSX".equalsIgnoreCase(format) ? parseXlsx(bytes, consumer) : parseCsv(bytes, consumer);
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw ApiException.badRequest("IMPORT_PARSE_FAILED", "The uploaded file could not be parsed"); }
    }

    private ParseSummary parseCsv(byte[] bytes, RowConsumer consumer) throws Exception {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true).get().parse(reader)) {
            List<String> headers = headers(parser.getHeaderNames());
            int count = 0;
            for (CSVRecord record : parser) {
                if (blank(record)) continue;
                if (++count > MAX_ROWS) throw ApiException.badRequest("IMPORT_TOO_MANY_ROWS", "A single import may contain at most 10,000 rows");
                LinkedHashMap<String,String> values = new LinkedHashMap<>();
                for (int i=0;i<headers.size();i++) values.put(headers.get(i), i < record.size() ? record.get(i).trim() : "");
                consumer.accept((int)record.getRecordNumber()+1, values);
            }
            return new ParseSummary(headers,count);
        }
    }

    private ParseSummary parseXlsx(byte[] bytes, RowConsumer consumer) throws Exception {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets()==0) throw ApiException.badRequest("IMPORT_EMPTY_FILE", "Workbook has no sheets");
            Sheet sheet=workbook.getSheetAt(0); Row head=sheet.getRow(sheet.getFirstRowNum());
            if (head==null) throw ApiException.badRequest("IMPORT_HEADERS_REQUIRED", "The first row must contain headers");
            DataFormatter formatter=new DataFormatter(Locale.ROOT); List<String> raw=new ArrayList<>();
            for (int i=0;i<head.getLastCellNum();i++) raw.add(formatter.formatCellValue(head.getCell(i)));
            List<String> headers=headers(raw); int count=0;
            for(int r=head.getRowNum()+1;r<=sheet.getLastRowNum();r++){
                Row row=sheet.getRow(r); if(row==null) continue; LinkedHashMap<String,String> values=new LinkedHashMap<>(); boolean any=false;
                for(int c=0;c<headers.size();c++){String value=formatter.formatCellValue(row.getCell(c)).trim(); if(!value.isBlank()) any=true; values.put(headers.get(c),value);}
                if(!any) continue; if(++count>MAX_ROWS) throw ApiException.badRequest("IMPORT_TOO_MANY_ROWS", "A single import may contain at most 10,000 rows");
                consumer.accept(r+1,values);
            }
            return new ParseSummary(headers,count);
        }
    }

    public byte[] write(String format,List<String> headers,Iterable<List<?>> rows){
        try{return "XLSX".equalsIgnoreCase(format)?writeXlsx(headers,rows):writeCsv(headers,rows);}catch(IOException ex){throw new IllegalStateException("Export failed",ex);}
    }
    private byte[] writeCsv(List<String> headers,Iterable<List<?>> rows)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
        try(Writer writer=new OutputStreamWriter(out,StandardCharsets.UTF_8);CSVPrinter printer=new CSVPrinter(writer,CSVFormat.DEFAULT)){printer.printRecord(headers);for(List<?> row:rows)printer.printRecord(row);}return out.toByteArray();
    }
    private byte[] writeXlsx(List<String> headers,Iterable<List<?>> rows)throws IOException{
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){Sheet sheet=workbook.createSheet("Data");Row head=sheet.createRow(0);for(int i=0;i<headers.size();i++)head.createCell(i).setCellValue(headers.get(i));int r=1;for(List<?> values:rows){Row row=sheet.createRow(r++);for(int i=0;i<values.size();i++)if(values.get(i)!=null)row.createCell(i).setCellValue(String.valueOf(values.get(i)));}workbook.write(out);return out.toByteArray();}
    }
    private List<String> headers(List<String> raw){if(raw==null||raw.isEmpty()||raw.size()>64)throw ApiException.badRequest("IMPORT_HEADERS_INVALID","Import must contain 1-64 columns");List<String> out=new ArrayList<>();Set<String> seen=new HashSet<>();for(String value:raw){String h=value==null?"":value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");if(h.isBlank()||!seen.add(h))throw ApiException.badRequest("IMPORT_HEADERS_INVALID","Headers must be unique and non-empty");out.add(h);}return out;}
    private boolean blank(CSVRecord record){for(String value:record)if(value!=null&&!value.trim().isBlank())return false;return true;}
    @FunctionalInterface public interface RowConsumer{void accept(int rowNumber,LinkedHashMap<String,String> row)throws Exception;}
    public record ParseSummary(List<String> headers,int rowCount){}
}
