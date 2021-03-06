package edu.mit.csail.cgs.warpdrive.components;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.regex.Matcher;
import java.io.*;

import edu.mit.csail.cgs.viz.colors.ColorSet;
import edu.mit.csail.cgs.warpdrive.*;
import edu.mit.csail.cgs.warpdrive.model.*;
import edu.mit.csail.cgs.warpdrive.paintable.*;
import edu.mit.csail.cgs.ewok.*;
import edu.mit.csail.cgs.ewok.nouns.*;
import edu.mit.csail.cgs.ewok.verbs.*;
import edu.mit.csail.cgs.ewok.verbs.binding.BindingExpander;
import edu.mit.csail.cgs.ewok.verbs.expression.LocatedExprMeasurementExpander;
import edu.mit.csail.cgs.ewok.verbs.motifs.PerBaseMotifMatch;
import edu.mit.csail.cgs.ewok.verbs.chipseq.*;
import edu.mit.csail.cgs.projects.chiapet.PairedStorage;
import edu.mit.csail.cgs.utils.*;
import edu.mit.csail.cgs.utils.database.DatabaseException;
import edu.mit.csail.cgs.utils.database.UnknownRoleException;
import edu.mit.csail.cgs.datasets.binding.*;
import edu.mit.csail.cgs.datasets.chipchip.ChipChipBayes;
import edu.mit.csail.cgs.datasets.chipchip.ChipChipData;
import edu.mit.csail.cgs.datasets.chipchip.ChipChipDataset;
import edu.mit.csail.cgs.datasets.chipchip.ChipChipDifferenceData;
import edu.mit.csail.cgs.datasets.chipchip.ChipChipMSP;
import edu.mit.csail.cgs.datasets.chipchip.ExptNameVersion;
import edu.mit.csail.cgs.datasets.chippet.ChipPetDatum;
import edu.mit.csail.cgs.datasets.chipseq.*;
import edu.mit.csail.cgs.datasets.expression.Experiment;
import edu.mit.csail.cgs.datasets.expression.ExpressionLoader;
import edu.mit.csail.cgs.datasets.expression.ProbePlatform;
import edu.mit.csail.cgs.datasets.general.NamedRegion;
import edu.mit.csail.cgs.datasets.general.NamedStrandedRegion;
import edu.mit.csail.cgs.datasets.general.NamedTypedRegion;
import edu.mit.csail.cgs.datasets.general.Region;
import edu.mit.csail.cgs.datasets.general.ScoredRegion;
import edu.mit.csail.cgs.datasets.general.ScoredStrandedRegion;
import edu.mit.csail.cgs.datasets.general.SpottedProbe;
import edu.mit.csail.cgs.datasets.general.StrandedPoint;
import edu.mit.csail.cgs.datasets.general.StrandedRegion;
import edu.mit.csail.cgs.datasets.locators.ChipChipDifferenceLocator;
import edu.mit.csail.cgs.datasets.motifs.*;
import edu.mit.csail.cgs.datasets.species.Gene;
import edu.mit.csail.cgs.datasets.species.Genome;
import edu.mit.csail.cgs.datasets.species.Organism;
import edu.mit.csail.cgs.deepseq.utilities.CommonUtils;
import edu.mit.csail.cgs.datasets.general.Point;

/* this is all for saveImage() */
import org.apache.batik.svggen.SVGGraphics2D;
import org.apache.batik.dom.GenericDOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.DOMImplementation;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/* RegionFrame encapsulates a set of painters that display a particular genomic region
 */

