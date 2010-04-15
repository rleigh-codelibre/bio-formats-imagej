//
// Importer.java
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

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.Rectangle;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import javax.xml.parsers.ParserConfigurationException;

import loci.common.Location;
import loci.common.ReflectException;
import loci.common.ReflectedUniverse;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelMerger;
import loci.formats.FilePattern;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.gui.XMLWindow;
import loci.formats.meta.IMetadata;
import loci.formats.services.OMEXMLService;
import loci.plugins.Colorizer;
import loci.plugins.LociImporter;
import loci.plugins.util.BF;
import loci.plugins.util.BFVirtualStack;
import loci.plugins.util.DataBrowser;
import loci.plugins.util.ImagePlusReader;
import loci.plugins.util.ImagePlusTools;
import loci.plugins.util.ROIHandler;
import loci.plugins.util.VirtualImagePlus;
import loci.plugins.util.VirtualReader;
import loci.plugins.util.WindowTools;

import org.xml.sax.SAXException;

/**
 * Core logic for the Bio-Formats Importer ImageJ plugin.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/loci-plugins/src/loci/plugins/importer/Importer.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/loci-plugins/src/loci/plugins/importer/Importer.java">SVN</a></dd></dl>
 *
 * @author Curtis Rueden ctrueden at wisc.edu
 * @author Melissa Linkert linkert at wisc.edu
 */
public class Importer {

  // -- Fields --

  /**
   * A handle to the plugin wrapper, for toggling
   * the canceled and success flags.
   */
  private LociImporter plugin;

  private ArrayList<ImagePlus> imps = new ArrayList<ImagePlus>();
  private String stackOrder = null;

  private IndexColorModel[] colorModels;

  // -- Constructor --

  public Importer(LociImporter plugin) {
    this.plugin = plugin;
  }

  // -- Importer API methods --

  /** Executes the plugin. */
  public void run(String arg) {
    ImporterOptions options = null;
    try {
      BF.debug("parse core options");
      options = parseOptions(arg, false);
      if (plugin.canceled) return;

      BF.debug("display option dialogs");
      showOptionDialogs(options);
      if (plugin.canceled) return;

      if (options.isShowMetadata()) {
        BF.debug("display metadata");
        IJ.showStatus("Populating metadata");
        displayOriginalMetadata(options);
        if (plugin.canceled) return;
      }
      else BF.debug("skip metadata");

      if (options.isShowOMEXML()) {
        BF.debug("show OME-XML");
        displayOMEXML(options);
        if (plugin.canceled) return;
      }
      else BF.debug("skip OME-XML");

      if (!options.isViewNone()) {
        BF.debug("read pixel data");
        readPixelData(options);
        if (plugin.canceled) return;
      }
      else BF.debug("skip pixel data"); // nothing to display

      if (options.showROIs()) {
        BF.debug("display ROIs");
        displayROIs(options);
        if (plugin.canceled) return;
      }
      else BF.debug("skip ROIs");

      BF.debug("finish");
      finish(options);
      if (plugin.canceled) return;
    }
    catch (FormatException exc) {
      boolean quiet = options == null ? false : options.isQuiet();
      WindowTools.reportException(exc, quiet,
        "Sorry, there was a problem during import.");
    }
    catch (IOException exc) {
      boolean quiet = options == null ? false : options.isQuiet();
      WindowTools.reportException(exc, quiet,
        "Sorry, there was an I/O problem during import.");
    }
  }

  /** Parses core options. */
  public ImporterOptions parseOptions(String arg, boolean quiet)
    throws IOException
  {
    ImporterOptions options = new ImporterOptions();
    options.loadOptions();
    options.parseArg(arg);
    return options;
  }

  public void showOptionDialogs(ImporterOptions options)
    throws FormatException, IOException
  {
    boolean success = options.showDialogs();
    if (!success) plugin.canceled = true;
  }

  /** Displays standard metadata in a table in its own window. */
  public void displayOriginalMetadata(ImporterOptions options)
    throws IOException
  {
    ImporterMetadata meta = options.getOriginalMetadata();
    meta.showMetadataWindow(options.getIdName());
  }

