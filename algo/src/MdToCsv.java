import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.messages.BookDepthMessage;
import com.optionscity.freeway.api.messages.MarketBidAskMessage;
import com.optionscity.freeway.api.messages.MarketLastMessage;

import java.util.Collection;

/**
 * Created by evoit on 8/1/2017.
 */
public class MdToCsv extends AbstractJob {
    // Variable decelerations
    boolean recLast;
    int depthLevels;

    @Override
    public void install(IJobSetup setup) {
        //setup.addVariable("Instrument","instrument to record", "instrument", "ES-20171215-F");
        setup.addVariable("Instruments", "Record up to 32 instruments", "instruments", "ES;F;;;DEC17;0;;");
        setup.addVariable("DepthLevels", "Number of Book Depth Levels to record", "int", "5");
        setup.addVariable("RecordLast", "Record Last Trade Messages", "boolean", "false");
        setup.setDefaultDescription("Records Market Data to CSV as Time Series");
    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);
        recLast = getBooleanVar("RecordLast");
        depthLevels = getIntVar("DepthLevels");
        container.subscribeToMarketLastMessages();
        container.subscribeToBookDepthMessages();
        //instrument = container.getVariable("Instrument");
        //container.filterMarketMessages(instrument);
        container.filterMarketMessages(getStringVar("Instruments"));
        //instrumentIds = instruments().getInstrumentIds();
    }

    public void onMarketLast(MarketLastMessage msg) {
        if (recLast) {
        log("~~New MarketLastMessage~~");
        log("The instrument id is " + msg.instrumentId + " last price is: " + msg.price + " qty " + msg.quantity);
        }
    }

    public void onBookDepth(BookDepthMessage m) {
        log("~~New BookDepth Msg~~");
        log("The instrument id is " + m.instrumentId);
        Book book = instruments().getBook(m.instrumentId);
        for (int i=0; i < depthLevels; i++){
            //log(B,);
            log("Bid Level " + (i+1) + " is " + book.bid[i].price + " quanity is " + book.bid[i].quantity);
            log("Ask Level " + (i+1) + " is " + book.ask[i].price + " quanity is " + book.ask[i].quantity);
        }
    }
}
