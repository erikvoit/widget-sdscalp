import com.optionscity.freeway.api.AbstractJob;
import com.optionscity.freeway.api.IContainer;
import com.optionscity.freeway.api.IJobSetup;

/**
 * Created by evoit on 8/9/2017.
 */
public class MomentumTrader extends AbstractJob {
    String instrumentId;
    int netPosition = 0;

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrumentId", "ES-20170915-F");

    }
    @Override
    public void begin(IContainer container) {
        super.begin(container);
        container.subscribeToSignals();
        instrumentId=container.getVariable("Instrument");
    }
}
