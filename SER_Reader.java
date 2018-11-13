import java.io.*;
import java.util.*;
import java.awt.*;
import ij.*;
import ij.plugin.*;
import ij.process.*;
import ij.io.*;
import ij.measure.*;
import ij.gui.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;

/*
Version: 0.1 (2018-11-13, 17:25 mmohn)

This ImageJ plugin reads .ser files recorded by FEI's TIA software.
Only 2D data is supported (no spectra). Special features for large
image stacks can be accessed by starting the reader plugin directly
from the plugin menu of ImageJ.

Copyright (c) 2018 Michael Mohn, Ulm University

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.

The plugin is based on the TIA (Emispec) file format description
by Dr Chris Boothroyd (http://www.er-c.org/cbb/info/TIAformat/),
and inspired by the TIA Reader plugin by Steffen Schmidt
(https://imagej.nih.gov/ij/plugins/tia-reader.html)
and the DM3 reader plugin of ImageJ.
*/


public class SER_Reader extends ImagePlus implements PlugIn
{

    // plugin mode
    private boolean advMode;

    // messages
    private String errTitle = "SER Reader Error";
    private String warnTitle = "SER Reader Warning";

    // file name and directory
    private String directory;
    private String fileName;

    // binary file
    private RandomAccessFile f;

    // general file properties
    private int byteOrder; // byte order, should be 0x4949 (II)
    private int serID; // series identification word, should be 0x0197
    private int verNum; // version number word, less or greater/equal 0x220
    private boolean oldVer; // old version ser file? true if verNum < 0x220
    private int dtypeID; // data type ID - 1D or 2D
    private int ttypeID; // tag type ID - time or time+position
    private boolean posTags; // tags with position info?
    private int totNel; // total number of elements initialized by TIA
    private int valNel; // number of valid elements recorded
    private long offDat; // offset of data offset array (position after "static" header)
    private int numDim; // number of dimensions - for normal stack of 2D images: nDim = 1

    // dimension array - will read only one, as nDim = 1 for TEM image stacks
    private int dimSize; // dimension size (number of slices!)
    private double calOff; // calibration offset for dimension along slices (time or energy...)
    private double calDel; // calibration delta (stepsize) along slice dimension
    private long calElem; // index of the calibrated element (for which offset is given)
    private int descrLen; // description length (no. of chars)
    private String descr; // description string
    private long unitLen; // unit string length
    private String unit; // unit string

    // data and tag offsets
    private long[] dataOff;
    private long[] tagOff;


    public void run(String arg) {
    /*  main function:
        controls the program flow,
        shows dialogs if necessary
    */

        // Show open dialog or use given file name
        if ((arg == null) || (arg == "")) {
            OpenDialog od = new OpenDialog("Load SER File...", arg);
            fileName = od.getFileName();
            if (fileName == null) return;
            directory = od.getDirectory();
            advMode = true;
        }
        else {
            File dest = new File(arg);
            directory = dest.getParent();
            fileName = dest.getName();
            advMode = false;
        }

        // Check file name and directory
        if ((fileName == null) || (fileName == "")) {
            IJ.showMessage(errTitle, "Invalid file path.");
            return;
        }
        if (!directory.endsWith(File.separator)) directory += File.separator;
        IJ.showStatus("Loading SER File: " + directory + fileName);


        // main part comes here:
        try{

            // get access to file
            f = new RandomAccessFile(directory + fileName, "r");

            // read header and data positions to instance variables
            readFile();

            // default settings that may be changed in "advanced" mode
            int startSlice = 1; // start with first slice
            int endSlice = valNel; // end with last slice
            int incSlice = 1; // increment is 1 (every slice)

            /* a dialog for additional features
                only in combination with above OpenDialog,
                i.e. when plugin is started explicitly from plugins menu
            */
            if (advMode) {
                GenericDialog gd = new GenericDialog("SER Reader");
                gd.addNumericField("Start:", 1, 0);
                gd.addNumericField("End:", valNel, 0);
                gd.addNumericField("Increment:", 1, 0);
                gd.showDialog();
                if (!gd.wasCanceled()) { // otherwise don't do anything (stack may be too large!)
                    /* start and end slices:
                        - "end" must be greater than "start" (also no negative increment allowed)
                        - negative numbers are counted from end of stack
                        - eventually, positions beyond the stack are set to 1 or valNel!
                        examples:
                            - start -2 and end -1 are the last two images of the stack!
                    */
                    int startInput = (int) gd.getNextNumber();
                    int endInput = (int) gd.getNextNumber();
                    if (startInput < 0) startInput = startInput + valNel+1; // negative input: slice number counted from end
                    if (endInput < 0) endInput = endInput + valNel+1;
                    if ((startInput<1) || (startInput>valNel) || (endInput<1) || (endInput>valNel) || (startInput>endInput)) {
                        IJ.showMessage(errTitle, "Wrong or misinterpreted Start and End:\nStart "+startInput+", End "+endInput);
                        return;
                    } else {
                        startSlice = startInput;
                        endSlice = endInput;
                    }
                    int incInput = (int) gd.getNextNumber();
                    if (incInput < 0) {
                        IJ.showMessage(errTitle, "Negative increment is not allowed.");
                        return;
                    } else {
                        incSlice = incInput;
                    } // no upper limit for increment, as this will simply result in a single image...
                } else {
                    return;
                }

                // show start, end, increment (for debugging)
                // IJ.showMessage("Conv: " + convType + ", Start: " + startSlice + ", End: " + endSlice + ", Inc: " + incSlice);
            } // fi: advanced mode ...


            // global calibration (will be initialized with first image)
            Calibration calib = null;
            int width = 0;
            int height = 0;
            int type = 0;

            // ImageStack for frames
            ImageStack imStack = null;

            // get single frames and add them to the above stack
            for (int i = startSlice; i <= endSlice; i+=incSlice) {
                ImagePlus tempImp = getImage(i);
                if (tempImp == null) {
                    IJ.error(errTitle, "Error reading slice no. " + i);
                    return;
                }
                if (i == startSlice) {
                    calib = tempImp.getCalibration();
                    width = tempImp.getWidth();
                    height = tempImp.getHeight();
                    type = tempImp.getType();
                    imStack = new ImageStack(width, height);
                }
                if (tempImp.getWidth() == width && tempImp.getHeight() == height && tempImp.getType() == type) {
                    ImageProcessor tempIp = tempImp.getProcessor();
                    tempIp.flipVertical();
                    imStack.addSlice(tempImp.getTitle(), tempIp);
                }
                else {
                    IJ.log(warnTitle + ": slice " + i + " skipped (wrong type or wrong dimensions).");
                }
            }

            // assign ImageStack and Calibration
            setStack(imStack);
            setCalibration(calib);
            setTitle(fileName);

            // show image
            if ( (arg==null) || arg.equals("") ) show();


        } catch (FileNotFoundException e) {
            IJ.showMessage(errTitle, "File not found.");
        } catch (IOException e) {
			IJ.showMessage(errTitle, "I/O error.");
        } finally { // cleaning up
            try {
                f.close();
            } catch (IOException e) {
                IJ.showMessage(errTitle, "I/O error.");
            }
            IJ.showStatus("");
		}


    }

