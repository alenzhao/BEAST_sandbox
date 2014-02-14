package dr.evomodel.epidemiology.casetocase;

import dr.app.tools.NexusExporter;
import dr.evolution.coalescent.*;
import dr.evolution.tree.FlexibleNode;
import dr.evolution.tree.FlexibleTree;
import dr.evolution.tree.NodeRef;
import dr.evolution.tree.Tree;
import dr.evolution.util.Taxon;
import dr.evolution.util.TaxonList;
import dr.evomodel.coalescent.DemographicModel;
import dr.evomodel.tree.TreeModel;
import dr.inference.model.Model;
import dr.inference.model.Parameter;
import dr.inference.model.Variable;
import dr.math.*;
import dr.math.distributions.NormalGammaDistribution;
import dr.math.functionEval.GammaFunction;
import dr.xml.*;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Intended to replace the tree prior; each partition is considered a tree in its own right generated by a
 * coalescent process
 *
 * @author Matthew Hall
 */

public class WithinCaseCoalescent extends CaseToCaseTreeLikelihood {

    public static final String WITHIN_CASE_COALESCENT = "withinCaseCoalescent";
    private double[] partitionTreeLogLikelihoods;
    private double[] storedPartitionTreeLogLikelihoods;
    private double[] timingLogLikelihoods;
    private double[] storedTimingLogLikelihoods;
    private boolean[] recalculateCoalescentFlags;
    private Treelet[] partitionsAsTrees;
    private Treelet[] storedPartitionsAsTrees;
    private DemographicModel demoModel;


    public WithinCaseCoalescent(PartitionedTreeModel virusTree, AbstractOutbreak caseData,
                                String startingNetworkFileName, Parameter infectionTimeBranchPositions,
                                Parameter maxFirstInfToRoot, DemographicModel demoModel)
            throws TaxonList.MissingTaxonException {
        this(virusTree, caseData, startingNetworkFileName, infectionTimeBranchPositions, null,
                maxFirstInfToRoot, demoModel);
    }

    public WithinCaseCoalescent(PartitionedTreeModel virusTree, AbstractOutbreak caseData,
                                String startingNetworkFileName, Parameter infectionTimeBranchPositions,
                                Parameter infectiousTimePositions, Parameter maxFirstInfToRoot,
                                DemographicModel demoModel)
            throws TaxonList.MissingTaxonException {
        super(WITHIN_CASE_COALESCENT, virusTree, caseData, infectionTimeBranchPositions, infectiousTimePositions,
                maxFirstInfToRoot);
        this.demoModel = demoModel;
        addModel(demoModel);
        partitionTreeLogLikelihoods = new double[noTips];
        storedPartitionTreeLogLikelihoods = new double[noTips];
        timingLogLikelihoods = new double[noTips];
        storedTimingLogLikelihoods = new double[noTips];
        recalculateCoalescentFlags = new boolean[noTips];

        partitionsAsTrees = new Treelet[caseData.size()];
        storedPartitionsAsTrees = new Treelet[caseData.size()];

        prepareTree(startingNetworkFileName);

        prepareTimings();
    }

    public static double[] logOfAllValues(double[] values){
        double[] out = Arrays.copyOf(values, values.length);

        for(int i=0; i<values.length; i++){
            out[i] = Math.log(out[i]);
        }
        return out;
    }

