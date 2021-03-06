package org.apache.pdfbox.pdmodel.font;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.CmapTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.OpenTypeFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.util.Matrix;

/**
 * Type 2 CIDFont (TrueType).
 * 
 * @author Ben Litchfield
 */
public class PDCIDFontType2 extends PDCIDFont
{
	private static final Log LOG = LogFactory.getLog(PDCIDFontType2.class);

	private final TrueTypeFont ttf;
	private final int[] cid2gid;
	private final Map<Integer, Integer> gid2cid;
	private final boolean hasIdentityCid2Gid;
	private final boolean isEmbedded;
	private final boolean isDamaged;
	private final CmapSubtable cmap; // may be null
	private Matrix fontMatrix;

	/**
	 * Constructor.
	 * 
	 * @param fontDictionary The font dictionary according to the PDF specification.
	 */
	public PDCIDFontType2(COSDictionary fontDictionary, PDType0Font parent) throws IOException
	{
		super(fontDictionary, parent);

		PDFontDescriptor fd = getFontDescriptor();
		PDStream ff2Stream = fd.getFontFile2();
		PDStream ff3Stream = fd.getFontFile3();

		TrueTypeFont ttfFont = null;
		boolean fontIsDamaged = false;
		if (ff2Stream != null)
		{
			try
			{
				// embedded
				TTFParser ttfParser = new TTFParser(true);
				ttfFont = ttfParser.parse(ff2Stream.createInputStream());
			}
			catch (NullPointerException e) // TTF parser is buggy
			{
				LOG.warn("Could not read embedded TTF for font " + getBaseFont(), e);
				fontIsDamaged = true;
			}
			catch (IOException e)
			{
				LOG.warn("Could not read embedded TTF for font " + getBaseFont(), e);
				fontIsDamaged = true;
			}
		}
		else if (ff3Stream != null)
		{
			try
			{
				// embedded
				OTFParser otfParser = new OTFParser(true);
				OpenTypeFont otf = otfParser.parse(ff3Stream.createInputStream());
				ttfFont = otf;

				if (otf.isPostScript())
				{
					// todo: we need more abstraction to support CFF fonts here
					throw new IOException("Not implemented: OpenType font with CFF table " +
							getBaseFont());
				}

				if (otf.hasLayoutTables())
				{
					LOG.error("OpenType Layout tables used in font " + getBaseFont() +
							" are not implemented in PDFBox and will be ignored");
				}
			}
			catch (NullPointerException e) // TTF parser is buggy
			{
				fontIsDamaged = true;
				LOG.warn("Could not read embedded OTF for font " + getBaseFont(), e);
			}
			catch (IOException e)
			{
				fontIsDamaged = true;
				LOG.warn("Could not read embedded OTF for font " + getBaseFont(), e);
			}
		}

		isEmbedded = ttfFont != null;
		isDamaged = fontIsDamaged;
		if (ttfFont == null)
		{
			// substitute
			TrueTypeFont ttfSubstitute = ExternalFonts.getTrueTypeFont(getBaseFont());
			if (ttfSubstitute != null)
			{
				ttfFont = ttfSubstitute;
			}
			else
			{
				// fallback
				ttfFont = ExternalFonts.getTrueTypeFallbackFont(getFontDescriptor());
				LOG.warn("Using fallback font '" + ttfFont + "' for '" + getBaseFont() + "'");
			}
		}
		ttf = ttfFont;
		cmap = getUnicodeCmap(ttf.getCmap());

		cid2gid = readCIDToGIDMap();
		gid2cid = invert(cid2gid);
		COSBase map = dict.getDictionaryObject(COSName.CID_TO_GID_MAP);
		hasIdentityCid2Gid = map instanceof COSName && ((COSName) map).getName().equals("Identity");
	}

	@Override
	public Matrix getFontMatrix()
	{
		if (fontMatrix == null)
		{
			// 1000 upem, this is not strictly true
			fontMatrix = new Matrix(0.001f, 0, 0, 0.001f, 0, 0);
		}
		return fontMatrix;
	}

	@Override
	public BoundingBox getBoundingBox() throws IOException
	{
		return ttf.getFontBBox();
	}

	private int[] readCIDToGIDMap() throws IOException
	{
		int[] cid2gid = null;
		COSBase map = dict.getDictionaryObject(COSName.CID_TO_GID_MAP);
		if (map instanceof COSStream)
		{
			COSStream stream = (COSStream) map;
			InputStream is = stream.getUnfilteredStream();
			byte[] mapAsBytes = IOUtils.toByteArray(is);
			IOUtils.closeQuietly(is);
			int numberOfInts = mapAsBytes.length / 2;
			cid2gid = new int[numberOfInts];
			int offset = 0;
			for (int index = 0; index < numberOfInts; index++)
			{
				int gid = (mapAsBytes[offset] & 0xff) << 8 | mapAsBytes[offset + 1] & 0xff;
				cid2gid[index] = gid;
				offset += 2;
			}
		}
		return cid2gid;
	}
	
	private Map<Integer, Integer> invert(int[] cid2gid)
	{
		if (cid2gid == null)
		{
			return null;
		}
		Map<Integer, Integer> inverse = new HashMap<Integer, Integer>();
		for (int i = 0; i < cid2gid.length; i++)
		{
			inverse.put(cid2gid[i], i);
		}
		return inverse;
	}

	@Override
	public int codeToCID(int code)
	{
		CMap cMap = parent.getCMap();

		// Acrobat allows bad PDFs to use Unicode CMaps here instead of CID CMaps, see PDFBOX-1283
		if (!cMap.hasCIDMappings() && cMap.hasUnicodeMappings())
		{
			return cMap.toUnicode(code).codePointAt(0); // actually: code -> CID
		}

		return cMap.toCID(code);
	}

