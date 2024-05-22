package com.itextpdf.io.font;

import com.itextpdf.io.source.RandomAccessFileOrArray;

import java.util.Set;

public class TrueTypeFontSubsetHelper extends  TrueTypeFontSubset {
    public TrueTypeFontSubsetHelper(String fileName, RandomAccessFileOrArray rf, Set<Integer> glyphsUsed, int directoryOffset, boolean subset) {
        super(fileName, rf, glyphsUsed, directoryOffset, subset);
    }

    @Override
    public byte[] process() throws java.io.IOException {
        return super.process();
    }
}
