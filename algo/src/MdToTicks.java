import com.optionscity.freeway.api.AbstractJob;
import com.optionscity.freeway.api.IContainer;
import com.optionscity.freeway.api.IJobSetup;

/**
 * Created by evoit on 8/2/2017.
 */
public class MdToTicks extends AbstractJob {
    String instrument="";

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrument", "ES-20170915-F");
        setup.addVariable("useLast", "Use last trade prices to construct tick", "boolean", "true");
        setup.addVariable("useTop", "Use top of book prices to construct tick", "boolean", "false");

    }
    @Override
    public void begin(IContainer container) {
        super.begin(container);
        //get instrument to create live ticks for
        instrument=container.getVariable("Instrument");
        container.subscribeToMarketLastMessages();
        container.subscribeToMarketBidAskMessages();
        //TODO implement event handlers
    }

}