    private boolean readFile() throws IOException {
    /*
        Read the header, go through file structure
        and save all important info in instance variables
    */

        /* read header */

        // read byte order
        byteOrder = getShort();
        if (byteOrder != 0x4949) {
            IJ.showMessage(errTitle, "Wrong byte order.");
            return false;
        }

        // read ser ID
        serID = getShort();
        if (serID != 0x0197) {
            IJ.showMessage(errTitle, "No ES Vision Series Data File.");
            return false;
        }

        // read ser version (important for number of bytes to read for offsets)
        verNum = getShort();
        oldVer = true ? (verNum < 0x220) : false;

        // data type ID: 1D or 2D (1D not supported!)
        dtypeID = getInt();
        if (dtypeID != 0x4122) {
            IJ.showMessage(errTitle, "1D data not supported. Please use another decoder.");
            return false;
        }

        // tag type ID:
        ttypeID = getInt();
        posTags = true ? (ttypeID == 0x4142) : false;

        // total number of elements, valid number of elements
        totNel = getInt();
        valNel = getInt();
//         IJ.showMessage("Number of valid elements (stack size)"+valNel);

        // offset of data offset array
        if (oldVer) {
            offDat = getInt();
        } else {
            offDat = getLong();
        }

        // number of dimensions: either 0 or 1, more than 1 not supported
        numDim = getInt();
        if (numDim != 1) {
            IJ.showMessage(errTitle, "Unsupported number of dimensions.\nOnly single images and image stacks are supported.");
            return false;
        }

        /* read dimension array */

        // dimension size
        dimSize = getInt();
        if (dimSize != totNel) { // should always be true for numDim = 1
            IJ.showMessage(errTitle, "Fatal error: Number of slices does not equal total number of images.");
            return false;
        }

        // calibration offset
        calOff = getDouble();

        // calibration delta
        calDel = getDouble();

        // calibration element
        calElem = getInt();

        // description length
        descrLen = getInt();
//         IJ.showMessage(""+descrLen);

        // description
        descr = "";
        for (int i=0; i<descrLen; i++) descr += Character.toChars(f.readByte())[0];
        // this seems complicated but reading chars directly results in a mess...
//         IJ.showMessage(""+descr);

        // units length
        unitLen = getInt();
//         IJ.showMessage(""+unitLen);

        // unit string
        unit = "";
        for (int i=0; i<unitLen; i++) unit += Character.toChars(f.readByte())[0];

        // check if we're still at the right place
        if (f.getFilePointer() != offDat) {
            IJ.showMessage(errTitle, "Fatal error: Got lost in the file.");
        }

        // initialize arrays for offsets, gather offsets
        dataOff = new long[totNel];
        tagOff = new long[totNel];
        for (int i=0; i<totNel; i++) dataOff[i] = getLong();
        for (int i=0; i<totNel; i++) tagOff[i] = getLong();
        // nice test: IJ.showMessage("File is larger than "+ tagOff[valNel-1]/1e9 + " GB");


        return true;
    }