    protected double calculateLogLikelihood(){

        if(DEBUG){
            super.debugOutputTree("bleh.nex", false);
        }

        double logL = 0;

        // you shouldn't need to do this, because C2CTransL will already have done it

        // super.prepareTimings();

        int noInfectiousCategories = ((WithinCaseCategoryOutbreak)cases).getInfectiousCategoryCount();

        HashMap<String, ArrayList<Double>> infectiousPeriodsByCategory
                = new HashMap<String, ArrayList<Double>>();


        for(AbstractCase aCase : cases.getCases()){

            String category = ((WithinCaseCategoryOutbreak)cases).getInfectiousCategory(aCase);

            if(!infectiousPeriodsByCategory.keySet().contains(category)){
                infectiousPeriodsByCategory.put(category, new ArrayList<Double>());
            }

            ArrayList<Double> correspondingList
                    = infectiousPeriodsByCategory.get(category);

            correspondingList.add(getInfectiousPeriod(aCase));
        }

        WithinCaseCategoryOutbreak.PriorType infPriorType = ((WithinCaseCategoryOutbreak) cases).getInfPriorType();

        switch(infPriorType){
            case NORMAL_GAMMA:
                for(String category : ((WithinCaseCategoryOutbreak) cases).getInfectiousCategories()){
                    ArrayList<Double> infPeriodsInThisCategory = infectiousPeriodsByCategory.get(category);

                    double count = (double)infPeriodsInThisCategory.size();

                    NormalGammaDistribution prior = ((WithinCaseCategoryOutbreak)cases)
                            .getInfectiousCategoryPrior(category);

                    double[] infPredictiveDistributionParameters=prior.getParameters();

                    double mu_0 = infPredictiveDistributionParameters[0];
                    double lambda_0 = infPredictiveDistributionParameters[1];
                    double alpha_0 = infPredictiveDistributionParameters[2];
                    double beta_0 = infPredictiveDistributionParameters[3];

                    double lambda_n = lambda_0 + count;
                    double alpha_n = alpha_0 + count/2;
                    double sum = 0;
                    for (Double infPeriod : infPeriodsInThisCategory) {
                        sum += infPeriod;
                    }
                    double mean = sum/count;

                    double sumOfDifferences = 0;
                    for (Double infPeriod : infPeriodsInThisCategory) {
                        sumOfDifferences += Math.pow(infPeriod-mean,2);
                    }

                    double mu_n = (lambda_0*mu_0 + sum)/(lambda_0 + count);
                    double beta_n = beta_0 + 0.5*sumOfDifferences + lambda_0*count*Math.pow(mean-mu_0, 2)/(2*(lambda_0+count));

                    double priorPredictiveProbability
                            = GammaFunction.logGamma(alpha_n)
                            - GammaFunction.logGamma(alpha_0)
                            + alpha_0*Math.log(beta_0)
                            - alpha_n*Math.log(beta_n)
                            + 0.5*Math.log(lambda_0)
                            - 0.5*Math.log(lambda_n)
                            - (count/2)*Math.log(2*Math.PI);

                    logL += priorPredictiveProbability;

                    //todo log the parameters of the "posterior"
                }
            case LOG_JEFFREYS:
                for(String category : ((WithinCaseCategoryOutbreak) cases).getInfectiousCategories()){
                    ArrayList<Double> infPeriodsInThisCategory = infectiousPeriodsByCategory.get(category);

                    double[] logInfPeriods = new double[infPeriodsInThisCategory.size()];

                    for(int j=0; j<infPeriodsInThisCategory.size(); j++){
                        logInfPeriods[j] = Math.log(infPeriodsInThisCategory.get(j));
                    }

                    double stdev = getSummaryStatistics(logInfPeriods)[3];

                    logL += -Math.log(stdev);
                }
            case JEFFREYS:
                for(String category : ((WithinCaseCategoryOutbreak) cases).getInfectiousCategories()){
                    ArrayList<Double> infPeriodsInThisCategory = infectiousPeriodsByCategory.get(category);

                    double[] infPeriods = new double[infPeriodsInThisCategory.size()];

                    for(int j=0; j<infPeriodsInThisCategory.size(); j++){
                        infPeriods[j] = infPeriodsInThisCategory.get(j);
                    }

                    double stdev = getSummaryStatistics(infPeriods)[3];

                    logL += -Math.log(stdev);
                }

        }

        if(hasLatentPeriods){
            WithinCaseCategoryOutbreak.PriorType latPriorType = ((WithinCaseCategoryOutbreak) cases).getLatPriorType();

            HashMap<String, ArrayList<Double>> latentPeriodsByCategory
                    = new HashMap<String, ArrayList<Double>>();


            for(AbstractCase aCase : cases.getCases()){

                String category = ((WithinCaseCategoryOutbreak)cases).getLatentCategory(aCase);

                if(!latentPeriodsByCategory.keySet().contains(category)){
                    latentPeriodsByCategory.put(category, new ArrayList<Double>());
                }

                ArrayList<Double> correspondingList
                        = latentPeriodsByCategory.get(category);

                correspondingList.add(getLatentPeriod(aCase));
            }


            switch(latPriorType){
                case NORMAL_GAMMA:

                    for(String category : ((WithinCaseCategoryOutbreak) cases).getLatentCategories()){
                        ArrayList<Double> latPeriodsInThisCategory = latentPeriodsByCategory.get(category);

                        double count = (double)latPeriodsInThisCategory.size();

                        NormalGammaDistribution prior = ((WithinCaseCategoryOutbreak)cases)
                                .getLatentCategoryPrior(category);

                        double[] latPredictiveDistributionParameters=prior.getParameters();

                        double mu_0 = latPredictiveDistributionParameters[0];
                        double lambda_0 = latPredictiveDistributionParameters[1];
                        double alpha_0 = latPredictiveDistributionParameters[2];
                        double beta_0 = latPredictiveDistributionParameters[3];

                        double lambda_n = lambda_0 + count;
                        double alpha_n = alpha_0 + count/2;
                        double sum = 0;
                        for (Double latPeriod : latPeriodsInThisCategory) {
                            sum += latPeriod;
                        }
                        double mean = sum/count;

                        double sumOfDifferences = 0;
                        for (Double latPeriod : latPeriodsInThisCategory) {
                            sumOfDifferences += Math.pow(latPeriod-mean,2);
                        }

                        double mu_n = (lambda_0*mu_0 + sum)/(lambda_0 + count);
                        double beta_n = beta_0 + 0.5*sumOfDifferences + lambda_0*count*Math.pow(mean-mu_0, 2)
                                /(2*(lambda_0+count));

                        double priorPredictiveProbability
                                = GammaFunction.logGamma(alpha_n)
                                - GammaFunction.logGamma(alpha_0)
                                + alpha_0*Math.log(beta_0)
                                - alpha_n*Math.log(beta_n)
                                + 0.5*Math.log(lambda_0)
                                - 0.5*Math.log(lambda_n)
                                - (count/2)*Math.log(2*Math.PI);

                        logL += priorPredictiveProbability;

                        //todo log the parameters of the "posterior"
                    }
                case LOG_JEFFREYS:
                    for(String category : ((WithinCaseCategoryOutbreak) cases).getLatentCategories()){
                        ArrayList<Double> latPeriodsInThisCategory = latentPeriodsByCategory.get(category);

                        double[] logLatPeriods = new double[latPeriodsInThisCategory.size()];

                        for(int j=0; j<latPeriodsInThisCategory.size(); j++){
                            logLatPeriods[j] = Math.log(latPeriodsInThisCategory.get(j));
                        }

                        double stdev = getSummaryStatistics(logLatPeriods)[3];

                        logL += -Math.log(stdev);
                    }
                case JEFFREYS:
                    for(String category : ((WithinCaseCategoryOutbreak) cases).getLatentCategories()){
                        ArrayList<Double> latPeriodsInThisCategory = latentPeriodsByCategory.get(category);

                        double[] latPeriods = new double[latPeriodsInThisCategory.size()];

                        for(int j=0; j<latPeriodsInThisCategory.size(); j++){
                            latPeriods[j] = latPeriodsInThisCategory.get(j);
                        }

                        double stdev = getSummaryStatistics(latPeriods)[3];

                        logL += -Math.log(stdev);
                    }
            }



        }

        explodeTree();

        boolean recalculateTimings = false;

        if(timingLogLikelihoods==null){
            timingLogLikelihoods = new double[noTips];
            recalculateTimings = true;
        }

        for(AbstractCase aCase : cases.getCases()){

            //todo weights (and remember if a weight is zero then the return value should be -INF)

            int number = cases.getCaseIndex(aCase);

            if(recalculateTimings){
                double infectionTime = getInfectionTime(aCase);
                AbstractCase parent = getInfector(aCase);
                if(parent!=null &&
                        (getInfectiousTime(parent)>infectionTime
                                || parent.culledYet(infectionTime))) {
                    timingLogLikelihoods[number] = Double.NEGATIVE_INFINITY;
                } else {
                    int possibleParents = 0;
                    for(int i=0; i<cases.size(); i++){
                        AbstractCase parentCandidate = cases.getCase(i);

                        if(i!=number && getInfectiousTime(parentCandidate)<infectionTime
                                && !parentCandidate.culledYet(infectionTime)){
                            possibleParents++;
                        }
                    }
                    if(possibleParents>1){
                        timingLogLikelihoods[number] = -Math.log(possibleParents);
                    } else {
                        timingLogLikelihoods[number] = 0.0;
                    }
                }
            }

            logL += timingLogLikelihoods[number];


            // and then the little tree calculations

            HashSet<AbstractCase> children = getInfectees(aCase);

            if(recalculateCoalescentFlags[number]){
                Treelet treelet = partitionsAsTrees[number];

                if(children.size()!=0){
                    MaxTMRCACoalescent coalescent = new MaxTMRCACoalescent(treelet, demoModel,
                            treelet.getRootHeight()+treelet.getRootBranchLength());
                    partitionTreeLogLikelihoods[number] = coalescent.calculateLogLikelihood();
                    recalculateCoalescentFlags[number] = false;
                    logL += partitionTreeLogLikelihoods[number];
                    if(partitionTreeLogLikelihoods[number]==Double.POSITIVE_INFINITY){
                        debugOutputTree("infCoalescent.nex", false);
                        debugTreelet(treelet, aCase+"_partition.nex");
                    }
                } else {
                    partitionTreeLogLikelihoods[number] = 0.0;
                }
            } else {
                logL += partitionTreeLogLikelihoods[number];
            }
        }
        likelihoodKnown = true;

        if(DEBUG){
            debugOutputTree("outstandard.nex", false);
            debugOutputTree("outfancy.nex", true);
        }

        return logL;


    }

