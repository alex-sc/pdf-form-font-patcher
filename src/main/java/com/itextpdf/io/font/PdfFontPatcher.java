package com.itextpdf.io.font;

import com.github.alexsc.pdf.PdfContentStreamEditor;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.*;
import java.util.*;

public class PdfFontPatcher {
    private static final Map<String, Set<Integer>> USED_CODES = new HashMap<>();

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

         /*
        var formResources = doc.getDocumentCatalog().getAcroForm().getDefaultResources();
        formResources.getCOSObject().setNeedToBeUpdated(true);
        COSDictionary formFonts = formResources.getCOSObject().getCOSDictionary(COSName.FONT);
        for (var fontName : doc.getDocumentCatalog().getAcroForm().getDefaultResources().getFontNames()) {
            formFonts.removeItem(fontName);
        }
         */

        PdfCharCodePatcher patcher = new PdfCharCodePatcher(doc.getPage(0), doc);
        patcher.processPage(doc.getPage(0));

        for (PDPage page : doc.getPages()) {
            page.getCOSObject().setNeedToBeUpdated(true);
            PDResources pageResources = page.getResources();
            COSDictionary pageFonts = pageResources.getCOSObject().getCOSDictionary(COSName.FONT);
            pageFonts.setNeedToBeUpdated(true);

            for (COSName name : pageFonts.keySet()) {
                PDFont font = pageResources.getFont(name);
                var used = USED_CODES.remove(font.getName());
                if (used != null) {
                    try {
                        System.err.println("Subsetting " + font.getName() + " " + used.size() + ": " + name);
                        subsetFont(font, doc, used);
                    } catch (Exception e) {
                        // Ignore
                        e.printStackTrace();
                    }
                }
            }
        }

        doc.save(new File("form-patched.pdf"));
    }

    public static class PdfCharCodePatcher extends PdfContentStreamEditor {
        public PdfCharCodePatcher(PDPage page, PDDocument document) {
            super(page, document);
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
            var usedChars = USED_CODES.computeIfAbsent(font.getName(), k -> new HashSet<>());
            usedChars.add(code);

            super.showGlyph(textRenderingMatrix, font, code, displacement);
        }

        @Override
        protected void write(ContentStreamWriter contentStreamWriter, Operator operator, List<COSBase> operands) throws IOException {
            super.write(contentStreamWriter, operator, operands);
        }
    }

    private static PDStream subsetFont(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var descriptor = font.getFontDescriptor();
        var fontFile3 = descriptor.getFontFile3();
        if (fontFile3 == null || !"CIDFontType0C".equals(fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE))) {
            System.err.println("Skipping " + font.getName());
            return null;
        }
        System.err.println("Processing " + font.getName());

        //System.err.println(font.getClass());
        //System.err.println("fontFile: " + (descriptor.getFontFile() != null));
        //System.err.println("fontFile2: " + (descriptor.getFontFile2() != null));
        //System.err.println("fontFile3: " + (descriptor.getFontFile3() != null));

        font.getCOSObject().setNeedToBeUpdated(true);
        descriptor.getCOSObject().setNeedToBeUpdated(true);
        var bytes = fontFile3.toByteArray();

        var tmpSubset = new CFFFontSubset(bytes, Set.of(0111));
        System.err.println(tmpSubset.glyphsInList);

        var subsetBytes2 = new CFFFontSubset(bytes, Set.of(0, 1)).Process();

        PDStream stream = new PDStream(doc);
        stream.getCOSObject().setName(COSName.SUBTYPE, fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE));
        var os = stream.createOutputStream(COSName.FLATE_DECODE);
        os.write(subsetBytes2);
        os.close();

        font.getFontDescriptor().setFontFile3(stream);

        return stream;
    }
}
