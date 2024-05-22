package com.itextpdf.io.font;

import com.github.alexsc.pdf.CustomPdfRenderer;
import com.itextpdf.signatures.DigestAlgorithms;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.*;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.*;
import java.util.stream.Stream;

public class PdfFontPatcher {
    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private PdfFontPatcher() {
        // Utility class
    }

    public static void main(String[] args) throws IOException {
        var input = new File("forms/FDA-431a_Dyn_Sec_Ext_08-25-2022.pdf");
        var output = new File("forms/opt.pdf");
        optimizeFonts(input, output);
    }

    public static void optimizeFonts(File input, File output) throws IOException {
        System.out.println("Processing " + input);
        PDDocument doc = Loader.loadPDF(input);

        var acroForm = doc.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            // Remove forms fields' default appearance
            for (Iterator<PDField> it = acroForm.getFieldIterator(); it.hasNext(); ) {
                PDField field = it.next();
                for (var widget : field.getWidgets()) {
                    widget.getCOSObject().removeItem(COSName.DA);
                }
            }

            // Remove form fonts
            var formResources = acroForm.getDefaultResources();
            COSDictionary formFonts = formResources.getCOSObject().getCOSDictionary(COSName.FONT);
            for (var fontName : doc.getDocumentCatalog().getAcroForm().getDefaultResources().getFontNames()) {
                formFonts.removeItem(fontName);
            }
        }

