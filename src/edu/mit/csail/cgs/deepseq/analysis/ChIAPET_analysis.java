package edu.mit.csail.cgs.deepseq.analysis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import edu.mit.csail.cgs.datasets.general.Point;
import edu.mit.csail.cgs.datasets.general.Region;
import edu.mit.csail.cgs.datasets.general.StrandedPoint;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.deepseq.utilities.CommonUtils;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSParser;
import edu.mit.csail.cgs.ewok.verbs.chipseq.GPSPeak;
import edu.mit.csail.cgs.tools.utils.Args;
import edu.mit.csail.cgs.utils.Pair;
import edu.mit.csail.cgs.utils.stats.StatUtil;

public class ChIAPET_analysis {
	Genome genome;
	Set<String> flags;
	String[] args;
	int read_merge_dist = 500;
	int tss_merge_dist = 500;
	int max_cluster_merge_dist = 2000;
	int distance_factor = 3;
	int self_exclude = 8000;
	int tss_radius = 2000;
	int chiapet_radius = 2000;
	double overlap_ratio = 0.8;

	TreeMap<Region, InteractionCall> r2it = new TreeMap<Region, InteractionCall>();
	String fileName = null;

	public ChIAPET_analysis(String[] args) {
		genome = CommonUtils.parseGenome(args);

		flags = Args.parseFlags(args);
		this.args = args;

		fileName = Args.parseString(args, "bedpe", null);

		read_merge_dist = Args.parseInteger(args, "read_merge_dist", read_merge_dist);
		tss_merge_dist = Args.parseInteger(args, "tss_merge_dist", tss_merge_dist);
		max_cluster_merge_dist = Args.parseInteger(args, "max_cluster_merge_dist", max_cluster_merge_dist);
		distance_factor = Args.parseInteger(args, "distance_factor", distance_factor);
		tss_radius = Args.parseInteger(args, "tss_radius", tss_radius);
		chiapet_radius = Args.parseInteger(args, "chiapet_radius", chiapet_radius);
		overlap_ratio = Args.parseDouble(args, "overlap_ratio", overlap_ratio);

	}

	public static void main(String args[]) {
		ChIAPET_analysis analysis = new ChIAPET_analysis(args);
		int type = Args.parseInteger(args, "type", 0);

		switch (type) {
		case 0:
			analysis.cleanUpOverlaps();
			analysis.countGenesPerRegion();
			analysis.StatsTAD();
			break;
		case 1: // count distal read pairs per gene (old: step1)
			analysis.countReadPairs();
			break;
		case 2: // count distal read pairs per gene (old: step2)
			analysis.clusterDistalReads();
			break;
		case 3: // region (1D merged-read) based clustering
			analysis.findAllInteractions();
			break;
		case 4: // find gene-based dense cluster of read pairs
			postProcessing(args);
			break;
		case 5:
			annotateInteractions(args);
			break;
		case 6: // merged-TSS based clustering
			getPetLength(args);
			break;
		}
	}

	/**
	 * Clean up data. Merge overlap distal regions if they are connected to the
	 * same TSS. Optionally remove distal regions that overlap with the
	 * connected TSS
	 */
	void cleanUpOverlaps() {
		ArrayList<String> texts = CommonUtils.readTextFile(fileName);
		// use tssString as the key to ensure uniqueness, tss Point objects have
		// different references even if from same tss
		TreeMap<String, ArrayList<Region>> tss2distalRegions = new TreeMap<String, ArrayList<Region>>();
		for (String line : texts) {
			String f[] = line.trim().split("\t");
			InteractionCall it = new InteractionCall();
			it.tssString = f[6];
			it.tss = Point.fromString(genome, f[6]);
			it.geneID = f[7];
			it.geneSymbol = f[8];
			if (f.length <= 13) {
				it.distal = Region.fromString(genome, f[9]);
				it.pvalue = Double.parseDouble(f[11]);
			} else {
				it.distal = Region.fromString(genome, f[13]);
				it.pvalue = Double.parseDouble(f[15]);
			}

			// skip interactions that have distal regions containing the TSS?
			if (flags.contains("rm_self")) {
				if (it.distal.contains(it.tss))
					continue;
			}
			// if duplicate, shrink 1bp to make it uniquie,
			// if connect to same TSS, it will be merged later
			while (r2it.containsKey(it.distal))
				it.distal = it.distal.expand(-1, -1);
			r2it.put(it.distal, it);
			if (!tss2distalRegions.containsKey(it.tssString))
				tss2distalRegions.put(it.tssString, new ArrayList<Region>());
			tss2distalRegions.get(it.tssString).add(it.distal);
		}

		// for each tss, merge overlapping distal regions (<1kb)
		for (String tss : tss2distalRegions.keySet()) {
			ArrayList<Region> regions = tss2distalRegions.get(tss);
			ArrayList<Region> mergedRegions = new ArrayList<Region>();
			Collections.sort(regions);
			Region previous = regions.get(0);
			ArrayList<Region> previousRegions = new ArrayList<Region>();
			previousRegions.add(previous);

			for (int i = 1; i < regions.size(); i++) {
				Region region = regions.get(i);
				// if overlaps with previous region, combine the regions, take
				// the best p-values
				if (previous.overlaps(region)) {
					previous = previous.combine(region);
					previousRegions.add(region);
				} else { // not overlap any more, update, then move to next one
					mergedRegions.add(previous);
					// merge overlapping regions, update interactions
					if (previousRegions.size() > 1) { // merged
						InteractionCall it = null;
						double bestPvalue = 1;
						for (Region r : previousRegions) {
							it = r2it.get(r);
							r2it.remove(r); // remove old one
							bestPvalue = Math.min(bestPvalue, it.pvalue);
						}
						it.distal = previous; // previous has been merged
						it.pvalue = bestPvalue;
						r2it.put(previous, it); // add merged region
					}
					previousRegions.clear();
					previous = region;
					previousRegions.add(previous);
				}
			}
			mergedRegions.add(previous);
			if (previousRegions.size() > 1) { // merged
				InteractionCall it = null;
				// merge overlapping regions, update interactions
				double bestPvalue = 1;
				for (Region r : previousRegions) {
					it = r2it.get(r);
					r2it.remove(r);
					bestPvalue = Math.min(bestPvalue, it.pvalue);
				}
				it.distal = previous;
				it.pvalue = bestPvalue;
				r2it.put(previous, it);
			}
			mergedRegions.trimToSize();
		}

		// print out cleaned up data
		if (flags.contains("print_merged")) {
			StringBuilder sb = new StringBuilder();
			for (Region r : r2it.keySet()) {
				InteractionCall it = r2it.get(r);
				sb.append(String.format("%s\t%s\t%s\t%s\t%s\t%s\t%d\t%.2e", it.distal.toBED(),
						it.tss.expand(2000).toBED(), it.tss.toString(), it.geneID, it.geneSymbol, it.distal.toString(),
						it.distal.getWidth(), it.pvalue)).append("\n");
			}
			CommonUtils.writeFile(
					fileName.replace(".bedpe", (flags.contains("rm_self") ? ".rmSelf" : "") + ".mergeDistal.bedpe"),
					sb.toString());
		}
	}

