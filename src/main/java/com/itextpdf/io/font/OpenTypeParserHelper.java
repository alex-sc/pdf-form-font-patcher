package com.itextpdf.io.font;

import java.io.IOException;

public class OpenTypeParserHelper extends OpenTypeParser {
    public OpenTypeParserHelper(byte[] ttf) throws IOException {
        super(ttf);
    }
}
