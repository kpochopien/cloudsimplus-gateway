/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package pl.edu.agh.csg;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.util.DataCloudTags;
import org.cloudbus.cloudsim.util.ResourceLoader;
import org.cloudbus.cloudsim.util.WorkloadReader;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipInputStream;

/**
 * Reads resource traces from a reader and
 * creates a list of ({@link Cloudlet Cloudlets}) (jobs). By default, it follows
 * the <a href="http://www.cs.huji.ac.il/labs/parallel/workload/">Standard Workload Format (*.swf files)</a>
 * from <a href="new.huji.ac.il/en">The Hebrew University of Jerusalem</a>. However, you can use other formats by
 * calling the methods below before running the simulation:
 * <ul>
 * <li> {@link #setComment(String)}
 * <li> {@link #setField(int, int, int, int, int)}
 * </ul>
 *
 * <p>
 * <b>NOTES:</b>
 * <ul>
 * <li>This class can only take <tt>one</tt> trace reader of the following format:
 * <i>ASCII text, zip, gz.</i>
 * <li>If you need to load multiple trace files, then you need to create
 * multiple instances of this class <tt>each with a unique entity name</tt>.
 * <li>If size of the trace reader is huge or contains lots of traces, please
 * increase the JVM heap size accordingly by using <tt>java -Xmx</tt> option
 * when running the simulation.
 * <li>The default Cloudlet reader size for sending to and receiving from a Datacenter is
 * {@link DataCloudTags#DEFAULT_MTU}. However, you can
 * specify the reader size by using {@link Cloudlet#setFileSize(long)}.
 * <li>A job run time is only for 1 PE <tt>not</tt> the total number of
 * allocated PEs. Therefore, a Cloudlet length is also calculated for 1 PE.<br>
 * For example, job #1 in the trace has a run time of 100 seconds for 2
 * processors. This means each processor runs job #1 for 100 seconds, if the
 * processors have the same specification.
 * </ul>
 * </p>
 *
 * @author Anthony Sulistio
 * @author Marcos Dias de Assuncao
 * @todo The last item in the list above is not true. The cloudlet length is not
 * divided by the number of PEs. If there is more than 1 PE, all PEs run the
 * same number of MI as specified in the {@link Cloudlet#getLength()}
 * attribute. See {@link Cloudlet#setNumberOfPes(long)} method documentation.
 * @see WorkloadReader
 */
public class WorkloadFileReader implements WorkloadReader {
    private final String filePath;
    private final InputStream reader;
    /**
     * List of Cloudlets created from the trace {@link #reader}.
     */
    private final List<Cloudlet> cloudlets;
    /**
     * If the field index of the job number ({@link #jobNum}) is equals to this
     * constant, it means the number of the job doesn't have to be gotten from
     * the trace reader, but has to be generated by this workload generator class.
     */
    private final int IRRELEVANT = -1;
    /**
     * @see #getMips()
     */
    private int mips;
    /**
     * Field index of job number.
     */
    private int jobNum = 0;
    /**
     * Field index of submit time of a job.
     */
    private int submitTime = 1;
    /**
     * Field index of execution time of a job.
     */
    private int runTime = 3;
    /**
     * Field index of number of processors needed for a job.
     */
    private int numProc = 4;
    /**
     * Field index of required number of processors.
     */
    private int reqNumProc = 7;
    /**
     * Field index of required running time.
     */
    private int reqRunTime = 8;
    /**
     * Field index of user who submitted the job.
     */
    private int userId = 11;
    /**
     * Field index of group of the user who submitted the job.
     */
    private int groupId = 12;
    /**
     * Max number of fields in the trace reader.
     */
    private int maxField = 18;
    /**
     * A string that denotes the start of a comment.
     */
    private String comment = ";";
    /**
     * A temp array storing all the fields read from a line of the trace reader.
     */
    private String[] fieldArray;

    /**
     * @see #getMaxLinesToRead()
     */
    private int maxLinesToRead;

    private Predicate<Cloudlet> predicate;

    private long jobPeSplitThreshold = 4;