  public void displayOMEXML(ImporterOptions options)
    throws FormatException, IOException
  {
    if (options.isViewBrowser()) {
      // NB: Data Browser has its own internal OME-XML metadata window,
      // which we'll trigger once we have created a Data Browser.
      // So there is no need to pop up a separate OME-XML here.
    }
    else {
      XMLWindow metaWindow =
        new XMLWindow("OME Metadata - " + options.getIdName());
      Exception exc = null;
      try {
        ServiceFactory factory = new ServiceFactory();
        OMEXMLService service = factory.getInstance(OMEXMLService.class);
        metaWindow.setXML(service.getOMEXML(options.getOMEMetadata()));
        WindowTools.placeWindow(metaWindow);
        metaWindow.setVisible(true);
      }
      catch (DependencyException e) { exc = e; }
      catch (ServiceException e) { exc = e; }
      catch (ParserConfigurationException e) { exc = e; }
      catch (SAXException e) { exc = e; }

      if (exc != null) throw new FormatException(exc);
    }
  }

  public void readPixelData(ImporterOptions options)
    throws FormatException, IOException
  {
    ImagePlusReader r = options.getReader();

    if (options.isVirtual()) {
      int totalSeries = 0;
      for (int s=0; s<r.getSeriesCount(); s++) {
        if (options.isSeriesOn(s)) totalSeries++;
      }
      ((VirtualReader) r.getReader()).setRefCount(totalSeries);
    }

    for (int s=0; s<r.getSeriesCount(); s++) {
      if (!options.isSeriesOn(s)) continue;
      r.setSeries(s);

      boolean[] load = new boolean[r.getImageCount()];
      int cBegin = options.getCBegin(s);
      int cEnd = options.getCEnd(s);
      int cStep = options.getCStep(s);
      int zBegin = options.getZBegin(s);
      int zEnd = options.getZEnd(s);
      int zStep = options.getZStep(s);
      int tBegin = options.getTBegin(s);
      int tEnd = options.getTEnd(s);
      int tStep = options.getTStep(s);
      for (int c=cBegin; c<=cEnd; c+=cStep) {
        for (int z=zBegin; z<=zEnd; z+=zStep) {
          for (int t=tBegin; t<=tEnd; t+=tStep) {
            //int index = r.isOrderCertain() ? r.getIndex(z, c, t) : c;
            int index = r.getIndex(z, c, t);
            load[index] = true;
          }
        }
      }
      int total = 0;
      for (int j=0; j<r.getImageCount(); j++) if (load[j]) total++;

      FileInfo fi = new FileInfo();

      // populate other common FileInfo fields
      String idDir = options.getIdLocation() == null ?
        null : options.getIdLocation().getParent();
      if (idDir != null && !idDir.endsWith(File.separator)) {
        idDir += File.separator;
      }
      fi.fileName = options.getIdName();
      fi.directory = idDir;

      long startTime = System.currentTimeMillis();
      long time = startTime;

      ImageStack stackB = null; // for byte images (8-bit)
      ImageStack stackS = null; // for short images (16-bit)
      ImageStack stackF = null; // for floating point images (32-bit)
      ImageStack stackO = null; // for all other images (24-bit RGB)

      Rectangle cropRegion = options.getCropRegion(s);
      int w = options.doCrop() ? cropRegion.width : r.getSizeX();
      int h = options.doCrop() ? cropRegion.height : r.getSizeY();
      int c = r.getRGBChannelCount();
      int type = r.getPixelType();

      int q = 0;
      stackOrder = options.getStackOrder();
      if (stackOrder.equals(ImporterOptions.ORDER_DEFAULT)) {
        stackOrder = r.getDimensionOrder();
      }
      ((VirtualReader) r.getReader()).setOutputOrder(stackOrder);

      options.getOMEMetadata().setPixelsDimensionOrder(stackOrder, s, 0);

      // dump OME-XML to ImageJ's description field, if available

      try {
        ServiceFactory factory = new ServiceFactory();
        OMEXMLService service = factory.getInstance(OMEXMLService.class);
        fi.description = service.getOMEXML(options.getOMEMetadata());
      }
      catch (DependencyException de) { }
      catch (ServiceException se) { }

      if (options.isVirtual()) {
        int cSize = r.getSizeC();
        int pt = r.getPixelType();
        boolean doMerge = options.isMergeChannels();
        boolean eight = pt != FormatTools.UINT8 && pt != FormatTools.INT8;
        boolean needComposite = doMerge && (cSize > 3 || eight);
        int merge = (needComposite || !doMerge) ? 1 : cSize;

        r.setSeries(s);
        // NB: ImageJ 1.39+ is required for VirtualStack
        BFVirtualStack virtualStackB = new BFVirtualStack(options.getId(),
          r, options.isColorize(), doMerge, options.isRecord());
        stackB = virtualStackB;
        if (doMerge) {
          for (int j=0; j<r.getImageCount(); j++) {
            int[] pos = r.getZCTCoords(j);
            if (pos[1] > 0) continue;
            String label = constructSliceLabel(
              new ChannelMerger(r).getIndex(pos[0], pos[1], pos[2]),
              new ChannelMerger(r), options.getOMEMetadata(), s,
              options.getZCount(s), options.getCCount(s),
              options.getTCount(s));
            virtualStackB.addSlice(label);
          }
        }
        else {
          for (int j=0; j<r.getImageCount(); j++) {
            String label = constructSliceLabel(j, r,
              options.getOMEMetadata(), s, options.getZCount(s),
              options.getCCount(s), options.getTCount(s));
            virtualStackB.addSlice(label);
          }
        }
      }
      else {
        if (r.isIndexed()) colorModels = new IndexColorModel[r.getSizeC()];

        for (int i=0; i<r.getImageCount(); i++) {
          if (!load[i]) continue;

          // limit message update rate
          long clock = System.currentTimeMillis();
          if (clock - time >= 100) {
            String sLabel = r.getSeriesCount() > 1 ?
              ("series " + (s + 1) + ", ") : "";
            String pLabel = "plane " + (i + 1) + "/" + total;
            IJ.showStatus("Reading " + sLabel + pLabel);
            time = clock;
          }
          IJ.showProgress((double) q++ / total);

          String label = constructSliceLabel(i, r,
            options.getOMEMetadata(), s, options.getZCount(s),
            options.getCCount(s), options.getTCount(s));

          // get image processor for ith plane
          ImageProcessor[] p = r.openProcessors(i, cropRegion);
          ImageProcessor ip = p[0];
          if (p.length > 1) {
            ip = ImagePlusTools.makeRGB(p).getProcessor();
          }
          if (ip == null) {
            plugin.canceled = true;
            return;
          }

          int channel = r.getZCTCoords(i)[1];
          if (colorModels != null && p.length == 1) {
            colorModels[channel] = (IndexColorModel) ip.getColorModel();
          }

          // add plane to image stack
          if (ip instanceof ByteProcessor) {
            if (stackB == null) stackB = new ImageStack(w, h);
            stackB.addSlice(label, ip);
          }
          else if (ip instanceof ShortProcessor) {
            if (stackS == null) stackS = new ImageStack(w, h);
            stackS.addSlice(label, ip);
          }
          else if (ip instanceof FloatProcessor) {
            // merge image plane into existing stack if possible
            if (stackB != null) {
              ip = ip.convertToByte(true);
              stackB.addSlice(label, ip);
            }
            else if (stackS != null) {
              ip = ip.convertToShort(true);
              stackS.addSlice(label, ip);
            }
            else {
              if (stackF == null) stackF = new ImageStack(w, h);
              stackF.addSlice(label, ip);
            }
          }
          else if (ip instanceof ColorProcessor) {
            if (stackO == null) stackO = new ImageStack(w, h);
            stackO.addSlice(label, ip);
          }
        }
      }

      IJ.showStatus("Creating image");
      IJ.showProgress(1);

      showStack(stackB, s, fi, options);
      showStack(stackS, s, fi, options);
      showStack(stackF, s, fi, options);
      showStack(stackO, s, fi, options);

      long endTime = System.currentTimeMillis();
      double elapsed = (endTime - startTime) / 1000.0;
      if (r.getImageCount() == 1) {
        IJ.showStatus("Bio-Formats: " + elapsed + " seconds");
      }
      else {
        long average = (endTime - startTime) / r.getImageCount();
        IJ.showStatus("Bio-Formats: " + elapsed + " seconds (" +
          average + " ms per plane)");
      }
    }

    if (options.isConcatenate()) {
      ArrayList<Integer> widths = new ArrayList<Integer>();
      ArrayList<Integer> heights = new ArrayList<Integer>();
      ArrayList<Integer> types = new ArrayList<Integer>();
      ArrayList<ImagePlus> newImps = new ArrayList<ImagePlus>();

      for (int j=0; j<imps.size(); j++) {
        ImagePlus imp = imps.get(j);
        int wj = imp.getWidth();
        int hj = imp.getHeight();
        int tj = imp.getBitDepth();
        boolean append = false;
        for (int k=0; k<widths.size(); k++) {
          int wk = ((Integer) widths.get(k)).intValue();
          int hk = ((Integer) heights.get(k)).intValue();
          int tk = ((Integer) types.get(k)).intValue();

          if (wj == wk && hj == hk && tj == tk) {
            ImagePlus oldImp = newImps.get(k);
            ImageStack is = oldImp.getStack();
            ImageStack newStack = imp.getStack();
            for (int s=0; s<newStack.getSize(); s++) {
              is.addSlice(newStack.getSliceLabel(s + 1),
                newStack.getProcessor(s + 1));
            }
            oldImp.setStack(oldImp.getTitle(), is);
            newImps.set(k, oldImp);
            append = true;
            k = widths.size();
          }
        }
        if (!append) {
          widths.add(new Integer(wj));
          heights.add(new Integer(hj));
          types.add(new Integer(tj));
          newImps.add(imp);
        }
      }

      boolean splitC = options.isSplitChannels();
      boolean splitZ = options.isSplitFocalPlanes();
      boolean splitT = options.isSplitTimepoints();

      for (int j=0; j<newImps.size(); j++) {
        ImagePlus imp = (ImagePlus) newImps.get(j);
        imp.show();
        if (splitC || splitZ || splitT) {
          IJ.runPlugIn("loci.plugins.Slicer", "slice_z=" + splitZ +
            " slice_c=" + splitC + " slice_t=" + splitT +
            " stack_order=" + stackOrder + " keep_original=false " +
            "hyper_stack=" + options.isViewHyperstack() + " ");
          imp.close();
        }
        if (options.isMergeChannels() && options.isWindowless()) {
          IJ.runPlugIn("loci.plugins.Colorizer", "stack_order=" + stackOrder +
            " merge=true merge_option=[" + options.getMergeOption() + "] " +
            "series=" + r.getSeries() + " hyper_stack=" +
            options.isViewHyperstack() + " ");
          imp.close();
        }
        else if (options.isMergeChannels()) {
          IJ.runPlugIn("loci.plugins.Colorizer", "stack_order=" + stackOrder +
            " merge=true series=" + r.getSeries() + " hyper_stack=" +
            options.isViewHyperstack() + " ");
          imp.close();
        }
      }
    }
  }

