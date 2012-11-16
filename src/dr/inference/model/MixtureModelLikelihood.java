package dr.inference.model;

import dr.inference.loggers.LogColumn;
import dr.math.MathUtils;

/**
 * Package: MixtureModelLikelihood
 * Description:
 * <p/>
 * <p/>
 * Created by
 *
 * @author Alexander V. Alekseyenko (alexander.alekseyenko@gmail.com)
 *         Date: 11/16/12
 *         Time: 4:36 PM
 */
public class MixtureModelLikelihood extends AbstractModel implements Likelihood {
    double logLikelihood;
    boolean isKnownLogLikelihood;
    Likelihood[] likelihoods;
    Parameter contributions;

    /**
     * @param name Model Name
     */
    public MixtureModelLikelihood(String name) {
        super(name);
    }

    public Model getModel() {
        return this;
    }

    public double getLogLikelihood() {
        if(isKnownLogLikelihood)
            return logLikelihood;

        return calculateLogLikelihood();  //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public double calculateLogLikelihood() {
        logLikelihood = 1;
        for(int i=0; i<contributions.getDimension(); ++i){
            logLikelihood += MathUtils.add_log(logLikelihood,
                    Math.log(contributions.getParameterValue(i)) + likelihoods[i].getLogLikelihood());
        }

        return logLikelihood;
    }

    public void makeDirty() {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public String prettyName() {
        return null;  //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public boolean isUsed() {
        return false;  //AUTOGENERATED METHOD IMPLEMENTATION
    }

    @Override
    protected void handleModelChangedEvent(Model model, Object object, int index) {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    @Override
    protected void handleVariableChangedEvent(Variable variable, int index, Variable.ChangeType type) {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    @Override
    protected void storeState() {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    @Override
    protected void restoreState() {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    @Override
    protected void acceptState() {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public void setUsed() {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public boolean evaluateEarly() {
        return false;  //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public String getId() {
        return null;  //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public void setId(String id) {
        //AUTOGENERATED METHOD IMPLEMENTATION
    }

    public LogColumn[] getColumns() {
        return new LogColumn[0];  //AUTOGENERATED METHOD IMPLEMENTATION
    }
}
