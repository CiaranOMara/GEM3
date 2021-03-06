package edu.mit.csail.cgs.deepseq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.mit.csail.cgs.datasets.chipseq.ChipSeqLocator;
import edu.mit.csail.cgs.datasets.general.Region;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.species.Organism;
import edu.mit.csail.cgs.deepseq.utilities.AlignmentFileReader;
import edu.mit.csail.cgs.deepseq.utilities.DBReadLoader;
import edu.mit.csail.cgs.deepseq.utilities.FileReadLoader;
import edu.mit.csail.cgs.deepseq.utilities.ReadDBReadLoader;
import edu.mit.csail.cgs.deepseq.utilities.ReadLoader;
import edu.mit.csail.cgs.ewok.verbs.RegionParser;
import edu.mit.csail.cgs.projects.readdb.PairedHit;
import edu.mit.csail.cgs.tools.utils.Args;
import edu.mit.csail.cgs.utils.ArgParser;
import edu.mit.csail.cgs.utils.NotFoundException;
import edu.mit.csail.cgs.utils.Pair;

/**
 * DeepSeqExpt is basically an interface to the ReadLoader. This allows the ReadLoader to be abstract; ReadLoaders can be implemented to load from the DB or from Files.
 *   
 * @author shaun
 *
 */
public class DeepSeqExpt {
	private ReadLoader loader;
	private Genome gen;
	protected int rLen =32; //move towards an experiment-specific read length
	protected int startShift;
	protected int fivePrimeExt;
	protected int threePrimeExt;
	protected int maxMismatches=5;
	protected boolean useNonUniqueReads=false;
	protected double scalingFactor=1.0;
	protected boolean pairedEndData = false;
	
	
	public DeepSeqExpt(Genome g, List<ChipSeqLocator> locs, String db, int readLen){this(g,locs,db,readLen,false);}
	public DeepSeqExpt(Genome g, List<ChipSeqLocator> locs, String db, int readLen, boolean pairedEnd){
		if(g==null){
			System.err.println("Error: the genome must be defined in order to use the Gifford Lab DB"); System.exit(1);
		}
		pairedEndData = pairedEnd;
		rLen = readLen;
		gen = g;
		try {
			if(db.equals("db"))
				loader = new DBReadLoader(gen, locs, rLen, pairedEndData);
			else if(db.equals("readdb"))
				loader = new ReadDBReadLoader(gen, locs, rLen, pairedEndData);
			else{
				System.err.println("Database type must be \"db\" or \"readdb\"");System.exit(1);
			}
		} catch (NotFoundException e) {
			e.printStackTrace();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
		rLen = loader.getReadLen();
		startShift=0;
		fivePrimeExt=0;
		threePrimeExt=0;
	}
	public DeepSeqExpt(Genome g, List<File> files, boolean useNonUnique, String format, int readLen){
		this(g,files,useNonUnique, format,readLen, 1);
	}
	public DeepSeqExpt(Genome g, List<File> files, boolean useNonUnique, String format,int readLen, int idStart){
		gen = g;
		rLen = readLen;
		useNonUniqueReads=useNonUnique;
		loader = new FileReadLoader(gen, files, format,maxMismatches,useNonUniqueReads, rLen, idStart);
		if(gen==null)
			gen = loader.getGenome();
		rLen = loader.getReadLen();
		startShift=0;
		fivePrimeExt=0;
		threePrimeExt=0;
	}
	/**
	 * This constructor is used for estimating genome chrom sizes
	 * @param files
	 * @param format
	 */
	public DeepSeqExpt(List<File> files,String format){
		loader = new FileReadLoader(files, format);
		gen = loader.getGenome();
	}
	
	//Accessors
	public Genome getGenome(){return gen;}
	public void setGenome(Genome g){gen = g; loader.setGenome(g);}
	public int getReadLen(){return rLen;}
	public double getHitCount(){return loader.getHitCount();} 
	public double getWeightTotal(){return loader.getTotalWeight();}
	public double getStrandedWeightTotal(char strand){return loader.getStrandedWeight(strand);}
	public double getScalingFactor(){return scalingFactor;}
	public List<ReadHit> loadHits(Region r){return(loader.loadHits(r));}
	public List<ReadHit> loadPairsAsSingle(Region r){return(loader.loadPairs(r));}
	public List<PairedHit> loadPairsAsPairs(Region r){return(loader.loadPairsAsPairs(r));}
	public List<ExtReadHit> loadExtHits(Region r){return(loader.loadExtHits(r, startShift, fivePrimeExt, threePrimeExt));}
	public int countHits(Region r){return(loader.countHits(r));}
	public double sumWeights(Region r){return(loader.sumWeights(r));}
	public void setShift(int s){startShift=s;}
	public void setFivePrimeExt(int e){fivePrimeExt=e;}
	public void setThreePrimeExt(int e){threePrimeExt=e;}
	public void setScalingFactor(double sf){scalingFactor=sf;}
	public void setPairedEnd(boolean pe){pairedEndData=pe; loader.setPairedEnd(pe);}
	public boolean isPairedEnd(){return pairedEndData;}
	public boolean isFromDB(){
		return loader instanceof DBReadLoader;
	}
	public boolean isFromFile(){
		return loader instanceof FileReadLoader;
	}
	public boolean isFromReadDB(){
		return loader instanceof ReadDBReadLoader;
	}

	public HashMap<String, Integer> getChrom2ID(){
		return loader.getChrom2ID();
	}
	public HashMap<Integer,String> getId2Chrom(){
		return loader.getId2Chrom();
	}
	
	// load paired base coordinates (sorted) and counts
	public Pair<ArrayList<Integer>,ArrayList<Float>> loadStrandedBaseCounts(Region r, char strand){
	    //if pairedend throw error
		if (loader instanceof ReadDBReadLoader){
			return ((ReadDBReadLoader)loader).loadStrandedBaseCounts(r, strand);
		}
		if (loader instanceof FileReadLoader){
			return ((FileReadLoader)loader).loadStrandedFivePrimeCounts(r, strand);
		}
		else
			return null;
	}
	
	//load base coordinates for paired end only
	public Pair<Pair<ArrayList<Integer>, ArrayList<ArrayList<Integer>>>, ArrayList<ArrayList<Float>>> loadStrandedBaseCountsPaired(Region r, char strand){
	    if (loader instanceof FileReadLoader){
            return ((FileReadLoader)loader).loadStrandedPairedEndCounts(r, strand);
        }
	    return null;
	}
	
	public String getBED_StrandedReads(Region r, char strand, double probability){
		if (loader instanceof ReadDBReadLoader){
			return ((ReadDBReadLoader)loader).getBED_StrandedReads(r, strand, probability);
		}
		else
			return null;		
	}
	
	// load all start coordinates (unsorted if multiple conditions)
	public ArrayList<int [][][]> getAllStarts(){
		if (loader instanceof FileReadLoader){
			return ((FileReadLoader)loader).getAllStarts();
		}
		else
			return null;
	}
	
	//A main method to test if the Loaders are working:
	//Print the ChIP-seq hits in a region in the format taken by the Matlab peak-finder.
	public static void main(String[] args) {
		DeepSeqExpt dse=null;
		ArgParser ap = new ArgParser(args);
		ArrayList<Region> regs = new ArrayList<Region>();
        if(ap.hasKey("regions")&& (ap.hasKey("species")||ap.hasKey("geninfo"))) {
        	try {
        		Genome gen=null;
	        	if(ap.hasKey("species")&&ap.hasKey("genome")){
	        		Organism currorg = Organism.getOrganism(ap.getKeyValue("species"));
	        		gen = currorg.getGenome(ap.getKeyValue("genome"));
	            }else if(ap.hasKey("geninfo")){
	            	gen = new Genome("Genome", new File(ap.getKeyValue("geninfo")));
	        	}
	        	
	        	//Load the regions
        		File rFile = new File(ap.getKeyValue("regions"));
    			if(!rFile.isFile()){System.err.println("Invalid positive file name");System.exit(1);}
    	        BufferedReader reader = new BufferedReader(new FileReader(rFile));
    	        String line;
    	        while ((line = reader.readLine()) != null) {
    	            line = line.trim();
    	            String[] words = line.split("\\s+");
    	            if(words.length>=1){
    	            	RegionParser parser = new RegionParser(gen);
    	            	Region r = parser.execute(words[0]);
    	            	if(r!=null){regs.add(r);}
    	            }
    	        }reader.close();
    	        
    	        if(ap.hasKey("expt")){
    	        	List<ChipSeqLocator> expts =  Args.parseChipSeq(args,"expt");
    	        	dse = new DeepSeqExpt(gen, expts, "db", 32);
    	        }else if(ap.hasKey("eland")){
    	        	ArrayList<File> f = new ArrayList<File>();
    	        	for(String s : Args.parseStrings(args, "eland"))
    	        		f.add(new File(s));
    	        	dse = new DeepSeqExpt(gen, f, false, "ELAND", 32);
    	        }
    	        
    	        if(dse!=null){
	    	        for(Region r : regs){
	    	        	List<ReadHit> reads = dse.loadHits(r);
	    	        	for(ReadHit x : reads){
	    	        		int str = x.getStrand()=='+' ? 1:-1;
	    	        		int relStart=x.getFivePrime()-r.getStart();
	    	        		//System.out.println(x.getFivePrime()+"\t"+str);
	    	        		System.out.println(relStart+"\t"+str);
	    	        	}	    	        		
	    	        }
    	        }    	        	
            } catch (NotFoundException e) {
    		 	e.printStackTrace();
    		} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
        }else{
        	System.err.println("Usage:\n " +
                    "DeepSeqExpt \n" +
                    " Required: \n" +
                    "  --regions <file containing coordinates>\n" +
                    " Options:" +
                    "  --species <organism name> " +
                    "  --genome <genome version> "+
                    "  --expt <IP expt> \n" +
                    "     OR" +
                    "  --geninfo <chr name/lengths> "+
                    "  --eland <ELAND file> \n" +
                    "");
        	return;
        }
	}  
	
	//Clean up the loaders
	public void closeLoaders(){
		loader.cleanup();
	}
}