  public void displayROIs(ImporterOptions options) {
    if (options.showROIs()) {
      BF.debug("display ROIs");

      ImagePlus[] impsArray = imps.toArray(new ImagePlus[0]);
      ROIHandler.openROIs(options.getOMEMetadata(), impsArray);
    }
    else BF.debug("skip ROIs");
  }

  public void finish(ImporterOptions options) throws IOException {
    if (!options.isVirtual()) options.getReader().close();
    plugin.success = true;
  }

  // -- Helper methods --

  /**
   * Displays the given image stack according to
   * the specified parameters and import options.
   */
  private void showStack(ImageStack stack, int series, FileInfo fi,
    ImporterOptions options) throws FormatException, IOException
  {
    if (stack == null) return;

    String seriesName = options.getOMEMetadata().getImageName(series);
    String file = options.getCurrentFile();
    IMetadata meta = options.getOMEMetadata();
    int cCount = options.getCCount(series);
    int zCount = options.getZCount(series);
    int tCount = options.getTCount(series);
    IFormatReader r = options.getReader();
    int sizeZ = r.getSizeZ();
    int sizeC = r.getEffectiveSizeC();
    int sizeT = r.getSizeT();
    boolean windowless = options.isWindowless();

    String title = getTitle(r, file, seriesName, options.isGroupFiles());
    ImagePlus imp = null;
    if (options.isVirtual()) {
      imp = new VirtualImagePlus(title, stack);
      ((VirtualImagePlus) imp).setReader(r);
    }
    else imp = new ImagePlus(title, stack);

    // place metadata key/value pairs in ImageJ's info field
    String metadata = options.getOriginalMetadata().toString();
    imp.setProperty("Info", metadata);

    // retrieve the spatial calibration information, if available
    ImagePlusTools.applyCalibration(meta, imp, r.getSeries());
    imp.setFileInfo(fi);
    imp.setDimensions(cCount, zCount, tCount);

    // display the image stack using the appropriate plugin

    boolean hyper = options.isViewHyperstack() || options.isViewBrowser();
    imp.setOpenAsHyperStack(hyper);
    int nSlices = imp.getNSlices();
    int nFrames = imp.getNFrames();

    if (options.isAutoscale() && !options.isVirtual()) {
      ImagePlusTools.adjustColorRange(imp, r);
    }
    else if (!(imp.getProcessor() instanceof ColorProcessor)) {
      // ImageJ may autoscale the images anyway, so we need to manually
      // set the display range to the min/max values allowed for
      // this pixel type
      imp.setDisplayRange(0, Math.pow(2, imp.getBitDepth()) - 1);
    }

    boolean splitC = options.isSplitChannels();
    boolean splitZ = options.isSplitFocalPlanes();
    boolean splitT = options.isSplitTimepoints();

    int z = r.getSizeZ();
    int c = r.getSizeC();
    int t = r.getSizeT();

    if (!options.isConcatenate() && options.isMergeChannels()) imp.show();

    if (imp.isVisible() && !options.isVirtual()) {
      String arg = "stack_order=" + stackOrder + " merge=true series=" +
        r.getSeries() + " hyper_stack=" + options.isViewHyperstack();
      if (windowless) arg += " merge_option=[" + options.getMergeOption() + "]";
      arg += " ";
      IJ.runPlugIn("loci.plugins.Colorizer", arg);
      if (WindowManager.getCurrentImage().getID() != imp.getID()) {
        imp.close();
      }
    }

    imp.setDimensions(imp.getStackSize() / (nSlices * nFrames),
      nSlices, nFrames);

    if (options.isViewVisBio()) {
      // NB: avoid dependency on optional loci.visbio packages
      ReflectedUniverse ru = new ReflectedUniverse();
      try {
        ru.exec("import loci.visbio.data.Dataset");
        //ru.setVar("name", name);
        //ru.setVar("pattern", pattern);
        ru.exec("dataset = new Dataset(name, pattern)");
        // TODO: finish VisBio logic
      }
      catch (ReflectException exc) {
        WindowTools.reportException(exc, options.isQuiet(),
          "Sorry, there was a problem interfacing with VisBio");
        return;
      }
    }
    else if (options.isViewImage5D()) {
      ReflectedUniverse ru = new ReflectedUniverse();
      try {
        ru.exec("import i5d.Image5D");
        ru.setVar("title", imp.getTitle());
        ru.setVar("stack", imp.getStack());
        ru.setVar("sizeC", c);
        ru.setVar("sizeZ", z);
        ru.setVar("sizeT", t);
        ru.exec("i5d = new Image5D(title, stack, sizeC, sizeZ, sizeT)");
        ru.setVar("cal", imp.getCalibration());
        ru.setVar("fi", imp.getOriginalFileInfo());
        ru.exec("i5d.setCalibration(cal)");
        ru.exec("i5d.setFileInfo(fi)");
        //ru.exec("i5d.setDimensions(sizeC, sizeZ, sizeT)");
        ru.exec("i5d.show()");
      }
      catch (ReflectException exc) {
        WindowTools.reportException(exc, options.isQuiet(),
          "Sorry, there was a problem interfacing with Image5D");
        return;
      }
    }
    else if (options.isViewView5D()) {
      WindowManager.setTempCurrentImage(imp);
      IJ.run("start viewer", "");
    }
    else {
      // NB: ImageJ 1.39+ is required for hyperstacks

      if (!options.isConcatenate()) {
        if (options.isViewBrowser()) {
          DataBrowser dataBrowser = new DataBrowser(imp, null,
            r.getChannelDimTypes(), r.getChannelDimLengths());
          if (options.isShowOMEXML()) dataBrowser.showMetadataWindow();
        }
        else if (!imp.isVisible()) imp.show();

        boolean customColorize = options.isCustomColorize();
        boolean browser = options.isViewBrowser();
        boolean virtual = options.isVirtual();

        if (options.isColorize() || customColorize) {
          IJ.runPlugIn("loci.plugins.Colorizer", "stack_order=" + stackOrder +
            " merge=false colorize=true ndx=" + (customColorize ? "-1" : "0") +
            " series=" + r.getSeries() + " hyper_stack=" +
            options.isViewHyperstack() + " ");
          imp.close();
        }
        else if (colorModels != null && !browser && !virtual) {
          Colorizer colorizer = new Colorizer();
          String arg = "stack_order=" + stackOrder + " merge=false " +
            "colorize=true series=" + r.getSeries() + " hyper_stack=" +
            hyper + " ";
          colorizer.setup(arg, imp);
          for (int channel=0; channel<colorModels.length; channel++) {
            byte[][] lut = new byte[3][256];
            colorModels[channel].getReds(lut[0]);
            colorModels[channel].getGreens(lut[1]);
            colorModels[channel].getBlues(lut[2]);
            colorizer.setLookupTable(lut, channel);
          }
          new PlugInFilterRunner(colorizer, "", arg);
          imp.close();
        }

        if (splitC || splitZ || splitT) {
          IJ.runPlugIn("loci.plugins.Slicer", "slice_z=" + splitZ +
            " slice_c=" + splitC + " slice_t=" + splitT +
            " stack_order=" + stackOrder + " keep_original=false " +
            "hyper_stack=" + hyper + " ");
          imp.close();
        }
      }
      imps.add(imp);
    }
  }

