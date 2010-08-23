package edu.mit.csail.cgs.tools.chipseq;

import java.io.IOException;
import java.sql.SQLException;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSParser;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSPeak;
import edu.mit.csail.cgs.utils.NotFoundException;
import edu.mit.csail.cgs.utils.database.DatabaseException;
import edu.mit.csail.cgs.datasets.chipseq.ChipSeqAnalysisResult;

/**
 * See AnalysisImporter docs.  Command line options are the same; the only difference
 * is that GPSAnalysisImporter parses the GPS native output format.
 */

public class GPSAnalysisImporter extends AnalysisImporter {

    /* oracle complains about underflow if we don't limit the pvalues.  the actual 
       min value is somewhere between E-100 and E-200, but I didn't bother tracking 
       it down more closely since I don't think the difference really matters
    */
    public final static double minpval = Math.pow(10,-100);

    private int lineno = 0;

    public static void main(String args[]) throws NotFoundException, SQLException, DatabaseException, IOException {
        GPSAnalysisImporter importer = new GPSAnalysisImporter();
        importer.parseArgs(args);
        importer.run(System.in);
        importer.close();
    }
    public ChipSeqAnalysisResult parseLine(String line) {
        GPSPeak p = GPSParser.parseLine(getGenome(),
                                        line,
                                        ++lineno);



        return new ChipSeqAnalysisResult(getGenome(),
                                         p.getChrom(),
                                         p.getLocation(),
                                         p.getLocation()+1,
                                         p.getLocation(),
                                         p.getStrength(),
                                         p.getControlStrength(),
                                         p.getStrength(),
                                         p.getShape(),
                                         p.getPvalue(),
                                         p.getStrength()/p.getControlStrength());
    }


}