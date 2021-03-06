package edu.oregonstate.cartography.grid.operators;

import edu.oregonstate.cartography.grid.Grid;
import edu.oregonstate.cartography.gui.ProgressIndicator;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Computes illuminated contour lines from a digital elevation model.
 *
 * @author Jim Eynard and Bernhard Jenny, Cartography and Geovisualization
 * Group, Oregon State University
 */
public class IlluminatedContoursOperator extends ThreadedGridOperator {

    // anti-aliasing width is half a cell size
    private final double AA_DIST_PX = 0.5;

    // a progress indicator for communicating progress and for checking for cancel events
    private ProgressIndicator progress = null;
    // this image will receive the computed contour lines
    private BufferedImage image;
    // illuminated and shaded or only shaded contours
    private final boolean illuminated;
    // color of illuminated contour lines
    private final int illuminatedColor;
    // color of shadowed contour lines
    private final int shadowedColor;
    // width of lowest shaded lines
    private final double shadowWidthLow;
    // width of highestshaded lines
    private final double shadowWidthHigh;
    // width of lowest illuminated lines
    private final double illuminatedWidthLow;
    // width of highest illuminated lines
    private final double illuminatedWidthHigh;
    // minimum line width
    private final double minWidth;
    // minimum distance between lines
    private final double minLineDist;
    // azimuth of illumination from north in clockwise direction in degrees
    private final double azimuth;
    // contour interval
    private final double interval;
    // a gradient between black and white is applied inside this transition angle
    // in degree
    private final int gradientAngle;
    // standard deviation of Gaussian blur filter applied to grid to create smoothGrid
    private final double aspectGaussBlur;
    // a low-pass version of the source grid. Created with standard deviation
    // of aspectGaussBlur
    private Grid smoothGrid;
    // transition angle between illuminated and shaded contour lines, usually 90 degrees
    private final int transitionAngle;
    // pixel buffer to render to
    private int[] imageBuffer;
    // lowest elevation in grid
    private final float gridMin;
    // highest elevation in grid
    private final float gridMax;
    
    /**
     *
     * @param illuminated
     * @param illuminatedColor
     * @param shadowedColor
     * @param shadowWidthLow
     * @param shadowWidthHigh
     * @param illuminatedWidthLow
     * @param minWidth
     * @param illuminatedWidthHigh
     * @param minLineDist
     * @param azimuth
     * @param interval
     * @param gradientAngle
     * @param aspectGaussBlur
     * @param transitionAngle
     * @param gridMinMax
     */
    public IlluminatedContoursOperator(boolean illuminated,
            int illuminatedColor,
            int shadowedColor,
            double shadowWidthLow,
            double shadowWidthHigh,
            double illuminatedWidthLow,
            double illuminatedWidthHigh,
            double minWidth,
            double minLineDist,
            double azimuth,
            double interval,
            int gradientAngle,
            double aspectGaussBlur,
            int transitionAngle,
            float[] gridMinMax) {
        this.illuminated = illuminated;
        // set first byte to 0 for later bitwise or operation with alpha value
        this.illuminatedColor = (illuminatedColor << 8) >>> 8;
        // set first byte to 0 for later bitwise or operation with alpha value
        this.shadowedColor = (shadowedColor << 8) >>> 8;
        this.shadowWidthLow = shadowWidthLow;
        this.shadowWidthHigh = shadowWidthHigh;
        this.illuminatedWidthLow = illuminatedWidthLow;
        this.illuminatedWidthHigh = illuminatedWidthHigh;
        this.minWidth = minWidth;
        this.minLineDist = minLineDist;
        this.azimuth = azimuth;
        this.interval = Math.abs(interval);
        this.gradientAngle = gradientAngle;
        this.aspectGaussBlur = aspectGaussBlur;
        this.transitionAngle = transitionAngle;
        this.gridMin = gridMinMax[0];
        this.gridMax = gridMinMax[1];
    }