        // Collect used glyphs on all pages
        CustomPdfRenderer renderer = new CustomPdfRenderer(doc);
        var patchedStreamMap = new HashMap<String, PDStream>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            renderer.renderImage(i);
        }
        var usedCodes = renderer.getUsedCodes();

        // Optimize fonts on all pages
        Set<String> processedStreams = new HashSet<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            System.out.println("Processing page " + i);
            var page = doc.getPage(i);

            PDResources pageResources = page.getResources();
            COSDictionary pageFonts = pageResources.getCOSObject().getCOSDictionary(COSName.FONT);
            if (pageFonts == null) {
                pageFonts = new COSDictionary();
            }

            for (COSName name : pageFonts.keySet()) {
                PDFont font = pageResources.getFont(name);

                var streamSize = getFontStreamSize(font);
                if (streamSize == 0) {
                    // Nothing to optimize
                    continue;
                }

                String key = getFontContentDigest(font);
                if (processedStreams.contains(key)) {
                    continue;
                }
                var used = usedCodes.get(key);
                if (used != null) {
                    try {
                        var patchedStream = patchedStreamMap.get(key);
                        if (patchedStream != null) {
                            System.out.println("Using cache " + font.getName());
                            setFontBytes(font, patchedStream);
                        } else {
                            System.out.println("Optimizing used font " + font.getName() + " " + used.size() + ": " + name + " " + streamSize + " " + used.size() + ": " + key) ;
                            var patched = optimizeFont(font, doc, used);
                            if (patched != null) {
                                patchedStreamMap.put(key, patched);
                            }
                            usedCodes.put(getFontContentDigest(font), usedCodes.get(key));

                            processedStreams.add(getFontContentDigest(font));
                        }
                    } catch (Exception e) {
                        // Ignore
                        e.printStackTrace();
                    }
                } else {
                    System.out.println("Removing unused font " + font.getName() + " " + name + ": " + getFontStreamSize(font) + ": " + key);
                    pageFonts.removeItem(name);
                }
            }
        }

        doc.setAllSecurityToBeRemoved(true);
        doc.save(output);
        doc.close();

        System.out.println("Optimized file " + input.getName()  + " from " + input.length() + " to " + output.length());
    }

    public static String getFontContentDigest(PDFont font) throws IOException {
        try {
            return new BigInteger(DigestAlgorithms.digest(new ByteArrayInputStream(getFontBytes(font)), DigestAlgorithms.SHA1, BouncyCastleProvider.PROVIDER_NAME)).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
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
        if (font instanceof PDTrueTypeFont) {
            System.out.println("Processing TrueType font " + font.getName() + " with used glyph count = " + usedCodes.size() + " and fontFile size = " + getFontStreamSize(font));
            return optimizeTrueTypeFont((PDTrueTypeFont) font, doc, usedCodes);
        }

        if (font instanceof PDType1CFont) {
            System.out.println("Processing Type1C font " + font.getName() + " with used glyph count = " + usedCodes.size() + " and fontFile size = " + getFontStreamSize(font));
            return optimizeType1CFont((PDType1CFont) font, doc, usedCodes);
        }

        if (!(font instanceof PDType0Font)) {
            throw new IllegalStateException("Unexpected font " + font.getName() + " " + font.getClass());
        }

        var descriptor = font.getFontDescriptor();
        var fontFile3 = descriptor.getFontFile3();

        // Type FontFile3
        // Subtype CIDFontType0C or Type1C
        if (fontFile3 != null && "CIDFontType0C".equals(fontFile3.getCOSObject().getNameAsString(COSName.SUBTYPE))) {
            System.out.println("Processing CIDFontType0C font " + font.getName() + " with used glyph count = " + usedCodes.size() + " and fontFile size = " + getFontStreamSize(font));
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

    private static PDStream optimizeType1CFont(PDType1CFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        return null;
    }

    private static PDStream optimizeTrueTypeFont(PDTrueTypeFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        return PDCIDFontType2EmbedderHelper.embedTrueTypeFont(font, doc, usedCodes);
    }

    private static void optimizeCIDTrueType(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var bytes = getFontBytes(font);

        var otfParser = new OTFParser(true);
        OpenTypeFont otfFont = otfParser.parse(new RandomAccessReadBuffer(bytes));
        PDCIDFontType2EmbedderHelper.embedPDCIDFontType2(doc, font, otfFont, font.isVertical(), usedCodes);

        System.out.println("From " + bytes.length + " to " + getFontBytes(font).length);
    }

    private static PDStream optimizeCIDFontType0C(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        return optimizeGenericCffFont(font, doc, usedCodes);
    }

    private static PDStream optimizeGenericCffFont(PDFont font, PDDocument doc, Set<Integer> usedCodes) throws IOException {
        var bytes = getFontBytes(font);

        // Temp subset just to get the number of glyphs
        var tmpSubset = new CFFFontSubset(bytes, Set.of(0), true);
        var allGlyphs = new HashSet<Integer>();
        // TODO: is this okay?
        System.err.println(tmpSubset.fonts[0].nglyphs);
        for (int i = 0; i < tmpSubset.fonts[0].nglyphs - 1; i++) {
            if (usedCodes.contains(tmpSubset.fonts[0].gidToCid[i])) {
                allGlyphs.add(i);
            }
        }

        // Rebuild the font
        var subsetBytes = new CFFFontSubset(bytes, allGlyphs).Process();

        System.out.println("From " + bytes.length + " to " + subsetBytes.length);

        // Put the stream back
        return setFontBytes(font, doc, subsetBytes);
    }

    public static PDStream getFontStream(PDFont font) throws IOException {
        var descriptor = font.getFontDescriptor();
        return Arrays.asList(descriptor.getFontFile(), descriptor.getFontFile2(), descriptor.getFontFile3())
                .stream()
                .filter(Objects::nonNull)
                .findFirst().get();

    }

    public static byte[] getFontBytes(PDFont font) throws IOException {
        var descriptor = font.getFontDescriptor();
        var stream = Arrays.asList(descriptor.getFontFile(), descriptor.getFontFile2(), descriptor.getFontFile3())
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
        if (stream.isPresent()) {
            return stream.get().toByteArray();
        } else {
            return new byte[0];
        }
    }

    public static PDStream setFontBytes(PDFont font, PDDocument doc, byte[] bytes) throws IOException {
        var descriptor = font.getFontDescriptor();
        var fontFile = Stream.of(descriptor.getFontFile(), descriptor.getFontFile2(), descriptor.getFontFile3())
                .filter(Objects::nonNull)
                .findFirst().get();

        PDStream stream = new PDStream(doc);
        var subType = fontFile.getCOSObject().getNameAsString(COSName.SUBTYPE);
        if (subType != null) {
            stream.getCOSObject().setName(COSName.SUBTYPE, subType);
        }
        stream.getCOSObject().setInt(COSName.LENGTH1, bytes.length);
        var os = stream.createOutputStream(COSName.FLATE_DECODE);
        os.write(bytes);
        os.close();

        setFontBytes(font, stream);

        return stream;
    }

    private static void setFontBytes(PDFont font, PDStream stream) {
        var descriptor = font.getFontDescriptor();
        if (descriptor.getFontFile() != null) {
            descriptor.setFontFile(stream);
        } else if (descriptor.getFontFile2() != null) {
            descriptor.setFontFile2(stream);
        } else {
            descriptor.setFontFile3(stream);
        }
    }
}