    private ImagePlus getImage(int i) throws IOException {
        ImagePlus returnImage = null;

        // i'th slice: data and tag positions in file
        long dOff = dataOff[i-1];
        long tOff = tagOff[i-1];

        // 2D data variables
        f.seek(dOff);
        double calOffX = getDouble();
        double calDelX = getDouble();
        int calElemX = getInt();
        double calOffY = getDouble();
        double calDelY = getDouble();
        int calElemY = getInt();
        int dataType = getShort();
        int arrSizeX = getInt();
        int arrSizeY = getInt();
        long dataPos = f.getFilePointer();

        // data tag variables
        f.seek(tOff);
        int tagTypeID = getShort();
        getShort(); // 2 undocumented bytes mentioned by C. Boothroyd
        long time = getInt(); // long is needed for conversion from s to ms since 1970
        double posX = getDouble();
        double posY = getDouble();

        // change calibration from meters to appropriate unit
        int calUnit = 0; // n for factor 1000^n smaller than meter (1 is mm, 2 is um etc.)
        double d=calDelX*arrSizeX; // this is the image width in meters
        while (d < 10) { // for measurements and scalebars I don't want values << 1.0
            calUnit += 1;
            d *= 1000;
        }
        double unitFactor = Math.pow(1000, calUnit);
        String unitStr=""; //
        switch (calUnit) {
            case 0: unitStr="m";
                break;
            case 1: unitStr="mm";
                break;
            case 2: unitStr="um";
                break;
            case 3: unitStr="nm";
                break;
            case 4: unitStr="pm";
                break;
            case 5: unitStr="fm";
                break;
            default: unitStr="arb. u.";
                break;
        }

        // copy relevant info to FileInfo object and use FileOpener to read image data
        FileInfo fInfo = new FileInfo();
        fInfo.fileFormat = FileInfo.RAW;
        fInfo.intelByteOrder = true;
        fInfo.nImages = 1;
        fInfo.longOffset = dataPos;
        fInfo.whiteIsZero = false;
        fInfo.directory = directory;
        fInfo.fileName = fileName;
        fInfo.width = arrSizeX;
        fInfo.height = arrSizeY;
        fInfo.pixelWidth = calDelX*unitFactor;
        fInfo.pixelHeight = calDelY*unitFactor;
        fInfo.unit = unitStr;
        switch (dataType) {
            case 1: fInfo.fileType = FileInfo.GRAY8;
                    break;
            case 2: fInfo.fileType = FileInfo.GRAY16_UNSIGNED;
                    break;
            case 3: fInfo.fileType = FileInfo.GRAY32_UNSIGNED;
                    break;
            case 4: fInfo.fileType = FileInfo.GRAY16_SIGNED;
                    break;
            case 5: fInfo.fileType = FileInfo.GRAY16_SIGNED;
                    break;
            case 6: fInfo.fileType = FileInfo.GRAY32_INT;
                    break;
            case 7: fInfo.fileType = FileInfo.GRAY32_FLOAT;
                    break;
            case 8: fInfo.fileType = FileInfo.GRAY64_FLOAT;
                    break;
            default: return null;
        }

        // use FileOpener to read image data & create ImagePlus
        try {
            FileOpener fOpener = new FileOpener(fInfo);
            returnImage = fOpener.open(false); // w/o dialog
        } catch (Exception e) {
//             IJ.error(errTitle, "Exception reading slice.");
            return null;
        }

        // change image title to time string
        // Note that this will only have an effect for image stacks,
        // where date and time are used as titles for individual frames.
        // Title of final ImagePlus object will be overwritten with fileName.
        Date dTime = new Date(time*1000);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        returnImage.setTitle(format.format(dTime));

        return returnImage;
    }




    /*
    Functions for little endian byte order
    */

    final int getShort() throws IOException {
		int b1 = f.read();
		int b2 = f.read();
		return ((b2<<8) + b1<<0);
	}

    final int getInt() throws IOException {
		int b1 = f.read();
		int b2 = f.read();
		int b3 = f.read();
		int b4 = f.read();
		return ((b4<<24) + (b3<<16) + (b2<<8) + (b1<<0));
	}

	final long getLong() throws IOException {
		long b1 = f.read();
		long b2 = f.read();
		long b3 = f.read();
		long b4 = f.read();
		long b5 = f.read();
		long b6 = f.read();
		long b7 = f.read();
		long b8 = f.read();
		return ((b8<<56) + (b7<<48) + (b6<<40) + (b5<<32) + (b4<<24) + (b3<<16) + (b2<<8) + (b1<<0));
	}

	final float getFloat() throws IOException {
		return Float.intBitsToFloat(getInt());
	}

	final double getDouble() throws IOException {
		return Double.longBitsToDouble(getLong());
	}

}
