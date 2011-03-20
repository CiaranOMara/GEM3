package edu.mit.csail.cgs.ewok.verbs.motifs;

import java.util.ArrayList;
import java.util.Collections;

import edu.mit.csail.cgs.deepseq.utilities.CommonUtils;
import edu.mit.csail.cgs.utils.sequence.SequenceUtils;

public class Kmer implements Comparable<Kmer>{
	static cern.jet.random.engine.RandomEngine randomEngine = new cern.jet.random.engine.MersenneTwister();
	
	String kmerString;
	public String getKmerString() {	return kmerString;}
	int k;
	public int getK(){return k;}
	int seqHitCount; //one hit at most for one sequence, to avoid simple repeat
	double strength;	// the total read counts from all events support this kmer
	public double getStrength(){return strength;}
	public void setStrength(double strength){this.strength = strength;}
	public void incrStrength(double strength){this.strength += strength;}
	double hg;
	int negCount;
	
	Kmer reference;		//
	public Kmer getRef(){return reference;}
	int shift;			// the shift to achieve best score
	public int getShift(){return shift;}
	int score;			// best possible number of matches (with or w/o shift) wrt reference Kmer
	public int getScore(){return score;}	
	/**
	 *  The shift of kmer start from the middle of motif(PWM) (Pos_kmer-Pos_wm)
	 */
	int kmerShift;			
	/**
	 *  Get the shift of kmer start from the middle of motif(PWM) (Pos_kmer-Pos_wm)
	 */
	 public int getKmerShift(){return kmerShift;}
	public void setKmerShift(int s){kmerShift=s;}
	int group=-1;			// the group of motif
	public int getGroup(){return group;}
	public void setGroup(int g){group=g;}
	
	public Kmer(String kmerStr, int hitCount){
		this.kmerString = kmerStr;
		this.k = kmerString.length();
		this.seqHitCount = hitCount;
		if (randomEngine.nextDouble()>0.5)
			this.kmerShift = -(this.k-1-(this.k-1)/2);
		else
			this.kmerShift = -(this.k-1)/2;
	}
	
	/** 
	 * Use reverse compliment to represent the kmer
	 */
	public void RC(){
		kmerString = getKmerRC();
	}
	public int getNegCount() {
		return negCount;
	}
	public void setNegCount(int negCount) {
		this.negCount = negCount;
	}

	// sort kmer by weight
	public int compareByWeight(Kmer o) {
		double diff = o.strength-strength;
		return diff==0?kmerString.compareTo(o.kmerString):(diff<0)?-1:1;  // descending
	}
	
	// default, sort kmer by seqHitCount
	public int compareTo(Kmer o) {
		double diff = o.seqHitCount-seqHitCount;
		return diff==0?kmerString.compareTo(o.kmerString):(diff<0)?-1:1; // descending
	}
	public boolean hasString(String kmerString){
		return this.kmerString.equals(kmerString);
	}
	public String toString(){
		double hg_lg = Math.log10(hg);
		if (hg_lg==Double.NEGATIVE_INFINITY)
			hg_lg=-100;
		return kmerString+"\t"+seqHitCount+"\t"+negCount+"\t"+String.format("%.1f", hg_lg)+
			   "\t"+String.format("%.1f", strength)+"\t"+kmerShift;
	}
	public static String toHeader(){
		return "EnrichedKmer\tPosCt\tNegCt\tHGP_10\tStrengt\tOffset";
	}

	public int getSeqHitCount() {
		return seqHitCount;
	}
	public void setSeqHitCount(int count) {
		seqHitCount=count;
	}
	public void incrSeqHitCount() {
		seqHitCount++;
	}
	
