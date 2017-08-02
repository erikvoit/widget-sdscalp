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
        // StringBuilder to build CSV format book depth
        // to be used for time series creation
        StringBuilder sb = new StringBuilder();
        log("~~New BookDepth Msg~~");
        sb.append("B,");
        sb.append(System.currentTimeMillis());
        sb.append(",");
        sb.append(m.instrumentId);
        sb.append(",");
        sb.append(depthLevels);
        sb.append(",");
        Book book = instruments().getBook(m.instrumentId);
        for (int i=0; i < depthLevels; i++){
            sb.append(book.bid[i].price);
            sb.append(",");
            sb.append(book.bid[i].quantity);
            sb.append(",");
        }
        sb.append(depthLevels);
        for (int i=0; i < depthLevels; i++){
            sb.append(",");
            sb.append(book.ask[i].price);
            sb.append(",");
            sb.append(book.ask[i].quantity);
        }
        String csvFormat = sb.toString();
        log(""+csvFormat);
    }
}
