package com.eclassroom.core;

import com.eclassroom.core.integration.BulkImportParser;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BulkImportParserTest {
    private final BulkImportParser parser = new BulkImportParser();

    @Test
    void csvHandlesExcelBomAndNormalizesHeaders() {
        byte[] bytes=("\uFEFFStudent Code,Full Name,Date of Birth\r\nHS001,Nguyen Van A,2010-01-02\r\n").getBytes(StandardCharsets.UTF_8);
        var rows=parser.parse("CSV",bytes);
        assertEquals(1,rows.size());
        assertEquals("HS001",rows.getFirst().values().get("student_code"));
        assertEquals("Nguyen Van A",rows.getFirst().values().get("full_name"));
    }

    @Test
    void xlsxReadsFirstWorksheet() throws Exception {
        byte[] bytes;
        try(Workbook workbook=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("Students");var head=sheet.createRow(0);head.createCell(0).setCellValue("student_code");head.createCell(1).setCellValue("full_name");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("HS002");row.createCell(1).setCellValue("Tran Thi B");workbook.write(out);bytes=out.toByteArray();
        }
        var rows=parser.parse("XLSX",bytes);
        assertEquals("HS002",rows.getFirst().values().get("student_code"));
    }

    @Test
    void rejectsDuplicateHeadersAndEmptyData() {
        assertThrows(RuntimeException.class,()->parser.parse("CSV","student_code,student_code\nA,B\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(RuntimeException.class,()->parser.parse("CSV","student_code,full_name\n".getBytes(StandardCharsets.UTF_8)));
    }
}
