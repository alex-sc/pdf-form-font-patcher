package com.itextpdf.io.font;

import com.github.alexsc.pdf.CustomPdfRenderer;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.*;
import java.util.*;

public class PdfFontPatcher {

    private PdfFontPatcher() {
        // Utility class
    }

    public static void main(String[] args) throws IOException {
        PDDocument doc = PDDocument.load(new File("form.pdf"));

        // Remove forms fields' default appearance
        for (Iterator<PDField> it = doc.getDocumentCatalog().getAcroForm().getFieldIterator(); it.hasNext(); ) {
            PDField field = it.next();
            var widgets = field.getWidgets();
            if (widgets != null && !widgets.isEmpty()) {
                widgets.get(0).getCOSObject().removeItem(COSName.DA);
            }
        }

        // Remove form fonts
        var formResources = doc.getDocumentCatalog().getAcroForm().getDefaultResources();
        COSDictionary formFonts = formResources.getCOSObject().getCOSDictionary(COSName.FONT);
        for (var fontName : doc.getDocumentCatalog().getAcroForm().getDefaultResources().getFontNames()) {
            formFonts.removeItem(fontName);
        }

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            var page = doc.getPage(i);
            System.err.println(page.getAnnotations().size() + " annos");

            // Detect actually used fonts
            //PdfGlyphUsageCounter usageCounter = new PdfGlyphUsageCounter(page, doc);
            //usageCounter.processPage(page);

            CustomPdfRenderer renderer = new CustomPdfRenderer(doc);
            renderer.renderImage(i);
            var usedCodes = renderer.getUsedCodes();
            var patchedStreamMap = new HashMap<String, PDStream>();

            PDResources pageResources = page.getResources();
            COSDictionary pageFonts = pageResources.getCOSObject().getCOSDictionary(COSName.FONT);

            for (COSName name : pageFonts.keySet()) {
                PDFont font = pageResources.getFont(name);
                var key = font.getName();
                var used = usedCodes.get(key);
                if (used != null) {
                    try {
                        var patchedStream = patchedStreamMap.get(key);
                        if (patchedStream != null) {
                            font.getFontDescriptor().setFontFile3(patchedStream);
                        } else {
                            System.err.println("Subsetting used font " + font.getName() + " " + used.size() + ": " + name);
                            var patched = subsetFont(font, doc, used);
                            patchedStreamMap.put(key, patched);
                        }
                    } catch (Exception e) {
                        // Ignore
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("Removing unused font " + font.getName() + " " + name + ": " + getFontStreamSize(font));
                    pageFonts.removeItem(name);
                }
            }
        }

        doc.save(new File("form-patched.pdf"));
    }

    private static int getFontStreamSize(PDFont font) throws IOException {
        var descriptor = font.getFontDescriptor();
        var fontFile = descriptor.getFontFile();
        if (fontFile != null) {
            return fontFile.toByteArray().length;
        }
        var fontFile2 = descriptor.getFontFile2();
        if (fontFile2 != null) {
            return fontFile2.toByteArray().length;
        }
        var fontFile3 = descriptor.getFontFile3();
        if (fontFile3 != null) {
            return fontFile3.toByteArray().length;
        }
        return 0;
    }

    private static PDStream subsetFont(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var descriptor = font.getFontDescriptor();
        var fontFile3 = descriptor.getFontFile3();
        if (fontFile3 == null || !"CIDFontType0C".equals(fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE))) {
            System.err.println("Skipping " + font.getName() + " with size " + getFontStreamSize(font));
            return null;
        }
        System.err.println("Processing " + font.getName());
        var bytes = fontFile3.toByteArray();

        // Temp subset just to get the number of glyphs
        var tmpSubset = new CFFFontSubset(bytes, Set.of(0));
        var fdSelect =  tmpSubset.fonts[0].FDSelect;
        var allGlyphs = new HashSet<Integer>();
        for (int i = 0; i < fdSelect.length; i++) {
            allGlyphs.add(i);
        }

        // Rebuild the font
        var subsetBytes = new CFFFontSubset(bytes, allGlyphs).Process();

        // Put the stream back
        PDStream stream = new PDStream(doc);
        stream.getCOSObject().setName(COSName.SUBTYPE, fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE));
        var os = stream.createOutputStream(COSName.FLATE_DECODE);
        os.write(subsetBytes);
        os.close();

        System.out.println("From " + bytes.length + " to " + subsetBytes.length);

        font.getFontDescriptor().setFontFile3(stream);

        return stream;
    }
}