	/**
	 * Count number of genes connected by each region (overlapping regions are
	 * merged).
	 * 
	 * @param fileName
	 */
	void countGenesPerRegion() {
		// load RefSeq gene annotation
		int tssRange = Args.parseInteger(args, "tss_range", 100);
		ArrayList<String> texts = CommonUtils.readTextFile(Args.parseString(args, "genes", null));
		TreeMap<StrandedPoint, TreeSet<String>> tss2genes = new TreeMap<StrandedPoint, TreeSet<String>>();
		for (int i = 0; i < texts.size(); i++) {
			String t = texts.get(i);
			if (t.startsWith("#"))
				continue;
			String f[] = t.split("\t");
			String chr = f[2].replace("chr", "");
			char strand = f[3].charAt(0);
			StrandedPoint tss = new StrandedPoint(genome, chr, Integer.parseInt(f[strand == '+' ? 4 : 5]), strand);
			String symbol = f[12];
			if (!tss2genes.containsKey(tss))
				tss2genes.put(tss, new TreeSet<String>());
			tss2genes.get(tss).add(symbol);
		}
		/** all TSS annotatons organized by chroms */
		HashMap<String, ArrayList<StrandedPoint>> chr2tsss = new HashMap<String, ArrayList<StrandedPoint>>();
		for (StrandedPoint tss : tss2genes.keySet()) {
			String chr = tss.getChrom();
			if (!chr2tsss.containsKey(chr))
				chr2tsss.put(chr, new ArrayList<StrandedPoint>());
			chr2tsss.get(chr).add(tss);
		}
		for (String chr : chr2tsss.keySet()) {
			ArrayList<StrandedPoint> tsss = chr2tsss.get(chr);
			Collections.sort(tsss);
			// print inter-TSS distances
			// for (int i=0;i<tsss.size()-1;i++){
			// System.out.println(tsss.get(i+1).distance(tsss.get(i)));
			// }
		}

		// merge nearby regions, collect genes within tssRange of the TSSs.
		TreeSet<String> allGenes = new TreeSet<String>();
		ArrayList<Region> rs = new ArrayList<Region>();
		// if coords are provided, only report the subset of distal regions that
		// overlaps
		String coords_file = Args.parseString(args, "coords", null);
		String coords_name = Args.parseString(args, "coords_name", null);
		if (coords_file != null) {
			ArrayList<Point> coords = CommonUtils.loadCgsPointFile(coords_file, genome);
			for (Region r : r2it.keySet()) {
				for (Point p : coords) {
					if (!r.getChrom().equalsIgnoreCase(p.getChrom()))
						continue;
					if (r.distance(p) <= 500) { // add distal anchor if overlap
												// with enhancer coords
						rs.add(r);
						r2it.get(r).overlapCoords.add(p);
					}
				}
			}

			// print the enhancer_coords and the tss_geneSymbol pairs
			StringBuilder sb1 = new StringBuilder();
			sb1.append("#Coord\tTSS\tGene\n");
			for (Region r : rs) {
				InteractionCall it = r2it.get(r);
				Point tss = it.tss;
				ArrayList<StrandedPoint> tsss = chr2tsss.get(tss.getChrom());
				ArrayList<StrandedPoint> tss_linked = new ArrayList<StrandedPoint>();
				if (tsss == null) {
					continue;
				}
				int idx = Collections.binarySearch(tsss, tss);
				if (idx < 0)
					idx = -(idx + 1); // insert point
				for (int j = idx; j < tsss.size(); j++) {
					if (tss.distance(tsss.get(j)) > tssRange)
						break;
					else
						tss_linked.add(tsss.get(j));
				}
				for (int j = idx - 1; j >= 0; j--) {
					if (tss.distance(tsss.get(j)) > tssRange)
						break;
					else
						tss_linked.add(tsss.get(j));
				}
				for (StrandedPoint t : tss_linked) {
					for (Point p : it.overlapCoords) {
						TreeSet<String> genes = tss2genes.get(t);
						for (String g : genes)
							sb1.append(p.toString()).append("\t").append(t.toString()).append("\t").append(g)
									.append("\n");
					}
				}
			}
			CommonUtils.writeFile(fileName.replace(".bedpe", (flags.contains("rm_self") ? ".rmSelf" : "")
					+ (coords_file != null ? ("." + coords_name) : "") + ".tss" + tssRange + ".coord2genes.txt"),
					sb1.toString());

		} else
			rs.addAll(r2it.keySet()); // add all distal regions, rs is sorted,
										// r2it is a TreeMap

		Region merged = rs.get(0);
		ArrayList<Integer> ids = new ArrayList<Integer>();
		ids.add(0);
		StringBuilder sb = new StringBuilder("#Merged_region\twidth\tr_id\tgenes\tcount\n");

		for (int i = 1; i < rs.size(); i++) {
			Region r = rs.get(i);
			if (merged.getChrom().equals(r.getChrom())
					&& merged.distance(r) < Args.parseInteger(args, "distance", 1000)) {
				merged = merged.combine(r);
				ids.add(i);
			} else {
				sb.append(merged.toString()).append("\t").append(merged.getWidth()).append("\t");

				// for each merged region, get all TSS, find refSeq genes within
				// 100bp window
				TreeSet<String> genes = new TreeSet<String>();
				for (int id : ids) {
					InteractionCall it = r2it.get(rs.get(id));
					sb.append(id).append(",");
					Point tss = it.tss;
					ArrayList<StrandedPoint> tsss = chr2tsss.get(tss.getChrom());
					if (tsss == null) {
						genes.add(it.geneSymbol);
						continue;
					}
					int idx = Collections.binarySearch(tsss, tss);
					if (idx < 0)
						idx = -(idx + 1); // insert point
					for (int j = idx; j < tsss.size(); j++) {
						if (tss.distance(tsss.get(j)) > tssRange)
							break;
						else
							genes.addAll(tss2genes.get(tsss.get(j)));
					}
					for (int j = idx - 1; j >= 0; j--) {
						if (tss.distance(tsss.get(j)) > tssRange)
							break;
						else
							genes.addAll(tss2genes.get(tsss.get(j)));
					}
				}
				CommonUtils.replaceEnd(sb, '\t');
				for (String s : genes)
					sb.append(s).append(",");
				CommonUtils.replaceEnd(sb, '\t');
				sb.append(genes.size()).append("\n");
				ids.clear();
				ids.add(i);
				allGenes.addAll(genes);

				merged = r; // setup for next merge
			}
		}
		// finish the last merge
		sb.append(merged.toString()).append("\t").append(merged.getWidth()).append("\t");
		TreeSet<String> genes = new TreeSet<String>();
		for (int id : ids) {
			sb.append(id).append(",");
			Point tss = r2it.get(rs.get(id)).tss;
			ArrayList<StrandedPoint> tsss = chr2tsss.get(tss.getChrom());
			if (tsss == null) {
				genes.add(r2it.get(rs.get(id)).geneSymbol);
				continue;
			}
			int idx = Collections.binarySearch(tsss, tss);
			if (idx < 0)
				idx = -(idx + 1); // insert point
			for (int j = idx; j < tsss.size(); j++) {
				if (tss.distance(tsss.get(j)) > tssRange)
					break;
				else
					genes.addAll(tss2genes.get(tsss.get(j)));
			}
			for (int j = idx - 1; j >= 0; j--) {
				if (tss.distance(tsss.get(j)) > tssRange)
					break;
				else
					genes.addAll(tss2genes.get(tsss.get(j)));
			}
		}
		CommonUtils.replaceEnd(sb, '\t');
		if (genes.isEmpty())
			sb.append("None").append(",");
		for (String s : genes)
			sb.append(s).append(",");
		CommonUtils.replaceEnd(sb, '\t');
		sb.append(genes.size()).append("\n");
		allGenes.addAll(genes);

		// System.out.println(sb.toString());
		CommonUtils.writeFile(fileName.replace(".bedpe", (flags.contains("rm_self") ? ".rmSelf" : "")
				+ (coords_file != null ? ("." + coords_name) : "") + ".tss" + tssRange + ".per_region_count.txt"),
				sb.toString());

		// print out all linked genes
		sb = new StringBuilder();
		for (String g : allGenes)
			sb.append(g).append("\n");
		CommonUtils.writeFile(
				fileName.replace(".bedpe", (flags.contains("rm_self") ? ".rmSelf" : "")
						+ (coords_file != null ? ("." + coords_name) : "") + ".tss" + tssRange + ".geneSymbols.txt"),
				sb.toString());
	}

	void StatsTAD() {
		String tad_file = Args.parseString(args, "tad", null);
		ArrayList<Region> tads = CommonUtils.load_BED_regions(genome, tad_file).car();
		Collections.sort(tads);
		ArrayList<InteractionCall> itNonTAD = new ArrayList<InteractionCall>();
		ArrayList<InteractionCall> itSameTAD = new ArrayList<InteractionCall>();
		ArrayList<InteractionCall> itCrossTAD = new ArrayList<InteractionCall>();

		for (Region r : r2it.keySet()) {
			Region mid = r.getMidpoint().expand(0);
			int idx = Collections.binarySearch(tads, mid);
			Region tad = null;
			if (idx < 0) {
				idx = -(idx + 1) - 1; // insert point - 1 ==> Previous object
				tad = tads.get(idx);
				if (!tad.contains(mid)) {
					// System.err.println(String.format("Point %s is not within
					// any TAD!", p.toString()));
					itNonTAD.add(r2it.get(r));
				} else {// now tad contains the distal coord
					if (tad.contains(r2it.get(r).tss))
						itSameTAD.add(r2it.get(r));
					else { // find the tad that TSS is in
						Region tss = r2it.get(r).tss.expand(0);
						idx = Collections.binarySearch(tads, tss);
						if (idx < 0) {
							idx = -(idx + 1) - 1; // insert point - 1 ==>
													// Previous object
							tad = tads.get(idx);
							if (!tad.contains(tss)) // TSS is not in a TAD
								itNonTAD.add(r2it.get(r));
							else // in TAD, must be another TAD
								itCrossTAD.add(r2it.get(r));
						}
					}
				}
			}
		}

		System.out.println(String.format("In same TAD:\t %d\nCross TAD:\t %d\nNot in TAD:\t %d\n", itSameTAD.size(),
				itCrossTAD.size(), itNonTAD.size()));

		StringBuilder sb = new StringBuilder(
				"#Interaction\tdistance\tdistal\ttss\tSymbol\tgeneID\tp_-lg10\tTAD_status\n");
		for (InteractionCall it : itSameTAD)
			sb.append(it.toString()).append("\t1\n");
		for (InteractionCall it : itCrossTAD)
			sb.append(it.toString()).append("\t2\n");
		for (InteractionCall it : itNonTAD)
			sb.append(it.toString()).append("\t0\n");
		CommonUtils.writeFile(
				fileName.replace(".bedpe", (flags.contains("rm_self") ? ".rmSelf" : "") + ".StatsTAD.txt"),
				sb.toString());

	}

