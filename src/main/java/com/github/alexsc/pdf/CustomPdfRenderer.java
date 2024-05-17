package com.github.alexsc.pdf;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.pdfbox.contentstream.operator.*;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.util.*;

public class CustomPdfRenderer extends PDFRenderer {
    private final Map<String, Set<Integer>> usedCodes = new HashMap<>();

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

    public Map<String, Set<Integer>> getUsedCodes() {
        return usedCodes;
    }

    class CustomPageDrawer extends PageDrawer {

        public CustomPageDrawer(PageDrawerParameters parameters) throws IOException {
            super(parameters);
            addOperator(new SetFontAndSize());
            addOperator(new DrawObject());
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
            var fontUsedCodes = usedCodes.computeIfAbsent(font.getName(), k -> new HashSet<>());
            fontUsedCodes.add(code);
        }
    }

    class SetFontAndSize extends OperatorProcessor
    {
        private final Log LOG = LogFactory.getLog(org.apache.pdfbox.contentstream.operator.text.SetFontAndSize.class);

        @Override
        public void process(Operator operator, List<COSBase> arguments) throws IOException
        {
            if (arguments.size() < 2)
            {
                throw new MissingOperandException(operator, arguments);
            }

            COSBase base0 = arguments.get(0);
            COSBase base1 = arguments.get(1);
            if (!(base0 instanceof COSName))
            {
                return;
            }
            if (!(base1 instanceof COSNumber))
            {
                return;
            }
            COSName fontName = (COSName) base0;
            if (!usedCodes.containsKey(fontName.getName())) {
                usedCodes.put(fontName.getName(), new HashSet<>());
            }
            float fontSize = ((COSNumber) base1).floatValue();
            context.getGraphicsState().getTextState().setFontSize(fontSize);
            PDFont font = context.getResources().getFont(fontName);
            if (font == null)
            {
                LOG.warn("font '" + fontName.getName() + "' not found in resources");
            }
            context.getGraphicsState().getTextState().setFont(font);
        }

        @Override
        public String getName()
        {
            return OperatorName.SET_FONT_AND_SIZE;
        }
    }
}
