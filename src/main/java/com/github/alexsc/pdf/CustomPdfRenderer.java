package com.github.alexsc.pdf;

import com.itextpdf.io.font.PdfFontPatcher;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.*;


public class CustomPdfRenderer extends PDFRenderer {
    private final Map<String, Set<Integer>> usedCodes = new HashMap<>();
    private final Map<PDFont, String> fontDigestCache = new IdentityHashMap<>();

    public CustomPdfRenderer(PDDocument document) {
        super(document);
    }

    @Override
    protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException
    {
        var pageDrawer = new CustomPageDrawer(parameters);
        pageDrawer.setAnnotationFilter(annotation -> true);
        return pageDrawer;
    }

    private String getFontContentDigest(PDFont font) throws IOException {
        var digest = fontDigestCache.get(font);
        if (digest == null) {
            digest = PdfFontPatcher.getFontContentDigest(font);
            fontDigestCache.put(font, digest);
        }
        return digest;
    }

    public Map<String, Set<Integer>> getUsedCodes() {
        return usedCodes;
    }

    class CustomPageDrawer extends PageDrawer {

        public CustomPageDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
            addOperator(new SetFontAndSize(this));
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
            addCode(font, code);
        }

        @Override
        protected void showFontGlyph(Matrix textRenderingMatrix, PDFont font, int code,
                                     Vector displacement) throws IOException {
            addCode(font, code);
        }

        private void addCode(PDFont font, int code) throws IOException {
            String fontDigest = getFontContentDigest(font);
            var fontUsedCodes = usedCodes.computeIfAbsent(fontDigest, k -> new HashSet<>());
            var added = fontUsedCodes.add(code);
//            if ("OYVCFQ+Arial-ItalicMT".equals(font.getName()) && added) {
//                System.err.println(code + " " + (char) code + " " + font.toUnicode(code));
//            }
        }
    }

    class SetFontAndSize extends OperatorProcessor
    {
        private final Log LOG = LogFactory.getLog(org.apache.pdfbox.contentstream.operator.text.SetFontAndSize.class);

        protected SetFontAndSize(PDFStreamEngine context) {
            super(context);
        }

        @Override
        public void process(Operator operator, List<COSBase> arguments) throws IOException
        {
            if (arguments.size() < 2) {
                throw new MissingOperandException(operator, arguments);
            }

            COSBase base0 = arguments.get(0);
            COSBase base1 = arguments.get(1);
            if (!(base0 instanceof COSName))  {
                return;
            }
            if (!(base1 instanceof COSNumber)) {
                return;
            }
            COSName fontName = (COSName) base0;
            float fontSize = ((COSNumber) base1).floatValue();
            getContext().getGraphicsState().getTextState().setFontSize(fontSize);
            PDFont font = getContext().getResources().getFont(fontName);
            if (font == null) {
                LOG.warn("font '" + fontName.getName() + "' not found in resources");
            } else {
                String key = getFontContentDigest(font);
                if (!usedCodes.containsKey(key)) {
                    // System.err.println(fontName + " => " + key);
                    usedCodes.put(key, new HashSet<>());
                }
            }
            getContext().getGraphicsState().getTextState().setFont(font);
        }

        @Override
        public String getName()
        {
            return OperatorName.SET_FONT_AND_SIZE;
        }
    }
}