	private void countReadPairs() {
		long tic = System.currentTimeMillis();
		HashSet<String> geneSet = new HashSet<String>();
		String gString = Args.parseString(args, "genes", null);
		if (gString == null) {
			String gFile = Args.parseString(args, "gene_file", null);
			ArrayList<String> lines = CommonUtils.readTextFile(gFile);
			for (String g : lines)
				geneSet.add(g.trim());
		} else {
			String genes[] = Args.parseString(args, "genes", null).split(",");
			for (String g : genes)
				geneSet.add(g.trim());
		}

		// load refSeq gene annotation
		int tssRadius = Args.parseInteger(args, "tss_range", 10001) / 2;
		int chiapetRadius = Args.parseInteger(args, "chiapet_radius", 2000);
		ArrayList<String> gene_annots = CommonUtils.readTextFile(Args.parseString(args, "gene_anno", null));
		TreeMap<String, TreeSet<StrandedPoint>> gene2tss = new TreeMap<String, TreeSet<StrandedPoint>>();
		for (int i = 0; i < gene_annots.size(); i++) {
			String t = gene_annots.get(i);
			if (t.startsWith("#"))
				continue;
			String f[] = t.split("\t");
			String symbol = f[12];
			if (!geneSet.contains(symbol))
				continue;
			String chr = f[2].replace("chr", "");
			char strand = f[3].charAt(0);
			StrandedPoint tss = new StrandedPoint(genome, chr, Integer.parseInt(f[strand == '+' ? 4 : 5]), strand);
			if (!gene2tss.containsKey(symbol))
				gene2tss.put(symbol, new TreeSet<StrandedPoint>());
			gene2tss.get(symbol).add(tss);
		}

		// load read pairs
		// for now, just loop through every read pair. To run faster, should
		// sort by each end and use binary search to find tss end overlaps
		ArrayList<String> read_pairs = CommonUtils.readTextFile(Args.parseString(args, "read_pair", null));
		HashMap<String, Pair<ArrayList<Point>, ArrayList<Point>>> chr2reads = new HashMap<String, Pair<ArrayList<Point>, ArrayList<Point>>>();
		for (String s : read_pairs) {
			String[] f = s.split("\t");
			Point r1 = Point.fromString(genome, f[0]);
			String r1Chrom = r1.getChrom();
			Point r2 = Point.fromString(genome, f[1]);
			if (!r1Chrom.equals(r2.getChrom())) // skip if not from the same
												// chromosome
				continue;
			if (!chr2reads.containsKey(r1Chrom)) {
				ArrayList<Point> r1s = new ArrayList<Point>();
				ArrayList<Point> r2s = new ArrayList<Point>();
				chr2reads.put(r1Chrom, new Pair<ArrayList<Point>, ArrayList<Point>>(r1s, r2s));
			}
			Pair<ArrayList<Point>, ArrayList<Point>> reads = chr2reads.get(r1Chrom);
			reads.car().add(r1);
			reads.cdr().add(r2);
		}

		System.out.println("Loaded ChIA-PET read pairs: " + CommonUtils.timeElapsed(tic));
		System.out.println();

		// load TF sites
		ArrayList<String> tfs = CommonUtils.readTextFile(Args.parseString(args, "tf_sites", null));
		ArrayList<List<GPSPeak>> allPeaks = new ArrayList<List<GPSPeak>>();
		for (int i = 0; i < tfs.size(); i++) {
			try {
				allPeaks.add(GPSParser.parseGPSOutput(tfs.get(i), genome));
				System.out.println("Loaded " + tfs.get(i));
			} catch (IOException e) {
				System.out.println(tfs.get(i) + " does not have a valid GPS/GEM event call file.");
				e.printStackTrace(System.err);
				System.exit(1);
			}
		}

		// load histone mark regions
		ArrayList<String> hms = CommonUtils.readTextFile(Args.parseString(args, "regions", null));
		ArrayList<List<Region>> allRegions = new ArrayList<List<Region>>();
		for (int i = 0; i < hms.size(); i++) {
			allRegions.add(CommonUtils.load_BED_regions(genome, hms.get(i)).car());
			System.out.println("Loaded " + hms.get(i));
		}
		System.out.println();
		// TreeMap<String, ArrayList<Integer>> gene2distances = new
		// TreeMap<String, ArrayList<Integer>>();
		// compute distance for each gene
		ArrayList<String> geneList = new ArrayList<String>();
		geneList.addAll(gene2tss.keySet());
		StringBuilder sb = new StringBuilder();

		for (int id = 0; id < geneList.size(); id++) {
			String g = geneList.get(id);
			System.out.print(g + " ");
			ArrayList<Integer> distances = new ArrayList<Integer>();
			ArrayList<ArrayList<Integer>> isTfBounds = new ArrayList<ArrayList<Integer>>();
			TreeSet<StrandedPoint> coords = gene2tss.get(g);
			// if gene has multiple TSSs, use the center position
			int count = coords.size();
			StrandedPoint centerPoint = null;
			for (StrandedPoint p : coords) {
				if (count < coords.size() / 2)
					break;
				else {
					centerPoint = p;
					count--;
				}
			}

			// if one end of the read pair is near TSS, compute the offset of
			// the other end
			boolean isMinus = centerPoint.getStrand() == '-';
			Pair<ArrayList<Point>, ArrayList<Point>> reads = chr2reads.get(centerPoint.getChrom());
			if (reads == null)
				continue;
			ArrayList<Point> read1s = reads.car();
			ArrayList<Point> read2s = reads.cdr();
			for (int i = 0; i < read1s.size(); i++) {
				int offset_p1 = read1s.get(i).offset(centerPoint);
				int offset_p2 = read2s.get(i).offset(centerPoint);
				int dist_p1 = Math.abs(offset_p1);
				int dist_p2 = Math.abs(offset_p2);
				// only add distance to the list if one read is within
				// TSS_Radius, the other read is outside of TSS_Radius
				if (dist_p1 < tssRadius) {
					if (dist_p2 > tssRadius) {
						distances.add(isMinus ? -offset_p2 : offset_p2);
						ArrayList<Integer> isBound = new ArrayList<Integer>();
						Point p = read2s.get(i);
						for (int j = 0; j < allPeaks.size(); j++) {
							List<GPSPeak> peaks = allPeaks.get(j);
							int bound = 0;
							for (GPSPeak gps : peaks) {
								if (gps.getChrom().equals(p.getChrom()) && gps.distance(p) <= chiapetRadius) {
									bound = 1;
									break;
								}
							}
							isBound.add(bound);
						}
						for (int j = 0; j < allRegions.size(); j++) {
							List<Region> rs = allRegions.get(j);
							int bound = 0;
							for (Region r : rs) {
								// if the region r contains point p, or the
								// distance between midPoint of r and p is less
								// than ChIAPET_radias
								if (r.getChrom().equals(p.getChrom())
										&& (r.contains(p) || r.getMidpoint().distance(p) <= chiapetRadius)) {
									bound = 1;
									break;
								}
							}
							isBound.add(bound);
						}
						isTfBounds.add(isBound);
					}
				} else {
					if (dist_p2 < tssRadius) {
						distances.add(isMinus ? -offset_p1 : offset_p1);
						ArrayList<Integer> isBound = new ArrayList<Integer>();
						Point p = read1s.get(i);
						for (int j = 0; j < allPeaks.size(); j++) {
							List<GPSPeak> peaks = allPeaks.get(j);
							int bound = 0;
							for (GPSPeak gps : peaks) {
								if (gps.getChrom().equals(p.getChrom()) && gps.distance(p) <= chiapetRadius) {
									bound = 1;
									break;
								}
							}
							isBound.add(bound);
						}
						for (int j = 0; j < allRegions.size(); j++) {
							List<Region> rs = allRegions.get(j);
							int bound = 0;
							for (Region r : rs) {
								// if the region r contains point p, or the
								// distance between midPoint of r and p is less
								// than ChIAPET_radias
								if (r.getChrom().equals(p.getChrom())
										&& (r.contains(p) || r.getMidpoint().distance(p) <= chiapetRadius)) {
									bound = 1;
									break;
								}
							}
							isBound.add(bound);
						}
						isTfBounds.add(isBound);
					}
				}
			}

			if (!distances.isEmpty()) {
				// gene2distances.put(g, distances);
				for (int i = 0; i < distances.size(); i++) {
					sb.append(g).append("\t").append(centerPoint.toString()).append("\t").append(id);
					sb.append("\t").append(distances.get(i));
					for (int b : isTfBounds.get(i))
						sb.append("\t").append(b);
					sb.append("\n");
				}
			}
		} // for each gene
		CommonUtils.writeFile("all_genes.distal_offsets.txt", sb.toString());

		System.out.println("\n\n" + CommonUtils.timeElapsed(tic));
	}