    /**
     * Create a new WorkloadFileReader object.
     *
     * @param filePath the workload trace file path in one of the following formats: <i>ASCII text, zip, gz.</i>
     * @param reader   a {@link InputStreamReader} object to read the file
     * @param mips     the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     *                 Considering the workload reader provides the run time for each
     *                 application registered inside the reader, the MIPS value will be used
     *                 to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     *                 so that it's expected to execute, inside the VM with the given MIPS capacity,
     *                 for the same time as specified into the workload reader.
     * @throws FileNotFoundException
     * @throws IllegalArgumentException when the workload trace file name is null or empty; or the resource PE mips <= 0
     * @pre mips > 0
     * @see #getInstance(String, int)
     */
    private WorkloadFileReader(final String filePath, final InputStream reader, final int mips, final long jobPeSplitThreshold) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Invalid trace reader name.");
        }

        this.setMips(mips);
        this.reader = reader;
        this.filePath = filePath;

        /* A default predicate which indicates that a Cloudlet will be
           created for any job read from the workload reader.
           That is, there isn't an actual condition to create a Cloudlet. */
        this.predicate = c -> true;

        this.cloudlets = new ArrayList<>();
        this.maxLinesToRead = -1;
        this.jobPeSplitThreshold = jobPeSplitThreshold;
    }

    /**
     * Gets a {@link WorkloadFileReader} instance from a workload file
     * inside the <b>application's resource directory</b>.
     * Use the available constructors if you want to load a file outside the resource directory.
     *
     * @param fileName the workload trace <b>relative file name</b> in one of the following formats: <i>ASCII text, zip, gz.</i>
     * @param mips     the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     *                 Considering the workload reader provides the run time for each
     *                 application registered inside the reader, the MIPS value will be used
     *                 to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     *                 so that it's expected to execute, inside the VM with the given MIPS capacity,
     *                 for the same time as specified into the workload reader.
     * @throws IllegalArgumentException when the workload trace file name is null or empty; or the resource PE mips <= 0
     * @throws UncheckedIOException     when the file cannot be accessed (such as when it doesn't exist)
     * @pre mips > 0
     * @post $none
     */
    public static WorkloadFileReader getInstance(final String fileName, final int mips, long jobPeSplitThreshold) {
        final InputStream reader = ResourceLoader.getInputStream(WorkloadFileReader.class, fileName);
        return new WorkloadFileReader(fileName, reader, mips, jobPeSplitThreshold);
    }

    @Override
    public List<Cloudlet> generateWorkload() throws IOException {
        if (cloudlets.isEmpty()) {
            // create a temp array
            fieldArray = new String[maxField];

            /*@todo It would be implemented
            using specific classes to avoid ifs.
            If a new format is included, the code has to be
            changed to include another if*/
            if (filePath.endsWith(".gz")) {
                readGZIPFile(reader);
            } else if (filePath.endsWith(".zip")) {
                readZipFile(reader);
            } else {
                readTextFile(reader);
            }
        }

        return cloudlets;
    }

    @Override
    public WorkloadReader setPredicate(Predicate<Cloudlet> predicate) {
        this.predicate = predicate;
        return this;
    }

    /**
     * Sets the string that identifies the start of a comment line.
     *
     * @param comment a character that denotes the start of a comment, e.g. ";" or "#"
     * @return <code>true</code> if it is successful, <code>false</code> otherwise
     * @pre comment != null
     * @post $none
     */
    public boolean setComment(final String comment) {
        if (comment != null && !comment.trim().isEmpty()) {
            this.comment = comment;
            return true;
        }

        return false;
    }

    /**
     * Tells this class what to look in the trace reader. This method should be
     * called before the start of the simulation.
     * <p/>
     * By default, this class follows the standard workload format as specified
     * in <a
     * href="http://www.cs.huji.ac.il/labs/parallel/workload/">
     * http://www.cs.huji.ac.il/labs/parallel/workload/</a> <br>
     * However, you can use other format by calling this method.
     * <p/>
     * The parameters must be a positive integer number starting from 1. A
     * special case is where
     * <tt>jobNum == {@link #IRRELEVANT}</tt>, meaning the job or cloudlet ID
     * will be generate by the Workload class, instead of reading from the trace
     * reader.
     *
     * @param maxField   max. number of field/column in one row
     * @param jobNum     field/column number for locating the job ID
     * @param submitTime field/column number for locating the job submit time
     * @param runTime    field/column number for locating the job run time
     * @param numProc    field/column number for locating the number of PEs
     *                   required to run a job
     * @throws IllegalArgumentException if any of the arguments are not within
     *                                  the acceptable ranges
     * @pre maxField > 0
     * @pre submitTime > 0
     * @pre runTime > 0
     * @pre numProc > 0
     * @post $none
     */
    public void setField(
            final int maxField,
            final int jobNum,
            final int submitTime,
            final int runTime,
            final int numProc) {
        // need to subtract by 1 since array starts at 0.
        if (jobNum > 0) {
            this.jobNum = jobNum - 1;
        } else if (jobNum == 0) {
            throw new IllegalArgumentException("Invalid job number field.");
        } else {
            this.jobNum = -1;
        }

        // get the max. number of field
        if (maxField > 0) {
            this.maxField = maxField;
        } else {
            throw new IllegalArgumentException("Invalid max. number of field.");
        }

        // get the submit time field
        if (submitTime > 0) {
            this.submitTime = submitTime - 1;
        } else {
            throw new IllegalArgumentException("Invalid submit time field.");
        }

        // get the run time field
        if (runTime > 0) {
            reqRunTime = runTime - 1;
        } else {
            throw new IllegalArgumentException("Invalid run time field.");
        }

        // get the number of processors field
        if (numProc > 0) {
            reqNumProc = numProc - 1;
        } else {
            throw new IllegalArgumentException("Invalid number of processors field.");
        }
    }

    /**
     * Creates a Cloudlet with the given information.
     *
     * @param id      a Cloudlet ID
     * @param runTime The number of seconds the Cloudlet has to run.
     *                {@link Cloudlet#getLength()} is computed based on
     *                the {@link #getMips() mips} and this value.
     * @param numProc number of Cloudlet's PEs
     * @return the created Cloudlet
     * @see #mips
     */
    private Cloudlet createCloudlet(
            final int id,
            final int runTime,
            final long numProc,
            final double submissionDelay) {
        final long len = runTime * (long) mips;
        final UtilizationModel utilizationModel = new UtilizationModelFull();
        Cloudlet cloudlet = new CloudletSimple(id, len, numProc)
                .setFileSize(DataCloudTags.DEFAULT_MTU)
                .setOutputSize(DataCloudTags.DEFAULT_MTU)
                .setUtilizationModel(utilizationModel);
        cloudlet.setSubmissionDelay(submissionDelay);
        return cloudlet;
    }

    /**
     * Extracts relevant information from a given array of fields, representing
     * a line from the trace reader, and creates a cloudlet using this
     * information.
     *
     * @param array the array of fields generated from a line of the trace reader.
     * @return the created Cloudlet
     */
    private List<Cloudlet> createCloudletFromTraceLine(final String[] array) {
        Integer obj;

        // get the submit time
        final Long l = Long.valueOf(array[submitTime].trim());
        //@todo the submission time would be used as the Cloudlet submission delay
        final long submitTime = l.intValue();

        // if the required run time field is ignored, then use the actual run time
        obj = Integer.valueOf(array[runTime].trim());
        int runTime = obj;

        // according to the SWF manual, runtime of 0 is possible due
        // to rounding down. E.g. runtime is 0.4 seconds -> runtime = 0
        if (runTime <= 0) {
            runTime = 1; // change to 1 second
        }

        // get the number of allocated processors
        obj = Integer.valueOf(array[reqNumProc].trim());
        int numProc = obj;

        /* if the required num of allocated processors field is ignored
        or zero, then use the actual field
        */
        if (numProc == IRRELEVANT || numProc == 0) {
            obj = Integer.valueOf(array[this.numProc].trim());
            numProc = obj;
        }

        // finally, check if the num of PEs required is valid or not
        if (numProc <= 0) {
            numProc = 1;
        }

        // get the job number
        int beginningId = cloudlets.size() + 1;

        List<Cloudlet> cloudlets = new ArrayList<>();
        int remainingPEs = numProc;
        int id = beginningId;

        while(remainingPEs > this.jobPeSplitThreshold) {
            cloudlets.add(createCloudlet(id++, runTime, this.jobPeSplitThreshold, submitTime / 1000.0));
            remainingPEs -= this.jobPeSplitThreshold;
        }
        cloudlets.add(createCloudlet(id++, runTime, remainingPEs, submitTime / 1000.0));

        return cloudlets;
    }

    /**
     * Breaks a line from the trace reader into many fields into the
     * {@link #fieldArray} and create a Cloudlet from it
     * if the {@link #setPredicate(Predicate) Predicate} is met
     * and the line is not commented.
     *
     * @param line a line from the trace reader
     * @return the created {@link Cloudlet} or {@link Cloudlet#NULL}
     * if, after reading the trace line, the conditions
     * to create the Cloudlet were not met or the line read
     * was commented.
     * @see #setPredicate(Predicate)
     */
    private List<Cloudlet> parseTraceLineAndCreateCloudlets(final String line) {
        // skip a comment line
        if (line.startsWith(comment)) {
            return Arrays.asList(Cloudlet.NULL);
        }

        final String[] sp = line.split("\\s+"); // split the fields based on a space
        int index = 0; // the index of an array

        // check for each field in the array
        for (final String elem : sp) {
            if (!elem.trim().isEmpty()) {
                fieldArray[index] = elem;
                index++;
            }
        }

        //If all the fields could not be read, don't create the Cloudlet.
        if (index < maxField) {
            return Arrays.asList(Cloudlet.NULL);
        }

        final List<Cloudlet> c = createCloudletFromTraceLine(fieldArray);

        return c.stream().filter(predicate).collect(Collectors.toList());
    }

    /**
     * Reads traces from a InputStream to a workload reader
     * in any supported format.
     *
     * @param inputStream the stream that is able to read data from a workload reader
     * @return <code>true</code> if successful, <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the reader
     */
    private void readFile(final InputStream inputStream) throws IOException {
        //the reader is safely closed by the caller
        final BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        int line = 1;
        String readLine;
        while ((readLine = readNextLine(reader, line)) != null) {
            final List<Cloudlet> readCloudlets = parseTraceLineAndCreateCloudlets(readLine);
            final List<Cloudlet> nonNullCloudlets = readCloudlets.stream().filter(c -> c != Cloudlet.NULL).collect(Collectors.toList());
            if (nonNullCloudlets.size() > 0) {
                cloudlets.addAll(nonNullCloudlets);
                line++;
            }
        }
    }

    /**
     * Reads traces from a text reader, usually with the swf extension, one line at a time.
     *
     * @param inputStream a reader name
     * @return <code>true</code> if successful, <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the reader
     */
    protected void readTextFile(final InputStream inputStream) throws IOException {
        readFile(inputStream);
    }

    /**
     * Reads traces from a gzip reader, one line at a time.
     *
     * @param inputStream a {@link InputStream} to read the file
     * @return <code>true</code> if successful; <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the reader
     */
    protected void readGZIPFile(final InputStream inputStream) throws IOException {
        readFile(new GZIPInputStream(inputStream));
    }

    /**
     * Reads a set of trace files inside a Zip reader.
     *
     * @param inputStream a {@link InputStream} to read the file
     * @return <code>true</code> if reading a reader is successful;
     * <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the reader
     */
    protected boolean readZipFile(final InputStream inputStream) throws IOException {
        try (ZipInputStream zipFile = new ZipInputStream(inputStream)) {
            while (zipFile.getNextEntry() != null) {
                readFile(zipFile);
            }
            return true;
        }
    }

    /**
     * Reads the next line of the workload reader.
     *
     * @param reader     the object that is reading the workload reader
     * @param lineNumber the number of the line that that will be read from the workload reader
     * @return the line read; or null if there isn't any more lines to read or if
     * the number of lines read reached the {@link #getMaxLinesToRead()}
     */
    private String readNextLine(BufferedReader reader, int lineNumber) throws IOException {
        if (reader.ready() && (maxLinesToRead == -1 || lineNumber <= maxLinesToRead)) {
            return reader.readLine();
        }

        return null;
    }

    /**
     * Gets the maximum number of lines of the workload reader that will be read.
     * The value -1 indicates that all lines will be read, creating
     * a cloudlet from every one.
     *
     * @return
     */
    public int getMaxLinesToRead() {
        return maxLinesToRead;
    }

    /**
     * Sets the maximum number of lines of the workload reader that will be read.
     * The value -1 indicates that all lines will be read, creating
     * a cloudlet from every one.
     *
     * @param maxLinesToRead the maximum number of lines to set
     */
    public void setMaxLinesToRead(int maxLinesToRead) {
        this.maxLinesToRead = maxLinesToRead;
    }

    /**
     * Gets the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     * Considering the workload reader provides the run time for each
     * application registered inside the reader, the MIPS value will be used
     * to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     * so that it's expected to execute, inside the VM with the given MIPS capacity,
     * for the same time as specified into the workload reader.
     */
    public int getMips() {
        return mips;
    }

    /**
     * Sets the MIPS capacity of the PEs from the VM where each created Cloudlet is supposed to run.
     * Considering the workload reader provides the run time for each
     * application registered inside the reader, the MIPS value will be used
     * to compute the {@link Cloudlet#getLength() length of the Cloudlet (in MI)}
     * so that it's expected to execute, inside the VM with the given MIPS capacity,
     * for the same time as specified into the workload reader.
     *
     * @param mips the MIPS value to set
     */
    public final WorkloadFileReader setMips(final int mips) {
        if (mips <= 0) {
            throw new IllegalArgumentException("MIPS must be greater than 0.");
        }
        this.mips = mips;
        return this;
    }

}