public class RegionPanel extends JPanel
		implements ActionListener, KeyListener, Listener<EventObject>, PainterContainer, MouseListener {

	// controls at the bottom of the panel
	private JPanel buttonPanel;
	private RegionContentPanel mainPanel;
	// scrolling controls
	private JButton leftButton, rightButton, zoomInButton, zoomOutButton, farLeftButton, farRightButton;
	private JTextField status;
	// maps a track name to its coordinates in the current layout
	private Hashtable<String, Integer> ulx, uly, lrx, lry;
	// maps a track name to the set of painters int hat track
	private Hashtable<String, ArrayList<RegionPaintable>> painters;
	// keeps track of the order in which painters are to be drawn
	private ArrayList<RegionPaintable> allPainters;
	// Thin painters are painters that have requested a fixed amount of space.
	// Thick painters want as much space as is available

	// private Hashtable<String,Integer> trackPaintOrderThick;
	// private Hashtable<String,Integer> trackPaintOrderThin;
	private Hashtable<String, Integer> trackPaintOrder;

	private Hashtable<String, Integer> trackSpace;
	// set of all the models and painters
	private HashSet<RegionModel> allModels;
	private Genome genome;
	// painterCount is the total number of painters.
	// readyCount is reset to 0 when the current region changes and keep track
	// of how many painters have reported in as ready. When readyCount ==
	// painterCount, we can try to draw.
	private int readyCount, painterCount;
	private Region currentRegion;
	private WarpOptions currentOptions;

	// mgp is here because there's at most one instance per RegionPanel
	// and this is an easy way to keep track of it.
	// private MultiGenePainter mgp = null;
	private ExonGenePainter egp = null;
	private static Color transparentWhite = new Color(255, 255, 255, 80);
	private boolean forceupdate = false, firstconfig = true;

	private Hashtable<RegionPaintable, ArrayList<RegionModel>> painterModelMap = new Hashtable<RegionPaintable, ArrayList<RegionModel>>();

	public RegionPanel(Genome g) {
		super();
		init(g);
		currentOptions = new WarpOptions();
	}

	public RegionPanel(WarpOptions opts) {
		super();
		if (WarpOptions.genomeString != null) {
			genome = new Genome("Genome", new File(WarpOptions.genomeString));
		} else {
			Organism organism = null;
			try {
				organism = Organism.getOrganism(opts.species);
				genome = organism.getGenome(opts.genome);
				// System.err.println("Creating a new RP for " + g);
				// System.err.println("opts.genome was " + opts.genome);
			} catch (NotFoundException ex) {
				System.err.println("Fatal Error in RegionPanel(WarpOptions)");
				ex.printStackTrace();
				// a little weird, but it'd happen anyway in init();
				throw new NullPointerException("Fatal Error in RegionPanel(WarpOptions)");
			}
		}
		init(genome);
		currentOptions = opts;
		addPaintersFromOpts(opts);
		// Find our initial region.
		Region startingRegion = null;
		if (opts.gene != null && opts.gene.matches("...*")) {
			startingRegion = regionFromString(genome, opts.gene);
		}
		if (startingRegion != null) {
			setRegion(startingRegion);
		} else if (opts.start >= 0 && opts.chrom != null) {
			setRegion(new Region(genome, opts.chrom, opts.start, opts.stop));
		} else if (opts.position != null && opts.position.length() > 0) {
			Region r = regionFromString(genome, opts.position);
			if (r != null) {
				setRegion(r);
			} else {
				r = regionFromString(genome, opts.gene);
				if (r != null) {
					setRegion(r);
				} else {
					throw new NullPointerException("Need a valid starting position in either chrom or gene");
				}
			}
		} else {
			if (WarpOptions.genomeString != null)
				setRegion(new Region(genome, "19", 200000, 420000));
			else
				throw new NullPointerException("Need a starting position in either chrom or gene");
		}
		if (opts.regionListFile != null) {
			java.util.List<Region> regions = readRegionsFromFile(genome, opts.regionListFile);
			RegionListPanel p = new RegionListPanel(this, regions);
			RegionListPanel.makeFrame(p);
		}
	}

	public void handleWindowClosing() {
		for (RegionPaintable rp : allPainters) {
			rp.cleanup();
		}
//		System.out.println("RegionPanel finished cleanup.");
	}

	public void init(Genome g) {
		genome = g;
		allModels = new HashSet<RegionModel>();
		painters = new Hashtable<String, ArrayList<RegionPaintable>>();

		// trackPaintOrderThick = new Hashtable<String,Integer>();
		// trackPaintOrderThin = new Hashtable<String,Integer>();
		trackPaintOrder = new Hashtable<String, Integer>();

		trackSpace = new Hashtable<String, Integer>();
		allPainters = new ArrayList<RegionPaintable>();
		ulx = new Hashtable<String, Integer>();
		uly = new Hashtable<String, Integer>();
		lrx = new Hashtable<String, Integer>();
		lry = new Hashtable<String, Integer>();
		painterCount = 0;
		readyCount = 0;

		currentRegion = new Region(g, "1", 0, 1000);

		buttonPanel = new JPanel();
		mainPanel = new RegionContentPanel();
		mainPanel.addMouseListener(this);
		setLayout(new BorderLayout());
		buttonPanel.setLayout(new GridBagLayout());

		leftButton = new JButton("<-");
		leftButton.setToolTipText("step left");
		rightButton = new JButton("->");
		rightButton.setToolTipText("step right");
		zoomInButton = new JButton("++");
		zoomInButton.setToolTipText("zoom in");
		zoomOutButton = new JButton("--");
		zoomOutButton.setToolTipText("zoom out");
		farLeftButton = new JButton("<<<-");
		farLeftButton.setToolTipText("jump left");
		farRightButton = new JButton("->>>");
		farRightButton.setToolTipText("jump right");
		status = new JTextField();
		Dimension buttonSize = new Dimension(30, 20);
		leftButton.setMaximumSize(buttonSize);
		rightButton.setMaximumSize(buttonSize);
		zoomInButton.setMaximumSize(buttonSize);
		zoomOutButton.setMaximumSize(buttonSize);
		farLeftButton.setMaximumSize(buttonSize);
		farRightButton.setMaximumSize(buttonSize);
		status.setMinimumSize(new Dimension(160, 20));
		status.setPreferredSize(new Dimension(300, 20));

		buttonPanel.add(farLeftButton);
		buttonPanel.add(leftButton);
		buttonPanel.add(zoomOutButton);
		buttonPanel.add(status);
		buttonPanel.add(zoomInButton);
		buttonPanel.add(rightButton);
		buttonPanel.add(farRightButton);

		leftButton.addActionListener(this);
		rightButton.addActionListener(this);
		status.addActionListener(this);
		zoomInButton.addActionListener(this);
		zoomOutButton.addActionListener(this);
		farLeftButton.addActionListener(this);
		farRightButton.addActionListener(this);
		buttonPanel.addKeyListener(this);
		mainPanel.addKeyListener(this);
		setBackground(Color.WHITE);
		add(mainPanel, BorderLayout.CENTER);
		add(buttonPanel, BorderLayout.SOUTH);
	}

	public void addPaintersFromOpts(WarpOptions opts) {

//		System.out.println("***** addPaintersFromOpts()");

		if (opts.genomeString == null
				&& !(opts.species.equals(genome.getSpecies()) && opts.genome.equals(genome.getVersion()))) {
			// if someone tries to add painters from a different species,
			// create a new frame for them instead.
			// this will probably get changed later if we have multi-species
			// painters, but that's not implemented now
			// System.err.println("RP.addPaintersFromOpts is creating a new RF for " +
			// opts.genome);
			RegionFrame frame = new RegionFrame(opts);
			return;
		}

		// System.out.println("***** addPaintersFromOpts() ---> Line 2");
		if (opts.hash) {
			HashMarkPaintable p = new HashMarkPaintable();
			// SimpleHashMarkPaintable p = new SimpleHashMarkPaintable();
			p.setLabel("Chromosomal position");
			p.addEventListener(this);
			addPainter(p);
		}
		if (WarpOptions.genomeString == null) {
			opts.mergeInto(currentOptions);
			ChipChipDataset dataset = new ChipChipDataset(genome);
			// there should be one scale model for each chip-chip track. We need to keep
			// them
			// here because multiple chipchip datasets (ie painters) may be on the same
			// track (ie piece
			// of screen real-estate)
			Hashtable<String, ChipChipScaleModel> scalemodels = new Hashtable<String, ChipChipScaleModel>();
			RegionMapperModel seqmodel = null;
			if (opts.gccontent || opts.cpg || opts.seqletters || opts.regexmatcher || opts.pyrpurcontent) {
				seqmodel = new RegionMapperModel(new SequenceGenerator(genome));
				addModel(seqmodel);
				Thread t = new Thread(seqmodel);
				t.start();
			}

			if (opts.gccontent) {
				GCContentPainter p = new GCContentPainter(seqmodel);
				p.addEventListener(this);
				p.setOption(WarpOptions.GCCONTENT, null);
				addPainter(p);
				addModelToPaintable(p, seqmodel);
			}
			if (opts.pyrpurcontent) {
				GCContentPainter p = new GCContentPainter(seqmodel);
				p.setLabel("Pyr (red) Pur (blue)");
				p.addEventListener(this);
				p.setOption(WarpOptions.GCCONTENT, null);
				GCContentProperties props = p.getProperties();
				props.BlueBases = "AG";
				props.RedBases = "CT";
				addPainter(p);
				addModelToPaintable(p, seqmodel);
			}
			if (opts.cpg) {
				CpGPainter p = new CpGPainter(seqmodel);
				p.setLabel("CpG");
				p.addEventListener(this);
				p.setOption(WarpOptions.CPG, null);
				addPainter(p);
				addModelToPaintable(p, seqmodel);
			}
			if (opts.regexmatcher) {
				RegexMatchPainter p = new RegexMatchPainter(seqmodel);
				p.setLabel("regexes");
				p.addEventListener(this);
				p.setOption(WarpOptions.REGEXMATCHER, null);
				addPainter(p);
				addModelToPaintable(p, seqmodel);
				for (String r : opts.regexes.keySet()) {
					p.addRegex(r, opts.regexes.get(r));
				}
			}

			if (opts.seqletters) {
				BasePairPainter p = new BasePairPainter(seqmodel);
				p.setLabel("Sequence");
				p.addEventListener(this);
				p.setOption(WarpOptions.SEQLETTERS, null);
				addPainter(p);
				addModelToPaintable(p, seqmodel);
			}
		}
		if (opts.chiapetExpts.size() > 0) {
			try {
				for (String k : opts.chiapetExpts.keySet()) {
					String name = opts.chiapetExpts.get(k);
					SortedMap<Pair<Point, Point>, Float> interactions = new TreeMap<Pair<Point, Point>, Float>(
							new Comparator<Pair<Point, Point>>() {

								public int compare(Pair<Point, Point> arg0, Pair<Point, Point> arg1) {
									int tor = arg0.car().compareTo(arg1.car());
									if (tor == 0) {
										return arg0.cdr().compareTo(arg1.cdr());
									} else {
										return tor;
									}
								}

							});
					SortedMap<Pair<Point, Point>, Pair<Region, Region>> interactionAnchors = new TreeMap<Pair<Point, Point>, Pair<Region, Region>>(
							new Comparator<Pair<Point, Point>>() {

								public int compare(Pair<Point, Point> arg0, Pair<Point, Point> arg1) {
									int tor = arg0.car().compareTo(arg1.car());
									if (tor == 0) {
										return arg0.cdr().compareTo(arg1.cdr());
									} else {
										return tor;
									}
								}

							});
					System.err.println(String.format("parsing %s with label %s.", k, name));
					long tic = System.currentTimeMillis();
					BufferedReader r = null;
					FileInputStream fis = new FileInputStream(k);
					try {
						r = new BufferedReader(new InputStreamReader(new GZIPInputStream(fis)));
					}
					catch(IOException e) {
						fis.close();
						r = new BufferedReader(new InputStreamReader(new FileInputStream(k)));
					}
					String s;
					String[] split;
					// r.readLine();
					while ((s = r.readLine()) != null) {
						split = s.split("\t");
						if (split.length == 2) { // if only 2 columns, read-pair data format, set count=1
							try {
								interactions.put(new Pair<Point, Point>(Point.fromString(genome, split[0]),
										Point.fromString(genome, split[1])), 1f);
							} catch (Exception e) {
								System.err.println(s);
								r.close();
								throw e;
							}
							continue;
						}
						if (!(split[0].equals("noise") || split[1].equals("noise"))) {
							try {
								Point left = Point.fromString(genome, split[0]);
								Point right = Point.fromString(genome, split[1]);
								interactions.put(new Pair<Point, Point>(left, right), Float.valueOf(split[2]));
								if (split.length >= 5)
									interactionAnchors.put(new Pair<Point, Point>(left, right),
											new Pair<Region, Region>(Region.fromString(genome, split[3]),
													Region.fromString(genome, split[4])));
							} catch (Exception e) {
								System.err.println(s);
								r.close();
								throw e;
							}
						}
					}
					r.close();
					System.err.println(CommonUtils.timeElapsed(tic));
					RegionModel m = new InteractionAnalysisModel(interactions, interactionAnchors);
					RegionPaintable p = new InteractionAnalysisPainter((InteractionAnalysisModel) m);
					addModel(m);
					Thread t = new Thread((Runnable) m);
					t.start();
					p.setLabel(name);
					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, m);
				}

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (opts.chiapetArcs.size() > 0) {
			try {
				ChipSeqLoader loader = new ChipSeqLoader();
				for (int i = 0; i < opts.chiapetArcs.size(); i++) {

					Collection<ChipSeqAlignment> alignments = loader.loadAlignment_noOracle(opts.chiapetArcs.get(i),
							genome, WarpOptions.readdb);
					InteractionArcModel m = new InteractionArcModel(alignments);
					InteractionArcPainter p = new InteractionArcPainter(m);
					addModel(m);
					Thread t = new Thread((Runnable) m);
					t.start();
					p.setLabel("Interaction " + opts.chiapetArcs.get(i).toString());

					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, m);
				}
				loader.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		RegionExpanderFactoryLoader<Gene> gfLoader;
		RegionExpanderFactoryLoader<NamedTypedRegion> annotLoader;
		gfLoader = new RegionExpanderFactoryLoader<Gene>("gene");
		annotLoader = new RegionExpanderFactoryLoader<NamedTypedRegion>("annots");

		if (opts.genes.size() > 0 && egp == null) {
			GeneModel geneModel = new GeneModel();

			for (int i = 0; i < opts.genes.size(); i++) {
				RegionExpanderFactory<Gene> genefactory = gfLoader.getFactory(genome, opts.genes.get(i).toString());
				Expander<Region, Gene> expander = genefactory.getExpander(genome);
				geneModel.addExpander(expander);
			}

			addModel(geneModel);
			Thread t = new Thread(geneModel);
			t.start();

			egp = new ExonGenePainter(geneModel);
			egp.setLabel("genes");
			egp.addEventListener(this);
			addPainter(egp);
			addModelToPaintable(egp, geneModel);
		}

		if (WarpOptions.geneTable != null) { // add gene annotaton track
			GeneModel geneModel = new GeneModel();
			RefGeneGenerator<Region> refgenes = new RefGeneGenerator<Region>(WarpOptions.geneTable);
			geneModel.addExpander(refgenes);

			addModel(geneModel);
			Thread t = new Thread(geneModel);
			t.start();

			egp = new ExonGenePainter(geneModel);
			egp.setLabel("genes");
			egp.addEventListener(this);
			addPainter(egp);
			addModelToPaintable(egp, geneModel);
		}

		if (opts.chipseqExpts.size() > 0) {
			try {
				ChipSeqLoader loader = new ChipSeqLoader();
				for (int i = 0; i < opts.chipseqExpts.size(); i++) {
					Collection<ChipSeqAlignment> alignments = loader.loadAlignment_noOracle(opts.chipseqExpts.get(i),
							genome, WarpOptions.readdb);
					RegionModel m;
					RegionPaintable p;
					if (opts.chipseqHistogramPainter) {
						m = new ChipSeqHistogramModel(alignments);
						p = new ChipSeqHistogramPainter((ChipSeqHistogramModel) m);
					} else {
						System.err.println("Using old ChipSeq painters");
						m = new ChipSeqDataModel(new edu.mit.csail.cgs.projects.readdb.Client(), alignments);
						p = new ChipSeqAboveBelowStrandPainter((ChipSeqDataModel) m);
					}
					addModel(m);
					Thread t = new Thread((Runnable) m);
					t.start();
					p.setLabel(opts.chipseqExpts.get(i).toString());

					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, m);
				}
				loader.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (opts.pairedChipseqExpts.size() > 0) {
			try {
				ChipSeqLoader loader = new ChipSeqLoader();
				for (int i = 0; i < opts.pairedChipseqExpts.size(); i++) {

					Collection<ChipSeqAlignment> alignments = loader.loadAlignment_noOracle(opts.pairedChipseqExpts.get(i),
							genome, WarpOptions.readdb);
					PairedEndModel m = new PairedEndModel(alignments);
					PairedEndPainter p = new PairedEndPainter(m);
					addModel(m);
					Thread t = new Thread((Runnable) m);
					t.start();
					p.setLabel("Paired " + opts.pairedChipseqExpts.get(i).toString());

					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, m);
				}
				loader.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		if (opts.chipseqAnalyses.size() > 0) {
			try {
				ChipSeqLoader loader = new ChipSeqLoader(true);
				for (int i = 0; i < opts.chipseqAnalyses.size(); i++) {
					ChipSeqAnalysis a = opts.chipseqAnalyses.get(i);
					ChipSeqAnalysisModel m = new ChipSeqAnalysisModel(a);
					ChipSeqAnalysisPainter p = new ChipSeqAnalysisPainter(a, m);
					addModel(m);
					Thread t = new Thread((Runnable) m);
					t.start();
					p.setLabel(a.toString());

					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, m);
				}
				loader.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		if (opts.exprExperiments.size() > 0) {
			try {

				for (int i = 0; i < opts.exprExperiments.size(); i++) {
					ExpressionLoader loader = new ExpressionLoader();
					Experiment expt = opts.exprExperiments.get(i);
					ProbePlatform plat = expt.getPlatform();
					LocatedExprMeasurementExpander exp = new LocatedExprMeasurementExpander(loader, expt.getName(),
							plat.getName());

					ExpressionProbeModel model = new ExpressionProbeModel(exp);
					addModel(model);

					ExpressionMeasurementPainter p = new ExpressionMeasurementPainter(model);
					p.setLabel("Expression");
					p.setOption(WarpOptions.EXPRESSION, null);

					p.addEventListener(this);
					addPainter(p);
					addModelToPaintable(p, model);

					Thread t = new Thread(model);
					t.start();
				}

			} catch (SQLException e) {
				e.printStackTrace();
			} catch (UnknownRoleException e) {
				e.printStackTrace();
			}
		}


		for (int i = 0; i < opts.otherannots.size(); i++) {

			RegionExpanderFactory factory = annotLoader.getFactory(genome, opts.otherannots.get(i).toString());
			Expander expander = factory.getExpander(genome);
			RegionExpanderModel m;
			RegionPaintable p;
			if (factory.getProduct().equals("NamedTypedRegion")) {
				m = new RegionExpanderModel<NamedTypedRegion>((Expander<Region, NamedTypedRegion>) expander);
				p = new NamedTypedPainter((RegionExpanderModel<NamedTypedRegion>) m);
			} else if (factory.getProduct().equals("ScoredRegion")) {
				double max = 1.0;
				if (opts.otherannots.get(i).toString().equals("readcoverage")) {
					max = 40;
				}
				m = new RegionExpanderModel<ScoredRegion>((Expander<Region, ScoredRegion>) expander);
				p = new HeightScoredPainter((RegionExpanderModel<ScoredRegion>) m, max);
			} else if (factory.getProduct().equals("NamedStrandedRegion")) {
				m = new RegionExpanderModel<NamedStrandedRegion>((Expander<Region, NamedStrandedRegion>) expander);
				p = new NamedStrandedPainter((RegionExpanderModel<NamedStrandedRegion>) m);
			} else if (factory.getProduct().equals("StrandedRegion")) {
				m = new RegionExpanderModel<StrandedRegion>((Expander<Region, StrandedRegion>) expander);
				p = new NamedStrandedPainter((RegionExpanderModel<StrandedRegion>) m);
			} else if (factory.getProduct().equals("NamedRegion")) {
				m = new RegionExpanderModel<NamedRegion>((Expander<Region, NamedRegion>) expander);
				p = new NamedStrandedPainter((RegionExpanderModel<NamedRegion>) m); // yes, this works.
																					// NamedStrandedPainter can handle
																					// non-stranded Regions
			} else if (factory.getProduct().equals("HarbisonRegCodeRegion")) {
				m = new RegionExpanderModel<HarbisonRegCodeRegion>((Expander<Region, HarbisonRegCodeRegion>) expander);
				p = new HarbisonRegCodePainter((RegionExpanderModel<HarbisonRegCodeRegion>) m);
			} else if (factory.getProduct().equals("HarbisonRegCodeProbes")) {
				m = new RegionExpanderModel<HarbisonRegCodeProbe>((Expander<Region, HarbisonRegCodeProbe>) expander);
				p = new HarbisonProbePainter((RegionExpanderModel<HarbisonRegCodeProbe>) m);
			} else if (factory.getProduct().equals("SpottedProbe")) {
				m = new RegionExpanderModel<SpottedProbe>((Expander<Region, SpottedProbe>) expander);
				p = new SpottedProbePainter((RegionExpanderModel<SpottedProbe>) m);
			} else if (factory.getProduct().equals("ChipPet")) {
				m = new ScoreTrackModel((Expander<Region, ChipPetDatum>) expander);
				// p = new ScoreTrackPainter((ScoreTrackModel)m);
				p = new IntervalPainter((ScoreTrackModel) m);
			} else {
				throw new RuntimeException("Don't understand product type " + factory.getProduct());
			}
			// System.err.println("Created " + p + " for " + m);
			addModel(m);
			Thread t = new Thread(m);
			t.start();
			p.setLabel(opts.otherannots.get(i).toString());
			p.addEventListener(this);
			p.setOption(WarpOptions.OTHERANNOTS, opts.otherannots.get(i));
			addPainter(p);
			addModelToPaintable(p, m);
		}

		for (int i = 0; i < opts.motifs.size(); i++) {
			WeightMatrix matrix = opts.motifs.get(i);
			MarkovBackgroundModel bgModel = null;
			try {
				String bgmodelname = "whole genome zero order";
				BackgroundModelMetadata md = BackgroundModelLoader.getBackgroundModel(bgmodelname, 1, "MARKOV",
						genome.getDBID());
				if (md != null) {
					bgModel = BackgroundModelLoader.getMarkovModel(md);
				} else {
					System.err.println("Couldn't get metadata for " + bgmodelname);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}

			if (bgModel != null) {
				matrix.toLogOdds(bgModel);
			} else {
				matrix.toLogOdds();
			}

			PerBaseMotifMatch match = new PerBaseMotifMatch(matrix);
			RegionMapperModel<Double[]> m = new RegionMapperModel<Double[]>(
					new Mapper.Compose<Region, String, Double[]>(new SequenceGenerator(genome), match));
			addModel(m);
			Thread t = new Thread(m);
			t.start();
			PerBaseScorePainter p = new PerBaseScorePainter<Double>(m, matrix.getMinScore(), 0.0, matrix.getMaxScore());
			p.setLabel(opts.motifs.get(i).toString());
			p.addEventListener(this);
			p.setOption(WarpOptions.MOTIFS, opts.motifs.get(i));
			addPainter(p);
			addModelToPaintable(p, m);
		}

		for (int i = 0; i < opts.motifscans.size(); i++) {
			try {
				MotifScanResultsGenerator g = new MotifScanResultsGenerator(opts.motifscans.get(i));
				RegionExpanderModel<ScoredStrandedRegion> m = new RegionExpanderModel<ScoredStrandedRegion>(g);
				addModel(m);
				Thread t = new Thread(m);
				t.start();
				MotifScanPainter p = new MotifScanPainter(m);
				String trackname = opts.motifscans.get(i).matrix.name;
				p.setLabel(trackname);
				p.addEventListener(this);
				p.setOption(WarpOptions.MOTIFSCANS, opts.motifscans.get(i));
				addPainter(p);
				addModelToPaintable(p, m);
			} catch (NotFoundException ex) {
				System.err.println("Couldn't find any such motif scan : " + opts.motifscans.get(i));
				ex.printStackTrace();
			}
		}
		for (String k : opts.regionTracks.keySet()) {
			addTrackFromFile(k, opts.regionTracks.get(k));
		}

		if (firstconfig) {
			firstconfig = false;
		} else {
			forceupdate = true;
			setRegion(getRegion());
		}
	}

	/*
	 * when we take a paintable out of the display, we also need to take it out of
	 * currentOptions. Failing to remove the paintable from currentOptions makes it
	 * impossible to add the paintable back into the display later (when we add
	 * paintables later, we take the difference of the new options and the
	 * currentOptions. If a removed paintable is still in currentOptions, the
	 * difference will remove it from the new options and it won't be re-added)
	 */
	public void removePainterFromOpts(RegionPaintable p) {
		switch (p.getOptionKey()) {
		case WarpOptions.BINDINGSCAN:
			currentOptions.bindingScans.clear();
		case WarpOptions.GENES:
			currentOptions.genes.remove(p.getOptionInfo());
		case WarpOptions.NCRNAS:
			currentOptions.ncrnas.remove(p.getOptionInfo());
		case WarpOptions.OTHERANNOTS:
			currentOptions.otherannots.remove(p.getOptionInfo());
		case WarpOptions.AGILENTDATA:
			currentOptions.agilentdata.remove(p.getOptionInfo());
		case WarpOptions.BAYESRESULTS:
			currentOptions.bayesresults.remove(p.getOptionInfo());
		case WarpOptions.AGILENTLL:
			currentOptions.agilentll.remove(p.getOptionInfo());
		case WarpOptions.MSP:
			currentOptions.msp.remove(p.getOptionInfo());
		case WarpOptions.MOTIFSCANS:
			currentOptions.motifscans.remove(p.getOptionInfo());
		case WarpOptions.PEAKS:
			currentOptions.peakCallers.remove(p.getOptionInfo());
		case WarpOptions.SEQLETTERS:
			currentOptions.seqletters = false;
		case WarpOptions.GCCONTENT:
			currentOptions.gccontent = false;
		}
	}

	/*
	 * removes all the painters in a track from the visualizer. This removes the
	 * track from the visualizer and unregisters the RegionPanel as a listener from
	 * the Paintable. The Paintable should keep track of how many listeners it has
	 * and should unregister itself from its models when it has no listeners. The
	 * Models, in turn, should call their stopRunning() method when they have no
	 * listeners
	 */
	public void removeTrack(String trackname) {
		if (!painters.containsKey(trackname)) {
			return;
		}
		ArrayList<RegionPaintable> plist = painters.get(trackname);
		for (int i = 0; i < plist.size(); i++) {
			plist.get(i).removeEventListener(this);
			if (!plist.get(i).hasListeners()) {
				removePainterFromOpts(plist.get(i));
				allPainters.remove(plist.get(i));
				painterModelMap.remove(plist.get(i));
				painterCount--;
			}
		}

		/*
		 * if (trackPaintOrderThin.containsKey(trackname)) {
		 * removeTrackOrder(trackname,trackPaintOrderThin); } else if
		 * (trackPaintOrderThick.containsKey(trackname)){
		 * removeTrackOrder(trackname,trackPaintOrderThick); }
		 */
		if (trackPaintOrder.containsKey(trackname)) {
			removeTrackOrder(trackname, trackPaintOrder);
		}

		painters.remove(trackname);
		for (RegionModel m : (HashSet<RegionModel>) allModels.clone()) {
			if (!m.hasListeners()) {
				allModels.remove(m);
			}
		}
		repaint();
	}

	/*
	 * Removes a track with specified name from the track order. Shifts all the
	 * other tracks up one to fill in the empty space
	 */
	private void removeTrackOrder(String trackName, Hashtable<String, Integer> table) {
		if (!table.containsKey(trackName)) {
			return;
		}
		int value = table.get(trackName);
		table.remove(trackName);
		for (String k : table.keySet()) {
			if (table.get(k) > value) {
				table.put(k, table.get(k) - 1);
			}
		}
	}

	public WarpOptions getCurrentOptions() {
		return currentOptions;
	}

	public void actionPerformed(ActionEvent e) {
		if (e.getSource() == leftButton) {
			int increment = (int) (currentRegion.getWidth() * .25);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() - increment, currentRegion.getEnd() - increment));
			repaint();
		}

		if (e.getSource() == rightButton) {
			int increment = (int) (currentRegion.getWidth() * .25);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() + increment, currentRegion.getEnd() + increment));
			repaint();
		}

		if (e.getSource() == farLeftButton) {
			int increment = (int) (currentRegion.getWidth() * .85);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() - increment, currentRegion.getEnd() - increment));
			repaint();
		}

		if (e.getSource() == farRightButton) {
			int increment = (int) (currentRegion.getWidth() * .85);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() + increment, currentRegion.getEnd() + increment));
			repaint();
		}

		if (e.getSource() == zoomInButton) {
			int increment = (int) (currentRegion.getWidth() * .25);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() + increment, currentRegion.getEnd() - increment));
			repaint();
		}

		if (e.getSource() == zoomOutButton) {
			int increment = (int) (currentRegion.getWidth() * .5);
			setRegion(new Region(currentRegion.getGenome(), currentRegion.getChrom(),
					currentRegion.getStart() - increment, currentRegion.getEnd() + increment));
			repaint();
		}
		if (e.getSource() == status) {
			Region r = regionFromString(genome, status.getText().trim());
			if (r != null) {
				setRegion(r);
			}
		}
	}

	/* need to finish grabbing the key stuff from GFFPanel */
	public void keyPressed(KeyEvent e) {
	}

	public void keyReleased(KeyEvent e) {
	}

	public void keyTyped(KeyEvent e) {
	}
	/*
	 * this is a custom String -> int routine that handles suffixes such as k and m
	 */

	public void setRegion(Region newRegion) {
		if (newRegion.getChrom().matches("^chr.*")) {
			newRegion = new Region(newRegion.getGenome(), newRegion.getChrom().replaceAll("^chr", ""),
					newRegion.getStart(), newRegion.getEnd());
		}
		if (newRegion.getEnd() - newRegion.getStart() < 30) {
			newRegion = new Region(newRegion.getGenome(), newRegion.getChrom(), newRegion.getStart() - 15,
					newRegion.getEnd() + 15);
		}
		if (newRegion.getStart() < 1) {
			newRegion = new Region(newRegion.getGenome(), newRegion.getChrom(), 1, newRegion.getEnd());
		}
		if (newRegion.getEnd() > newRegion.getGenome().getChromLength(newRegion.getChrom())) {
			newRegion = new Region(newRegion.getGenome(), newRegion.getChrom(), newRegion.getStart(),
					newRegion.getGenome().getChromLength(newRegion.getChrom()));
		}

		if (!newRegion.equals(currentRegion) || forceupdate) {
			currentRegion = newRegion;
			status.setText(currentRegion.getLocationString());

			/*
			 * kick the painters here to give them a little extra time to update their data
			 * before we try to paint them
			 */
			readyCount = 0;
			// set the new region in the paintables
			for (RegionPaintable p : allPainters) {
				p.setRegion(newRegion);
			}
			// set the new region in the models
			// and call notify on them. This gives a RegionModel
			// the option of wait()ing in a separate thread
			// if it so desires.
			for (RegionModel m : allModels) {
				synchronized (m) {
					m.setRegion(newRegion);
					m.notifyAll();
				}
			}
			repaint();
		}
	}

	public void close() {
		for (RegionModel m : allModels) {
			synchronized (m) {
				m.stopRunning();
				m.notifyAll();
			}
		}
		try {
			Thread.sleep(400);
		} catch (Exception e) {

		}
	}

	/*
	 * this parses input from the region/location text area and turns it into a
	 * region. If the text is not parseable as a cgsRegion or bedRegion, try to
	 * turn it into a gene and use that.
	 */
	public static Region regionFromString(Genome genome, String input) {
		String trimmed = input.trim().replaceAll("\\s+", "");
		Region r = Region.fromString(genome, input);
		if (r == null) {
			String[] f = input.split("\t");
			// BED format is end exclusive
			if (f.length>=3)
				r = new Region(genome, f[0].replace("chr", "").replace("Chr", ""), Integer.parseInt(f[1]), Integer.parseInt(f[2])-1);
			if (r==null) {
				trimmed = trimmed.toLowerCase();
				if (WarpOptions.geneTable.containsKey(trimmed)) 
					return WarpOptions.geneTable.get(trimmed).get(0);
			}
		}
		return r;
	}

	public static java.util.List<Region> readRegionsFromFile(Genome g, String filename) {
		ArrayList<Region> regions = new ArrayList<Region>();
		try {
			BufferedReader r = new BufferedReader(new FileReader(filename));
			String s;
			while ((s = r.readLine()) != null) {
				Region region = regionFromString(g, s);
				if (region != null) {
					regions.add(region);
				} else {
					System.err.println("Couldn't parse " + s);
				}

			}
			r.close();
		} catch (IOException ex) {
			throw new RuntimeException("Can't read " + filename, ex);
		}
		return regions;
	}

	/**
	 * add a new painter to this panel and have it load its default properties
	 */
	public void addPainter(RegionPaintable p) {
		if (p == null) {
			return;
		}
		painterCount++;
		String pk = p.getLabel();
		if (painters.get(pk) == null) {
			painters.put(pk, new ArrayList<RegionPaintable>());
			ulx.put(pk, 0);
			uly.put(pk, 0);
			lrx.put(pk, 0);
			lry.put(pk, 0);
		}
		painters.get(p.getLabel()).add(p);
		p.getProperties().loadDefaults();
		allPainters.add(p);
	}

	public void changePainter(RegionPaintable oldPainter, RegionPaintable newPainter) {
		String pk = oldPainter.getLabel();
		if ((oldPainter == null) || (newPainter == null) || (!painters.containsKey(pk))) {
			return;
		}
		newPainter.addEventListener(this);
		newPainter.setLabel(oldPainter.getLabel());

		ArrayList<RegionPaintable> painterList = painters.get(pk);
		painterList.set(painterList.indexOf(oldPainter), newPainter);
		allPainters.set(allPainters.indexOf(oldPainter), newPainter);
		painterModelMap.put(newPainter, painterModelMap.get(oldPainter));
		painterModelMap.remove(oldPainter);
		oldPainter.cleanup();
		for (RegionModel m : painterModelMap.get(newPainter)) {
			m.notifyListeners();
		}
		repaint();
	}

	public void addModel(RegionModel m) {
		if (m == null) {
			throw new NullPointerException("Don't you give me a null model");
		}
		allModels.add(m);
	}

	public void addModelToPaintable(RegionPaintable p, RegionModel m) {
		if (painterModelMap.get(p) == null) {
			painterModelMap.put(p, new ArrayList<RegionModel>());
		}
		painterModelMap.get(p).add(m);
		m.getProperties().loadDefaults();
	}

	public void removeModel(RegionModel m) {
		for (RegionPaintable k : painterModelMap.keySet()) {
			ArrayList<RegionModel> l = painterModelMap.get(k);
			l.remove(m);
		}
		allModels.remove(m);
	}

	/*
	 * recompute the layout for this panel. Newly added painters will not be visible
	 * until you call this method
	 */
	public void computeLayout(int x, int y, int width, int height) {
		int ypos = height;
		int thickSpace = height;
		int thickCount = 0;

		Set<String> keyset = painters.keySet();
		HashMap<String, Boolean> thickMap = new HashMap<String, Boolean>();

		String[] keys = new String[keyset.size()];
		int i = 0;
		for (String s : painters.keySet()) {
			keys[i++] = s;
		}
		Arrays.sort(keys, new AddedOrderComparator());

		Hashtable<String, Integer> requests = new Hashtable<String, Integer>();
		for (i = 0; i < keys.length; i++) {
			String s = keys[i];
			boolean isthick = false;
			int maxspace = 0;
			ArrayList<RegionPaintable> plist = painters.get(s);
			for (int j = 0; j < plist.size(); j++) {
				int request = plist.get(j).getMaxVertSpace();
				if (request == -1) {
					isthick = true;
					continue;
				} else if (maxspace < request) {
					maxspace = request;
				}
			}
			requests.put(s, maxspace);

			/*
			 * if (isthick) { if (!trackPaintOrderThick.containsKey(s)) {
			 * trackPaintOrderThick.put(s,trackPaintOrderThick.size() + 1); } // make sure
			 * the key isn't in both tables if it switched // from being thin to thick
			 * removeTrackOrder(s,trackPaintOrderThin); } else { if
			 * (!trackPaintOrderThin.containsKey(s)) {
			 * trackPaintOrderThin.put(s,trackPaintOrderThin.size() + 1); }
			 * removeTrackOrder(s,trackPaintOrderThick); }
			 */

			thickMap.put(s, isthick);

			if (!trackPaintOrder.containsKey(s)) {
				trackPaintOrder.put(s, trackPaintOrder.size() + 1);
			}

		}

		for (String s : thickMap.keySet()) {
			if (!thickMap.get(s)) {
				int allocated;
				if (trackSpace.containsKey(s + "_requested")
						&& !trackSpace.get(s + "_allocated").equals(trackSpace.get(s + "_requested"))) {
					allocated = trackSpace.get(s + "_allocated");
				} else {
					allocated = requests.get(s);
				}
				thickSpace -= allocated;
			} else {
				thickCount += 1;
			}
		}

		int thickAlloc = (thickSpace - 5 * thickCount) / (Math.max(1, thickCount));

		/*
		 * keys = new String[trackPaintOrderThin.size()]; i = 0; for (String s :
		 * trackPaintOrderThin.keySet()) { keys[i++] = s; } Arrays.sort(keys,new
		 * HashtableComparator(trackPaintOrderThin));
		 */
		keys = new String[trackPaintOrder.size()];
		i = 0;
		for (String s : trackPaintOrder.keySet()) {
			keys[i++] = s;
		}
		Arrays.sort(keys, new HashtableComparator(trackPaintOrder));

		// layout from the bottom up
		for (i = keys.length - 1; i >= 0; i--) {
			String s = keys[i];
			int allocated;
			if (thickMap.get(s)) {
				allocated = thickAlloc;
			} else if (trackSpace.containsKey(s + "_requested")
					&& !trackSpace.get(s + "_allocated").equals(trackSpace.get(s + "_requested"))) {
				allocated = trackSpace.get(s + "_allocated");
				// System.err.println("USING stored allocation for " + s + ": " + allocated + ".
				// Requested was " +
				// trackSpace.get(s + "_requested"));
			} else {
				allocated = requests.get(s);
				// System.err.println("USING requested allocation for " + s + ": " + allocated);
			}
			trackSpace.put(s + "_allocated", allocated);
			// System.err.println("STORING allocation for " + s + " as " + allocated);

			ulx.put(s, x);
			lrx.put(s, width + x);

			if (thickMap.get(s)) {
				lry.put(s, ypos - 10);
				uly.put(s, ypos - allocated + 10);
			} else {
				lry.put(s, ypos);
				uly.put(s, ypos - allocated);
			}

			ypos -= allocated;
		}

//		System.out.println();

		/*
		 * keys = null; keys = new String[trackPaintOrderThick.size()]; i = 0; for
		 * (String s : trackPaintOrderThick.keySet()) { keys[i++] = s; }
		 * Arrays.sort(keys,new HashtableComparator(trackPaintOrderThick));
		 * 
		 * if (trackPaintOrderThick.size() > 0) { int thickspace = ypos /
		 * trackPaintOrderThick.size(); for (i = keys.length -1; i >= 0; i--) { String s
		 * = keys[i]; ulx.put(s,x); lrx.put(s,width + x); lry.put(s,ypos - 10); ypos -=
		 * thickspace; uly.put(s,ypos + 10); } }
		 */

		for (String k : requests.keySet()) {
			trackSpace.put(k + "_requested", requests.get(k));
			// System.err.println("STORING request for " + k + " as " + requests.get(k));
		}
	}

	/*
	 * this is a callback from a painter to the RegionPanel saying that the painter
	 * is ready to be painted. This implementation is just a heuristic, but the goal
	 * is to avoid calling repaint() until all the painters are done
	 */
	public synchronized void eventRegistered(EventObject e) {
		if (e.getSource() instanceof VizPaintable) {
			readyCount++;
			if (readyCount >= painterCount) {
				repaint();
			}
		}
	}

	public void paintComponent(Graphics g) {
		paintComponent(g, getX(), getY(), getWidth(), getHeight());
	}

	public void paintComponent(Graphics g, int x, int y, int width, int height) {
		// System.err.println("Calling paint component from RP");
		/*
		 * don't need to do this; java is calling paintComponent() on the panel anyway,
		 * so this just leads to double painting which slows things down
		 */
		// mainPanel.paintComponent(g,mainPanel.getX(),mainPanel.getY(),
		// mainPanel.getWidth(),mainPanel.getHeight());
	}

	public boolean allCanPaint() {
		boolean canpaint = true;
		for (String s : painters.keySet()) {
			ArrayList<RegionPaintable> plist = painters.get(s);
			for (int i = 0; i < plist.size(); i++) {
				canpaint = canpaint && plist.get(i).canPaint();
			}
		}
		return canpaint;
	}

	class RegionContentPanel extends JPanel {
		private Hashtable painted = new Hashtable<Object, Boolean>();

		public void paintComponent(Graphics g) {
			paintComponent(g, mainPanel.getX(), mainPanel.getY(), mainPanel.getWidth(), mainPanel.getHeight());
		}

		public void paintComponent(Graphics g, int x, int y, int width, int height) {
			/*
			 * two passes: first make sure everyone can paint. If everyone is ready, call
			 * computeLayout (in case anyone's space request changed based on the amount of
			 * data they have) and then do the painting
			 */
			Graphics2D graphics = (Graphics2D) g;
			boolean canpaint = true;
			for (String s : painters.keySet()) {
				ArrayList<RegionPaintable> plist = painters.get(s);
				for (int i = 0; i < plist.size(); i++) {
					canpaint = canpaint && plist.get(i).canPaint();
				}
			}

			if (!canpaint) {
				g.setColor(transparentWhite);
				g.fillRect(0, 0, width, height);
				return;
			}
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			computeLayout(x, y, width, height);
			for (String s : painters.keySet()) {
				ArrayList<RegionPaintable> plist = painters.get(s);
				for (int i = 0; i < plist.size(); i++) {
					// if (!plist.get(i).wantsPaint()) {continue;}
					// System.err.println("Painting " + plist.get(i) + " in " + ulx.get(s) + "," +
					// uly.get(s) +
					// " " + lrx.get(s) + "," + lry.get(s));
					// System.err.println(" time is " + System.currentTimeMillis());
					try {
						plist.get(i).paintItem(graphics, ulx.get(s), uly.get(s), lrx.get(s), lry.get(s));
						// System.err.println("Time after " + plist.get(i).getLabel() + " is " +
						// System.currentTimeMillis() );
					} catch (Exception e) {
						e.printStackTrace();
						graphics.setColor(Color.RED);
						graphics.drawString("Error: " + e.toString(), ulx.get(s), lry.get(s));
					}

				}
			}
		}
	}

	public void saveImage(File f, int w, int h, boolean raster) throws IOException {
		if (raster) {
			BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics g = im.getGraphics();
			Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHints(
					new RenderingHints(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));
			mainPanel.paintComponent(g, 0, 0, w, h);
			ImageIO.write(im, "png", f);
			g.dispose();
		} else {
			DOMImplementation domImpl = GenericDOMImplementation.getDOMImplementation();
			// Create an instance of org.w3c.dom.Document
			Document document = domImpl.createDocument(null, "svg", null);
			// Create an instance of the SVG Generator
			SVGGraphics2D svgGenerator = new SVGGraphics2D(document);
			svgGenerator.setSVGCanvasSize(new Dimension(w, h));
			// Ask the test to render into the SVG Graphics2D implementation
			svgGenerator.setColor(Color.white);
			svgGenerator.fillRect(0, 0, w, h);
			mainPanel.paintComponent(svgGenerator, 50, 50, w - 100, h - 100);

			// Finally, stream out SVG to the standard output using UTF-8
			// character to byte encoding
			boolean useCSS = true; // we want to use CSS style attribute
			Writer out = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
			svgGenerator.stream(out, useCSS);
		}
	}

	public Genome getGenome() {
		return genome;
	}

	public Region getRegion() {
		return currentRegion;
	}

	public boolean equals(Object o) {
		if (o instanceof RegionPanel) {
			return (o == this);
		} else {
			return false;
		}
	}

	public void addTrackFromFile() {
		JFileChooser chooser;
		chooser = new JFileChooser(new File(System.getProperty("user.dir")));
		int v = chooser.showOpenDialog(null);
		if (v == JFileChooser.APPROVE_OPTION) {
			File f = chooser.getSelectedFile();
			addTrackFromFile(f.getAbsolutePath(), f.getName());
		}
	}

	public void addTrackFromFile(String fname, String trackname) {
		System.err.println("Adding " + fname + " with name " + trackname);
		try {
			java.util.List<Region> regions = readRegionsFromFile(genome, fname);
			StaticExpander<Region, Region> expander = new StaticExpander<Region, Region>(regions);
			RegionExpanderModel<Region> model = new RegionExpanderModel<Region>(expander);
			addModel(model);
			Thread t = new Thread(model);
			t.start();
			NamedStrandedPainter p = new NamedStrandedPainter(model);
			p.setLabel(trackname);
			p.addEventListener(this);
			addPainter(p);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private class HashtableComparator implements Comparator<String> {
		private Hashtable<String, Integer> t;

		public HashtableComparator(Hashtable<String, Integer> t) {
			this.t = t;
		}

		public int compare(String a, String b) {
			return t.get(a) - t.get(b);
		}

		public boolean equals(Object o) {
			return (o == this);
		}
	}

	private class AddedOrderComparator implements Comparator<String> {
		public int compare(String a, String b) {
			int mina, minb, i;
			mina = 1000000;
			minb = 1000000;
			ArrayList<RegionPaintable> plist = painters.get(a);
			for (i = 0; i < plist.size(); i++) {
				int added = allPainters.lastIndexOf(plist.get(i));
				if (added < mina) {
					mina = added;
				}
			}
			plist = painters.get(b);
			for (i = 0; i < plist.size(); i++) {
				int added = allPainters.lastIndexOf(plist.get(i));
				if (added < minb) {
					minb = added;
				}
			}
			return mina - minb;
		}

		public boolean equals(Object o) {
			return (o == this);
		}
	}

	class ConfigureActionListener implements ActionListener {
		private String k;

		public ConfigureActionListener(String k) {
			this.k = k;
		}

		public void actionPerformed(ActionEvent e) {
			configureTrack(k);
		}
	}

	class RemoveActionListener implements ActionListener {
		private String k;
		private RegionPanel panel;

		public RemoveActionListener(String k, RegionPanel p) {
			this.k = k;
			this.panel = p;
		}

		public void actionPerformed(ActionEvent e) {
			removeTrack(k);
			panel.repaint();
		}
	}

	class SavePrefsActionListener implements ActionListener {
		private ArrayList<RegionPaintable> p;

		public SavePrefsActionListener(ArrayList<RegionPaintable> p) {
			this.p = p;
		}

		public void actionPerformed(ActionEvent e) {
			for (RegionPaintable paintable : p) {
				paintable.getProperties().saveToFile();
				if (painterModelMap.get(paintable) != null) {
					for (RegionModel m : painterModelMap.get(paintable)) {
						if (m instanceof WarpModel) {
							((WarpModel) m).getProperties().saveToFile();
						}
					}
				}
			}
		}
	}

	class LoadPrefsActionListener implements ActionListener {
		private ArrayList<RegionPaintable> p;

		public LoadPrefsActionListener(ArrayList<RegionPaintable> p) {
			this.p = p;
		}

		public void actionPerformed(ActionEvent e) {
			for (RegionPaintable paintable : p) {
				// save TrackLabel so that it is not overwritten
				String trackLabel = paintable.getProperties().TrackLabel;
				paintable.getProperties().loadFromFile();
				paintable.getProperties().TrackLabel = trackLabel;
				if (painterModelMap.get(paintable) != null) {
					for (RegionModel m : painterModelMap.get(paintable)) {
						if (m instanceof WarpModel) {
							((WarpModel) m).getProperties().loadFromFile();
						}
					}
				}
			}
		}
	}

	class SaveAllPrefsActionListener implements ActionListener {
		private RegionPanel panel;

		public SaveAllPrefsActionListener(RegionPanel p) {
			this.panel = p;
		}

		public void actionPerformed(ActionEvent e) {
			JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.dir")));
			chooser.setDialogTitle("Save preferences to...");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int returnVal = chooser.showSaveDialog(null);
			if (returnVal == JFileChooser.APPROVE_OPTION) {
				File f = chooser.getSelectedFile();
				for (String k : painters.keySet()) {
					for (RegionPaintable p : painters.get(k)) {
						p.savePropsInDir(f);
					}
				}
			}
		}
	}

	class LoadAllPrefsActionListener implements ActionListener {
		private RegionPanel panel;

		public LoadAllPrefsActionListener(RegionPanel p) {
			this.panel = p;
		}

		public void actionPerformed(ActionEvent e) {
			JFileChooser chooser = new JFileChooser(new File(System.getProperty("user.dir")));
			chooser.setDialogTitle("Load preferences from...");
			chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
			int returnVal = chooser.showOpenDialog(null);
			if (returnVal == JFileChooser.APPROVE_OPTION) {
				File f = chooser.getSelectedFile();
				for (String k : painters.keySet()) {
					for (RegionPaintable p : painters.get(k)) {
						p.loadPropsInDir(f);
					}
				}
			}
			panel.repaint();
		}
	}

	class MoveInLayoutActionListener implements ActionListener {
		private String key;
		private int dir;
		private RegionPanel panel;

		public MoveInLayoutActionListener(String k, int dir, RegionPanel p) {
			this.key = k;
			this.dir = dir;
			this.panel = p;
		}

		public void actionPerformed(ActionEvent e) {
			Hashtable<String, Integer> t = null;

			/*
			 * if (trackPaintOrderThick.containsKey(key)) { t = trackPaintOrderThick; } else
			 * if (trackPaintOrderThin.containsKey(key)) { t = trackPaintOrderThin; } else {
			 * return; }
			 */
			if (trackPaintOrder.containsKey(key)) {
				t = trackPaintOrder;
			} else {
				return;
			}

			int old = t.get(key);
			int desired = old + dir;
			for (String k : t.keySet()) {
				if (t.get(k) == desired) {
					t.put(k, old);
					t.put(key, desired);
					break;
				}
			}
			panel.repaint();
		}
	}

	class ChangeTrackSize implements ActionListener {
		private String key;
		private double factor;
		private RegionPanel panel;

		public ChangeTrackSize(String key, double factor, RegionPanel p) {
			this.key = key;
			this.factor = factor;
			this.panel = p;
		}

		public void actionPerformed(ActionEvent e) {
			if (!trackSpace.containsKey(key + "_allocated")) {
				return;
			}
			trackSpace.put(key + "_allocated", (int) (trackSpace.get(key + "_allocated") * factor));
			panel.computeLayout(getX(), getY(), getWidth(), getHeight());
			panel.repaint();
		}
	}

	/*
	 * respond to a right-click on a track. Since tracks may overlap, keys is a set
	 * of Strings that describes the propertykeys for all of the tracks that we need
	 * to be able to handle
	 */
	public JPopupMenu trackRightClick(ArrayList<String> keys) {
		JPopupMenu menu = new JPopupMenu("Track Setup");
		JMenuItem item;
		for (String k : keys) {
			item = new JMenuItem("Configure " + k.substring(0, Math.min(60, k.length())));
			item.addActionListener(new ConfigureActionListener(k));
			menu.add(item);
			item = new JMenuItem("Remove " + k.substring(0, Math.min(60, k.length())));
			item.addActionListener(new RemoveActionListener(k, this));
			menu.add(item);

			item = new JMenuItem("Save Prefs");
			item.addActionListener(new SavePrefsActionListener(painters.get(k)));
			menu.add(item);
			item = new JMenuItem("Load Prefs");
			item.addActionListener(new LoadPrefsActionListener(painters.get(k)));
			menu.add(item);
			item = new JMenuItem("Save All Prefs");
			item.addActionListener(new SaveAllPrefsActionListener(this));
			menu.add(item);
			item = new JMenuItem("Load All Prefs");
			item.addActionListener(new LoadAllPrefsActionListener(this));
			menu.add(item);

			item = new JMenuItem("Move Up in Layout");
			item.addActionListener(new MoveInLayoutActionListener(k, -1, this));
			menu.add(item);
			item = new JMenuItem("Move Down in Layout");
			item.addActionListener(new MoveInLayoutActionListener(k, 1, this));
			menu.add(item);
			item = new JMenuItem("Increase track size");
			item.addActionListener(new ChangeTrackSize(k, 1.3, this));
			menu.add(item);
			item = new JMenuItem("Decrease track size");
			item.addActionListener(new ChangeTrackSize(k, .75, this));
			menu.add(item);
		}
		return menu;
	}

	public void configureTrack(String track) {
		if (painters.containsKey(track)) {
			HashSet<WarpProperties> props = new HashSet<WarpProperties>();
			ArrayList<RegionPaintable> plist = painters.get(track);
			for (RegionPaintable p : plist) {
//				System.err.println("looking at painter " + p);
				props.add(p.getProperties());
				if (painterModelMap.get(p) == null) {
					continue;
				}
				for (RegionModel m : painterModelMap.get(p)) {
					props.add(m.getProperties());
				}
			}
			WarpProperties.configure(props, this);
		}
	}

	public void mouseClicked(MouseEvent e) {
		// System.err.println("CLICK " + e);
		if (e.getButton() == MouseEvent.BUTTON3 || e.isPopupTrigger()) {
			int xpos = e.getX();
			int ypos = e.getY();
			ArrayList<String> keys = new ArrayList<String>();
			for (String pk : painters.keySet()) {
				if (ulx.get(pk) <= xpos && lrx.get(pk) >= xpos && uly.get(pk) <= ypos && lry.get(pk) >= ypos) {
					keys.add(pk);
				}
			}
			JPopupMenu m = trackRightClick(keys);
			m.show(mainPanel, xpos, ypos);
		} else {
			int xpos = e.getX();
			int ypos = e.getY();
			int totalitems = 0;
			JPopupMenu m = new JPopupMenu("Stuff");
			for (String pk : painters.keySet()) {
				if (ulx.get(pk) <= xpos && lrx.get(pk) >= xpos && uly.get(pk) <= ypos && lry.get(pk) >= ypos) {
					for (RegionPaintable p : painters.get(pk)) {
						p.mouseClicked(e);
						ArrayList<JMenuItem> items = p.mouseClickedMenu(e);
						if (items == null) {
							continue;
						}
						String k = p.getLabel();
						if (totalitems > 0) {
							m.addSeparator();
						}
						JMenuItem label = new JMenuItem(k);
						label.setEnabled(false);
						m.add(label);
						for (JMenuItem item : items) {
							m.add(item);
						}
						totalitems++;
					}
				}
			}
			if (totalitems > 0) {
				m.show(e.getComponent(), xpos, ypos);
			}
		}
	}

	public void mouseEntered(MouseEvent e) {
	}

	public void mouseExited(MouseEvent e) {
	}

	public void mousePressed(MouseEvent e) {
		int xpos = e.getX();
		int ypos = e.getY();
		for (String pk : painters.keySet()) {
			if (ulx.get(pk) <= xpos && lrx.get(pk) >= xpos && uly.get(pk) <= ypos && lry.get(pk) >= ypos) {
				for (RegionPaintable p : painters.get(pk)) {
					p.mousePressed(e);
				}
			}
		}
	}

	public void mouseReleased(MouseEvent e) {
		int xpos = e.getX();
		int ypos = e.getY();
		for (String pk : painters.keySet()) {
			if (ulx.get(pk) <= xpos && lrx.get(pk) >= xpos && uly.get(pk) <= ypos && lry.get(pk) >= ypos) {
				for (RegionPaintable p : painters.get(pk)) {
					p.mouseReleased(e);
				}
			}
		}
	}
}