	private void clusterDistalReads() {
		int tss_exclude = Args.parseInteger(args, "tss_exclude", 8000);
		int step = Args.parseInteger(args, "merge_dist", 1500);
		int minRead = Args.parseInteger(args, "min_count", 2);

		// load data
		ArrayList<String> lines = CommonUtils.readTextFile(Args.parseString(args, "tss_reads", null));
		TSSwithReads tss = new TSSwithReads();
		tss.symbol = "---";
		ArrayList<TSSwithReads> allTss = new ArrayList<TSSwithReads>();
		for (String l : lines) { // each line is a distal read
			String f[] = l.split("\t");
			int offset = Integer.parseInt(f[3]);
			if (Math.abs(offset) < tss_exclude) // skip the read if it is within
												// TSS exclude region
				continue;
			if (!f[0].equals(tss.symbol)) { // a new gene
				tss = new TSSwithReads();
				tss.symbol = f[0];
				tss.coord = StrandedPoint.fromString(genome, f[1]);
				tss.id = Integer.parseInt(f[2]);
				tss.reads = new TreeMap<Integer, ArrayList<Boolean>>();
				allTss.add(tss);
			}
			ArrayList<Boolean> isBound = new ArrayList<Boolean>();
			for (int i = 4; i < f.length; i++) {
				isBound.add(f[i].equals("1"));
			}
			tss.reads.put(offset, isBound);
		}

		lines = CommonUtils.readTextFile(Args.parseString(args, "germ", null));
		HashMap<Point, ArrayList<Point>> germTss2distals = new HashMap<Point, ArrayList<Point>>();
		ArrayList<Point> germTss = new ArrayList<Point>();
		for (String l : lines) { // each line is a call
			String f[] = l.split("\t");
			Point t = new Region(genome, f[3].replace("chr", ""), Integer.parseInt(f[4]), Integer.parseInt(f[5]))
					.getMidpoint();
			if (!germTss2distals.containsKey(t))
				germTss2distals.put(t, new ArrayList<Point>());
			germTss2distals.get(t)
					.add(new Region(genome, f[0].replace("chr", ""), Integer.parseInt(f[1]), Integer.parseInt(f[2]))
							.getMidpoint());
		}
		germTss.addAll(germTss2distals.keySet());
		Collections.sort(germTss);

		lines = CommonUtils.readTextFile(Args.parseString(args, "mango", null));
		HashMap<Point, ArrayList<Point>> a2bs = new HashMap<Point, ArrayList<Point>>();
		HashMap<Point, ArrayList<Point>> b2as = new HashMap<Point, ArrayList<Point>>();
		for (String l : lines) { // each line is a call
			String f[] = l.split("\t");
			Point a = new Region(genome, f[0].replace("chr", ""), Integer.parseInt(f[1]), Integer.parseInt(f[2]))
					.getMidpoint();
			Point b = new Region(genome, f[3].replace("chr", ""), Integer.parseInt(f[4]), Integer.parseInt(f[5]))
					.getMidpoint();
			if (!a2bs.containsKey(a))
				a2bs.put(a, new ArrayList<Point>());
			a2bs.get(a).add(b);
			if (!b2as.containsKey(b))
				b2as.put(b, new ArrayList<Point>());
			b2as.get(b).add(a);
		}
		ArrayList<Point> aPoints = new ArrayList<Point>();
		aPoints.addAll(a2bs.keySet());
		Collections.sort(aPoints);
		ArrayList<Point> bPoints = new ArrayList<Point>();
		bPoints.addAll(b2as.keySet());
		Collections.sort(bPoints);

		// cluster the reads
		for (TSSwithReads t : allTss) {
			ArrayList<Integer> cluster = new ArrayList<Integer>();
			for (int offset : t.reads.keySet()) {
				if (cluster.isEmpty() || offset - cluster.get(cluster.size() - 1) < step) {
					cluster.add(offset);
				} else { // have a large distance, finish old cluster, create
							// new cluster
					if (cluster.size() >= minRead) { // at least 2 reads
						int median = cluster.get(cluster.size() / 2);
						Point tssPoint = t.coord;
						Point distalPoint = new Point(genome, t.coord.getChrom(),
								t.coord.getLocation() + (t.coord.getStrand() == '+' ? median : -median));
						Region distalRegion = null;
						if (t.coord.getStrand() == '+') {
							distalRegion = new Region(genome, t.coord.getChrom(),
									t.coord.getLocation() + cluster.get(0),
									t.coord.getLocation() + cluster.get(cluster.size() - 1));
						} else {
							distalRegion = new Region(genome, t.coord.getChrom(),
									t.coord.getLocation() - cluster.get(cluster.size() - 1),
									t.coord.getLocation() - cluster.get(0));
						}
						// print result if the read cluster is not in the tss
						// exclusion range
						System.out.print(String.format("%s\t%s\t%s\t%s\t%d\t%d\t%d\t", t.symbol,
								t.coord.getLocationString(), distalRegion.getLocationString(),
								distalPoint.getLocationString(), median, cluster.size(), distalRegion.getWidth()));

						// print binding overlap information
						int count = t.reads.get(cluster.get(0)).size();
						for (int c = 0; c < count; c++) {
							boolean isBound = false;
							for (int clusterOffset : cluster) {
								isBound = isBound || t.reads.get(clusterOffset).get(c);
							}
							System.out.print(isBound ? "1\t" : "0\t");
						}

						// print ChIA-PET call overlap info
						Point tssLeft = new Point(genome, t.coord.getChrom(), t.coord.getLocation() - 2000);
						Point tssRight = new Point(genome, t.coord.getChrom(), t.coord.getLocation() + 2000);

						// GERM
						int index = Collections.binarySearch(germTss, tssLeft);
						if (index < 0) // if key not found
							index = -(index + 1);
						int indexRight = Collections.binarySearch(germTss, tssRight);
						if (indexRight < 0) // if key not found
							indexRight = -(indexRight + 1);
						// if key match found, continue to search (
						// binarySearch() give undefined index with multiple
						// matches)
						boolean isOverlapped = false;
						indexRange: for (int i = index - 1; i <= indexRight + 2; i++) {
							if (i < 0 || i >= germTss.size())
								continue;
							try {
								Point tt = germTss.get(i);
								if (tt.distance(tssPoint) <= 2000) {
									if (!germTss2distals.containsKey(tt))
										continue;
									for (Point d : germTss2distals.get(tt)) {
										// System.out.print(tt.getLocationString()+"\t"+d.getLocationString());
										if (d.distance(distalPoint) <= 2000) {
											isOverlapped = true;
											// System.out.println("\tHIT");
											break indexRange;
										}
										// else
										// System.out.println();
									}
								}
							} catch (IllegalArgumentException e) { // ignore
							}
						}
						System.out.print(isOverlapped ? "1\t" : "0\t");

						// Mango
						index = Collections.binarySearch(aPoints, tssLeft);
						if (index < 0) // if key not found
							index = -(index + 1);
						indexRight = Collections.binarySearch(aPoints, tssRight);
						if (indexRight < 0) // if key not found
							indexRight = -(indexRight + 1);
						// if key match found, continue to search (
						// binarySearch() give undefined index with multiple
						// matches)
						isOverlapped = false;
						indexA: for (int i = index - 1; i <= indexRight + 2; i++) {
							if (i < 0 || i >= aPoints.size())
								continue;
							try {
								Point a = aPoints.get(i);
								if (a.distance(tssPoint) <= 2000) {
									if (!a2bs.containsKey(a))
										continue;
									for (Point b : a2bs.get(a)) {
										if (b.distance(distalPoint) <= 2000) {
											isOverlapped = true;
											break indexA;
										}
									}
								}
							} catch (IllegalArgumentException e) { // ignore
							}
						}
						if (isOverlapped)
							System.out.print("1\t");
						else {
							index = Collections.binarySearch(bPoints, tssLeft);
							if (index < 0) // if key not found
								index = -(index + 1);
							indexRight = Collections.binarySearch(bPoints, tssRight);
							if (indexRight < 0) // if key not found
								indexRight = -(indexRight + 1);
							// if key match found, continue to search (
							// binarySearch() give undefined index with multiple
							// matches)
							isOverlapped = false;
							indexB: for (int i = index - 1; i <= indexRight + 2; i++) {
								if (i < 0 || i >= bPoints.size())
									continue;
								try {
									Point b = bPoints.get(i);
									if (b.distance(tssPoint) <= 2000) {
										if (!b2as.containsKey(b))
											continue;
										for (Point a : b2as.get(b)) {
											if (a.distance(distalPoint) <= 2000) {
												isOverlapped = true;
												break indexB;
											}
										}
									}
								} catch (IllegalArgumentException e) { // ignore
								}
							}
							System.out.print(isOverlapped ? "1\t" : "0\t");
						}

						System.out.println();
					}
					cluster.clear();
					cluster.add(offset);
					continue;
				}
			}
		}
	}

	private void findAllInteractions() {
		long tic0 = System.currentTimeMillis();
		long tic = System.currentTimeMillis();

		// load read pairs
		// only use read pairs on the same chromosome, and longer than
		// self_exclude distance; the left read is required to be lower than the
		// right read; if not,
		// flip the two

		// sort by each end so that we can search to find matches or overlaps
		System.out.println("Loading ChIA-PET read pairs: " + CommonUtils.timeElapsed(tic));
		int min = 2; // minimum number of PET count to be called an interaction
		int numQuantile = Args.parseInteger(args, "num_quantile", 200);
		int minDistance = Args.parseInteger(args, "min_distance", 2000);
		ArrayList<Integer> dist_minus_plus = new ArrayList<Integer>();
		ArrayList<Integer> dist_plus_minus = new ArrayList<Integer>();
		ArrayList<Integer> dist_minus_minus = new ArrayList<Integer>();
		ArrayList<Integer> dist_plus_plus = new ArrayList<Integer>();

		ArrayList<String> read_pairs = CommonUtils.readTextFile(Args.parseString(args, "read_pair", null));
		boolean isBEDPE = Args.parseString(args, "format", "rp").equalsIgnoreCase("bedpe");
		// store PET as single ends
		ArrayList<Point> reads = new ArrayList<Point>(); 
		// all PET sorted by the low end
		ArrayList<ReadPair> low = new ArrayList<ReadPair>(); 
		ArrayList<ReadPair> high = new ArrayList<ReadPair>(); // sort high end
		StrandedPoint tmp1 = null;
		for (String s : read_pairs) {
			String[] f = s.split("\t");
			StrandedPoint r1;
			StrandedPoint r2;
			if (!isBEDPE){
				r1 = StrandedPoint.fromString(genome, f[0]);
				r2 = StrandedPoint.fromString(genome, f[1]);
			}
			else{
				char strand1 = f[8].charAt(0);
				r1 = new StrandedPoint(genome, f[0].replace("chr", ""), (Integer.parseInt(f[1])+Integer.parseInt(f[2]))/2, strand1);
				char strand2 = f[9].charAt(0);
				r2 = new StrandedPoint(genome, f[3].replace("chr", ""), (Integer.parseInt(f[4])+Integer.parseInt(f[5]))/2, strand2);
				// if not both ends are aligned properly, skip
				if (r1.getChrom().equals("*")){
					// add as single end if mapped
					if (!r2.getChrom().equals("*"))	
						reads.add(r1);
					continue;
				}
				if (r2.getChrom().equals("*")){
					// add as single end if mapped
					if (!r1.getChrom().equals("*")) 
						reads.add(r1);
					continue;
				}
			}
			String r1Chrom = r1.getChrom();
			reads.add(r1);
			reads.add(r2);
			// TODO: change next line if prediction cross-chrom interactions
			// r1 and r2 should be on the same chromosome
			if (!r1Chrom.equals(r2.getChrom())) 
				continue;
			if (r1.getLocation() > r2.getLocation()){
				tmp1 = r1;
				r2 = r1;
				r1 = tmp1;
			}
			// count PETs by strand-orientation
			if (r1.getLocation() > r2.getLocation())
				System.err.print("Not sorted ");
			int dist = r1.distance(r2);
			if (dist < minDistance)
				continue;

			if (r1.getStrand() == '-') {
				if (r2.getStrand() == '+')
					dist_minus_plus.add(dist);
				else if (r2.getStrand() == '-')
					dist_minus_minus.add(dist);
			} else if (r1.getStrand() == '+') {
				if (r2.getStrand() == '+')
					dist_plus_plus.add(dist);
				else if (r2.getStrand() == '-')
					dist_plus_minus.add(dist);
			}

			ReadPair rp = new ReadPair();
			if (r1.compareTo(r2) < 0) { // r1 should be lower than r2
				rp.r1 = r1;
				rp.r2 = r2;
			} else {
				rp.r1 = r2;
				rp.r2 = r1;
			}
			low.add(rp);
			ReadPair rp2 = new ReadPair();
			rp2.r1 = rp.r1;
			rp2.r2 = rp.r2;
			high.add(rp2);
		}

		low.trimToSize();
		high.trimToSize();
		Collections.sort(low, new Comparator<ReadPair>() {
			public int compare(ReadPair o1, ReadPair o2) {
				return o1.compareRead1(o2);
			}
		});
		Collections.sort(high, new Comparator<ReadPair>() {
			public int compare(ReadPair o1, ReadPair o2) {
				return o1.compareRead2(o2);
			}
		});

		ArrayList<Point> lowEnds = new ArrayList<Point>();
		for (ReadPair r : low)
			lowEnds.add(r.r1);
		lowEnds.trimToSize();
		ArrayList<Point> highEnds = new ArrayList<Point>();
		for (ReadPair r : high)
			highEnds.add(r.r2);
		highEnds.trimToSize();

		reads.trimToSize();
		Collections.sort(reads);

		System.out.println("\nLoaded total single reads = " + (reads.size() / 2) + ", filtered PETs =" + highEnds.size()
		+ " : " + CommonUtils.timeElapsed(tic));

		ArrayList<Integer> dist_other = new ArrayList<Integer>();
		dist_other.addAll(dist_plus_plus);
		dist_plus_plus = null;
		dist_other.addAll(dist_plus_minus);
		dist_plus_minus = null;
		dist_other.addAll(dist_minus_minus);
		dist_minus_minus = null;
		dist_other.trimToSize();
		Collections.sort(dist_other);
		dist_minus_plus.trimToSize();
		Collections.sort(dist_minus_plus);

		int step = dist_other.size() / numQuantile;
		// [e0 e1), end exclusive
		ArrayList<Integer> edges = new ArrayList<Integer>(); 
		// first idx for the number that is equal or larger than edge
		ArrayList<Integer> indexes_other = new ArrayList<Integer>();
		ArrayList<Integer> indexes_minus_plus = new ArrayList<Integer>(); 
		for (int i = 0; i <= numQuantile; i++) {
			int edge = dist_other.get(i * step);
			edges.add(edge);
			indexes_other.add(CommonUtils.findKey(dist_other, edge));
			indexes_minus_plus.add(CommonUtils.findKey(dist_minus_plus, edge));
		}
		ArrayList<Double> mpNonSelfFraction = new ArrayList<Double>();
		for (int i = 0; i < edges.size() - 1; i++) {
			double mpNonSelfCount = (indexes_other.get(i + 1) - indexes_other.get(i)) / 3.0;
			int mpCount = indexes_minus_plus.get(i + 1) - indexes_minus_plus.get(i);
			double frac = mpNonSelfCount / mpCount;
			if (frac > 1) {
				mpNonSelfFraction.add(1.0);
				break;
			} else
				mpNonSelfFraction.add(frac);
		}
		indexes_other = null;
		indexes_minus_plus = null;
		for (int i = edges.size() - 1; i >= mpNonSelfFraction.size(); i--)
			edges.remove(i);

		// output the distance-fraction table
		StringBuilder dfsb = new StringBuilder();
		for (int i = 0; i < edges.size(); i++)
			dfsb.append(edges.get(i)).append("\t").append(mpNonSelfFraction.get(i)).append("\n");
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".minusPlusFraction.txt", dfsb.toString());

