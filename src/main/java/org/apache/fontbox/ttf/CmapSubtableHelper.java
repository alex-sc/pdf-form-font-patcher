package org.apache.fontbox.ttf;

import org.apache.fontbox.cmap.CMap;

import java.util.Collections;
import java.util.List;

public class CmapSubtableHelper extends CmapSubtable {
    private final CMap cMap;

    public CmapSubtableHelper(CMap cMap) {
        this.cMap = cMap;
    }

    @Override
    public int getGlyphId(int codePointAt) {
        if ("Adobe-Identity-UCS".equals(cMap.getName())) {
            return codePointAt;
        }
        return cMap.toCID(codePointAt);
    }

    /**
     * Returns all possible character codes for the given gid, or null if there is none.
     *
     * @param gid glyph id
     * @return a list with all character codes the given gid maps to
     */
    @Override
    public List<Integer> getCharCodes(int gid) {
        return Collections.singletonList(gid);
    }
}