	/** 
	 * Set the reference Kmer
	 * Find the best score, strand(RC), shift for this Kmer to align with reference Kmer 
	 * @param ref
	 */
	public void setReference(Kmer ref){
		reference = ref;
		byte[] thisBytes = kmerString.getBytes();
		byte[] refBytes = ref.kmerString.getBytes();
		score = 0;
		shift = -99;
		for (int s=-2;s<=+2;s++){
			int count = 0;
			for (int i=-2;i<refBytes.length+2;i++){
				if (i<0 || i>refBytes.length-1 ||i+s<0 || i+s>refBytes.length-1 )
					continue;
				if (refBytes[i]==thisBytes[i+s]){
					count ++;
				}
			}
			if (count>score){
				score = count;
				shift = s;
			}
		}
		// try RC
		byte[] rcBytes = getKmerRC().getBytes();
		boolean useRC=false;
		for (int s=-2;s<=+2;s++){
			int count = 0;
			for (int i=-2;i<refBytes.length+2;i++){
				if (i<0 || i>refBytes.length-1 ||i+s<0 || i+s>refBytes.length-1 )
					continue;
				if (refBytes[i]==rcBytes[i+s]){
					count ++;
				}
			}
			if (count>score){
				score = count;
				shift = s;
				useRC = true;
			}
		}
		if (useRC)
			RC();
	}
	
	/**
	 * Extend this kmer from reference kmer, if they are only offset base off
	 * @param ref
	 */
	public boolean extendKmer(Kmer ref, int offset){
		reference = ref;
		byte[] thisBytes = kmerString.getBytes();
		byte[] refBytes = ref.kmerString.getBytes();
		score = 0;
		shift = -99;
		for (int s=-offset;s<=offset;s++){
			for (int i=-offset;i<refBytes.length+offset;i++){
				if (i<0 || i>refBytes.length-1 ||i+s<0 || i+s>refBytes.length-1 )
					continue;
				if (refBytes[i]!=thisBytes[i+s])	// if mismatch
					break;
			}
			score = refBytes.length-Math.abs(s);
			shift = s;
		}
		// try RC
		byte[] rcBytes = getKmerRC().getBytes();
		boolean useRC=false;
		for (int s=-offset;s<=offset;s++){
			for (int i=-offset;i<refBytes.length+offset;i++){
				if (i<0 || i>refBytes.length-1 ||i+s<0 || i+s>refBytes.length-1 )
					continue;
				if (refBytes[i]!=rcBytes[i+s])	// if mismatch
					break;
			}
			int thisScore = refBytes.length-Math.abs(s);
			if (thisScore>score){
				score = thisScore;
				useRC = true;
			}
			shift = s;
		}
		
		if (useRC)
			RC();
		
		return score>0;
	}
	
	/**
	 * calculate the best shift for input kmer to align with this kmer
	 * allow for 2 mismatches, or 1 shift + 1 mismatch, or 2 shift
	 * @param kmer
	 * @return best shift for input kmer
	 */
//	public int shift(String kmer){
//		byte[] thisBytes = this.kmerString.getBytes();
//		byte[] kmerBytes = kmer.getBytes();
//		int maxScore = 0;
//		int maxScoreShift = -99;
//		for (int s=-2;s<=+2;s++){
//			int score = 0;
//			for (int i=-2;i<thisBytes.length+2;i++){
//				if (i<0 || i>thisBytes.length-1 ||i+s<0 || i+s>thisBytes.length-1 )
//					continue;
//				if (thisBytes[i]==kmerBytes[i+s]){
//					score ++;
//				}
//			}
//			if (score>k*0.8){
//				if (score>maxScore){
//					maxScore = score;
//					maxScoreShift = s;
//				}
//			}
//		}
//		return maxScoreShift;
//	}
	
	public String getKmerRC(){
		return SequenceUtils.reverseComplement(kmerString);
	}
	
	public static void printKmers(ArrayList<Kmer> kmers, String filePrefix){
		if (kmers==null || kmers.isEmpty())
			return;
		
		Collections.sort(kmers);
		
		StringBuilder sb = new StringBuilder();
		sb.append(Kmer.toHeader());
		sb.append("\n");
		for (Kmer kmer:kmers){
			sb.append(kmer.toString()).append("\n");
		}
		CommonUtils.writeFile(String.format("%s_kmer_%d.txt",filePrefix, kmers.get(0).getK()), sb.toString());
	}
	
//	public static void main(String[] args){
//		Kmer k1 = new Kmer("CCAGAAGAGGGC", 32);
//		Kmer k2 = new Kmer("CCCTCTTCTGGC", 3);
//		k2.setReference(k1);
//		System.out.println(k2.getShift());
//	}
	
}