	/**
	 * Returns the GID for the given character code.
	 *
	 * @param code character code
	 * @return GID
	 */
	public int codeToGID(int code) throws IOException
	{
		if (!isEmbedded)
		{
			// The conforming reader shall select glyphs by translating characters from the
			// encoding specified by the predefined CMap to one of the encodings in the TrueType
			// font's 'cmap' table. The means by which this is accomplished are implementation-
			// dependent.

			String unicode;

			if (cid2gid != null || hasIdentityCid2Gid)
			{
				int cid = codeToCID(code);
				// strange but true, Acrobat allows non-embedded GIDs, test with PDFBOX-2060
				if (hasIdentityCid2Gid)
				{
					return cid;
				}
				else
				{
					return cid2gid[cid];
				}
			}
			else
			{
				// test with PDFBOX-1422 and PDFBOX-2560
				unicode = parent.toUnicode(code);
			}

			if (unicode == null)
			{
				return 0;
			}
			else if (unicode.length() > 1)
			{
				LOG.warn("trying to map a multi-byte character using 'cmap', result will be poor");
			}
			
			// a non-embedded font always has a cmap (otherwise ExternalFonts won't load it)
			return cmap.getGlyphId(unicode.codePointAt(0));
		}
		else
		{
			// If the TrueType font program is embedded, the Type 2 CIDFont dictionary shall contain
			// a CIDToGIDMap entry that maps CIDs to the glyph indices for the appropriate glyph
			// descriptions in that font program.

			int cid = codeToCID(code);
			if (cid2gid != null)
			{
				// use CIDToGIDMap
				if (cid < cid2gid.length)
				{
					return cid2gid[cid];
				}
				else
				{
					return 0;
				}
			}
			else
			{
				// "Identity" is the default CIDToGIDMap
				if (cid < ttf.getNumberOfGlyphs())
				{
					return cid;
				}
				else
				{
					// out of range CIDs map to GID 0
					return 0;
				}
			}
		}
	}

	/**
	 * Returns the best Unicode from the font (the most general). The PDF spec says that "The means
	 * by which this is accomplished are implementation-dependent."
	 */
	private CmapSubtable getUnicodeCmap(CmapTable cmapTable)
	{
		if (cmapTable == null)
		{
			return null;
		}
		
		CmapSubtable cmap = cmapTable.getSubtable(CmapTable.PLATFORM_UNICODE,
				CmapTable.ENCODING_UNICODE_2_0_FULL);
		if (cmap == null)
		{
			cmap = cmapTable.getSubtable(CmapTable.PLATFORM_UNICODE,
					CmapTable.ENCODING_UNICODE_2_0_BMP);
		}
		if (cmap == null)
		{
			cmap = cmapTable.getSubtable(CmapTable.PLATFORM_WINDOWS,
					CmapTable.ENCODING_WIN_UNICODE_BMP);
		}
		if (cmap == null)
		{
			// Microsoft's "Recommendations for OpenType Fonts" says that "Symbol" encoding
			// actually means "Unicode, non-standard character set"
			cmap = cmapTable.getSubtable(CmapTable.PLATFORM_WINDOWS,
					CmapTable.ENCODING_WIN_SYMBOL);
		}
		if (cmap == null)
		{
			// fallback to the first cmap (may not ne Unicode, so may produce poor results)
			LOG.warn("Used fallback cmap for font " + getBaseFont());
			cmap = cmapTable.getCmaps()[0];
		}
		return cmap;
	}

	//    @OverrideTODO
	public float getHeight(int code) throws IOException
	{
		// todo: really we want the BBox, (for text extraction:)
		return (ttf.getHorizontalHeader().getAscender() + -ttf.getHorizontalHeader().getDescender())
				/ ttf.getUnitsPerEm(); // todo: shouldn't this be the yMax/yMin?
	}

	//    @OverrideTODO
	public float getWidthFromFont(int code) throws IOException
	{
		int gid = codeToGID(code);
		int width = ttf.getAdvanceWidth(gid);
		int unitsPerEM = ttf.getUnitsPerEm();
		if (unitsPerEM != 1000)
		{
			width *= 1000f / unitsPerEM;
		}
		return width;
	}

	@Override
    public byte[] encode(int unicode)
    {
        int cid = -1;
        if (isEmbedded)
        {
            // embedded fonts always use CIDToGIDMap, with Identity as the default
            if (parent.getCMap().getName().startsWith("Identity-"))
            {
                if (cmap != null)
                {
                    cid = cmap.getGlyphId(unicode);
                }
            }
            else
            {
                // if the CMap is predefined then there will be a UCS-2 CMap
                if (parent.getCMapUCS2() != null)
                {
                    cid = parent.getCMapUCS2().toCID(unicode);
                }
            }

            // otherwise we require an explicit ToUnicode CMap
            if (cid == -1)
            {
                // todo: invert the ToUnicode CMap?
                cid = 0;
            }
        }
        else
        {
            // a non-embedded font always has a cmap (otherwise it we wouldn't load it)
            cid = cmap.getGlyphId(unicode);
        }

        if (cid == 0)
        {
            throw new IllegalArgumentException(
                    String.format("No glyph for U+%04X in font %s", unicode, getName()));
        }

        // CID is always 2-bytes (16-bit) for TrueType
        return new byte[] { (byte)(cid >> 8 & 0xff), (byte)(cid & 0xff) };
    }

	@Override
	public boolean isEmbedded()
	{
		return isEmbedded;
	}

	@Override
	public boolean isDamaged()
	{
		return isDamaged;
	}

	/**
	 * Returns the embedded or substituted TrueType font.
	 */
	public TrueTypeFont getTrueTypeFont()
	{
		return ttf;
	}
}
