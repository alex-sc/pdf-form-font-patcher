package com.itextpdf.io.font;

import com.github.alexsc.pdf.CustomPdfRenderer;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;

import java.io.*;
import java.util.*;

public class PdfFontPatcher {

    private PdfFontPatcher() {
        // Utility class
    }

    public static void main(String[] args) throws IOException {
        var input = new File("form.pdf");
        var output = new File("form-patched.pdf");
        optimizeFonts(input, output);
    }

    public static void optimizeFonts(File input, File output) throws IOException {
        System.out.println("Processing " + input);
        PDDocument doc = PDDocument.load(input);

        // Remove forms fields' default appearance
        var acroForm = doc.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            for (Iterator<PDField> it = acroForm.getFieldIterator(); it.hasNext(); ) {
                PDField field = it.next();
                var widgets = field.getWidgets();
                if (widgets != null && !widgets.isEmpty()) {
                    widgets.get(0).getCOSObject().removeItem(COSName.DA);
                }
            }

            // Remove form fonts
            var formResources = acroForm.getDefaultResources();
            COSDictionary formFonts = formResources.getCOSObject().getCOSDictionary(COSName.FONT);
            for (var fontName : doc.getDocumentCatalog().getAcroForm().getDefaultResources().getFontNames()) {
                formFonts.removeItem(fontName);
            }
        }

        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            var page = doc.getPage(i);

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
                var streamSize = getFontStreamSize(font);
                if (streamSize == 0) {
                    continue;
                }

                var key = font.getName();
                var used = usedCodes.get(key);
                if (used != null) {
                    if (!key.startsWith("BCZRWE")) {
                        //continue;
                    }
                    try {
                        var patchedStream = patchedStreamMap.get(key);
                        if (patchedStream != null) {
                            font.getFontDescriptor().setFontFile3(patchedStream);
                        } else {
                            System.out.println("Optimizing used font " + font.getName() + " " + used.size() + ": " + name + " " + streamSize);
                            var patched = optimizeFont(font, doc, used);
                            if (patched != null) {
                                patchedStreamMap.put(key, patched);
                            }
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

        doc.save(output);

        System.out.println("Optimized file " + input.getName()  + " from " + input.length() + " to " + output.length());
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

    private static PDStream optimizeFont(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        if (!(font instanceof PDType0Font)) {
            return null;
        }

        var descriptor = font.getFontDescriptor();
        var fontFile3 = descriptor.getFontFile3();

        // Type FontFile3
        // Subtype CIDFontType0C or Type1C
        if (fontFile3 != null && "CIDFontType0C".equals(fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE))) {
            System.out.println("Processing CIDFontType0C font " + font.getName());
            return optimizeCIDFontType0C(font, doc, usedCodes);
        }

        //
        var fontFile2 = descriptor.getFontFile2();
        if (fontFile2 != null) {
            System.out.println("Processing CIDTrueType font " + font.getName() + " with used glyph count = " + usedCodes.size() + " and fontFile size = " + getFontStreamSize(font));
            optimizeCIDTrueType(font, doc, usedCodes);
            return null;
        }

        System.err.println("Unexpected font " + font.getName());

        return null;
    }

    private static void optimizeCIDTrueType(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var bytes = getFontBytes(font);

        var otfParser = new OTFParser(true);
        OpenTypeFont otfFont = otfParser.parse(new ByteArrayInputStream(bytes));
        PDCIDFontType2EmbedderHelper.embedPDCIDFontType2(doc, font, otfFont, font.isVertical(), usedCodes);

        System.out.println("From " + bytes.length + " to " + getFontBytes(font).length);
    }

    private static PDStream optimizeCIDFontType0C(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var descriptor = font.getFontDescriptor();
        var bytes = getFontBytes(font);
        var fontFile = descriptor.getFontFile3();

        // Temp subset just to get the number of glyphs
        var tmpSubset = new CFFFontSubset(bytes, Set.of(0), true);
        var fdSelect = tmpSubset.fonts[0].FDSelect;
        var allGlyphs = new HashSet<Integer>();
        // TODO: is this okay?
        for (int i = 0; i < fdSelect.length; i++) {
            //if (usedCodes.contains(tmpSubset.fonts[0].gidToCid[i])) {
                allGlyphs.add(i);
            //}
        }

        // Rebuild the font
        var subsetBytes = new CFFFontSubset(bytes, allGlyphs).Process();

        // Put the stream back
        PDStream stream = new PDStream(doc);
        stream.getCOSObject().setName(COSName.SUBTYPE, fontFile.getCOSObject().getNameAsString(COSName.SUBTYPE));
        var os = stream.createOutputStream(COSName.FLATE_DECODE);
        os.write(subsetBytes);
        os.close();

        System.out.println("From " + bytes.length + " to " + subsetBytes.length);

        font.getFontDescriptor().setFontFile3(stream);

        return stream;
    }

    private static byte[] getFontBytes(PDFont font) throws IOException {
        var descriptor = font.getFontDescriptor();
        return Arrays.asList(descriptor.getFontFile(), descriptor.getFontFile2(), descriptor.getFontFile3())
                .stream()
                .filter(Objects::nonNull)
                .findFirst().get().toByteArray();

    }
}