    public void storeState(){
        super.storeState();
        storedPartitionsAsTrees = Arrays.copyOf(partitionsAsTrees, partitionsAsTrees.length);
        storedPartitionTreeLogLikelihoods = Arrays.copyOf(partitionTreeLogLikelihoods,
                partitionTreeLogLikelihoods.length);
        storedTimingLogLikelihoods = Arrays.copyOf(timingLogLikelihoods, timingLogLikelihoods.length);
    }

    public void restoreState(){
        super.restoreState();
        partitionsAsTrees = storedPartitionsAsTrees;
        partitionTreeLogLikelihoods = storedPartitionTreeLogLikelihoods;
        timingLogLikelihoods = storedTimingLogLikelihoods;
    }

    protected void handleModelChangedEvent(Model model, Object object, int index) {

        super.handleModelChangedEvent(model, object, index);

        if(model == treeModel){

            if(object instanceof PartitionedTreeModel.PartitionsChangedEvent){
                HashSet<AbstractCase> changedPartitions =
                        ((PartitionedTreeModel.PartitionsChangedEvent)object).getCasesToRecalculate();
                for(AbstractCase aCase : changedPartitions){
                    recalculateCaseWCC(aCase);
                }
            }
        } else if(model == branchMap){
            if(object instanceof ArrayList){

                for(int i=0; i<((ArrayList) object).size(); i++){
                    BranchMapModel.BranchMapChangedEvent event
                            =  (BranchMapModel.BranchMapChangedEvent)((ArrayList) object).get(i);

                    recalculateCaseWCC(event.getOldCase());
                    recalculateCaseWCC(event.getNewCase());

                    NodeRef node = treeModel.getNode(event.getNodeToRecalculate());
                    NodeRef parent = treeModel.getParent(node);

                    if(parent!=null){
                        recalculateCaseWCC(branchMap.get(parent.getNumber()));
                    }
                }
            } else {
                throw new RuntimeException("Unanticipated model changed event from BranchMapModel");
            }
        } else if(model == demoModel){
            Arrays.fill(recalculateCoalescentFlags, true);
        }
        timingLogLikelihoods=null;
    }

