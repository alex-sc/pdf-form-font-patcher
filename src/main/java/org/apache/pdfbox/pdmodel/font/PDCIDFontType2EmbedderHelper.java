package org.apache.pdfbox.pdmodel.font;

import com.itextpdf.io.font.*;
import com.itextpdf.io.font.TrueTypeFont;
import org.apache.fontbox.ttf.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class PDCIDFontType2EmbedderHelper {

    public static void embedPDCIDFontType2(PDDocument document, PDFont font, OpenTypeFont ttf,
                                           boolean vertical, Set<Integer> codePoints) throws IOException {

        var postScriptTable = new PostScriptTableHelper();
        ttf.getTableMap().put(PostScriptTable.TAG, postScriptTable);

        var cmap = font.getToUnicodeCMap();

        CmapSubtable cmapSubtable = new CmapSubtableHelper(cmap);
        cmapSubtable.setPlatformId(CmapTable.PLATFORM_UNICODE);
        cmapSubtable.setPlatformEncodingId(CmapTable.ENCODING_UNICODE_2_0_FULL);

        var cmapTable = new CmapTableHelper(null);
        cmapTable.setCmaps(new CmapSubtable[] {cmapSubtable});
        ttf.getTableMap().put(CmapTable.TAG, cmapTable);

        var dict = font.getCOSObject();
        var embedded = new PDCIDFontType2Embedder(document, dict, ttf, true, (PDType0Font) font, vertical);
        for (Integer codePoint : codePoints) {
            embedded.addToSubset(codePoint);
        }
        embedded.subset();
    }

    public static PDStream embedTrueTypeFont(PDTrueTypeFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var bytes = PdfFontPatcher.getFontBytes(font);
        Files.write(Path.of("font-before.ttf"), bytes);

        TrueTypeFont fpf = (TrueTypeFont) FontProgramFactory.createFont(bytes);
        Set<Integer> usedGlyphs = new HashSet<>();
        for (Integer usedCode : usedCodes) {
            usedGlyphs.add(font.codeToGID(usedCode));
        }

        var newBytes2 = fpf.getSubset(usedGlyphs, false);

        System.out.println("From " + bytes.length + " to " + newBytes2.length);
        Files.write(Path.of("font-after.ttf"), newBytes2);

        return PdfFontPatcher.setFontBytes(font, doc, newBytes2);
    }
}