  /** Get an appropriate stack title, given the file name. */
  private String getTitle(IFormatReader r, String file, String seriesName,
    boolean groupFiles)
  {
    String[] used = r.getUsedFiles();
    String title = file.substring(file.lastIndexOf(File.separator) + 1);
    if (used.length > 1 && groupFiles) {
      FilePattern fp = new FilePattern(new Location(file));
      if (fp != null) {
        title = fp.getPattern();
        if (title == null) {
          title = file;
          if (title.indexOf(".") != -1) {
            title = title.substring(0, title.lastIndexOf("."));
          }
        }
        title = title.substring(title.lastIndexOf(File.separator) + 1);
      }
    }
    if (seriesName != null && !file.endsWith(seriesName) &&
      r.getSeriesCount() > 1)
    {
      title += " - " + seriesName;
    }
    if (title.length() > 128) {
      String a = title.substring(0, 62);
      String b = title.substring(title.length() - 62);
      title = a + "..." + b;
    }
    return title;
  }

  /** Constructs slice label. */
  private String constructSliceLabel(int ndx, IFormatReader r,
    IMetadata meta, int series, int zCount, int cCount, int tCount)
  {
    r.setSeries(series);
    int[] zct = r.getZCTCoords(ndx);
    int[] subC = r.getChannelDimLengths();
    String[] subCTypes = r.getChannelDimTypes();
    StringBuffer sb = new StringBuffer();
    boolean first = true;
    if (cCount > 1) {
      if (first) first = false;
      else sb.append("; ");
      int[] subCPos = FormatTools.rasterToPosition(subC, zct[1]);
      for (int i=0; i<subC.length; i++) {
        boolean ch = subCTypes[i].equals(FormatTools.CHANNEL);
        sb.append(ch ? "c" : subCTypes[i]);
        sb.append(":");
        sb.append(subCPos[i] + 1);
        sb.append("/");
        sb.append(subC[i]);
        if (i < subC.length - 1) sb.append(", ");
      }
    }
    if (zCount > 1) {
      if (first) first = false;
      else sb.append("; ");
      sb.append("z:");
      sb.append(zct[0] + 1);
      sb.append("/");
      sb.append(r.getSizeZ());
    }
    if (tCount > 1) {
      if (first) first = false;
      else sb.append("; ");
      sb.append("t:");
      sb.append(zct[2] + 1);
      sb.append("/");
      sb.append(r.getSizeT());
    }
    // put image name at the end, in case it is long
    String imageName = meta.getImageName(series);
    if (imageName != null && !imageName.trim().equals("")) {
      sb.append(" - ");
      sb.append(imageName);
    }
    return sb.toString();
  }

  // -- Main method --

  /** Main method, for testing. */
  public static void main(String[] args) {
    new ImageJ(null);
    StringBuffer sb = new StringBuffer();
    for (int i=0; i<args.length; i++) {
      if (i > 0) sb.append(" ");
      sb.append(args[i]);
    }
    new LociImporter().run(sb.toString());
  }

}