    protected void handleVariableChangedEvent(Variable variable, int index, Parameter.ChangeType type) {


        super.handleVariableChangedEvent(variable, index, type);

        if(variable == infectionTimeBranchPositions){
            recalculateCaseWCC(index);

            AbstractCase parent = getInfector(cases.getCase(index));
            if(parent!=null){
                recalculateCaseWCC(parent);

            }
        }

        if(variable == infectionTimeBranchPositions || variable == infectiousTimePositions){
            timingLogLikelihoods=null;
        }
    }

    protected void recalculateCaseWCC(int index){
        partitionsAsTrees[index] = null;
        recalculateCoalescentFlags[index] = true;
    }

    protected void recalculateCaseWCC(AbstractCase aCase){
        recalculateCaseWCC(cases.getCaseIndex(aCase));
    }


    public void makeDirty(){
        super.makeDirty();
        Arrays.fill(recalculateCoalescentFlags, true);
        timingLogLikelihoods=null;
        Arrays.fill(partitionsAsTrees, null);
    }

    // Tears the tree into small pieces. Indexes correspond to indexes in the outbreak.

    private void explodeTree(){
        if(DEBUG){
            debugOutputTree("test.nex", false);
        }
        for(int i=0; i<cases.size(); i++){
            if(partitionsAsTrees[i]==null){
                AbstractCase aCase = cases.getCase(i);

                NodeRef partitionRoot = getEarliestNodeInPartition(aCase);

                double infectionTime = getInfectionTime(branchMap.get(partitionRoot.getNumber()));
                double rootTime = getNodeTime(partitionRoot);

                FlexibleNode newRoot = new FlexibleNode();

                FlexibleTree littleTree = new FlexibleTree(newRoot);
                littleTree.beginTreeEdit();

                if(!treeModel.isExternal(partitionRoot)){
                    for(int j=0; j<treeModel.getChildCount(partitionRoot); j++){
                        copyPartitionToLittleTree(littleTree, treeModel.getChild(partitionRoot, j), newRoot, aCase);
                    }
                }

                littleTree.endTreeEdit();

                littleTree.resolveTree();

                partitionsAsTrees[i] = new Treelet(littleTree, rootTime - infectionTime);
            }
        }
    }

