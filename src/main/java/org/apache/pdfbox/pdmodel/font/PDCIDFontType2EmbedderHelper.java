package org.apache.pdfbox.pdmodel.font;

import org.apache.fontbox.ttf.*;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.HashMap;
import java.util.Set;

public class PDCIDFontType2EmbedderHelper {

    public static byte[] embedPDCIDFontType2(PDDocument document, PDFont font, OpenTypeFont ttf,
        boolean vertical, Set<Integer> codePoints) throws IOException {

        var postScriptTable = new PostScriptTableHelper(ttf);
        ttf.getTableMap().put(PostScriptTable.TAG, postScriptTable);

        var cmap = font.getToUnicodeCMap();

        CmapSubtable cmapSubtable = new CmapSubtableHelper(cmap);
        cmapSubtable.setPlatformId(CmapTable.PLATFORM_UNICODE);
        cmapSubtable.setPlatformEncodingId(CmapTable.ENCODING_UNICODE_2_0_FULL);

        var cmapTable = new CmapTableHelper(null);
        cmapTable.setCmaps(new CmapSubtable[] {cmapSubtable});
        ttf.getTableMap().put(CmapTable.TAG, cmapTable);

        var dict = font.getCOSObject();
        var embedded = new PDCIDFontType2Embedder(document, dict, ttf, true, null, vertical);
        for (Integer codePoint : codePoints) {
            embedded.addToSubset(codePoint);
        }
        embedded.subset();

        return new PDType0Font(dict).getFontDescriptor().getFontFile2().toByteArray();
    }
}
