package edu.mit.csail.cgs.tools.chipseq;

import java.util.*;
import java.io.*;
import java.sql.*;
import edu.mit.csail.cgs.datasets.general.*;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.species.Organism;
import edu.mit.csail.cgs.utils.NotFoundException;
import edu.mit.csail.cgs.utils.database.DatabaseException;
import edu.mit.csail.cgs.tools.utils.Args;
import edu.mit.csail.cgs.datasets.chipseq.*;

/**
 * Imports the results from a chipseq analysis (ie, peak calling) to the database.
 * Command line args are
 * --species "species;genome"
 * --name "iCdx2 at Day4 in P2A, bowtie_unique"
 * --version "GPS stringent threshold"
 * --program "GPS, git commit abc123"
 * [--inactive]
 * [--paramfile params.txt]
 * [--foreground "exptname;exprreplicate;alignname"]  (can specify multiple --foreground)
 * [--background "exptname;exprreplicate;alignname"]  (can specify multiple --background)
 * 
 * Peak calls are read on stdin as TSV.  Columns are
 * mandatory:
 * 1) chromosome name
 * 2) startpos
 * 3) stoppos
 * optional:
 * 4) position (eg, center of peak)
 * 5) fgcount (double, number of foreground reads for the event)
 * 6) bgcount (double)
 * 7) strength (double)
 * 8) shape (double)
 * 9) pvalue (double)
 * 10 fold enrichment (double)
 *
 * Paramsfile is in key=value format
 *
 * specify --inactive if you want to save the results but don't want them to show up by default in lists of analyses.
 * You might do this, eg, for early rounds of GPS output.
 *
 */

public class AnalysisImporter {

    public static void main(String args[]) throws NotFoundException, SQLException, DatabaseException, IOException {
        AnalysisImporter importer = new AnalysisImporter();
        importer.parseArgs(args);
        importer.run(System.in);
        importer.close();
    }

    private ChipSeqAnalysis analysis;
    private Genome genome;
    public AnalysisImporter() {}
    public void parseArgs(String args[]) throws NotFoundException, SQLException, DatabaseException, IOException {
        String name = Args.parseString(args,"name",null);
        String version = Args.parseString(args,"version",null);
        String program = Args.parseString(args,"program",null);
        genome = Args.parseGenome(args).cdr();
        ChipSeqLoader loader = new ChipSeqLoader();
        analysis = new ChipSeqAnalysis(name,version,program, !Args.parseFlags(args).contains("inactive"));

        String paramsfname = Args.parseString(args,"paramfile",null);
        if (paramsfname != null) {
            analysis.setParameters(ChipSeqLoader.readParameters(new BufferedReader(new FileReader(paramsfname))));
        }
        Set<ChipSeqAlignment> fg = new HashSet<ChipSeqAlignment>();
        Set<ChipSeqAlignment> bg = new HashSet<ChipSeqAlignment>();
        for (String s : Args.parseStrings(args,"foreground")) {
            String pieces[] = s.split(";");
            if (pieces.length == 2) {
                System.err.println("fg 2");
                fg.addAll(loader.loadAlignments(pieces[0],
                                                null,
                                                pieces[1],
                                                null,null,null,
                                                genome));
            } else if (pieces.length == 3) {
                System.err.println("fg 3");
                fg.addAll(loader.loadAlignments(new ChipSeqLocator(pieces[0],pieces[1],pieces[2]),genome));
            } else {
                System.err.println("Bad alignment spec: " + s);
            }
        }
        for (String s : Args.parseStrings(args,"background")) {
            String pieces[] = s.split(";");
            if (pieces.length == 2) {
                bg.addAll(loader.loadAlignments(pieces[0],
                                                null,
                                                pieces[1],
                                                null,null,null,
                                                genome));

            } else if (pieces.length == 3) {
                bg.addAll(loader.loadAlignments(new ChipSeqLocator(pieces[0],pieces[1],pieces[2]),genome));
            } else {
                System.err.println("Bad alignment spec: " + s);
            }
        }
        System.err.println("FG is : " + fg + "   and BG is " + bg);
        analysis.setInputs(fg,bg);
    }
    public void run(InputStream input) throws SQLException, IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line;
        while ((line = reader.readLine()) != null) {
            ChipSeqAnalysisResult r = null;
            try {
                r = parseLine(line);
            } catch (Exception e) {
                System.err.println("Couldn't parse line " + line);
                e.printStackTrace();
                continue;
            }
            if (r != null) {
                analysis.addResult(r);
            } else {
                System.err.println("Couldn't parse line " + line);
            }

        }
        analysis.store();
    }
    public ChipSeqAnalysisResult parseLine(String line) {
        String pieces[] = line.split("\\t");
        return new ChipSeqAnalysisResult(getGenome(),
                                         pieces[0],
                                         Integer.parseInt(pieces[1]),
                                         Integer.parseInt(pieces[2]),
                                         pieces[3].length() > 0 ? Integer.parseInt(pieces[3]) : null,
                                         pieces[4].length() > 0 ? Double.parseDouble(pieces[4]) : null,
                                         pieces[5].length() > 0 ? Double.parseDouble(pieces[5]) : null,
                                         pieces[6].length() > 0 ? Double.parseDouble(pieces[6]) : null,
                                         pieces[7].length() > 0 ? Double.parseDouble(pieces[7]) : null,
                                         pieces[8].length() > 0 ? Double.parseDouble(pieces[8]) : null,
                                         pieces[9].length() > 0 ? Double.parseDouble(pieces[9]) : null);
    }
    public void close() {}
    public Genome getGenome() {return genome;}
}