    public ArrayList<AbstractCase> postOrderTransmissionTreeTraversal(){
        return traverseTransmissionTree(branchMap.get(treeModel.getRoot().getNumber()));
    }

    private ArrayList<AbstractCase> traverseTransmissionTree(AbstractCase aCase){
        ArrayList<AbstractCase> out = new ArrayList<AbstractCase>();
        HashSet<AbstractCase> children = getInfectees(aCase);
        for(int i=0; i<getOutbreak().size(); i++){
            AbstractCase possibleChild = getOutbreak().getCase(i);
            // easiest way to maintain the set ordering of the cases?
            if(children.contains(possibleChild)){
                out.addAll(traverseTransmissionTree(possibleChild));
            }
        }
        out.add(aCase);
        return out;
    }

    private void copyPartitionToLittleTree(FlexibleTree littleTree, NodeRef oldNode, NodeRef newParent,
                                           AbstractCase partition){
        if(branchMap.get(oldNode.getNumber())==partition){
            if(treeModel.isExternal(oldNode)){
                NodeRef newTip = new FlexibleNode(new Taxon(treeModel.getNodeTaxon(oldNode).getId()));
                littleTree.addChild(newParent, newTip);
                littleTree.setBranchLength(newTip, treeModel.getBranchLength(oldNode));
            } else {
                NodeRef newChild = new FlexibleNode();
                littleTree.addChild(newParent, newChild);
                littleTree.setBranchLength(newChild, treeModel.getBranchLength(oldNode));
                for(int i=0; i<treeModel.getChildCount(oldNode); i++){
                    copyPartitionToLittleTree(littleTree, treeModel.getChild(oldNode, i), newChild, partition);
                }
            }
        } else {
            // we need a new tip
            NodeRef transmissionTip = new FlexibleNode(
                    new Taxon("Transmission_"+branchMap.get(oldNode.getNumber()).getName()));
            double parentTime = getNodeTime(treeModel.getParent(oldNode));
            double childTime = getInfectionTime(branchMap.get(oldNode.getNumber()));
            littleTree.addChild(newParent, transmissionTip);
            littleTree.setBranchLength(transmissionTip, childTime - parentTime);

        }
    }

