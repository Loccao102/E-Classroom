package com.eclassroom.core.integration;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class TabularExportWriter {
    public byte[] write(String format,List<String> headers,List<List<?>> rows){return "XLSX".equals(format)?xlsx(headers,rows):csv(headers,rows);}

    private byte[] csv(List<String> headers,List<List<?>> rows){
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});try(OutputStreamWriter writer=new OutputStreamWriter(out,StandardCharsets.UTF_8);CSVPrinter printer=new CSVPrinter(writer,CSVFormat.DEFAULT)){printer.printRecord(headers);for(List<?> row:rows)printer.printRecord(row);}return out.toByteArray();}
        catch(Exception ex){throw new IllegalStateException("Cannot generate CSV export",ex);}
    }

    private byte[] xlsx(List<String> headers,List<List<?>> rows){
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet sheet=workbook.createSheet("Data");Row head=sheet.createRow(0);for(int i=0;i<headers.size();i++)head.createCell(i).setCellValue(headers.get(i));
            int index=1;for(List<?> values:rows){Row row=sheet.createRow(index++);for(int i=0;i<values.size();i++){Object value=values.get(i);if(value!=null)row.createCell(i).setCellValue(String.valueOf(value));}}
            for(int i=0;i<headers.size();i++)sheet.autoSizeColumn(i);workbook.write(out);return out.toByteArray();
        }catch(Exception ex){throw new IllegalStateException("Cannot generate XLSX export",ex);}
    }
}
