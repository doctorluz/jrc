package jrc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.Operator;
import com.esri.core.geometry.OperatorContains;
import com.esri.core.geometry.OperatorFactoryLocal;
import com.esri.core.geometry.OperatorIntersects;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.hadoop.hive.GeometryUtils;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentLengthException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDTF;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.BinaryObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.io.BooleanWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.LongWritable;
import org.json.JSONException;

public class CellUdtf extends GenericUDTF {

  private static final Log LOG = LogFactory.getLog(CellUdtf.class);
  private static final int MIN_LAT = -90;
  private static final int MAX_LAT = 90;
  private static final int MIN_LON = -180;
  private static final int MAX_LON = 180;

  private static final SpatialReference SPATIAL_REFERENCE = SpatialReference.create(4326);
  private final OperatorIntersects intersectsOperator =
    (OperatorIntersects) OperatorFactoryLocal.getInstance().getOperator(Operator.Type.Intersects);
  private final OperatorContains containsOperator =
    (OperatorContains) OperatorFactoryLocal.getInstance().getOperator(Operator.Type.Contains);

  private Double cellSize;
  private long maxLonCell;
  private long maxLatCell;
  private DoubleObjectInspector doi;
  private BinaryObjectInspector boi;

  private final Object[] result = new Object[2];
  private final LongWritable cellWritable = new LongWritable();
  private final BooleanWritable fullyCoveredWritable = new BooleanWritable();

  public static void main(String... args) throws HiveException, IOException, JSONException {
    OGCGeometry ogcGeometry =
      //OGCGeometry.fromText("POLYGON ((-179.8 -89.8, -179.2 -89.8, -179.2 -89.2, -179.8 -89.2, -179.8 -89.8))");
      //OGCGeometry.fromText("POLYGON ((0.2 0.2, 0.8 0.2, 0.8 0.8, 0.2 0.8, 0.2 0.2))");
      //OGCGeometry.fromText("POLYGON ((-10 -10, 10 -10, 10 10, -10 10, -10 -10))");
    //  OGCGeometry.fromText("POLYGON ((-180 -90, 180 -90, 180 90, -180 90))");
    //OGCGeometry.fromText("POLYGON ((170 0, -170 0, -170 10, 170 10, 170 0))");
    OGCGeometry.fromJson(Resources.toString(Resources.getResource("species8543.json"), Charsets.US_ASCII));
    BytesWritable writable = GeometryUtils.geometryToEsriShapeBytesWritable(ogcGeometry);
    CellUdtf udf = new CellUdtf();

    ObjectInspector[] oi = {
      PrimitiveObjectInspectorFactory.javaDoubleObjectInspector,
      PrimitiveObjectInspectorFactory.writableBinaryObjectInspector
    };
    udf.initialize(oi);


    Object[] udfArgs = {
      0.1,
      writable
    };
    udf.process(udfArgs);

    //List<Long> evaluate = udf.evaluate(0.01, writable);
  }

  @Override
  public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
    if (argOIs.length != 2) {
      throw new UDFArgumentLengthException("cell() takes two arguments: cell size and geometry");
    }

    List<String> fieldNames = new ArrayList<String>();
    List<ObjectInspector> fieldOIs = new ArrayList<ObjectInspector>();

    if (argOIs[0].getCategory() != ObjectInspector.Category.PRIMITIVE || !argOIs[0].getTypeName()
      .equals(serdeConstants.DOUBLE_TYPE_NAME)) {
      throw new UDFArgumentException("cell(): cell_size has to be a double");
    }

    if (argOIs[1].getCategory() != ObjectInspector.Category.PRIMITIVE || !argOIs[1].getTypeName()
      .equals(serdeConstants.BINARY_TYPE_NAME)) {
      throw new UDFArgumentException("cell(): geom has to be binary");
    }

    doi = (DoubleObjectInspector) argOIs[0];
    boi = (BinaryObjectInspector) argOIs[1];

    fieldNames.add("cell");
    fieldNames.add("fullyCovered");
    fieldOIs.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
    fieldOIs.add(PrimitiveObjectInspectorFactory.writableBooleanObjectInspector);
    result[0] = cellWritable;
    result[1] = fullyCoveredWritable;