    private class Treelet extends FlexibleTree {

        private double rootBranchLength;

        private Treelet(FlexibleTree tree, double rootBranchLength){
            super(tree);
            this.rootBranchLength = rootBranchLength;
        }

        private double getRootBranchLength(){
            return rootBranchLength;
        }

        private void setRootBranchLength(double rootBranchLength){
            this.rootBranchLength = rootBranchLength;
        }
    }

    private class MaxTMRCACoalescent extends Coalescent {

        private double maxHeight;

        private MaxTMRCACoalescent(Tree tree, DemographicModel demographicModel, double maxHeight){
            super(tree, demographicModel.getDemographicFunction());

            this.maxHeight = maxHeight;

        }

        public double calculateLogLikelihood() {
            return calculatePartitionTreeLogLikelihood(getIntervals(), getDemographicFunction(), 0, maxHeight);
        }

    }

    public static double calculatePartitionTreeLogLikelihood(IntervalList intervals,
                                                             DemographicFunction demographicFunction, double threshold,
                                                             double maxHeight) {

        double logL = 0.0;

        double startTime = -maxHeight;
        final int n = intervals.getIntervalCount();

        //TreeIntervals sets up a first zero-length interval with a lineage count of zero - skip this one

        for (int i = 0; i < n; i++) {

            // time zero corresponds to the date of first infection

            final double duration = intervals.getInterval(i);
            final double finishTime = startTime + duration;

            final double intervalArea = demographicFunction.getIntegral(startTime, finishTime);
            double normalisationArea = demographicFunction.getIntegral(startTime, 0);

            if( intervalArea == 0 && duration != 0 ) {
                return Double.NEGATIVE_INFINITY;
            }
            final int lineageCount = intervals.getLineageCount(i);

            if(lineageCount>=2){

                final double kChoose2 = Binomial.choose2(lineageCount);

                if (intervals.getIntervalType(i) == IntervalType.COALESCENT) {

                    logL += -kChoose2 * intervalArea;

                    final double demographicAtCoalPoint = demographicFunction.getDemographic(finishTime);

                    if( duration == 0.0 || demographicAtCoalPoint * (intervalArea/duration) >= threshold ) {
                        logL -= Math.log(demographicAtCoalPoint);
                    } else {
                        return Double.NEGATIVE_INFINITY;
                    }

                } else {
                    double numerator = Math.exp(-kChoose2 * intervalArea) - Math.exp(-kChoose2 * normalisationArea);
                    logL += Math.log(numerator);

                }

                // normalisation

                double normExp = Math.exp(-kChoose2 * normalisationArea);

                double logDenominator;

                // the denominator has an irritating tendency to round to zero

                if(normExp!=1){
                    logDenominator = Math.log1p(-normExp);
                } else {
                    logDenominator = handleDenominatorUnderflow(-kChoose2 * normalisationArea);
                }


                logL -= logDenominator;

            }

            startTime = finishTime;
        }

        return logL;
    }


    private static double handleDenominatorUnderflow(double input){
        BigDecimal bigDec = new BigDecimal(input);
        BigDecimal expBigDec = BigDecimalUtils.exp(bigDec, bigDec.scale());
        BigDecimal one = new BigDecimal(1.0);
        BigDecimal oneMinusExpBigDec = one.subtract(expBigDec);
        BigDecimal logOneMinusExpBigDec = BigDecimalUtils.ln(oneMinusExpBigDec, oneMinusExpBigDec.scale());
        return logOneMinusExpBigDec.doubleValue();
    }

