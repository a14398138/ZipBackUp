package io.github.a14398138.zipbackup;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.enums.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class BackupSafetyTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final char[] password="correct horse battery staple".toCharArray();
    private File zip() throws Exception {
        File file=temp.newFile();
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(file),password)) {
            out.putNextEntry(Archive.parameters("写真/テスト.txt",false));
            out.write("写真のバックアップを復元できる".getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return file;
    }
    @Test public void aes256RoundTripAndVerify() throws Exception {
        File file=zip(); Archive.verify(file,password,()->{});
        try(ZipFile z=new ZipFile(file,password)) {
            FileHeader h=z.getFileHeaders().get(0);
            assertEquals(EncryptionMethod.AES,h.getEncryptionMethod());
            assertEquals(AesKeyStrength.KEY_STRENGTH_256,h.getAesExtraDataRecord().getAesKeyStrength());
            try(InputStream in=z.getInputStream(h)) { assertEquals("写真のバックアップを復元できる",new String(in.readAllBytes(),StandardCharsets.UTF_8)); }
        }
    }
    @Test public void wrongPasswordIsRejected() throws Exception {
        File file=zip(); assertThrows(Exception.class,()->Archive.verify(file,"wrong password".toCharArray(),()->{}));
    }
    @Test public void modifiedCiphertextIsRejected() throws Exception {
        File file=zip();
        try(RandomAccessFile f=new RandomAccessFile(file,"rw")) {
            f.seek(26); int name=f.readUnsignedByte()|f.readUnsignedByte()<<8;
            int extra=f.readUnsignedByte()|f.readUnsignedByte()<<8;
            long position=30+name+extra+16+2+3; f.seek(position); int b=f.read(); f.seek(position); f.write(b^1);
        }
        assertThrows(Exception.class,()->Archive.verify(file,password,()->{}));
    }
    @Test public void plainZipIsRejected() throws Exception {
        File file=temp.newFile();
        try(java.util.zip.ZipOutputStream z=new java.util.zip.ZipOutputStream(new FileOutputStream(file))) {
            z.putNextEntry(new java.util.zip.ZipEntry("plain.txt")); z.write(1); z.closeEntry();
        }
        assertThrows(IOException.class,()->Archive.verify(file,password,()->{}));
    }
    @Test public void rejectsTraversalAbsoluteAndAmbiguousPaths() {
        for(String name:Arrays.asList("../secret","/etc/passwd","a/../../b","a\\b","C:/test","a//b","a/./b","a\u0000b",""))
            assertThrows(name,IOException.class,()->ArchiveRules.path(name));
    }
    @Test public void unicodePathIsPreserved() throws Exception { assertArrayEquals(new String[]{"写真","旅行.jpg"},ArchiveRules.path("写真/旅行.jpg")); }
    @Test public void rejectsFileDirectoryAndCaseCollisions() throws Exception {
        ArchiveRules.PathIndex first=new ArchiveRules.PathIndex(); first.add("a",false);
        assertThrows(IOException.class,()->first.add("a/b",false));
        ArchiveRules.PathIndex second=new ArchiveRules.PathIndex(); second.add("Photo/a.jpg",false);
        assertThrows(IOException.class,()->second.add("photo/b.jpg",false));
        ArchiveRules.PathIndex third=new ArchiveRules.PathIndex(); third.add("a/",true); third.add("a/b",false);
    }
    @Test public void keepSevenAndNeverDeleteAll() {
        List<String> ids=Arrays.asList("9","8","7","6","5","4","3","2","1");
        assertEquals(Arrays.asList("2","1"),ArchiveRules.expired(ids,7));
        assertTrue(ArchiveRules.expired(ids.subList(0,3),7).isEmpty());
        assertThrows(IllegalArgumentException.class,()->ArchiveRules.expired(ids,0));
    }
    @Test public void wifiOnlyDoesNotPermitCellularEvenWhenUnmetered() {
        assertFalse(NetworkPolicy.allows(false,true,false,true));
        assertFalse(NetworkPolicy.allows(false,true,false,false));
        assertFalse(NetworkPolicy.allows(false,true,true,false));
        assertTrue(NetworkPolicy.allows(false,true,true,true));
    }
    @Test public void cellularRequiresOptInAndValidatedConnection() {
        assertTrue(NetworkPolicy.allows(true,true,false,false));
        assertFalse(NetworkPolicy.allows(true,false,false,false));
    }
    @Test public void cancellationInterruptsStreaming() throws Exception {
        assertThrows(IOException.class,()->Archive.copy(new ByteArrayInputStream(new byte[100]),new ByteArrayOutputStream(),()->{throw new IOException("cancel");},1000));
    }
    @Test public void oversizedExtractionStopsBeforeWriting() throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        assertThrows(IOException.class,()->Archive.copy(new ByteArrayInputStream(new byte[100]),out,()->{},10));
        assertEquals(0,out.size());
    }
}