    return ObjectInspectorFactory.getStandardStructObjectInspector(fieldNames, fieldOIs);
  }

  @Override
  public void process(Object[] args) throws HiveException {
    if (cellSize == null) {
      cellSize = doi.get(args[0]);
      maxLonCell = (int) Math.floor((2 * MAX_LON) / cellSize);
      maxLatCell = (int) Math.floor((2 * MAX_LAT) / cellSize);
    }

    // 1. Create bounding box
    OGCGeometry ogcGeometry = GeometryUtils.geometryFromEsriShape(boi.getPrimitiveWritableObject(args[1]));
    if (ogcGeometry == null) {
      LOG.warn("Geometry is null");
      return;
    }

    if (ogcGeometry.isEmpty()) {
      LOG.warn("Geometry is empty");
      return;
    }

    if (!"Polygon".equals(ogcGeometry.geometryType()) && !"MultiPolygon".equals(ogcGeometry.geometryType())) {
      LOG.warn("Geometry is not a polygon: " + ogcGeometry.geometryType());
      return;
    }

    Envelope envBound = new Envelope();
    ogcGeometry.getEsriGeometry().queryEnvelope(envBound);
    if (envBound.isEmpty()) {
      LOG.warn("Envelope is empty");
      return;
    }

    // 2. Get all cells
    getCellsEnclosedBy(envBound.getYMin(),
                       envBound.getYMax(),
                       envBound.getXMin(),
                       envBound.getXMax(),
                       cellSize,
                       ogcGeometry.getEsriGeometry());
  }

  @Override
  public void close() throws HiveException {
  }

  private Envelope initCellEnvelope(long cell) {
    long row = cell / maxLonCell;
    long col = cell % maxLonCell;
    Envelope envelope = new Envelope(MIN_LON + col * cellSize,
                                     MIN_LAT + row * cellSize,
                                     MIN_LON + col * cellSize + cellSize,
                                     MIN_LAT + row * cellSize + cellSize);
    intersectsOperator.accelerateGeometry(envelope, SPATIAL_REFERENCE, Geometry.GeometryAccelerationDegree.enumHot);
    return envelope;
  }

  private long toCellId(double latitude, double longitude, double cellSize) throws HiveException {
    if (latitude < MIN_LAT || latitude > MAX_LAT || longitude < MIN_LON || longitude > MAX_LON) {
      throw new HiveException("Invalid coordinates");
    } else {
      long la = getLatitudeId(latitude, cellSize);
      long lo = getLongitudeId(longitude, cellSize);
      return Math.min(Math.max(la + lo, 0), maxLatCell * maxLonCell - 1);
    }
  }

  /*
     floor((latitude + MAX_LAT) / cellSize) * ((2 * MAX_LON) / cellSize)
   */
  private long getLatitudeId(double latitude, double cellSize) {
    return new Double(Math.floor((latitude + MAX_LAT) / cellSize) * maxLonCell).longValue();
  }

  /*
     cell: floor((longitude + MAX_LON) / cell size)
     max:  2 * MAX_LON / cell size)
   */
  private long getLongitudeId(double longitude, double cellSize) {
    return new Double(Math.floor((longitude + MAX_LON) / cellSize)).longValue();
  }

  private void getCellsEnclosedBy(
    double minLat, double maxLat, double minLon, double maxLon, double cellSize, Geometry ogcGeometry
  ) throws HiveException {

    if (LOG.isDebugEnabled()) {
      LOG.debug("Establishing cells enclosed by (lon/lat), min: "
                + minLon
                + "/"
                + minLat
                + ", max: "
                + maxLon
                + "/"
                + maxLat);
    }

    // Create a 1 cell buffer around the area in question
    minLat = Math.max(MIN_LAT, minLat - cellSize);
    minLon = Math.max(MIN_LON, minLon - cellSize);

    maxLat = Math.min(MAX_LAT, maxLat + cellSize);
    maxLon = Math.min(MAX_LON, maxLon + cellSize);

    long lower = toCellId(minLat, minLon, cellSize);
    long upper = toCellId(maxLat, maxLon, cellSize);

    LOG.info("Unprocessed cells: " + lower + " -> " + upper);

    // Clip to the cell limit
    lower = Math.max(0, lower);
    upper = Math.min(maxLonCell * maxLatCell - 1, upper);

    LOG.info("Checking cells between " + lower + " and " + upper);

    long omitLeft = lower % maxLonCell;
    long omitRight = upper % maxLonCell;
    if (omitRight == 0) {
      omitRight = maxLonCell;
    }

    intersectsOperator.accelerateGeometry(ogcGeometry,
                                          SPATIAL_REFERENCE,
                                          Geometry.GeometryAccelerationDegree.enumHot);

    for (long i = lower; i <= upper; i++) {
      if (i % maxLonCell >= omitLeft && i % maxLonCell <= omitRight) {
        Envelope cell = initCellEnvelope(i);
        if (intersects(cell, ogcGeometry)) {
          if (contains(cell, ogcGeometry)) {
            cellWritable.set(i);
            fullyCoveredWritable.set(true);
            forward(result);
          } else {
            cellWritable.set(i);
            fullyCoveredWritable.set(false);
            forward(result);
          }
        }
      }

    }
  }

  private boolean intersects(Envelope cell, Geometry ogcGeometry) {
    return intersectsOperator.execute(ogcGeometry, cell, SPATIAL_REFERENCE, null);
  }

  private boolean contains(Envelope cell, Geometry ogcGeometry) {
    return containsOperator.execute(ogcGeometry, cell, SPATIAL_REFERENCE, null);
  }

}