    public void debugTreelet(Tree treelet, String fileName){
        try{
            FlexibleTree treeCopy = new FlexibleTree(treelet);
            for(int j=0; j<treeCopy.getNodeCount(); j++){
                FlexibleNode node = (FlexibleNode)treeCopy.getNode(j);
                node.setAttribute("Number", node.getNumber());
            }
            NexusExporter testTreesOut = new NexusExporter(new PrintStream(fileName));
            testTreesOut.exportTree(treeCopy);
        } catch (IOException ignored) {System.out.println("IOException");}
    }


    //************************************************************************
    // AbstractXMLObjectParser implementation
    //************************************************************************

    public static XMLObjectParser PARSER = new AbstractXMLObjectParser() {
        public static final String STARTING_NETWORK = "startingNetwork";
        public static final String INFECTION_TIMES = "infectionTimeBranchPositions";
        public static final String INFECTIOUS_TIMES = "infectiousTimePositions";
        public static final String MAX_FIRST_INF_TO_ROOT = "maxFirstInfToRoot";
        public static final String DEMOGRAPHIC_MODEL = "demographicModel";

        public String getParserName() {
            return WITHIN_CASE_COALESCENT;
        }

        public Object parseXMLObject(XMLObject xo) throws XMLParseException {

            PartitionedTreeModel virusTree = (PartitionedTreeModel) xo.getChild(TreeModel.class);

            String startingNetworkFileName=null;

            if(xo.hasChildNamed(STARTING_NETWORK)){
                startingNetworkFileName = (String) xo.getElementFirstChild(STARTING_NETWORK);
            }

            AbstractOutbreak caseSet = (AbstractOutbreak) xo.getChild(AbstractOutbreak.class);

            CaseToCaseTreeLikelihood likelihood;

            Parameter infectionTimes = (Parameter) xo.getElementFirstChild(INFECTION_TIMES);

            Parameter infectiousTimes = xo.hasChildNamed(INFECTIOUS_TIMES)
                    ? (Parameter) xo.getElementFirstChild(INFECTIOUS_TIMES) : null;

            Parameter earliestFirstInfection = (Parameter) xo.getElementFirstChild(MAX_FIRST_INF_TO_ROOT);

            DemographicModel demoModel = (DemographicModel) xo.getElementFirstChild(DEMOGRAPHIC_MODEL);

            try {
                likelihood = new WithinCaseCoalescent(virusTree, caseSet, startingNetworkFileName, infectionTimes,
                        infectiousTimes, earliestFirstInfection, demoModel);
            } catch (TaxonList.MissingTaxonException e) {
                throw new XMLParseException(e.toString());
            }

            return likelihood;
        }

        public String getParserDescription() {
            return "This element provides a tree prior for a partitioned tree, with each partitioned tree generated" +
                    "by a coalescent process";
        }

        public Class getReturnType() {
            return WithinCaseCoalescent.class;
        }

        public XMLSyntaxRule[] getSyntaxRules() {
            return rules;
        }

        private final XMLSyntaxRule[] rules = {
                new ElementRule(PartitionedTreeModel.class, "The tree"),
                new ElementRule(WithinCaseCategoryOutbreak.class, "The set of cases"),
                new ElementRule("startingNetwork", String.class, "A CSV file containing a specified starting network",
                        true),
                new ElementRule(MAX_FIRST_INF_TO_ROOT, Parameter.class, "The maximum time from the first infection to" +
                        "the root node"),
                new ElementRule(INFECTION_TIMES, Parameter.class),
                new ElementRule(INFECTIOUS_TIMES, Parameter.class, "For each case, proportions of the time between " +
                        "infection and first event that requires infectiousness (further infection or cull)" +
                        "that has elapsed before infectiousness", true),
                new ElementRule(DEMOGRAPHIC_MODEL, DemographicModel.class, "The demographic model for within-case" +
                        "evolution")
        };
    };
}