		System.out.println("\nAnalyzed strand-orientation of PETs: " + CommonUtils.timeElapsed(tic0));

		String bedpe_file = Args.parseString(args, "bedpe", null);
		if (bedpe_file != null) {
			System.out.println(bedpe_file);
			ArrayList<String> lines = CommonUtils.readTextFile(bedpe_file);
			for (String anchorString : lines) {
				// System.out.println(anchorString);
				String[] f = anchorString.split("\t");
				Region region1 = new Region(genome, f[0].replace("chr", ""), Integer.parseInt(f[1]),
						Integer.parseInt(f[2]));
				Region region2 = new Region(genome, f[3].replace("chr", ""), Integer.parseInt(f[4]),
						Integer.parseInt(f[5]));
				ArrayList<Integer> idx = CommonUtils.getPointsWithinWindow(lowEnds, region1);
				for (int id : idx) {
					ReadPair rp = low.get(id);
					if (region2.contains(rp.r2))
						System.out.println(rp + "\t" + (rp.r1.getLocation() - region1.getStart()) + "\t"
								+ (rp.r2.getLocation() - region2.getStart()));
				}
			}
			System.exit(0);
		}

		// one dimension clustering to define anchors (similar to GEM code)
		// TODO: use cross correlation to determine the distance to shift
		ArrayList<Region> rs0 = new ArrayList<Region>();
		ArrayList<Point> summits = new ArrayList<Point>();
		// cut the pooled reads into independent regions
		int start0 = 0;
		int minCount = 3;
		for (int i = 1; i < reads.size(); i++) {
			Point p0 = reads.get(i - 1);
			Point p1 = reads.get(i);
			// not same chorm, or a large enough gap to cut
			if ((!p0.getChrom().equals(p1.getChrom())) || p1.getLocation() - p0.getLocation() > read_merge_dist) { 
				// only select region with read count larger than minimum count
				int count = i - start0;
				if (count >= minCount) {
					Region r = new Region(genome, p0.getChrom(), reads.get(start0).getLocation(),
							reads.get(i - 1).getLocation());
					rs0.add(r);
					ArrayList<Point> ps = new ArrayList<Point>();
					for (int j = start0; j < i; j++)
						ps.add(reads.get(j));
					int maxCount = 0;
					int maxIdx = -1;
					for (int j = 0; j < ps.size(); j++) {
						Point mid = ps.get(j);
						int c = CommonUtils.getPointsWithinWindow(ps, mid, read_merge_dist).size();
						if (c > maxCount) {
							maxCount = c;
							maxIdx = start0 + j;
						}
					}
					summits.add(reads.get(maxIdx));
				}
				start0 = i;
			}
		}
		// the last region
		int count = reads.size() - start0;
		if (count >= minCount) {
			Region r = new Region(genome, reads.get(start0).getChrom(), reads.get(start0).getLocation(),
					reads.get(reads.size() - 1).getLocation());
			rs0.add(r);
			ArrayList<Point> ps = new ArrayList<Point>();
			for (int j = start0; j < reads.size(); j++)
				ps.add(reads.get(j));
			int maxCount = 0;
			int maxIdx = -1;
			for (int j = 0; j < ps.size(); j++) {
				Point mid = ps.get(j);
				int c = CommonUtils.getPointsWithinWindow(ps, mid, read_merge_dist).size();
				if (c > maxCount) {
					maxCount = c;
					maxIdx = start0 + j;
				}
			}
			summits.add(reads.get(maxIdx));
		}
		reads.clear();
		reads = null;
		
		System.out.println("\nMerged all PETs into " + rs0.size() + " regions, " + CommonUtils.timeElapsed(tic0));

		// load gene annotation
		ArrayList<String> lines = CommonUtils.readTextFile(Args.parseString(args, "gene_anno", null));
		ArrayList<Point> allTSS = new ArrayList<Point>();
		HashMap<StrandedPoint, ArrayList<String>> tss2geneSymbols = new HashMap<StrandedPoint, ArrayList<String>>();
		for (int i = 0; i < lines.size(); i++) {
			String t = lines.get(i);
			if (t.startsWith("#"))
				continue;
			String f[] = t.split("\t");
			String chr = f[2].replace("chr", "");
			char strand = f[3].charAt(0);
			StrandedPoint tss = new StrandedPoint(genome, chr, Integer.parseInt(f[strand == '+' ? 4 : 5]), strand);
			allTSS.add(tss);
			if (!tss2geneSymbols.containsKey(tss))
				tss2geneSymbols.put(tss, new ArrayList<String>());
			tss2geneSymbols.get(tss).add(f[12]);
		}
		allTSS.trimToSize();
		Collections.sort(allTSS);

		// load TF sites
		String tfs_file = Args.parseString(args, "tf_sites", null);
		ArrayList<ArrayList<Point>> allPeaks = new ArrayList<ArrayList<Point>>();
		if (tfs_file != null) {
			ArrayList<String> tfs = CommonUtils.readTextFile(tfs_file);
			for (int i = 0; i < tfs.size(); i++) {
				try {
					ArrayList<Point> ps = new ArrayList<Point>();
					ps.addAll(GPSParser.parseGPSOutput(tfs.get(i), genome));
					ps.trimToSize();
					Collections.sort(ps);
					allPeaks.add(ps);
					System.out.println("Loaded " + tfs.get(i));
				} catch (IOException e) {
					System.out.println(tfs.get(i) + " does not have a valid GPS/GEM event call file.");
					e.printStackTrace(System.err);
					System.exit(1);
				}
			}
			allPeaks.trimToSize();
		}

		// load histone mark or DHS, SE regions
		String hms_file = Args.parseString(args, "regions", null);
		ArrayList<List<Region>> allRegions = new ArrayList<List<Region>>();
		if (hms_file != null) {
			ArrayList<String> hms = CommonUtils.readTextFile(hms_file);
			for (int i = 0; i < hms.size(); i++) {
				allRegions.add(CommonUtils.load_BED_regions(genome, hms.get(i)).car());
				System.out.println("Loaded " + hms.get(i));
			}
			allRegions.trimToSize();
			System.out.println();
		}

		// load other Interaction calls
		String germ_file = Args.parseString(args, "germ", null);
		ArrayList<Point> tPoints = new ArrayList<Point>();
		HashMap<Point, ArrayList<Point>> t2ds = new HashMap<Point, ArrayList<Point>>();
		if (germ_file != null) {
			lines = CommonUtils.readTextFile(germ_file);
			for (String l : lines) { // each line is a call
				String f[] = l.split("\t");
				Point t = new Region(genome, f[3].replace("chr", ""), Integer.parseInt(f[4]), Integer.parseInt(f[5]))
						.getMidpoint();
				Point d = new Region(genome, f[0].replace("chr", ""), Integer.parseInt(f[1]), Integer.parseInt(f[2]))
						.getMidpoint();
				if (t.getLocation() > d.getLocation()) { // make sure t < d
					Point tmp = t;
					t = d;
					d = tmp;
				}
				if (!t2ds.containsKey(t))
					t2ds.put(t, new ArrayList<Point>());
				t2ds.get(t).add(d);
			}
			tPoints.addAll(t2ds.keySet());
			tPoints.trimToSize();
			Collections.sort(tPoints);
		}