    /**
     * Renders contours to the passed image.
     *
     * @param destinationImage Image must be this.scale times larger than the
     * grid.
     * @param grid Grid with elevation values.
     * @param slopeGrid Grid with slope values.
     * @param progress Progress indicator. Not used when scale is 1.
     */
    public void renderToImage(BufferedImage destinationImage, Grid grid, Grid slopeGrid, ProgressIndicator progress) {
        if (destinationImage == null) {
            throw new IllegalArgumentException();
        }
        this.image = destinationImage;
        this.progress = progress;
        this.imageBuffer = ((DataBufferInt) (image.getRaster().getDataBuffer())).getData();
        this.smoothGrid = new GridGaussLowPassOperator(aspectGaussBlur).operate(grid);
        super.operate(grid, slopeGrid);
    }

    /**
     * Compute a chunk of the destination grid.
     *
     * @param src The source terrain elevation grid.
     * @param slopeGrid Slope grid.
     * @param startRow The index of the first row of the source grid.
     * @param endRow The index of the first row of the source grid.
     */
    @Override
    protected void operate(Grid src, Grid slopeGrid, int startRow, int endRow) {
        startRow = Math.max(1, startRow);
        endRow = Math.min(src.getRows() - 2, endRow);
        int cols = src.getCols();

        int scale = image.getWidth() / src.getCols();
        if (scale == 1) {
            for (int row = startRow; row < endRow; row++) {
                for (int col = 1; col < cols - 1; col++) {
                    illuminatedContours(src, col, row);
                }
            }
        } else {
            // only report progress if this is the first chunk of the image
            // all chunks are the same size, but are rendered in different threads.
            boolean reportProgress = startRow == 1 && progress != null;

            for (int row = startRow; row < endRow; row++) {
                // stop rendering if the user canceled
                if (progress != null && progress.isCancelled()) {
                    return;
                }

                // report progress made
                if (reportProgress) {
                    int percentage = Math.round(100f * row / (endRow - startRow));
                    progress.progress(percentage);
                }

                // destination has different size than source grid.
                for (int col = 1; col < cols - 1; col++) {
                    scaledIlluminatedContours(src, col, row, slopeGrid, scale);
                }
            }
        }
    }

    /**
     * Compute a single grid value with illuminated contours that has the same
     * size as the source grid.
     *
     * @param src The source terrain elevation grid.
     * @param dst The destination grid with illuminated contour lines.
     * @param col The column in the source grid.
     * @param row The row in the source grid.
     */
    private void illuminatedContours(Grid src, int col, int row) {
        double elevation = src.getValue(col, row);
        double smoothAspect = smoothGrid.getAspect(col, row);
        smoothAspect = (smoothAspect + Math.PI) * 180 / Math.PI;
        double slope = src.getSlope(col, row);
        int g = computeGray(elevation, smoothAspect, slope, src.getCellSize());
        if ((g >>> 24) != 0) {
            //int argb = (int) g | ((int) g << 8) | ((int) g << 16) | 0xFF000000;
            imageBuffer[row * image.getWidth() + col] = g;
        }
    }

    /**
     * Compute a grid values corresponding to one cell in the source grid. The
     * destination grid has a different size than the source grid.
     *
     * @param src The source terrain elevation grid.
     * @param col The column in the source grid.
     * @param row The row in the source grid.
     * @param scale The image is this many times larger than the terrain model
     * grid.
     */
    private void scaledIlluminatedContours(Grid src, int col, int row,
            Grid slopeGrid, int scale) {
        final double cellSize = src.getCellSize();
        final double samplingDist = cellSize / scale / 100;
        final double west = src.getWest();
        final double north = src.getNorth();

        // render scale x scale subcells in the destination grid
        for (int r = 0; r < scale; r++) {
            for (int c = 0; c < scale; c++) {
                // convert column and row to geographic coordinates
                double x = west + ((col + (double) c / scale) * cellSize);
                double y = north - ((row + (double) r / scale) * cellSize);
                double elevation = src.getBilinearInterpol(x, y);
                double smoothAspect = smoothGrid.getAspect(x, y, samplingDist);
                smoothAspect = (smoothAspect + Math.PI) * 180 / Math.PI;
                double slopeVal = slopeGrid.getBilinearInterpol(x, y);
                int g = computeGray(elevation, smoothAspect, slopeVal, cellSize);
                if ((g >>> 24) != 0) {
                    int imageCol = col * scale + c;
                    int imageRow = row * scale + r;
                    imageBuffer[imageRow * image.getWidth() + imageCol] = g;
                }
            }
        }
    }

