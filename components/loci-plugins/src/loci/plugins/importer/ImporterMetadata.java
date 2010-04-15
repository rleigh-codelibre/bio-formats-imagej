//
// ImporterMetadata.java
//

/*
LOCI Plugins for ImageJ: a collection of ImageJ plugins including the
Bio-Formats Importer, Bio-Formats Exporter, Bio-Formats Macro Extensions,
Data Browser, Stack Colorizer and Stack Slicer. Copyright (C) 2005-@year@
Melissa Linkert, Curtis Rueden and Christopher Peterson.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.plugins.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;

import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.MetadataTools;
import loci.plugins.util.SearchableWindow;

/**
 * Helper class for storing original metadata key/value pairs.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/loci-plugins/src/loci/plugins/importer/ImporterMetadata.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/loci-plugins/src/loci/plugins/importer/ImporterMetadata.java">SVN</a></dd></dl>
 */
public class ImporterMetadata extends HashMap<String, Object> {

  // -- Constructor --

  public ImporterMetadata(IFormatReader r, ImporterOptions options,
    boolean usePrefix)
  {
    // merge global metadata
    putAll(r.getGlobalMetadata());

    // merge location path
    put("Location", options.getCurrentFile());

    final int oldSeries = r.getSeries();
    final int seriesCount = r.getSeriesCount();
    final int digits = digits(seriesCount);
    for (int i=0; i<seriesCount; i++) {
      if (!options.isSeriesOn(i)) continue;
      r.setSeries(i);

      // build prefix from image name and/or series number
      String s = "";
      if (usePrefix) {
        s = options.getOMEMetadata().getImageName(i);
        if ((s == null || s.trim().length() == 0) && seriesCount > 1) {
          StringBuffer sb = new StringBuffer();
          sb.append("Series ");
          int zeroes = digits - digits(i + 1);
          for (int j=0; j<zeroes; j++) sb.append(0);
          sb.append(i + 1);
          sb.append(" ");
          s = sb.toString();
        }
        else s += " ";
      }

      // merge series metadata
      Hashtable seriesMeta = r.getSeriesMetadata();
      MetadataTools.merge(seriesMeta, this, s);

      // merge core values
      final String pad = " "; // puts core values first when alphabetizing
      put(pad + s + "SizeX", new Integer(r.getSizeX()));
      put(pad + s + "SizeY", new Integer(r.getSizeY()));
      put(pad + s + "SizeZ", new Integer(r.getSizeZ()));
      put(pad + s + "SizeT", new Integer(r.getSizeT()));
      put(pad + s + "SizeC", new Integer(r.getSizeC()));
      put(pad + s + "IsRGB", new Boolean(r.isRGB()));
      put(pad + s + "PixelType",
        FormatTools.getPixelTypeString(r.getPixelType()));
      put(pad + s + "LittleEndian", new Boolean(r.isLittleEndian()));
      put(pad + s + "DimensionOrder", r.getDimensionOrder());
      put(pad + s + "IsInterleaved", new Boolean(r.isInterleaved()));
    }
    r.setSeries(oldSeries);
  }

  /** Returns a string with each key/value pair on its own line. */
  public String getMetadataString(String separator) {
    ArrayList<String> keys = new ArrayList<String>(keySet());
    Collections.sort(keys);

    StringBuffer sb = new StringBuffer();
    for (String key : keys) {
      sb.append(key);
      sb.append(separator);
      sb.append(get(key));
      sb.append("\n");
    }
    return sb.toString();
  }

  /** Displays the metadata in a searchable window. */
  public void showMetadataWindow(String name) {
    // sort metadata keys
    String metaString = getMetadataString("\t");
    SearchableWindow w = new SearchableWindow("Original Metadata - " + name,
      "Key\tValue", metaString, 400, 400);
    w.setVisible(true);
  }

  // -- Object API methods --

  public String toString() {
    return getMetadataString(" = ");
  }

  // -- Helper methods --

  /** Computes the given value's number of digits. */
  private static int digits(int value) {
    int digits = 0;
    while (value > 0) {
      value /= 10;
      digits++;
    }
    return digits;
  }

}