		String mango_file = Args.parseString(args, "mango", null);
		HashMap<Point, ArrayList<Point>> a2bs = new HashMap<Point, ArrayList<Point>>();
		ArrayList<Point> aPoints = new ArrayList<Point>();
		if (mango_file != null) {
			lines = CommonUtils.readTextFile(mango_file);
			for (String l : lines) { // each line is a call
				String f[] = l.split("\t");
				Point a = new Region(genome, f[0].replace("chr", ""), Integer.parseInt(f[1]), Integer.parseInt(f[2]))
						.getMidpoint();
				Point b = new Region(genome, f[3].replace("chr", ""), Integer.parseInt(f[4]), Integer.parseInt(f[5]))
						.getMidpoint();
				if (a.getLocation() > b.getLocation()) { // make sure a < b
					Point tmp = a;
					a = b;
					b = tmp;
				}

				if (!a2bs.containsKey(a))
					a2bs.put(a, new ArrayList<Point>());
				a2bs.get(a).add(b);
			}
			aPoints.addAll(a2bs.keySet());
			aPoints.trimToSize();
			Collections.sort(aPoints);
		}

		System.out.println("\nLoaded all the annotations, " + CommonUtils.timeElapsed(tic0));

		/**********************************************
		 * find dense PET cluster for each 1D cluster
		 **********************************************/
		ArrayList<Interaction> interactions = new ArrayList<Interaction>();
		HashSet<ReadPair> usedPETs = new HashSet<ReadPair>();

		tic = System.currentTimeMillis();

		for (int j = 0; j < rs0.size(); j++) { // for all regions
			Region region = rs0.get(j);

			// get the distal ends, merge nearby read pairs
			ArrayList<Integer> idx = CommonUtils.getPointsWithinWindow(lowEnds, region);
			if (idx.size() > 1) {
				ArrayList<ReadPair> rps = new ArrayList<ReadPair>();
				for (int i : idx) {
					rps.add(low.get(i));
				}
				Collections.sort(rps, new Comparator<ReadPair>() {
					public int compare(ReadPair o1, ReadPair o2) {
						return o1.compareRead2(o2);
					}
				});
				ArrayList<ReadPairCluster> rpcs = new ArrayList<ReadPairCluster>();
				int current = -100000;
				ReadPairCluster c = new ReadPairCluster();
				for (ReadPair rp : rps) {
					// a big gap
					if (rp.r2.getLocation() - current > read_merge_dist) { 
						if (c.pets.size() >= min) {
							rpcs.add(c);
						}
						c = new ReadPairCluster();
					}
					c.addReadPair(rp);
					current = rp.r2.getLocation();
				}
				if (c.pets.size() >= min) { // finish up the last cluster
					rpcs.add(c);
				}

				// test whether to merge clusters
				ArrayList<ReadPairCluster> toRemoveClusters = new ArrayList<ReadPairCluster>();
				for (int i = 1; i < rpcs.size(); i++) {
					ReadPairCluster c1 = rpcs.get(i - 1);
					ReadPairCluster c2 = rpcs.get(i);
					// cluster_merge_dist is dependent on the distance between
					// two anchor regions
					int dist = Math.min(c1.r2min + c1.r2max - c1.r1min - c1.r1max,
							c2.r2min + c2.r2max - c2.r1min - c2.r1max) / 2;
					int cluster_merge_dist = Math.min(max_cluster_merge_dist,
							Math.max(read_merge_dist, (int) Math.sqrt(dist) * distance_factor));
					if (c2.r2min - c1.r2max < cluster_merge_dist) {
						// simply merge c1 to c2
						for (ReadPair rp2 : c1.pets)
							c2.addReadPair(rp2);
						toRemoveClusters.add(c1);
					}
				} // for each pair of nearby clusters
				rpcs.removeAll(toRemoveClusters);
				toRemoveClusters.clear();
				toRemoveClusters = null;

				// refresh the PETs again because some PET1 might not be
				// included but are within the cluster_merge range
				for (ReadPairCluster cc : rpcs) {
					// int tmp = cc.pets.size();
					Region leftRegion = new Region(region.getGenome(), region.getChrom(), cc.r1min, cc.r1max);
					Region rightRegion = new Region(region.getGenome(), region.getChrom(), cc.r2min, cc.r2max);
					ArrayList<Integer> idx2 = CommonUtils.getPointsWithinWindow(lowEnds, leftRegion);
					cc.pets.clear();
					for (int i : idx2) {
						ReadPair rp = low.get(i);
						if (rightRegion.contains(rp.r2))
							cc.addReadPair(rp);
					}
				}

				ArrayList<ReadPairCluster> rpcs2 = splitRecursively(rpcs, true);
				if (rpcs2 != null) {
					rpcs = rpcs2;
					rpcs2 = null;
				}

				int maxEdge = edges.get(edges.size() - 1);
				for (ReadPairCluster cc : rpcs) {
					ArrayList<ReadPair> pets = cc.pets;
					if (pets.size() < min)
						continue;

					// mark all PETs in the cluster as used (PET2+)
					// to get real PET1 (no PET1 from the m-p adjustment)
					usedPETs.addAll(pets);	
					
					// count minus-plus PETs to adjust the PET counts
					ArrayList<ReadPair> mpRPs = new ArrayList<ReadPair>();
					for (ReadPair rp : pets)
						if (rp.r1.getStrand() == '-' && rp.r2.getStrand() == '+')
							mpRPs.add(rp);
					int totalCount = pets.size();
					int minusPlusCount = mpRPs.size();
					pets.removeAll(mpRPs);
					int adjustedCount = -1;
					Collections.sort(mpRPs, new Comparator<ReadPair>() {
						public int compare(ReadPair o1, ReadPair o2) {
							return o1.compareRead1(o2);
						}
					});
					// new PET cluster with adjustment
					ReadPairCluster rpc = new ReadPairCluster(); 
					if (pets.isEmpty()) { //  with only minus-plus PETs
						int dist = (cc.r2max + cc.r2min - cc.r1max - cc.r1min) / 2;
						if (dist >= maxEdge)
							adjustedCount = minusPlusCount;
						else {
							int index = Collections.binarySearch(edges, dist);
							if (index < 0) // if key not found
								index = -(index + 1);
							adjustedCount = (int) (minusPlusCount * mpNonSelfFraction.get(index));
						}
						// add the adjusted m-p PETs in the middle
						int midIndexMP = mpRPs.size() / 2 - adjustedCount /2;
						int endIndex = midIndexMP + adjustedCount;
						for (int k = midIndexMP; k < endIndex; k++)
							rpc.addReadPair(mpRPs.get(k));
					} else {
						for (ReadPair rp : pets)
							rpc.addReadPair(rp);
						int dist = (rpc.r2max + rpc.r2min - rpc.r1max - rpc.r1min) / 2;
						if (dist >= maxEdge)
							adjustedCount = totalCount;
						else {
							int index = Collections.binarySearch(edges, dist);
							if (index < 0) // if key not found
								index = -(index + 1);
							adjustedCount = totalCount - minusPlusCount
									+ (int) (minusPlusCount * mpNonSelfFraction.get(index));
						}
						// add the adjusted m-p PETs in the middle
						int extra = adjustedCount - (totalCount - minusPlusCount);
						int midIndexMP = mpRPs.size() / 2 - extra /2;
						int endIndex = midIndexMP + extra;
						for (int k = midIndexMP; k < endIndex; k++)
							rpc.addReadPair(mpRPs.get(k));
					}
					if (adjustedCount < min)
						continue;

					Interaction it = new Interaction();
					interactions.add(it);
					it.count = totalCount;
					it.count2 = totalCount - minusPlusCount;
					it.adjustedCount = adjustedCount;

					pets = rpc.pets;
					it.leftRegion = new Region(region.getGenome(), region.getChrom(), rpc.r1min, rpc.r1max);
					Collections.sort(pets, new Comparator<ReadPair>() {
						public int compare(ReadPair o1, ReadPair o2) {
							return o1.compareRead1(o2);
						}
					});
					if (pets.size() != 2)
						it.leftPoint = (Point) pets.get(pets.size()/2).r1;
					else
						it.leftPoint = it.leftRegion.getMidpoint();
					ArrayList<Integer> ts = CommonUtils.getPointsWithinWindow(allTSS,
							it.leftRegion.expand(tss_radius, tss_radius));
					StringBuilder tsb = new StringBuilder();
					TreeSet<String> gSymbols = new TreeSet<String>();
					for (int i : ts) {
						gSymbols.addAll(tss2geneSymbols.get(allTSS.get(i)));
					}
					for (String s : gSymbols)
						tsb.append(s).append(",");
					if (tsb.length() == 0)
						it.leftLabel = "nonTSS";
					else
						it.leftLabel = tsb.toString();

					it.rightRegion = new Region(region.getGenome(), region.getChrom(), rpc.r2min, rpc.r2max);
					Collections.sort(pets, new Comparator<ReadPair>() {
						public int compare(ReadPair o1, ReadPair o2) {
							return o1.compareRead2(o2);
						}
					});
					if (pets.size() != 2)
						it.rightPoint = (Point) pets.get(pets.size()/2).r2;
					else
						it.rightPoint = it.rightRegion.getMidpoint();
					ts = CommonUtils.getPointsWithinWindow(allTSS, it.rightRegion.expand(tss_radius, tss_radius));
					tsb = new StringBuilder();
					gSymbols.clear();
					for (int i : ts) {
						gSymbols.addAll(tss2geneSymbols.get(allTSS.get(i)));
					}
					for (String s : gSymbols)
						tsb.append(s).append(",");
					if (tsb.length() == 0)
						it.rightLabel = "nonTSS";
					else
						it.rightLabel = tsb.toString();
				}
				rpcs = null;
			} // all regions with long PETs
		} // loop over all regions

		interactions.trimToSize();
		
		System.out.println("\nCalled " + interactions.size() + " PET clusters, " + CommonUtils.timeElapsed(tic0));

		// mark PET1 (after removing the used PET2+)
		low.removeAll(usedPETs);
		low.trimToSize();
		high.clear();
		high = null;
		System.out.println("\nClustered PETs n=" + usedPETs.size() + "\nSingle PETs n=" + low.size());

