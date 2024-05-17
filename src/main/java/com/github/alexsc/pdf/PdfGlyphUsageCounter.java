package com.github.alexsc.pdf;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.*;

public class PdfGlyphUsageCounter extends PdfContentStreamEditor {
    private final Map<String, Set<Integer>> usedCodes = new HashMap<>();

    public PdfGlyphUsageCounter(PDPage page, PDDocument document) {
        super(page, document);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        var fontUsedCodes = usedCodes.computeIfAbsent(font.getName(), k -> new HashSet<>());
        fontUsedCodes.add(code);

        super.showGlyph(textRenderingMatrix, font, code, displacement);
    }

    public Map<String, Set<Integer>> getUsedCodes() {
        return usedCodes;
    }
}