    @Override
    public String getName() {
        return "Illuminated Contours";
    }

    /**
     * Returns the smallest angle between two angles
     *
     * @param a angle from east counterclockwise in degrees
     * @param b angle from east counterclockwise in degrees
     * @return The minimum angle between the two angles in degrees
     */
    private static double smallestAngleDiff(double a, double b) {
        final double d = Math.abs(a - b) % 360.;
        return (d > 180.) ? 360. - d : d;
    }

    /**
     * S-shaped smooth function using cubic Hermite interpolation
     * http://en.wikipedia.org/wiki/Smoothstep
     *
     * @param edge0 interpolated values for x below edge0 will be 0.
     * @param edge1 interpolated values for x above edge1 will be 1.
     * @param x The x value to interpolate a value for.
     * @return
     */
    private static double smoothstep(double edge0, double edge1, double x) {
        // scale, bias and saturate x to 0..1 range
        x = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
        // evaluate polynomial
        return x * x * (3 - 2 * x);

        // alternative smootherstep
        // return x * x * x * (x * (x * 6 - 15) + 10);
    }

    /**
     * Compute the gray value for the illuminated contour line image
     *
     * @param elevation Elevation of the point.
     * @param aspectDeg Terrain aspect at the point in degrees.
     * @param slopePerc Terrain slope at the point in rise/run [0..1].
     * @param cellSize Size of a grid cell. Same units as elevation parameter.
     * @return Gray value between 0 and 255.
     */
    private int computeGray(double elevation, double aspectDeg, double slopePerc, double cellSize) {
        final int BACKGROUND_COLOR = 0x00FFFFFF; // transparent white

        if (Double.isNaN(elevation) || Double.isNaN(aspectDeg) || slopePerc < 10e-11) {
            return BACKGROUND_COLOR;
        }

        // convert azimuth angle to geometric angle, from east counterclockwise
        double illuminationDeg = 90 - azimuth;
        // calculate minumum angle between illumination angle and aspect
        double angleDiffDeg = smallestAngleDiff(illuminationDeg, aspectDeg);
        double angleDiffRad = angleDiffDeg / 180. * Math.PI;

        // vary the shadowed and illuminated line widths with elevation
        double w = (gridMax - elevation) / (gridMax - gridMin);
        //double gamma = 2;
        //w = Math.pow(w, 1d / gamma);
        double shadowWidthPx = w * (shadowWidthLow - shadowWidthHigh) + shadowWidthHigh;

        // compute the line width (in pixels), which varies with the orientation relative
        // to the illumination direction.
        double lineWidthPx;
        if (illuminated) {
            //convert to radians
            double transitionAngleRad = transitionAngle / 180. * Math.PI;

            if (angleDiffDeg > transitionAngle) {
                //scale angleDiff to range between transitionAngle and 180 degrees
                angleDiffRad = (((angleDiffRad - transitionAngleRad) / (Math.PI - transitionAngleRad)) * (Math.PI / 2)) + (Math.PI / 2);
                //modulate with cosine
                lineWidthPx = shadowWidthPx * Math.abs(Math.cos(angleDiffRad));
            } else {
                //scale angleDiff to range between 0 and transitionAngle
                angleDiffRad = angleDiffRad / transitionAngleRad * (Math.PI / 2);
                double illuminatedWidth = w * (illuminatedWidthLow - illuminatedWidthHigh) + illuminatedWidthHigh;
                //modulate with cosine
                lineWidthPx = illuminatedWidth * Math.abs(Math.cos(angleDiffRad));
            }
        } else {
            //for shadowed contours
            //modulate with sine
            lineWidthPx = shadowWidthPx * Math.abs(Math.sin(angleDiffRad / 2));
        }

        // compute vertical z distance (in meters) to closest contour line
        // the sign of zDist equals the sign of the dividend (the number left of %)
        double zDist_m = Math.abs(elevation) % interval;
        if (zDist_m > interval / 2) {
            zDist_m = interval - zDist_m;
        }

        // maximum possible line width such that contours lines keep a minimum
        // distance to each other for the given slope
        // The line is shrunk by half of the minimum line distance if it is too 
        // close to a neighbor. (The neighbor is shrunk by the other half.)
        double maxLineWidth_m = interval / slopePerc - minLineDist * cellSize / 2;

        // make very thick lines thinner to avoid overlapping lines.
        // convert line width from pixels to units of z values (e.g. meters)
        double lineWidth_m = Math.min(maxLineWidth_m, lineWidthPx * cellSize);

        // make very thin lines thicker. The minimum line width parameter can override 
        // the minimum distance parameter. This is to make sure lines don't get
        // very thin in steep shadowed slopes.
        lineWidth_m = Math.max(lineWidth_m, minWidth * cellSize);
        double halfLineWidth_m = lineWidth_m / 2;

        // width of anti-aliased band along the outter border of the line
        double antiAliasingDist_m = AA_DIST_PX * cellSize;
        // make sure anti-aliasing distance is not wider than half of the line width
        // linearly shrink the anti-aliasing band, such that a line width of 0
        // has a anti-alising band with a width of 0.
        if (halfLineWidth_m < antiAliasingDist_m) {
            antiAliasingDist_m = halfLineWidth_m;
        }

        double t_m = zDist_m / slopePerc;

        // antialiasing increases the width of the line by antiAliasingDist_m
        if (t_m > halfLineWidth_m + antiAliasingDist_m) {
            return BACKGROUND_COLOR;
        }
        int alpha = 255 - (int) (255. * smoothstep(halfLineWidth_m,
                halfLineWidth_m + antiAliasingDist_m, t_m));
        
        if (!illuminated || angleDiffDeg >= (transitionAngle + gradientAngle)) {
            // shadowed side: return the color for shadowed slopes with alpha value
            return shadowedColor | (alpha << 24);
        } else if (angleDiffDeg <= (transitionAngle - gradientAngle)) {
            // illuminated side: return color for illuminated slopes with alpha value
            return (illuminated ? illuminatedColor : shadowedColor) | (alpha << 24);
        } else {
            // gradient between shaded and illuminated side: blend between
            // the color for illuminated slopes and the color for shaded slopes
            // and add the alpha value
            double d = transitionAngle + gradientAngle - angleDiffDeg;
            double colorW = d / (2. * gradientAngle);
            return mixColors(shadowedColor, illuminatedColor, alpha, (int) (colorW * 255d));
        }
    }

    /**
     * Mix two transparent RGB colors and assign an alpha value.
     * @param c1 Color 1. Alpha byte must be 0.
     * @param c2 Color 2. Alpha byte must be 0.
     * @param a Alpha value in 0..255
     * @param w Weight of the first color in 0..255
     * @return ARGB
     */
    private static int mixColors(int c1, int c2, int a, int w) {
        int r1 = (c1 >>> 16) & 0x000000FF;
        int g1 = (c1 >>> 8) & 0x000000FF;
        int b1 = c1 & 0x000000FF;
        
        int r2 = (c2 >>> 16) & 0x000000FF;
        int g2 = (c2 >>> 8) & 0x000000FF;
        int b2 = c2 & 0x000000FF;

        int r = w * (r2 - r1) / 255 + r1;
        int g = w * (g2 - g1) / 255 + g1;
        int b = w * (b2 - b1) / 255 + b1;

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