		/******************************
		 * Annotate and report
		 *******************************/
		System.out.println("\nAnnotate and report, " + CommonUtils.timeElapsed(tic0));
		// report the interactions and annotations
		StringBuilder sb = new StringBuilder();
		for (Interaction it : interactions) {
			sb.append(it.toString()).append("\t");

			// annotate the proximal and distal anchors with TF and HM and
			// regions
			ArrayList<Integer> isOverlapped = new ArrayList<Integer>();
			// left ancor
			int radius = it.leftRegion.getWidth() / 2 + chiapet_radius;
			for (ArrayList<Point> ps : allPeaks) {
				ArrayList<Point> p = CommonUtils.getPointsWithinWindow(ps, it.leftPoint, radius);
				isOverlapped.add(p.size());
			}
			for (List<Region> rs : allRegions) {
				isOverlapped.add(CommonUtils.getRegionsOverlapsWindow(rs, it.leftRegion, chiapet_radius).size());
			}
			// right anchor
			radius = it.rightRegion.getWidth() / 2 + chiapet_radius;
			for (ArrayList<Point> ps : allPeaks) {
				ArrayList<Point> p = CommonUtils.getPointsWithinWindow(ps, it.rightPoint, radius);
				isOverlapped.add(p.size());
			}
			for (List<Region> rs : allRegions) {
				isOverlapped.add(CommonUtils.getRegionsOverlapsWindow(rs, it.rightRegion, chiapet_radius).size());
			}
			// print out TF and region overlaps
			if (hms_file != null || tfs_file != null) {
				for (int b : isOverlapped)
					sb.append(b).append("\t");
			}
			// print ChIA-PET call overlap info
			if (germ_file != null || mango_file != null) {
				int leftHalfWidth = it.leftRegion.getWidth() / 2 + chiapet_radius;
				int rightHalfWidth = it.rightRegion.getWidth() / 2 + chiapet_radius;
				Point leftAnchorLeft = new Point(genome, it.leftPoint.getChrom(),
						it.leftPoint.getLocation() - chiapet_radius);
				Point leftAnchorRight = new Point(genome, it.leftPoint.getChrom(),
						it.leftPoint.getLocation() + chiapet_radius);

				// GERM
				int index = Collections.binarySearch(tPoints, leftAnchorLeft);
				if (index < 0) // if key not found
					index = -(index + 1);
				int indexRight = Collections.binarySearch(tPoints, leftAnchorRight);
				if (indexRight < 0) // if key not found
					indexRight = -(indexRight + 1);
				// if key match found, continue to search ( binarySearch() give
				// undefined index with multiple matches)
				boolean isGermOverlapped = false;
				indexRange: for (int i = index - 1; i <= indexRight + 2; i++) {
					if (i < 0 || i >= tPoints.size())
						continue;
					try {
						Point tt = tPoints.get(i);
						if (tt.distance(it.leftPoint) <= leftHalfWidth) {
							if (!t2ds.containsKey(tt))
								continue;
							for (Point d : t2ds.get(tt)) {
								if (d.distance(it.rightPoint) <= rightHalfWidth) {
									isGermOverlapped = true;
									break indexRange;
								}
							}
						}
					} catch (IllegalArgumentException e) { // ignore
					}
				}
				if (isGermOverlapped)
					sb.append("1\t");
				else
					sb.append("0\t");

				// Mango
				// aPoints and bPoints are the midPoint of the two anchorRegions
				index = Collections.binarySearch(aPoints, leftAnchorLeft);
				if (index < 0) // if key not found
					index = -(index + 1);
				indexRight = Collections.binarySearch(aPoints, leftAnchorRight);
				if (indexRight < 0) // if key not found
					indexRight = -(indexRight + 1);
				// if key match found, continue to search ( binarySearch() give
				// undefined index with multiple matches)
				boolean isMangoOverlapped = false;
				indexA: for (int i = index - 1; i <= indexRight + 2; i++) {
					if (i < 0 || i >= aPoints.size())
						continue;
					try {
						Point a = aPoints.get(i);
						if (a.distance(it.leftPoint) <= leftHalfWidth) {
							if (!a2bs.containsKey(a))
								continue;
							for (Point b : a2bs.get(a)) {
								if (b.distance(it.rightPoint) <= rightHalfWidth) {
									isMangoOverlapped = true;
									break indexA;
								}
							}
						}
					} catch (IllegalArgumentException e) { // ignore
					}
				}
				if (isMangoOverlapped)
					sb.append("1\t");
				else
					sb.append("0\t");
			}
			CommonUtils.replaceEnd(sb, '\n');
		}
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".readClusters.txt", sb.toString());

		// output BEDPE format
		// HERE we need to also include PET1 for MICC and ChiaSig analysis
		for (ReadPair rp : low) {
			Interaction it = new Interaction();
			interactions.add(it);
			it.leftPoint = rp.r1;
			it.rightPoint = rp.r2;
			it.leftRegion = new Region(rp.r1.getGenome(), rp.r1.getChrom(), rp.r1.getLocation(), rp.r1.getLocation());
			it.rightRegion = new Region(rp.r2.getGenome(), rp.r2.getChrom(), rp.r2.getLocation(), rp.r2.getLocation());
			it.count = 1;
			it.count2 = 1;
			it.adjustedCount = 1;
		}
		low.clear();
		low = null;

		sb = new StringBuilder();
		for (Interaction it : interactions) {
			Region distalLocal = it.rightRegion.expand(read_merge_dist, read_merge_dist);
			Region tssLocal = it.leftRegion.expand(read_merge_dist, read_merge_dist);
			int distalLocalCount, tssLocalCount;
			distalLocalCount = CommonUtils.getPointsWithinWindow(highEnds, distalLocal).size();
			tssLocalCount = CommonUtils.getPointsWithinWindow(lowEnds, tssLocal).size();
			sb.append(String.format("%s\t%s\t%d\t%d\t%d\n", tssLocal.toBED(), distalLocal.toBED(), it.adjustedCount,
					tssLocalCount, distalLocalCount));
		}
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".bedpe", sb.toString());

		System.out.println("\nDone: " + CommonUtils.timeElapsed(tic0));
	}

	/**
	 * split read pair cluster recursively <br>
	 * at gaps larger than cluster_merge_dist, on both ends alternatively
	 * because splitting at one end may remove some PETs that introduce gaps at
	 * the other end
	 */
	ArrayList<ReadPairCluster> splitRecursively(ArrayList<ReadPairCluster> rpcs, boolean toSplitLeftAnchor) {
		if (rpcs.isEmpty())
			return null;

		int min = 2;
		int countSplit = 0;
		ArrayList<ReadPairCluster> rpcs2 = new ArrayList<ReadPairCluster>();
		for (ReadPairCluster cc : rpcs) {
			HashMap<Point, ArrayList<ReadPair>> map = new HashMap<Point, ArrayList<ReadPair>>();
			ArrayList<Point> splitPoints = new ArrayList<Point>();
			HashSet<Point> tmp = new HashSet<Point>();
			int dist = cc.r2max - cc.r1min;
			int cluster_merge_dist = Math.min(max_cluster_merge_dist,
					Math.max(read_merge_dist, (int) Math.sqrt(dist) * distance_factor));
			for (ReadPair rp : cc.pets) {
				Point t = toSplitLeftAnchor ? rp.r1 : rp.r2;
				tmp.add(t);
				if (!map.containsKey(t))
					map.put(t, new ArrayList<ReadPair>());
				map.get(t).add(rp);
			}
			splitPoints.addAll(tmp);
			Collections.sort(splitPoints);
			int curr = -100000;
			ReadPairCluster c = new ReadPairCluster();
			countSplit--; // first split is not real, subtract count here
			for (Point p : splitPoints) {
				if (p.getLocation() - curr > cluster_merge_dist) { // a big gap
					countSplit++;
					if (c.pets.size() >= min)
						rpcs2.add(c);
					c = new ReadPairCluster();
				}
				for (ReadPair rp : map.get(p))
					c.addReadPair(rp);
				curr = p.getLocation();
			}
			if (c.pets.size() >= min) // finish up the last cluster
				rpcs2.add(c);
		}
		if (countSplit > 0) {
			ArrayList<ReadPairCluster> rpcs3 = splitRecursively(rpcs2, !toSplitLeftAnchor); // split
																							// at
																							// the
																							// other
																							// end
			return rpcs3 == null ? rpcs2 : rpcs3;
		} else
			return null;
	}

	private static void annotateInteractions(String[] args) {
		Genome genome = CommonUtils.parseGenome(args);
		String cpcFile = Args.parseString(args, "cpc", null);
		String bed1File = Args.parseString(args, "bed1", null);
		String bed2File = Args.parseString(args, "bed2", null);
		int win = Args.parseInteger(args, "win", 1500);

		Pair<ArrayList<Region>, ArrayList<String>> tmp = CommonUtils.load_BED_regions(genome, bed1File);
		ArrayList<Region> r1s = tmp.car();
		ArrayList<String> s1s = tmp.cdr();

		tmp = CommonUtils.load_BED_regions(genome, bed2File);
		ArrayList<Region> r2s = tmp.car();
		ArrayList<String> s2s = tmp.cdr();

		ArrayList<String> lines = CommonUtils.readTextFile(cpcFile);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			String t = lines.get(i);
			String f[] = t.split("\t");
			Region distal = Region.fromString(genome, f[5]);
			ArrayList<Integer> idx1 = CommonUtils.getRegionIdxOverlapsWindow(r1s, distal, win);
			ArrayList<Integer> idx2 = CommonUtils.getRegionIdxOverlapsWindow(r2s, distal, win);
			for (int j = 0; j < f.length; j++)
				sb.append(f[j]).append("\t");
			for (int id : idx1)
				sb.append(s1s.get(id)).append(",");
			if (idx1.isEmpty())
				sb.append("NULL");
			sb.append("\t");
			for (int id : idx2)
				sb.append(s2s.get(id)).append(",");
			if (idx2.isEmpty())
				sb.append("NULL");
			sb.append("\t").append(idx1.size()).append("\t").append(idx2.size()).append("\t");
			sb.append(f[10]).append("\t").append(f[f.length - 1]);
			sb.append("\n");
		}
		CommonUtils.writeFile(cpcFile.replace("txt", "") + "annotated.txt", sb.toString());
	}

	private static void postProcessing(String[] args) {
		String cpcFile = Args.parseString(args, "cpc", null);
		ArrayList<String> lines = CommonUtils.readTextFile(cpcFile);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.size(); i++) {
			String t = lines.get(i);
			String f[] = t.split("\t");
			String g1s[] = f[0].trim().split(",");
			String g2s[] = f[3].trim().split(",");
			for (String g1 : g1s) {
				for (String g2 : g2s) {
					sb.append(g1).append("\t").append(f[1]).append("\t").append(f[2]).append("\t").append(g2);
					for (int j = 4; j < f.length; j++)
						sb.append("\t").append(f[j]);
					sb.append("\n");
				}
			}
		}
		CommonUtils.writeFile(cpcFile.replace("txt", "") + "per_gene.txt", sb.toString());
	}

	private static void getPetLength(String[] args) {
		Genome genome = CommonUtils.parseGenome(args);
		ArrayList<String> read_pairs = CommonUtils.readTextFile(Args.parseString(args, "read_pair", null));
		boolean isBEDPE = Args.parseString(args, "format", "rp").equalsIgnoreCase("bedpe");
		StringBuilder sb_minus_plus = new StringBuilder();
		StringBuilder sb_plus_minus = new StringBuilder();
		StringBuilder sb_minus_minus = new StringBuilder();
		StringBuilder sb_plus_plus = new StringBuilder();
		StrandedPoint tmp = null;
		int count=0;
		for (String s : read_pairs) {
			System.out.print((count++)+" ");
			String[] f = s.split("\t");
			StrandedPoint r1;
			StrandedPoint r2;
			if (!isBEDPE){
				r1 = StrandedPoint.fromString(genome, f[0]);
				r2 = StrandedPoint.fromString(genome, f[1]);
			}
			else{
				char strand1 = f[8].charAt(0);
				r1 = new StrandedPoint(genome, f[0].replace("chr", ""), (Integer.parseInt(f[1])+Integer.parseInt(f[2]))/2, strand1);
				char strand2 = f[9].charAt(0);
				r2 = new StrandedPoint(genome, f[3].replace("chr", ""), (Integer.parseInt(f[4])+Integer.parseInt(f[5]))/2, strand2);
				// if not both ends are aligned properly, skip
				if (r1.getChrom().equals("*") || r2.getChrom().equals("*"))	
					continue;
			}
			// r1 and r2 should be on the same chromosome
			if (!r1.getChrom().equals(r2.getChrom())) 
				continue;
			if (r1.getLocation() > r2.getLocation()){
				tmp = r1;
				r2 = r1;
				r1 = tmp;
			}
			int dist = r1.distance(r2);
			if (r1.getStrand() == '-') {
				if (r2.getStrand() == '+')
					sb_minus_plus.append(dist).append("\n");
				else if (r2.getStrand() == '-')
					sb_minus_minus.append(dist).append("\n");
			} else if (r1.getStrand() == '+') {
				if (r2.getStrand() == '+')
					sb_plus_plus.append(dist).append("\n");
				else if (r2.getStrand() == '-')
					sb_plus_minus.append(dist).append("\n");
			}
		}
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".minusPlus.length.txt",
				sb_minus_plus.toString());
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".plusMinus.length.txt",
				sb_plus_minus.toString());
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".minusMinus.length.txt",
				sb_minus_minus.toString());
		CommonUtils.writeFile(Args.parseString(args, "out", "Result") + ".plusPlus.length.txt",
				sb_plus_plus.toString());
		System.exit(0);
	}

	private class TSSwithReads {
		String symbol;
		int id;
		StrandedPoint coord;
		TreeMap<Integer, ArrayList<Boolean>> reads; // distal read offset -->
													// binary binding indicator
	}

	/** r1 should have lower coordinate than r2 */
	private class ReadPair implements Comparable<ReadPair> {
		StrandedPoint r1;
		StrandedPoint r2;

		public int compareRead1(ReadPair i) {
			return r1.compareTo(i.r1);
		}

		public int compareRead2(ReadPair i) {
			return r2.compareTo(i.r2);
		}

		@Override
		public int compareTo(ReadPair arg0) {
			// TODO Auto-generated method stub
			return 0;
		}

		public String toString() {
			return r1.toString() + "--" + r2.toString();
		}
	}

	private class ReadPairCluster implements Comparable<ReadPairCluster> {
		int r1min = Integer.MAX_VALUE;
		int r1max = -1;
		int r2min = Integer.MAX_VALUE;
		int r2max = -1;
		private ArrayList<ReadPair> pets = new ArrayList<ReadPair>();

		void addReadPair(ReadPair rp) {
			if (r1min > rp.r1.getLocation())
				r1min = rp.r1.getLocation();
			if (r2min > rp.r2.getLocation())
				r2min = rp.r2.getLocation();
			if (r1max < rp.r1.getLocation())
				r1max = rp.r1.getLocation();
			if (r2max < rp.r2.getLocation())
				r2max = rp.r2.getLocation();
			pets.add(rp);
		}

		double getDensity(int padding) {
			return pets.size() * 10000000.0 / ((r1max - r1min + padding) * (r2max - r2min + padding));
		}

		@Override
		public int compareTo(ReadPairCluster arg0) {
			// TODO Auto-generated method stub
			return 0;
		}

		void sortByRead1() {
			Collections.sort(pets, new Comparator<ReadPair>() {
				public int compare(ReadPair o1, ReadPair o2) {
					return o1.compareRead1(o2);
				}
			});
		}

		void sortByRead2() {
			Collections.sort(pets, new Comparator<ReadPair>() {
				public int compare(ReadPair o1, ReadPair o2) {
					return o1.compareRead2(o2);
				}
			});
		}

		public String toString2() {
			StringBuilder sb = new StringBuilder();
			sb.append(pets.size()).append("=<");
			for (ReadPair rp : pets)
				sb.append(rp.toString()).append(",");
			CommonUtils.replaceEnd(sb, '>');
			return sb.toString();
		}

		public String toString0() {
			StringBuilder sb = new StringBuilder();
			sb.append(pets.size()).append("=<");
			sb.append(pets.get(0).r1.getChrom()).append(":").append(r1min).append("-").append(r1max);
			sb.append("==").append(r2min).append("-").append(r2max).append(">");
			return sb.toString();
		}

		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(pets.size()).append("=<");
			sb.append(r1max - r1min);
			sb.append("==").append(r2max - r2min).append(">");
			return sb.toString();
		}

	}

	class Interaction0 {
		Point tss;
		String geneSymbol;
		Region tssRegion;
		int geneID;
		Point distalPoint;
		boolean isTfAnchord;
		Region distalRegion;
		int count;
		int indirectCount;
		double density;

		// double pvalue;
		public String toString() {
			// return String.format("%d %.1f\t< %s %s -- %s >", count, density,
			// geneSymbol, tssRegion, distalRegion);
			int dist = distalPoint.offset(tss);
			int padding = Math.abs(dist / 20);
			return String.format("%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%.1f", geneSymbol,
					(tss instanceof StrandedPoint) ? (StrandedPoint) tss : tss, tssRegion, distalPoint, distalRegion,
					tss.getChrom() + ":"
							+ (Math.min(Math.min(tssRegion.getStart(), distalRegion.getStart()), tss.getLocation())
									- padding)
							+ "-"
							+ (Math.max(Math.max(tssRegion.getEnd(), distalRegion.getEnd()), tss.getLocation())
									+ padding),
					tssRegion.getWidth(), distalRegion.getWidth(), dist, count, indirectCount, density);
		}
	}

	/**
	 * Interaction object <br>
	 * 2016/09/01, cluster PETs without gene annotation, therefore, no more tss
	 * or distal distinction<br>
	 * The anchor regions will overlap gene annotation to label gene
	 * information, but only after the clustering step
	 * 
	 * @author yguo
	 *
	 */
	class Interaction {
		Point leftPoint;
		Region leftRegion;
		String leftLabel;
		Point rightPoint;
		Region rightRegion;
		String rightLabel;
		int count;
		int indirectCount;
		int count2;
		int adjustedCount;
		double density;

		// double pvalue;
		public String toString() {
			// return String.format("%d %.1f\t< %s %s -- %s >", count, density,
			// geneSymbol, tssRegion, distalRegion);
			int dist = rightPoint.offset(leftPoint);
			int padding = Math.abs(dist / 20);
			return String.format("%s\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d", 
					leftLabel, new Point(leftPoint).toString(), leftRegion, rightLabel, new Point(rightPoint).toString(), rightRegion,
					leftPoint.getChrom() + ":"
							+ (Math.min(Math.min(leftRegion.getStart(), rightRegion.getStart()),
									leftPoint.getLocation()) - padding)
							+ "-"
							+ (Math.max(Math.max(leftRegion.getEnd(), rightRegion.getEnd()), leftPoint.getLocation())
									+ padding), // whole it region
					leftRegion.getWidth(), rightRegion.getWidth(), dist, adjustedCount, count, count2);
		}
	}

	class InteractionCall {
		Point tss;
		String tssString;
		String geneSymbol;
		String geneID;
		Region distal;
		double pvalue;
		TreeSet<Point> overlapCoords = new TreeSet<Point>();

		public String toString() {
			int start, end;
			if (tss.getLocation() < distal.getMidpoint().getLocation()) { // if
																			// TSS
																			// is
																			// upstream
				start = tss.getLocation();
				end = distal.getEnd();
			} else {
				start = distal.getStart();
				end = tss.getLocation();
			}
			Region it = new Region(genome, tss.getChrom(), start, end).expand(2000, 2000);
			return String.format("%s\t%d\t%s\t%s\t%s\t%s\t%.2f", it, distal.getMidpoint().distance(tss),
					distal.toString(), tssString, geneSymbol, geneID, -Math.log10(pvalue));
		}
	}
}
