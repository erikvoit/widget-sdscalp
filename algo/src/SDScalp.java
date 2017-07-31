import com.optionscity.freeway.api.*;
import org.jquantlib.math.statistics.GeneralStatistics;
import org.jquantlib.math.matrixutilities.Array;

/**
 * Created by evoit on 7/31/2017.
 */
public class SDScalp extends AbstractJob {
    // variables
    final double[] fiveMinPriceArrayTestES = {2469.50, 2468.25, 2467.50, 2465.25, 2467.25, 2465.25, 2463.75, 2461.25, 2460.00, 2462.25, 2464.25, 2465.50, 2467.25, 2468.25, 2469.50, 2472.50, 2474.50, 2474.75, 2476.25, 2478.75};

    @Override
    public void install(IJobSetup setup) {

    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);

        GeneralStatistics fiveMinPrices = null;
        fiveMinPrices.addSequence(fiveMinPriceArrayTestES);

        log("The Standard deviation of the set is " + fiveMinPrices.standardDeviation());
    }
}
